// Generates roam.json, Pip's "roam" pieces: reusable, anchor-relative moves the app's roam director strings
// together on the home screen (apps/pip-app/.../ui/show/RoamDirector.kt).
//
//   node packages/pip-rive/roam/build-roam.mjs     (or: turbo run rive:roam)
//
// The output is generated: edit this script. build.sh compiles roam.json to dist/roam/roam.riv.
//
// ## The contract with the director
//
// Rive owns the motion; the director only sets view-model numbers and fires one trigger per move, then waits for
// the `moveEnd` event. Every move starts from rest and ends where the numbers said, so the director can "rebase":
// write Pip's new feet position into anchorX/anchorY, zero every other number, and fire `settle`.
//
//   anchorX, anchorY  the chain's root, screen px. At rest it is Pip's feet. For a swing it is the web's anchor.
//   armAngle          radians: the arm from the anchor to Pip's raised hand at the start of a swing (0 points down,
//                     positive turns clockwise). bodyAngle is always -armAngle, so Pip starts upright.
//   armLength, webScale  px from the anchor to his hand, and the same / 1000 (the web line is 1000 units long).
//   grip              px from his hand down to his feet while hanging (GRIP below); 0 otherwise.
//   sweep             radians the arm turns during the swing; reel: px it lengthens on release.
//   dx, dy            px from the start to the travel target (hop: the new feet; peeks: the icon's centre).
//   lift              px of the travel arc's height.
//   iconD             the target icon's diameter; peek offsets are in icon diameters.
//   face              -1 or 1: which way he looks and leans (a side peek's side).
//   icon<i>X/Y/D      every launcher icon on the page (slots 0 to SLOTS - 1): its circle's centre and diameter, margin
//                     included. Pip is always drawn behind these circles and their labels; a diameter of 0 frees a slot.
//   label<i>Y/W/H     the label under icon i: its top (the circle's bottom), width and height. Centred on icon<i>X.
//   holeX/Y/W/H       the box under a peek's icon that hides the rest of him while he crouches behind it (its centre
//                     and size). Sizes 0 hide nothing; keep the centre on screen (an inverted mask cuts its hole from
//                     an 8000 px square around it).
//
// Triggers: hop, peekLeft, peekRight, peekTop, lurk, swing, look, settle. Where each move leaves his feet:
//   hop                  anchor + (dx, dy)
//   peek*, lurk          anchor + (dx, dy) + (0, PEEK_END * iconD): standing on top of the icon
//   swing                anchor + rotate(armAngle + sweep) * (0, armLength + reel) + (0, grip)
//   look                 where he was
//
// ## How it composes
//
// Rive layers replace rather than add, so every keyed property has one owning layer, and each effect that must
// stack moves its own node in one chain (translations add, rotations add, scales multiply):
//
//   anchor (bound) > armBase (bound rot) > armSwing (Sweep) > armEnd (bound y) > reel (Reel)
//     > bodyBase (bound rot) > bodySwing (Sweep) > hang (bound y) > hopX (X) > hopY (Y) > arc (Lift)
//     > peek (Size) > squash (Main scale, Face rotation) > the Pip instance, its feet at the node's origin
//   armEnd > web (bound scaleY) > webShoot (Main) > the web line;  anchor > splatNode (Main) > the splat
//
// A move's size comes from the view model through 1D blends (see `pair`): a layer blending clips keyed at 0 and K
// by a number v plays f(t) * v exactly, so one set of clips serves every distance. Signed numbers (dx, dy, sweep,
// reel) go through RoamDirector.blendValue first; face is only ever -1 or 1, which blends exactly. Each blend clip holds its last frame,
// and every value it holds is proportional to a number the rebase zeroes, so rebasing never pops.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const r = (v) => Math.round(v * 10000) / 10000;

// ------------------------------------------------------------------------------------------------ constants

