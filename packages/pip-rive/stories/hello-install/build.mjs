// Builds hello-install.json, Pip's first-run greeting (about 18 s): he climbs out of his own launcher icon and says
// hi, hops down beside the Phone icon to eye it suspiciously, turns to the viewer with his shield up, then hops onto
// his icon and sinks back into it, hat last.
//
//   node packages/pip-rive/stories/hello-install/build.mjs        (or: turbo run rive:greet)
//
// The art comes from tools/pip-art/first-run/hello_install.py (stage.svg and art/*.svg); rerun it first when the
// pieces change. hello-install.json is generated: change this file, not the JSON.
//
// The contract with the app (RiveShowRunner, played by FirstRunGreeting): the first artboard plays its timeline
// once through `Main`, full screen over the home screen, so artboard units are screen pixels. Its view model gets
// the Pip launcher icon's centre and size (`pipX`, `pipY`, `pipD`, `pipLabelW`, `pipLabelH`); the entrance is
// bound to them, so Pip climbs out from behind his real icon wherever the launcher put it. The defaults are where
// the Pixel launcher puts it on the emulator, which is also what the previewer shows.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const FPS = 60;
const END = 1100;
const INK = "#FF3A2A26";
// The launcher icons the story binds to (RiveShowRunner sets them; GreetingSpot stands in when one is missing). The
// defaults are the Pixel launcher's layout on the emulator, which is also what the previewer shows.
const VM = { pipX: 922, pipY: 2002, pipD: 127, pipLabelW: 173, pipLabelH: 162, phoneX: 158, phoneY: 2002, phoneD: 127 };

// ---------------------------------------------------------------- tracks

const tracks = new Map();
/** A key: [ease] shapes the curve from this key to the next. A later key at the same frame replaces it. */
function k(target, property, frame, value, ease = "linear") {
  const id = `${target}|${property}`;
  if (!tracks.has(id)) tracks.set(id, { target, property, keys: new Map() });
  const key = { frame: Math.round(frame), ease };
  if (typeof value === "boolean") key.bool = value;
  else key.value = Math.round(value * 10000) / 10000;
  tracks.get(id).keys.set(key.frame, key);
}
const hold = (t, p, f, v) => k(t, p, f, v, "hold");
/** Keys a run of [frame, value, ease?] triples. */
function seq(target, property, list) {
  for (const [f, v, e] of list) k(target, property, f, v, e ?? "linear");
}

const events = new Map();
function cue(name, frame) {
  if (!events.has(name)) events.set(name, new Set());
  events.get(name).add(Math.round(frame));
}

/**
 * A spoken line: an event named `say:<line>` at frame f. The app plays the line's GPT-Live clip (rendered by
 * `turbo run voice:greeting`), or the phone's text-to-speech without one, queued, so a long line delays the next
 * rather than cutting it off; the previewer speaks it with the browser's and captions it. Keep lines short, and leave
 * each clip room to finish before the next cue (checked below, before the project is written).
 */
const say = (f, line) => cue(`say:${line}`, f);

// ---------------------------------------------------------------- Pip

/**
 * A nested Pip hung from a node chain so each effect has one owner: `<id>At` (his feet), `<id>Tilt` (rotation
 * about the feet), `<id>Size` (uniform size), `<id>Squash` (squash and stretch about the feet).
 */