/** Pip's nested artboard scale: a 540-unit box at 170 px, so he hides behind a launcher icon (as the Kotlin peeks). */
const S = 0.315;
/** Feet inside the nested Pip artboard (box 540 x 660 plus 90/110 padding). */
const FEET = { x: 360, y: 770 };
/** His raised hand while hanging, in box units: straight over his feet, at the hat's tip. */
const HAND = { x: 270, y: 0 };
/** px from the hanging hand down to his feet. RoamDirector.GRIP must match. */
const GRIP = r((FEET.y - (HAND.y + 110)) * S);
/** Feet offset, in icon diameters, from an icon's centre when he stands on top. RoamDirector.PEEK_END must match. */
const PEEK_END = -0.5;
/** Icon slots in the always-on icon mask; RoamPlanner's Roam.SLOTS must match. */
const SLOTS = 40;
/** Feet offset, in icon diameters, from an icon's centre when he is hidden behind it and its label. */
const HID = 1.22;

const EASE = {
  in: "easeIn",
  out: "easeOut",
  inOut: "easeInOut",
  sine: { cubic: [0.37, 0, 0.63, 1] },
  pendulum: { cubic: [0.45, 0, 0.4, 1] },
  snap: { cubic: [0.55, 0, 0.9, 0.4] },
  linear: "linear",
  hold: "hold",
};

// Blend ranges: wide enough for any value on a 1080 x 2424 screen (a 1D blend clamps outside its clips).
/** RoamDirector.BLEND_PX and BLEND_RAD must match px and rad. */
const K = { px: 4000, lift: 2000, d: 1000, rad: 7, face: 1 };

const TRIGGERS = ["hop", "peekLeft", "peekRight", "peekTop", "lurk", "swing", "look"];

// ------------------------------------------------------------------------------------------------ helpers

/** A track from [frame, value, ease?] keys. */
function track(target, property, keys) {
  keys.forEach(([f], i) => {
    if (i > 0 && f <= keys[i - 1][0]) throw new Error(`${target}.${property}: frame ${f} after ${keys[i - 1][0]}`);
  });
  return {
    target,
    property,
    keys: keys.map(([frame, value, ease]) => ({ frame, value: r(value), ...(ease ? { ease: EASE[ease] ?? ease } : {}) })),
  };
}
const input = (name, keys) => track(`pip/${name}`, "nestedValue", keys);
/** scaleX and scaleY of a node from [frame, sx, sy, ease] keys. */
const scale2 = (target, keys) => [
  track(target, "scaleX", keys.map(([f, sx, , e]) => [f, sx, e])),
  track(target, "scaleY", keys.map(([f, , sy, e]) => [f, sy, e])),
];

const clips = [];
const events = [];
const layers = [];

/** A plain clip. */
function clip(name, frames, tracks) {
  clips.push({ name, frames, tracks: tracks.flat() });
  return name;
}

/**
 * Clips for a 1D blend by [by] that plays u(t) * value, from unit tracks ([frame, u, ease] keys): one keyed at
 * [hi] (u * hi) over one keyed all zero at 0, plus one at [lo] (u * lo) when [lo] is negative. Returns the blend.
 *
 * Rive mixes a blend's two neighbouring clips in turn, each over the result so far (lower clip first), so the
 * output is (1 - t)^2 * lower + t * upper, t being where the value sits between them. With the zero clip as the
 * lower neighbour that is exactly value * u. Below zero it is -K * u * (1 - t)^2, so a negative value v must be
 * written as -sqrt(-v * K) (RoamDirector.blendValue) to play v * u.
 */
function pair(name, by, frames, lo, hi, unitTracks) {
  const scaled = (k) => unitTracks.map((t) => ({ ...t, keys: t.keys.map((key) => ({ ...key, value: r(key.value * k) })) }));
  const out = [];
  if (lo < 0) out.push({ clip: clip(`${name}@lo`, frames, scaled(lo)), value: lo });
  out.push({ clip: clip(`${name}@0`, frames, scaled(0)), value: 0 });
  out.push({ clip: clip(`${name}@hi`, frames, scaled(hi)), value: hi });
  return { by, clips: out };
}

/** Cue events (sounds and moveEnd) on a Main clip. */
function cues(clipName, list) {
  for (const [name, frame] of list) events.push({ name, clip: clipName, frames: [frame] });
}

/**
 * A layer: `rest` from entry and on `settle`, and each state entered from any state on its triggers.
 * [states] is a list of { name, clip | blend, on: [triggers] }.
 */
function layer(name, restClip, states) {
  layers.push({
    name,
    states: [{ name: "rest", clip: restClip }, ...states.map(({ on, ...s }) => s)],
    transitions: [
      { from: "entry", to: "rest" },
      { from: "any", to: "rest", conditions: [{ on: "settle" }] },
      ...states.flatMap((s) => s.on.map((t) => ({ from: "any", to: s.name, conditions: [{ on: t }] }))),
    ],
  });
}

// ------------------------------------------------------------------------------------------------ Main layer
// Owns: squash scale, webShoot, splatNode, and Pip's face and hand inputs. Every clip ends at the rest values.

const REST_INPUTS = { mood: 0, mouth: -1, eyesOpen: 1, lookY: 0, lidTop: 0, squint: 0, browRaise: 0, eyeScale: 1, handR: 0, handRX: 500, handRY: 560, handRAngle: 0 };
const restMain = clip("rest", 1, [
  scale2("squash", [[0, 1, 1]]),
  track("webShoot", "scaleY", [[0, 0]]),
  scale2("splatNode", [[0, 0, 0]]),
  Object.entries(REST_INPUTS).map(([k, v]) => input(k, [[0, v, k === "mood" || k === "mouth" || k === "handR" ? "hold" : undefined]])),
]);

/** The travel squash shared by hops and peeks: anticipate, stretch on launch, squash on landing at [land]. */
const travelSquash = (land) => [
  [0, 1, 1, "out"], [6, 1.14, 0.86, "in"], [9, 0.86, 1.18, "out"], [20, 1, 1, "sine"],
  [land - 3, 0.92, 1.1, "in"], [land + 1, 1.22, 0.8, "out"], [land + 6, 0.95, 1.05, "inOut"], [land + 9, 1, 1],
];
const blink = (f) => [[f, 1, "in"], [f + 3, 0.05, "hold"], [f + 5, 0.05, "out"], [f + 10, 1]];

const hop = clip("hop", 45, [
  scale2("squash", travelSquash(36)),
  input("lookY", [[0, 0, "out"], [6, -0.3, "inOut"], [22, -0.3, "inOut"], [28, 0.4, "inOut"], [40, 0.4, "inOut"], [45, 0]]),
  input("eyesOpen", blink(2)),
  input("mood", [[0, 0, "hold"], [12, 1, "hold"], [40, 0, "hold"]]),
]);
cues(hop, [["hop", 8], ["land", 36], ["moveEnd", 44]]);

/**
 * A peek's Main track set. [spot] is when he sees the viewer, [duck] when he snaps back down, [pop] when he jumps
 * out on top, [end] the clip length. [curious] spans the lidded, squinting look-around before the spot.
 */
function peekMain(name, { curious, spot, duck, pop, end, extra = [] }) {
  const land = pop + 30;
  const c = clip(name, end, [
    scale2("squash", [
      ...travelSquash(36).slice(0, 6),
      [46, 0.93, 1.07, "sine"], [spot - 30, 0.96, 1.04, "sine"],
      [spot, 1.06, 0.94, "out"], [spot + 14, 0.9, 1.12, "sine"], [spot + 20, 0.97, 1.03, "sine"],
      [duck, 0.95, 1.05, "in"], [duck + 6, 0.84, 1.2, "out"], [duck + 10, 1.26, 0.8, "out"], [duck + 20, 1, 1, "sine"],
      [pop, 1.15, 0.85, "out"], [pop + 8, 0.85, 1.2, "sine"], [pop + 24, 1, 1, "in"],
      [land, 1.22, 0.8, "out"], [land + 6, 0.95, 1.05, "inOut"], [end, 1, 1],
    ]),
    input("lidTop", [[0, 0, "inOut"], [curious[0], 0.32, "hold"], [curious[1], 0.32, "out"], [spot, 0]]),
    input("squint", [[0, 0, "inOut"], [curious[0], 0.15, "hold"], [curious[1], 0.15, "out"], [spot, 0]]),
    input("lookY", [[0, 0, "out"], [8, 0.5, "inOut"], [36, 0.5, "inOut"], [curious[0], -0.4, "hold"], [curious[1], -0.4, "inOut"], [spot, 0]]),
    input("browRaise", [[spot - 2, 0, "out"], [spot + 4, 0.8, "hold"], [duck - 4, 0.8, "inOut"], [duck + 4, 0]]),
    input("eyeScale", [[spot - 2, 1, "out"], [spot + 4, 1.15, "hold"], [duck - 4, 1.15, "inOut"], [duck + 4, 1]]),
    input("eyesOpen", [...blink(2), ...blink(spot - 16)]),
    input("mood", [[0, 0, "hold"], [spot, 1, "hold"], [land + 8, 0, "hold"]]),
    ...extra,
  ]);
  cues(c, [["hop", 8], ["peek", spot], ["pop", pop + 2], ["land", land], ["moveEnd", end - 1]]);
  return { clip: c, land };
}