function pipActor(id) {
  const at = `${id}At`, tilt = `${id}Tilt`, size = `${id}Size`, sq = `${id}Squash`;
  const a = {
    id, at, tilt, size, sq,
    pos(f, x, y, e) { k(at, "x", f, x, e); k(at, "y", f, y, e); },
    x(f, v, e) { k(at, "x", f, v, e); },
    y(f, v, e) { k(at, "y", f, v, e); },
    squash(f, sx, sy, e = "easeInOut") { k(sq, "scaleX", f, sx, e); k(sq, "scaleY", f, sy, e); },
    lean(f, rad, e = "easeInOut") { k(tilt, "rotation", f, rad, e); },
    grow(f, s, e = "easeInOut") { k(size, "scaleX", f, s, e); k(size, "scaleY", f, s, e); },
    in(name, f, v, e = "linear") { k(`${id}/${name}`, "nestedValue", f, v, e); },
    set(name, f, v) { hold(`${id}/${name}`, "nestedValue", f, v); },
    talk(f0, f1) { hold(`${id}/talking`, "nestedValue", f0, true); hold(`${id}/talking`, "nestedValue", f1, false); },
    /** A blink: lids close faster than they open. Swap discrete things (mood, headwear) at f + 3. */
    blink(f) { seq(`${id}/eyesOpen`, "nestedValue", [[f, 1, "easeIn"], [f + 4, 0, "hold"], [f + 7, 0, "easeOut"], [f + 13, 1]]); },
    /** Change of thought: blink and swap the mood (and anything else in [also]) behind the shut lids. */
    think(f, mood, also = {}) {
      a.blink(f);
      a.set("mood", f + 4, mood);
      for (const [name, v] of Object.entries(also)) a.set(name, f + 4, v);
    },
    /**
     * A hop from [from] to [to]: anticipation squash, stretch on launch, round at the apex, squash on landing and a
     * damped rebound. Up is ease-out, down is ease-in; x eases separately so the path arcs. Returns the land frame.
     */
    hop(f0, from, to, { h = 120, air = 22, anticip = 7, cues = true } = {}) {
      const fl = f0 + anticip, fa = fl + Math.round(air / 2), fe = fl + air;
      a.pos(f0, from[0], from[1]);
      a.squash(f0, 1, 1, "easeOut");
      a.squash(fl, 1.14, 0.87, "easeOut");
      a.squash(fl + 4, 0.88, 1.13, "easeInOut");
      a.squash(fa, 1, 1, "easeIn");
      a.squash(fe - 3, 0.92, 1.08, "easeOut");
      a.squash(fe + 2, 1.18, 0.84, "easeInOut");
      a.squash(fe + 8, 0.96, 1.04, "easeInOut");
      a.squash(fe + 14, 1, 1);
      k(at, "x", fl, from[0], "easeInOut");
      k(at, "x", fe, to[0]);
      k(at, "y", fl, from[1], "easeOut");
      k(at, "y", fa, Math.min(from[1], to[1]) - h, "easeIn");
      k(at, "y", fe, to[1]);
      // The hat lags the head and overshoots when he lands.
      seq(`${id}/hatSway`, "nestedValue", [[fl, 0, "easeOut"], [fl + 6, to[0] > from[0] ? -0.6 : 0.6, "easeInOut"],
        [fe + 3, to[0] > from[0] ? 0.7 : -0.7, "easeInOut"], [fe + 11, -0.25, "easeInOut"], [fe + 20, 0]]);
      if (cues) { cue("hop", fl); cue("land", fe); }
      return fe;
    },
  };
  return a;
}

const pip = pipActor("pip");
const icon = pipActor("pipIcon");

// ---------------------------------------------------------------- pieces

const svgBox = (file) => {
  const m = /viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"/.exec(fs.readFileSync(path.join(here, file), "utf8"));
  return { x: +m[1], y: +m[2], w: +m[3], h: +m[4] };
};
const setBox = (name) => svgBox(`../../sets/${name}.svg`);

const instances = [];
const nodes = [];
const extraArtboards = new Map();

/**
 * Places an artboard so its art's (0, 0) sits on a node `<id>At` at (x, y), which clips move, turn and scale
 * (`scale` is its resting size; key it with `pop`). The instance itself only carries opacity and the remaps.
 */
function piece(id, artboard, box, x, y, { scale = 1, parent, remaps = [], opacity = 0, masks } = {}) {
  nodes.push({ id: `${id}At`, ...(parent ? { parent } : {}), x, y, scaleX: scale, scaleY: scale });
  const inst = { id, artboard, parent: `${id}At`, x: box.x, y: box.y, opacity };
  if (remaps.length) { inst.stateMachine = "Main"; inst.remaps = remaps.map((clip) => ({ clip })); }
  if (masks) inst.masks = masks;
  instances.push(inst);
  return id;
}
function setPiece(id, name, x, y, opts = {}) {
  extraArtboards.set(name, { source: `../../sets/${name}.rivc.json` });
  return piece(id, name, setBox(name), x, y, opts);
}
function artPiece(id, name, file, x, y, opts = {}) {
  const box = svgBox(file);
  extraArtboards.set(name, { artboard: { name, width: box.w, height: box.h }, art: { svg: file } });
  return piece(id, name, box, x, y, opts);
}
/**
 * A callout card showing [words] (broken into lines by hand; each card is sized to its longest line): art/card-<n>.svg (drawn by hello_install.py about an anchor under the card, CARDS there)
 * with the words set inside, left-aligned, its anchor on node `<id>At` at (x, y) in [parent]'s space (the screen
 * without one). It pops from the anchor, so put that just above Pip's head.
 */