// Peeks share their beats; a lurk is a longer peek: up, side to side, up again, then out.
const PEEK = { curious: [50, 150], spot: 166, duck: 198, pop: 222, end: 264 };
const LURK = { curious: [50, 382], spot: 398, duck: 430, pop: 454, end: 496 };
const peekLeft = peekMain("peekLeft", PEEK);
const peekRight = peekMain("peekRight", PEEK);
const peekTop = peekMain("peekTop", PEEK);
const lurk = peekMain("lurk", {
  ...LURK,
  extra: [input("hatDroop", [[166, 0, "inOut"], [178, 0.5, "hold"], [382, 0.5, "inOut"], [398, 0]])],
});

const swing = clip("swing", 138, [
  scale2("squash", [
    [0, 1, 1, "out"], [8, 1.1, 0.9, "out"], [14, 0.92, 1.1, "sine"], [22, 0.9, 1.14, "sine"], [60, 0.95, 1.06, "sine"],
    [96, 0.9, 1.12, "out"], [104, 1, 1, "in"], [118, 0.92, 1.1, "in"], [121, 1.24, 0.8, "out"], [128, 0.95, 1.05, "inOut"], [138, 1, 1],
  ]),
  track("webShoot", "scaleY", [[0, 0], [10, 0, "out"], [20, 1, "hold"], [96, 1, "in"], [104, 0]]),
  scale2("splatNode", [[0, 0, 0], [18, 0, 0, "out"], [22, 1.3, 1.3, "inOut"], [27, 1, 1, "hold"], [98, 1, 1, "in"], [110, 0, 0]]),
  input("handR", [[0, 0, "hold"], [3, 1, "hold"], [126, 0, "hold"]]),
  input("handRX", [[0, 500, "hold"], [3, 500, "out"], [12, HAND.x, "hold"], [118, HAND.x, "inOut"], [126, 500]]),
  input("handRY", [[0, 560, "hold"], [3, 560, "out"], [12, HAND.y, "hold"], [118, HAND.y, "inOut"], [126, 560]]),
  input("handRAngle", [[3, 0, "out"], [12, -12, "hold"], [118, -12, "inOut"], [126, 0]]),
  input("lookY", [[0, 0, "out"], [6, -1, "hold"], [22, -1, "inOut"], [40, 0.2, "hold"], [100, 0.2, "inOut"], [110, 0.6, "inOut"], [138, 0]]),
  input("eyesOpen", blink(17)),
  input("mood", [[0, 0, "hold"], [20, 1, "hold"], [130, 0, "hold"]]),
  input("mouth", [[0, -1, "hold"], [24, 3, "hold"], [100, -1, "hold"]]),
]);
cues(swing, [["whoosh", 10], ["pop", 20], ["whoosh", 96], ["land", 121], ["moveEnd", 137]]);

const look = clip("look", 150, [
  scale2("squash", [[0, 1, 1], [116, 1, 1, "out"], [120, 1.08, 0.92, "sine"], [126, 0.95, 1.06, "sine"], [132, 1, 1]]),
  input("lookY", [[0, 0, "inOut"], [12, -0.3, "hold"], [100, -0.3, "inOut"], [112, 0]]),
  input("eyesOpen", blink(52)),
  input("browRaise", [[96, 0, "out"], [104, 0.7, "hold"], [124, 0.7, "inOut"], [134, 0]]),
  input("mood", [[0, 0, "hold"], [104, 1, "hold"], [140, 0, "hold"]]),
]);
cues(look, [["moveEnd", 149]]);