const CARD_GAP = 24;
const CARDS = { 1: { w: 630, h: 164, dx: 0 }, 2: { w: 545, h: 164, dx: 173 }, 3: { w: 610, h: 218, dx: 173 }, 4: { w: 575, h: 218, dx: -247 } };
const CARD_INK = "#FF22223A";
function callout(n, words, parent, x, y) {
  const { w, h, dx } = CARDS[n], file = `art/card-${n}.svg`, box = svgBox(file), name = `Card${n}`;
  const left = dx - w / 2 - box.x, top = -CARD_GAP - h - box.y;
  extraArtboards.set(name, {
    artboard: { name, width: box.w, height: box.h }, art: { svg: file },
    texts: [{
      id: `card${n}Text`, text: words, font: "Inter-Bold", size: 45, lineHeight: 54, color: CARD_INK,
      x: left + 41, y: top + 24, width: w - 82, height: h - 48, align: "left", verticalAlign: "middle",
    }],
  });
  return piece(`card${n}`, name, box, x, y, parent ? { parent } : {});
}
/** Pops callout [n] at f and takes it away at f1. */
function sayCard(n, f, f1) {
  show(`card${n}`, f);
  pop(`card${n}`, f);
  unpop(`card${n}`, f1);
  show(`card${n}`, f1 + 12, false);
  cue("pop", f);
}
const show = (id, f, on = true) => hold(id, "opacity", f, on ? 1 : 0);
/** Pops node `<id>At` from 0 to [s] with an overshoot, from frame f. */
function pop(id, f, s = 1, node = `${id}At`) {
  seq(node, "scaleX", [[f, 0, "easeOut"], [f + 8, s * 1.12, "easeInOut"], [f + 14, s]]);
  seq(node, "scaleY", [[f, 0, "easeOut"], [f + 8, s * 1.12, "easeInOut"], [f + 14, s]]);
}
/** Shrinks node `<id>At` from [s] to 0 over 12 frames from f, with a small anticipation swell. */
function unpop(id, f, s = 1, node = `${id}At`) {
  seq(node, "scaleX", [[f, s, "easeOut"], [f + 4, s * 1.08, "easeIn"], [f + 12, 0, "hold"]]);
  seq(node, "scaleY", [[f, s, "easeOut"], [f + 4, s * 1.08, "easeIn"], [f + 12, 0, "hold"]]);
}
/** Plays remapped clip [clip] of [id] from frame f over [frames] (its time runs 0 to 1). */
function play(id, clip, f, frames, { gate = false } = {}) {
  if (gate) { hold(`${id}/${clip}`, "mix", 0, 0); hold(`${id}/${clip}`, "mix", f, 1); }
  seq(`${id}/${clip}`, "time", [[0, 0, "hold"], [f, 0], [f + frames, 1, "hold"]]);
}
// ---------------------------------------------------------------- icon-relative moves

/**
 * Pip's feet hang from a chain that adds a weighted share of each bound icon position to his keyed position:
 * world = K + wPhone * (phoneX, phoneY) + wHome * (pipX, pipY). A weight is a node scaled by w over the bound node,
 * undone by a node scaled 1 / w under it, so Pip himself is never scaled. At rest a weight is EPS (a fraction of a
 * pixel), not 0, so its inverse stays finite. [hopVia] moves him from one space to another, keyed every frame with
 * "hold": the device renders between frames, and a linear tween of w next to one of 1 / w no longer multiplies to 1
 * (near EPS, half a frame in, Pip drew about 20 times his size).
 */
const EPS = 1e-4;
const BIG = 0.68; // his size centre stage
const SPACES = { abs: [0, 0], phone: [VM.phoneX, VM.phoneY], home: [VM.pipX, VM.pipY] };
nodes.push(
  { id: "wPhone", scaleX: EPS, scaleY: EPS }, { id: "atPhone", parent: "wPhone", bind: { x: "phoneX", y: "phoneY" } },
  { id: "uPhone", parent: "atPhone", scaleX: 1 / EPS, scaleY: 1 / EPS },
  { id: "wHome", parent: "uPhone", scaleX: EPS, scaleY: EPS }, { id: "atHome", parent: "wHome", bind: { x: "pipX", y: "pipY" } },
  { id: "uHome", parent: "atHome", scaleX: 1 / EPS, scaleY: 1 / EPS },
);
function weight(space, f, w) {
  if (space === "abs") return;
  const v = Math.round(Math.max(w, EPS) * 10000) / 10000, cap = space[0].toUpperCase() + space.slice(1);
  for (const p of ["scaleX", "scaleY"]) { k(`w${cap}`, p, f, v, "hold"); k(`u${cap}`, p, f, 1 / v, "hold"); }
}
/**
 * A hop from [a] in space [sa] to [b] in space [sb] ("abs" is screen pixels; "phone" and "home" are offsets from
 * the Phone and Pip icons' centres). The squash, hat and cues come from [pipActor.hop]; the path is a smooth blend
 * of the two spaces plus an arc that clears the higher end by [h] in the default layout. Returns the land frame.
 */
function hopVia(f0, sa, a, sb, b, { h = 120, air = 22, anticip = 7 } = {}) {
  const wa = [a[0] + SPACES[sa][0], a[1] + SPACES[sa][1]], wb = [b[0] + SPACES[sb][0], b[1] + SPACES[sb][1]];
  const fl = f0 + anticip, fe = pip.hop(f0, wa, wb, { h, air, anticip });
  const lift = Math.abs(wa[1] - wb[1]) / 2 + h;
  for (let f = f0; f <= fe; f++) {
    const p = Math.min(1, Math.max(0, (f - fl) / air)), u = p * p * (3 - 2 * p);
    k(pip.at, "x", f, (1 - u) * a[0] + u * b[0]);
    k(pip.at, "y", f, (1 - u) * a[1] + u * b[1] - 4 * p * (1 - p) * lift);
    weight(sa, f, 1 - u);
    weight(sb, f, u);
  }
  return fe;
}

// ================================================================ the timeline

// ---- A. Entrance: a twinkle on his new icon, his hat pokes out, he peeks both ways and zips up off the top.
nodes.push({ id: "iconAt", bind: { x: "pipX", y: "pipY" } });
const ICON_SCALE = 0.3;
nodes.push({ id: icon.at, parent: "iconAt", y: 260 }, { id: icon.tilt, parent: icon.at },
  { id: icon.size, parent: icon.tilt, scaleX: ICON_SCALE, scaleY: ICON_SCALE }, { id: icon.sq, parent: icon.size });
instances.push({ id: icon.id, artboard: "Pip", parent: icon.sq, x: -360, y: -770, masks: ["iconHole"] });
setPiece("sparkle", "fx-sparkle", 58, -64, { parent: "iconAt", scale: 0.7 });
show("sparkle", 2);
pop("sparkle", 2, 0.7);
unpop("sparkle", 44, 0.7);
cue("sparkle", 4);

seq(icon.at, "y", [[0, 260, "hold"], [22, 260, "easeOut"], [44, 70, "easeInOut"], [56, 80, "easeOut"], [64, 80, "easeOut"],
  [78, -6, "easeInOut"], [100, -14, "easeInOut"], [118, -8, "easeOut"], [126, 16, "easeIn"], [150, -2900, "hold"]]);
icon.squash(118, 1, 1, "easeOut");
icon.squash(126, 1.16, 0.85, "easeOut");
icon.squash(132, 0.8, 1.3, "hold");
seq(`${icon.id}/hatSway`, "nestedValue", [[40, 0, "easeOut"], [48, 0.9, "easeInOut"], [56, -0.6, "easeInOut"], [64, 0.35, "easeInOut"],
  [72, 0, "easeInOut"], [126, 0, "easeOut"], [132, 0.8]]);
seq(`${icon.id}/lookX`, "nestedValue", [[78, 0, "easeInOut"], [86, -1, "hold"], [100, -1, "easeInOut"], [110, 1, "hold"],
  [116, 1, "easeInOut"], [122, 0]]);
seq(`${icon.id}/lookY`, "nestedValue", [[120, 0, "easeOut"], [128, -1]]);
icon.blink(104);
icon.set("mood", 108, 1);
icon.set("mouth", 126, 3);
icon.in("browRaise", 120, 0, "easeOut");
icon.in("browRaise", 128, 1);
show(icon.id, 0);
show(icon.id, 150, false);
cue("peek", 44);
cue("peek", 80);
cue("whoosh", 127);

// Main Pip drops in from the top of the screen and lands centre stage.
nodes.push({ id: pip.at, parent: "uHome", x: 540, y: -420 }, { id: pip.tilt, parent: pip.at }, { id: pip.size, parent: pip.tilt, scaleX: BIG, scaleY: BIG },
  { id: pip.sq, parent: pip.size });