layer("Main", restMain, [
  { name: "hop", clip: hop, on: ["hop"] },
  { name: "peekLeft", clip: peekLeft.clip, on: ["peekLeft"] },
  { name: "peekRight", clip: peekRight.clip, on: ["peekRight"] },
  { name: "peekTop", clip: peekTop.clip, on: ["peekTop"] },
  { name: "lurk", clip: lurk.clip, on: ["lurk"] },
  { name: "swing", clip: swing, on: ["swing"] },
  { name: "look", clip: look, on: ["look"] },
]);

// ------------------------------------------------------------------------------------------------ Face layer
// Blend by `face` (-1 or 1): owns squash rotation (his lean), lookX and hatSway, all mirrored by face.

const faceRest = clip("face-rest", 1, [track("squash", "rotation", [[0, 0]]), input("lookX", [[0, 0]]), input("hatSway", [[0, 0]])]);
const faceTracks = ({ lean = [[0, 0]], lookX = [[0, 0]], sway = [[0, 0]] }) => [
  track("squash", "rotation", lean), input("lookX", lookX), input("hatSway", sway),
];
const faceState = (name, frames, spec, on) => ({ name, blend: pair(`face-${name}`, "face", frames, -K.face, K.face, faceTracks(spec)), on });

/** Eye darts and the head following a beat late, from [f]: one way, the other, then the viewer. */
const darts = (f) => ({
  lookX: [[f, 0, "out"], [f + 6, -1, "hold"], [f + 24, -1, "out"], [f + 30, 1, "hold"], [f + 44, 1, "inOut"], [f + 50, 0]],
  lean: [[f, 0, "sine"], [f + 10, -0.1, "hold"], [f + 28, -0.1, "sine"], [f + 36, 0.1, "hold"], [f + 46, 0.1, "sine"], [f + 54, 0]],
});
/**
 * A lean out past the icon's side [s] (1: the face side) from [f0], eyes darting while out and back on the viewer
 * at [spot], and the snap back down at [duck]. Matches the Size layer's `lean` (out by f0 + 68).
 */
const sideLean = (f0, spot, duck, s = 1) => ({
  lean: [[f0, 0, "sine"], [f0 + 22, 0.03 * s, "sine"], [f0 + 38, 0.05 * s, "sine"], [f0 + 52, 0.1 * s, "sine"], [f0 + 68, 0.7 * s, "sine"], [spot, 0.77 * s, "sine"], [spot + 14, 0.6 * s, "hold"], [duck, 0.6 * s, "snap"], [duck + 5, 0]],
  lookX: [[f0 + 68, 0, "out"], [f0 + 72, s, "hold"], [f0 + 82, s, "out"], [f0 + 86, -0.3 * s, "hold"], [spot - 6, -0.3 * s, "inOut"], [spot, 0]],
});
const merge = (...parts) => {
  const out = {};
  for (const p of parts) for (const [k, v] of Object.entries(p)) out[k] = [...(out[k] || []), ...v].sort((a, b) => a[0] - b[0]);
  return out;
};
const travelFace = {
  lookX: [[0, 0, "out"], [5, 0.8, "hold"], [30, 0.8, "inOut"], [42, 0]],
  lean: [[8, 0, "out"], [16, 0.12, "inOut"], [33, 0.04, "out"], [38, -0.05, "inOut"], [45, 0]],
  sway: [[8, 0, "out"], [16, -0.6, "inOut"], [36, -0.2, "out"], [40, 0.5, "inOut"], [45, 0]],
};
const sway = (f) => ({ sway: [[f, 0, "inOut"], [f + 12, -0.5, "inOut"], [f + 26, 0.4, "inOut"], [f + 40, 0]] });