show(pip.id, 0, false);
show(pip.id, 150);
pip.pos(0, 540, -420);
k(pip.at, "y", 150, -420, "easeIn");
k(pip.at, "y", 176, 1600);
pip.grow(0, BIG);
pip.squash(150, 0.84, 1.2, "hold");
pip.squash(172, 0.84, 1.2, "easeOut");
pip.squash(176, 1.3, 0.77, "easeInOut");
pip.squash(184, 0.92, 1.08, "easeInOut");
pip.squash(192, 1.04, 0.96, "easeInOut");
pip.squash(200, 1, 1);
seq(`${pip.id}/lookY`, "nestedValue", [[150, 1, "hold"], [178, 1, "easeOut"], [190, 0]]);
seq(`${pip.id}/hatDroop`, "nestedValue", [[150, 0.7, "hold"], [176, 0.7, "easeOut"], [186, 0]]);
seq(`${pip.id}/hatSway`, "nestedValue", [[176, 0, "easeOut"], [182, -0.9, "easeInOut"], [192, 0.55, "easeInOut"], [202, -0.2, "easeInOut"], [212, 0]]);
pip.set("mood", 150, 4);
pip.set("mouth", 150, 3);
pip.set("mouth", 184, -1);
cue("whoosh", 152);
cue("land", 176);

// ---- B. Hello: he blinks into a smile, waves (his far hand, thumb toward his face) and introduces himself.
// Each line's cue leaves its clip room to finish (assets/greeting-voice: 2.81, 2.57, 3.00 and 3.12 s).
const F1 = 184;
const GAP = 60; // 1 s of quiet between scenes, after one line's action settles and before the next line
pip.think(176, 1);
// The raised hand is handL drawn on his right: the rig mirrors handL, so its thumb points back at his face.
pip.set("handL", 0, 0);
pip.set("handL", F1, 4);
seq(`${pip.id}/handLX`, "nestedValue", [[0, 560, "hold"], [F1, 560, "easeOut"], [F1 + 12, 588, "hold"], [F1 + 112, 588, "easeIn"], [F1 + 126, 540]]);
seq(`${pip.id}/handLY`, "nestedValue", [[0, 560, "hold"], [F1, 560, "easeOut"], [F1 + 12, 330, "hold"], [F1 + 112, 330, "easeIn"], [F1 + 126, 560]]);
seq(`${pip.id}/handLAngle`, "nestedValue", [[F1, 0, "easeInOut"], [F1 + 14, 12, "easeInOut"], [F1 + 24, -22, "easeInOut"], [F1 + 34, 14, "easeInOut"],
  [F1 + 44, -22, "easeInOut"], [F1 + 54, 14, "easeInOut"], [F1 + 64, -20, "easeInOut"], [F1 + 76, 8, "easeInOut"], [F1 + 90, -10, "easeInOut"], [F1 + 104, 0]]);
pip.set("handL", F1 + 128, 0);
seq(`${pip.id}/lookX`, "nestedValue", [[F1, 0, "easeInOut"], [F1 + 8, 0.2, "hold"], [F1 + 150, 0.2, "easeInOut"], [F1 + 160, 0]]);
pip.in("browRaise", F1 + 6, 0, "easeOut");
pip.in("browRaise", F1 + 14, 0.6, "easeInOut");
pip.in("browRaise", F1 + 70, 0);
say(F1, "Hi! I'm Pip, your scam-spotting sidekick!");
pip.talk(F1 + 2, F1 + 168);
// A happy bounce on "Pip"
seq(pip.at, "y", [[F1 + 44, 1600, "easeOut"], [F1 + 54, 1574, "easeIn"], [F1 + 64, 1600, "hold"]]);
pip.squash(F1 + 40, 1, 1, "easeOut");
pip.squash(F1 + 44, 1.06, 0.95, "easeOut");
pip.squash(F1 + 50, 0.97, 1.04, "easeIn");
pip.squash(F1 + 64, 1.08, 0.93, "easeInOut");
pip.squash(F1 + 72, 1, 1);
callout(1, "Hi! I'm Pip, your\nscam-spotting sidekick!", null, 540, 1100);
sayCard(1, F1 - 4, F1 + 168);
cue("ding", F1 + 6);