layer("Face", faceRest, [
  faceState("hop", 45, travelFace, ["hop"]),
  faceState("peekLeft", PEEK.end, merge(sideLean(50, PEEK.spot, PEEK.duck), sway(PEEK.pop)), ["peekLeft"]),
  faceState("peekRight", PEEK.end, merge(sideLean(50, PEEK.spot, PEEK.duck), sway(PEEK.pop)), ["peekRight"]),
  faceState("peekTop", PEEK.end, merge(darts(112), sway(PEEK.pop)), ["peekTop"]),
  faceState("lurk", LURK.end, merge(
    darts(112),
    sideLean(180, 274, 290, -1),
    sideLean(304, LURK.spot, LURK.duck, 1),
    sway(LURK.pop),
  ), ["lurk"]),
  faceState("swing", 138, {
    lookX: [[0, 0, "out"], [10, 0.3, "inOut"], [30, 0.8, "hold"], [110, 0.8, "inOut"], [130, 0]],
    sway: [[22, 0, "inOut"], [60, -0.8, "inOut"], [100, 0.6, "inOut"], [124, -0.3, "inOut"], [138, 0]],
  }, ["swing"]),
  faceState("look", 150, {
    lookX: [[0, 0, "inOut"], [10, 1, "hold"], [50, 1, "inOut"], [60, -1, "hold"], [96, -1, "inOut"], [106, 0]],
    lean: [[0, 0, "inOut"], [14, 0.06, "hold"], [50, 0.06, "inOut"], [64, -0.06, "hold"], [96, -0.06, "inOut"], [110, 0]],
    sway: [[10, 0, "inOut"], [24, -0.4, "inOut"], [60, 0.4, "inOut"], [74, -0.2, "inOut"], [110, 0]],
  }, ["look"]),
]);

// ------------------------------------------------------------------------------------------------ travel layers
// Hops and peeks travel alike: 8 frames of anticipation, then 28 in the air, landing at frame 36.

const TRAVELS = ["hop", "peekLeft", "peekRight", "peekTop", "lurk"];
const travel = [[0, 0], [8, 0, "linear"], [36, 1]];
layer("X", clip("x-rest", 1, [track("hopX", "x", [[0, 0]])]), [
  { name: "travel", blend: pair("x-travel", "dx", 36, -K.px, K.px, [track("hopX", "x", travel)]), on: TRAVELS },
]);
layer("Y", clip("y-rest", 1, [track("hopY", "y", [[0, 0]])]), [
  { name: "travel", blend: pair("y-travel", "dy", 36, -K.px, K.px, [track("hopY", "y", travel)]), on: TRAVELS },
]);
// The arc: up with ease-out, down with ease-in, `lift` px at the top.
layer("Lift", clip("lift-rest", 1, [track("arc", "y", [[0, 0]])]), [
  { name: "travel", blend: pair("lift-travel", "lift", 36, 0, K.lift, [track("arc", "y", [[0, 0], [8, 0, "out"], [22, -1, "in"], [36, 0]])]), on: TRAVELS },
  { name: "swing", blend: pair("lift-swing", "lift", 138, 0, K.lift, [track("arc", "y", [[0, 0], [96, 0, "out"], [108, -1, "in"], [120, 0]])]), on: ["swing"] },
]);

// ------------------------------------------------------------------------------------------------ Size layer
// Peek offsets from the icon's centre, in icon diameters (a blend by iconD from 0 to K.d).

/** [frame, x, y, ease] in icon diameters -> the peek node's x and y unit tracks. */
const sizeTracks = (keys) => [
  track("peek", "x", keys.map(([f, x, , e]) => [f, x, e])),
  track("peek", "y", keys.map(([f, , y, e]) => [f, y, e])),
];
// Up over the icon while the travel lands on its centre, then straight down behind it: he only enters the box under
// the icon from above, so its straight sides never cut him.
const into = [[0, 0, 0], [8, 0, 0, "linear"], [30, 0, -0.55, "out"], [44, 0, HID, "in"]];
/** Hidden, then jump out on top at [pop]: up past the rim, land on it. */
const popOut = (pop) => [[pop, 0, HID, "out"], [pop + 18, 0, PEEK_END - 0.38, "in"], [pop + 30, 0, PEEK_END]];
/** Rise straight up: hat tip over the rim, a hold, then eyes clear. */
const rise = (f) => [[f, 0, HID, "sine"], [f + 18, 0, 0.66, "sine"], [f + 34, 0, 0.68, "sine"], [f + 62, 0, -0.12, "hold"]];
/**
 * Lean out to side [s]: rise straight until his feet clear the icon's centre (hat tip over the rim first), then lean.
 * Above the centre only the circle hides him, so nothing of him is cut by the straight edges of the box below it.
 */