// ---- C. "AI scammers can be tricky to notice!" He hops down beside the Phone icon (or the dock, without one),
// shrinking to icon scale, and squints at it, leaning in.
const F2 = F1 + 174 + GAP;
const PH = [210, 70]; // his feet, from the Phone icon's centre: to its right, level with its foot
say(F2, "AI scammers can be tricky to notice!");
pip.think(F2 - 6, 0);
const P2 = hopVia(F2, "abs", [540, 1600], "phone", PH, { h: 260, air: 32 });
pip.grow(F2 + 7, BIG);
pip.grow(P2, 0.5);
seq(`${pip.id}/lookX`, "nestedValue", [[F2 + 4, 0, "easeInOut"], [F2 + 12, -1, "hold"], [F2 + 150, -1, "easeInOut"], [F2 + 160, 0]]);
seq(`${pip.id}/lookY`, "nestedValue", [[F2 + 4, 0, "easeInOut"], [F2 + 12, 0.35, "hold"], [F2 + 150, 0.35, "easeInOut"], [F2 + 160, 0]]);
pip.set("brows", P2 + 6, 1);
pip.in("browRaise", P2 + 6, 0, "easeOut");
pip.in("browRaise", P2 + 16, -0.7, "hold");
pip.in("browRaise", F2 + 150, -0.7, "easeInOut");
pip.in("browRaise", F2 + 160, 0);
pip.in("squint", P2 + 6, 0, "easeOut");
pip.in("squint", P2 + 16, 0.55, "hold");
pip.in("squint", F2 + 104, 0.55, "easeInOut");
pip.in("squint", F2 + 112, 0.75, "hold");
pip.in("squint", F2 + 150, 0.75, "easeInOut");
pip.in("squint", F2 + 160, 0);
pip.lean(P2 + 8, 0, "easeInOut");
pip.lean(P2 + 24, -0.2, "hold");
pip.lean(F2 + 100, -0.2, "easeInOut");
pip.lean(F2 + 112, -0.27, "hold");
pip.lean(F2 + 148, -0.27, "easeInOut");
pip.lean(F2 + 160, 0);
pip.talk(F2 + 2, F2 + 152);
callout(2, "AI scammers can be\ntricky to notice!", pip.at, 0, -345);
sayCard(2, P2 + 2, F2 + 148);

// ---- D. "That's where I come in. I'm trained to defend you from them!" He turns to the viewer and raises his shield.
const F3 = F2 + 160 + GAP;
say(F3, "That's where I come in. I'm trained to defend you from them!");
pip.think(F3 - 4, 1, { brows: 0 });
pip.talk(F3 + 2, F3 + 180);
callout(3, "That's where I come in.\nI'm trained to defend\nyou from them!", pip.at, 0, -345);
sayCard(3, F3 + 2, F3 + 176);
artPiece("shield", "Shield", "art/shield.svg", 130, -120, { parent: pip.at, scale: 0.55 });
const SH = F3 + 72; // on "trained"
show("shield", SH);
pop("shield", SH, 0.55);
cue("sparkle", SH + 1);
pip.set("handR", SH, 4);
seq(`${pip.id}/handRX`, "nestedValue", [[SH, 500, "easeOut"], [SH + 10, 560, "hold"], [F3 + 176, 560, "easeInOut"], [F3 + 186, 500]]);
seq(`${pip.id}/handRY`, "nestedValue", [[SH, 560, "easeOut"], [SH + 10, 470, "hold"], [F3 + 176, 470, "easeInOut"], [F3 + 186, 560]]);
pip.set("handR", F3 + 188, 0);
// A proud little lift as the shield comes up.
seq(pip.sq, "scaleY", [[SH - 4, 1, "easeOut"], [SH + 6, 1.06, "easeInOut"], [SH + 16, 1]]);
seq(pip.sq, "scaleX", [[SH - 4, 1, "easeOut"], [SH + 6, 0.96, "easeInOut"], [SH + 16, 1]]);
pip.in("blush", SH, 0, "easeOut");
pip.in("blush", SH + 12, 0.4, "hold");
pip.in("blush", F3 + 170, 0.4, "easeInOut");
pip.in("blush", F3 + 184, 0);

// ---- E. "I'm always working in the background to keep you safe." The shield goes down, he hops onto his own icon
// and sinks into it, hat last (the icon hole hides him). Without a Pip icon, the hole is below the bottom edge, so
// he ducks out there.
const F4 = F3 + 186 + GAP;
say(F4, "I'm always working in the background to keep you safe.");
unpop("shield", F3 + 178, 0.55); // down with his hand, before the pause
show("shield", F3 + 190, false);
pip.talk(F4 + 2, F4 + 182);
const HOME = [0, -52]; // his feet, from the Pip icon's centre: standing on top of it
seq(`${pip.id}/lookX`, "nestedValue", [[F4 + 6, 0, "easeInOut"], [F4 + 14, 1, "hold"], [F4 + 40, 1, "easeInOut"], [F4 + 56, 0]]);
const P4 = hopVia(F4 + 12, "phone", PH, "home", HOME, { h: 280, air: 34 });
pip.grow(F4 + 19, 0.5);
pip.grow(P4, 0.32);
pip.in("blush", P4 + 4, 0, "easeOut");
pip.in("blush", P4 + 14, 0.6);
pip.blink(P4 + 30);
// A last look at the viewer, then down he goes: a crouch, a stretch, and he slides into the icon.
const SINK = P4 + 50;
// His last line's card hangs over his icon, so it stays while he sinks and he finishes the line from inside.
callout(4, "I'm always working in\nthe background to\nkeep you safe.", "uHome", HOME[0], HOME[1] - 222);
sayCard(4, P4 + 2, F4 + 186);
pip.squash(SINK - 8, 1, 1, "easeOut");
pip.squash(SINK, 1.14, 0.86, "easeOut");
pip.squash(SINK + 6, 0.86, 1.16, "easeInOut");
pip.squash(SINK + 30, 0.9, 1.1);
seq(pip.at, "y", [[SINK, HOME[1], "easeOut"], [SINK + 5, HOME[1] - 14, "easeIn"], [SINK + 28, HOME[1] + 260, "hold"]]);
seq(`${pip.id}/hatDroop`, "nestedValue", [[SINK + 4, 0, "easeOut"], [SINK + 14, 0.8]]);
seq(`${pip.id}/hatSway`, "nestedValue", [[SINK + 10, 0, "easeInOut"], [SINK + 18, 0.5, "easeInOut"], [SINK + 26, -0.4]]);
show(pip.id, SINK + 30, false);
cue("whoosh", SINK + 4);
setPiece("sparkle2", "fx-sparkle", 58, -64, { parent: "iconAt", scale: 0.7 });
show("sparkle2", SINK + 26);
pop("sparkle2", SINK + 26, 0.7);
unpop("sparkle2", SINK + 66, 0.7);
show("sparkle2", SINK + 80, false);
cue("sparkle", SINK + 28);
if (SINK + 82 > END || F4 + 200 > END) throw new Error(`timeline runs past END ${END}`);

cue("showEnd", END - 1);

// Pip's lines play as pre-rendered clips (apps/pip-app/app/src/main/assets/greeting-voice, `turbo run
// voice:greeting`), queued so a long line delays the next. Fail if a clip would still be playing at the next line's
// cue, which pushes that line (and every one after it) off its beat. Lines without a clip yet are skipped.
{
  const dir = path.join(here, "../../../../apps/pip-app/app/src/main/assets/greeting-voice");
  const manifest = path.join(dir, "lines.json");
  if (fs.existsSync(manifest)) {
    const clips = JSON.parse(fs.readFileSync(manifest, "utf8")).lines ?? {};
    const seconds = (file) => {
      const b = fs.readFileSync(path.join(dir, file));
      const rate = b.readUInt32LE(24), bytesPerFrame = b.readUInt16LE(32);
      for (let o = 12; o + 8 <= b.length; o += 8 + b.readUInt32LE(o + 4)) {
        if (b.toString("ascii", o, o + 4) === "data") return b.readUInt32LE(o + 4) / bytesPerFrame / rate;
      }
      throw new Error(`${file}: no data chunk`);
    };
    const lines = [...events].filter(([n]) => n.startsWith("say:")).map(([n, fs_]) => [Math.min(...fs_), n.slice(4)])
      .sort((a, b) => a[0] - b[0]);
    let free = 0, prev = "";
    for (const [f, line] of lines) {
      if (f < free - 2) throw new Error(`"${line}" is cued at ${f}, but "${prev}" is still playing until ${Math.ceil(free)}`);
      const file = clips[line];
      free = file ? Math.max(f, free) + seconds(file) * FPS : f;
      prev = line;
    }
  }
}

// ---------------------------------------------------------------- project

const RIG_DEFAULTS = Object.fromEntries(JSON.parse(fs.readFileSync(path.join(here, "../../pip/pip-inputs.json"), "utf8"))
  .stateMachine.inputs.map((i) => [i.name, i.type === "boolean" ? (i.defaultBool ?? false) : (i.default ?? 0)]));