const lean = (f, s) => [[f, 0, HID, "sine"], [f + 22, 0, 0.66, "sine"], [f + 38, 0, 0.68, "sine"], [f + 52, 0, -0.05, "sine"], [f + 68, s * 0.17, -0.15, "hold"]];
/** The spotted jump and the duck back down, keeping [x], [y]. */
const spotted = (x, y, spot, duck) => [
  [spot, x, y, "out"], [spot + 14, x, y - 0.1, "sine"], [spot + 20, x, y - 0.02, "sine"], [duck, x, y, "snap"],
  // Straighten above the centre line first, then drop: no lean below the circle.
  [duck + 5, 0, -0.05, "snap"], [duck + 12, 0, HID, "hold"],
];
const peekSize = (name, beats, end) => ({ name, blend: pair(`size-${name}`, "iconD", end, 0, K.d, sizeTracks(beats)), on: [name] });

layer("Size", clip("size-rest", 1, sizeTracks([[0, 0, 0]])), [
  peekSize("peekLeft", [...into, ...lean(50, -1), ...spotted(-0.17, -0.15, PEEK.spot, PEEK.duck), ...popOut(PEEK.pop)], PEEK.end),
  peekSize("peekRight", [...into, ...lean(50, 1), ...spotted(0.17, -0.15, PEEK.spot, PEEK.duck), ...popOut(PEEK.pop)], PEEK.end),
  peekSize("peekTop", [...into, ...rise(50), ...spotted(0, -0.12, PEEK.spot, PEEK.duck), ...popOut(PEEK.pop)], PEEK.end),
  peekSize("lurk", [
    ...into, ...rise(50), [166, 0, -0.12, "snap"], [178, 0, HID, "hold"],
    ...lean(180, -1), [290, -0.17, -0.15, "snap"], [295, 0, -0.05, "snap"], [302, 0, HID, "hold"],
    ...lean(304, 1), ...spotted(0.17, -0.15, LURK.spot, LURK.duck), ...popOut(LURK.pop),
  ], LURK.end),
]);

// ------------------------------------------------------------------------------------------------ swing layers
// The arm turns by `sweep` (a pendulum: slow at both ends), his body rights itself a beat behind, and on release
// the arm lengthens by `reel` so he lands exactly on the target.

layer("Sweep", clip("sweep-rest", 1, [track("armSwing", "rotation", [[0, 0]]), track("bodySwing", "rotation", [[0, 0]])]), [
  {
    name: "swing",
    blend: pair("sweep-swing", "sweep", 138, -K.rad, K.rad, [
      track("armSwing", "rotation", [[0, 0], [22, 0, "pendulum"], [96, 1]]),
      track("bodySwing", "rotation", [[0, 0], [22, 0, "inOut"], [74, -0.3, "inOut"], [112, -1]]),
    ]),
    on: ["swing"],
  },
]);
layer("Reel", clip("reel-rest", 1, [track("reel", "y", [[0, 0]])]), [
  { name: "swing", blend: pair("reel-swing", "reel", 138, -K.px, K.px, [track("reel", "y", [[0, 0], [96, 0, "out"], [120, 1]])]), on: ["swing"] },
]);

// ------------------------------------------------------------------------------------------------ the project

const numbers = [
  ["anchorX", 540], ["anchorY", 1500], ["armAngle", 0], ["bodyAngle", 0], ["armLength", 0], ["webScale", 0], ["grip", 0],
  ["sweep", 0], ["reel", 0], ["dx", 0], ["dy", 0], ["lift", 0], ["iconD", 0], ["face", 1],
  ["holeX", 540], ["holeY", 1212], ["holeW", 0], ["holeH", 0],
  ...Array.from({ length: SLOTS }, (_, i) => [[`icon${i}X`, 0], [`icon${i}Y`, 0], [`icon${i}D`, 0], [`label${i}Y`, 0], [`label${i}W`, 0], [`label${i}H`, 0]]).flat(),
];