const STAGE_GROUPS = Object.fromEntries([...fs.readFileSync(path.join(here, "stage.svg"), "utf8")
  .matchAll(/<g id="([^"]+)"(?: transform="translate\(([-\d.]+),([-\d.]+)\)")?(?: opacity="([\d.]+)")?/g)]
  .map(([, id, x, y, o]) => [id, { x: +(x ?? 0), y: +(y ?? 0), opacity: o === undefined ? 1 : +o }]));

/** What [target]'s [property] is before any key, so a track keyed only later doesn't leak its first value back to 0. */
function restValue(target, property) {
  const [owner, input] = target.split("/");
  if (input && (owner === pip.id || owner === icon.id)) return RIG_DEFAULTS[input];
  if (input && property === "time") return 0;
  if (input && property === "mix") return 1;
  const node = nodes.find((n) => n.id === target);
  if (node) return node[property] ?? (property.startsWith("scale") ? 1 : 0);
  if (target === pip.id && property === "opacity") return 0;
  const inst = instances.find((i) => i.id === target);
  if (inst && property === "opacity") return inst.opacity ?? 1;
  const g = STAGE_GROUPS[target];
  if (g) return g[property] ?? (property.startsWith("scale") ? 1 : 0);
  return undefined;
}

const ORDER = ["opacity", "x", "y", "rotation", "scaleX", "scaleY", "time", "mix", "nestedValue"];
const outTracks = [...tracks.values()].map(({ target, property, keys }) => {
  const list = [...keys.values()].sort((a, b) => a.frame - b.frame);
  // Before its first key a track holds its target's resting value (a rig default, a node's or group's setup).
  if (list[0].frame > 0) {
    const rest = restValue(target, property);
    const first = { frame: 0, ease: "hold" };
    if (typeof rest === "boolean") first.bool = rest;
    else first.value = rest ?? list[0].value ?? 0;
    list.unshift(first);
  }
  for (const key of list) if (key.ease === "linear") delete key.ease;
  for (const key of list) if (key.frame > END) throw new Error(`${target}.${property} keyed at ${key.frame}, past END`);
  return { target, property, keys: list };
}).sort((a, b) => a.target.localeCompare(b.target) || ORDER.indexOf(a.property) - ORDER.indexOf(b.property));

const project = {
  format: 1,
  assets: [{ name: "Inter-Bold", font: "../../showreel/fonts/Inter-Bold.ttf" }],
  artboards: [
    {
      artboard: { name: "HelloInstall", width: 1080, height: 2424, clip: true },
      art: { svg: "stage.svg" },
      viewModel: { name: "HelloInstall", properties: Object.entries(VM).map(([name, v]) => ({ name, type: "number", default: v })) },
      clips: [{ name: "timeline", frames: END, tracks: outTracks }],
      nodes,
      masks: [{
        id: "iconHole", width: 1, height: 1, invert: true,
        parts: [
          { shape: "ellipse", width: VM.pipD, height: VM.pipD, bind: { x: "pipX", y: "pipY", width: "pipD", height: "pipD" } },
          // From the icon's centre down past its label: deep enough to hide all of him before he climbs out.
          { width: VM.pipLabelW, height: 420, originY: 0, bind: { x: "pipX", y: "pipY", width: "pipLabelW" } },
        ],
      }],
      instances: orderInstances(instances),
      events: [...events.entries()].map(([name, frames]) => ({ name, clip: "timeline", frames: [...frames].sort((a, b) => a - b) })),
    },
    { source: "../../pip/pip-inputs.json", control: "inputs" },
    ...extraArtboards.values(),
  ],
};

/** Draw order, bottom to top: backdrops and sets, the icon Pip, the main Pip, then what he holds or swats. */
function orderInstances(list) {
  const top = new Set(["shield", "card1", "card2", "card3", "card4"]);
  const main = { id: pip.id, artboard: "Pip", parent: pip.sq, x: -360, y: -770, opacity: 0, masks: ["iconHole"] };
  return [...list.filter((i) => !top.has(i.id)), main, ...list.filter((i) => top.has(i.id))];
}

const out = path.join(here, "hello-install.json");
fs.writeFileSync(out, JSON.stringify(project, null, 1) + "\n");
console.log(`hello-install: ${out} (${END} frames, ${(END / FPS).toFixed(2)} s, ${outTracks.length} tracks, ${instances.length + 1} instances)`);