const project = {
  format: 1,
  artboards: [
    {
      artboard: { name: "Roam", width: 1080, height: 2424 },
      viewModel: {
        name: "Roam",
        properties: [
          ...numbers.map(([name, d]) => ({ name, type: "number", default: d })),
          ...[...TRIGGERS, "settle"].map((name) => ({ name, type: "trigger" })),
        ],
      },
      nodes: [
        { id: "anchor", bind: { x: "anchorX", y: "anchorY" } },
        { id: "armBase", parent: "anchor", bind: { rotation: "armAngle" } },
        { id: "armSwing", parent: "armBase" },
        { id: "armEnd", parent: "armSwing", bind: { y: "armLength" } },
        { id: "web", parent: "armEnd", bind: { scaleY: "webScale" } },
        { id: "webShoot", parent: "web", scaleY: 0 },
        { id: "reel", parent: "armEnd" },
        { id: "bodyBase", parent: "reel", bind: { rotation: "bodyAngle" } },
        { id: "bodySwing", parent: "bodyBase" },
        { id: "hang", parent: "bodySwing", bind: { y: "grip" } },
        { id: "hopX", parent: "hang" },
        { id: "hopY", parent: "hopX" },
        { id: "arc", parent: "hopY" },
        { id: "peek", parent: "arc" },
        { id: "squash", parent: "peek" },
        { id: "splatNode", parent: "anchor", scaleX: 0, scaleY: 0 },
      ],
      instances: [
        { id: "splat", artboard: "Splat", parent: "splatNode", x: -40, y: -40 },
        { id: "webLine", artboard: "Web", parent: "webShoot", x: -8, y: -1000 },
        { id: "pip", artboard: "Pip", parent: "squash", x: r(-FEET.x * S), y: r(-FEET.y * S), scale: S, masks: ["icons", "hole"] },
      ],
      masks: [
        // Every icon and its label, in front of Pip. The mask's own shape is a 1 px dot in the corner; the slots are
        // its parts, which never overlap (each label starts where its circle ends), so one even-odd clip cuts them all.
        {
          id: "icons", x: 0, y: 0, width: 1, height: 1, invert: true, disjoint: true,
          parts: Array.from({ length: SLOTS }, (_, i) => [
            { shape: "ellipse", width: 160, height: 160, bind: { x: `icon${i}X`, y: `icon${i}Y`, width: `icon${i}D`, height: `icon${i}D` } },
            { width: 100, height: 40, originY: 0, bind: { x: `icon${i}X`, y: `label${i}Y`, width: `label${i}W`, height: `label${i}H` } },
          ]).flat(),
        },
        {
          id: "hole", x: 540, y: 1212, shape: "rectangle", width: 216, height: 220, invert: true,
          bind: { x: "holeX", y: "holeY", width: "holeW", height: "holeH" },
        },
      ],
      clips,
      events,
      stateMachine: { name: "Main", control: "viewModel", layers },
    },
    { source: "../pip/pip-inputs.json", control: "inputs" },
    { artboard: { name: "Web", width: 16, height: 1000 }, art: { svg: "web.svg" } },
    { artboard: { name: "Splat", width: 80, height: 80 }, art: { svg: "splat.svg" } },
  ],
};

// Short arrays and objects on one line keeps the file readable and its diffs small.
function format(v, indent = "") {
  const flat = JSON.stringify(v);
  if (flat.length <= 110 || v === null || typeof v !== "object") return flat;
  const inner = indent + "  ";
  if (Array.isArray(v)) return `[\n${v.map((x) => inner + format(x, inner)).join(",\n")}\n${indent}]`;
  return `{\n${Object.entries(v).map(([k, x]) => `${inner}${JSON.stringify(k)}: ${format(x, inner)}`).join(",\n")}\n${indent}}`;
}
const json = format(project);
fs.writeFileSync(path.join(here, "roam.json"), json + "\n");
console.log(`roam.json: ${clips.length} clips, ${layers.length} layers, ${events.length} cues; GRIP=${GRIP} PEEK_END=${PEEK_END}`);
