// Generates Pip's Rive source from the numbers in the Kotlin renderer (ui/PipRenderer.kt, ui/show/PipLife.kt):
//
//   node packages/pip-rive/pip/build-pip.mjs
//
// writes, next to this script:
//   pip.svg          the art, split into animatable parts (ids are what clips and the state machine name)
//   pip.json         the rig and behaviours, driven by the "Pip" view model (the app)
//   pip-inputs.json  the same rig driven by state machine inputs (control: inputs), for a nested Pip in the showreel
//
// The source format has no comment field, so the intent lives here. The files are generated: edit this script.
//
// How the rig composes. Rive layers don't add: a layer writing a property replaces what an earlier layer wrote
// (and a 1D blend resets its properties to the setup pose every frame). So every animated property belongs to
// exactly one layer, and effects that must stack (a mood's heavy lid, a channel's lidTop, three blink rhythms, a
// look and the idle darts) each move their own node in a chain of nested nodes, so their translations add,
// rotations add and scales multiply. Where two owners must be OR-ed rather than multiplied (brows shown by mood or
// by the `brows` channel; mouths from mood or from the `mouth` channel) the part is drawn twice, each copy gated
// by its own owner's opacity.
//
// Keyed translations and rotations are offsets from each node's setup pose. That pose depends on how the art pass
// placed the node (group pivots move a node's origin), so the script compiles the art once, reads every node's
// setup transform back with `rivc dump`, and keys setup + offset.
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const rivc = path.join(repo, "packages/rivc/rivc");
const tmp = fs.mkdtempSync(path.join(process.env.TMPDIR || "/tmp", "pip-build-"));

// ------------------------------------------------------------------------------------------------ small helpers

const r = (v) => Math.round(v * 1000) / 1000;
const rad = (deg) => (deg * Math.PI) / 180;
const clamp01 = (v) => Math.max(0, Math.min(1, v));
const lerp = (a, b, t) => a + (b - a) * t;
const hex2 = (n) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, "0").toUpperCase();
const rgb = (c) => `#${hex2(c[0])}${hex2(c[1])}${hex2(c[2])}`;
const argb = (a, c) => `#${hex2(a * 255)}${hex2(c[0])}${hex2(c[1])}${hex2(c[2])}`;
const parseHex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));

const EASE = {
  in: "easeIn",
  out: "easeOut",
  inOut: "easeInOut",
  sine: { cubic: [0.37, 0, 0.63, 1] }, // easeInOutSine
  outSine: { cubic: [0.61, 1, 0.88, 1] },
  linear: "linear",
  hold: "hold",
};

// Palette (PipRenderer).
const C = {
  eyeBrown: "#8B3F37",
  ink: "#3A2A26",
  handLine: "#C4836C",
  skin: "#FDE3D2",
};

// ------------------------------------------------------------------------------------------------ path tools

/** Absolute M/L/C/Q/Z path data to a list of [cmd, [x, y]...]. */
function parsePath(d) {
  const toks = d.match(/[MLCQZ]|-?\d*\.?\d+(?:e-?\d+)?/g);
  const out = [];
  let i = 0;
  const arity = { M: 1, L: 1, C: 3, Q: 2, Z: 0 };
  while (i < toks.length) {
    const cmd = toks[i++];
    const pts = [];
    for (let k = 0; k < arity[cmd]; k++) pts.push([+toks[i++], +toks[i++]]);
    out.push([cmd, pts]);
  }
  return out;
}
const pathString = (segs) => segs.map(([c, pts]) => [c, ...pts.map((p) => `${r(p[0])} ${r(p[1])}`)].join(" ")).join(" ");
const mapPath = (d, f) => pathString(parsePath(d).map(([c, pts]) => [c, pts.map(([x, y]) => f(x, y))]));

const cubicAt = (p0, p1, p2, p3, t) => {
  const u = 1 - t;
  return [0, 1].map((k) => u * u * u * p0[k] + 3 * u * u * t * p1[k] + 3 * u * t * t * p2[k] + t * t * t * p3[k]);
};
const quadAt = (p0, p1, p2, t) => {
  const u = 1 - t;
  return [0, 1].map((k) => u * u * p0[k] + 2 * u * t * p1[k] + t * t * p2[k]);
};
const ellipsePts = (cx, cy, rx, ry, n, start = Math.PI) =>
  Array.from({ length: n }, (_, i) => {
    const a = start + (i * 2 * Math.PI) / n;
    return [cx + rx * Math.cos(a), cy + ry * Math.sin(a)];
  });

/** A closed smooth path (Catmull-Rom through [pts], as cubics). */
function smoothClosed(pts) {
  const n = pts.length;
  const P = (i) => pts[(i + n) % n];
  let d = `M ${r(pts[0][0])} ${r(pts[0][1])}`;
  for (let i = 0; i < n; i++) {
    const p0 = P(i - 1), p1 = P(i), p2 = P(i + 1), p3 = P(i + 2);
    const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
    const c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
    d += ` C ${r(c1[0])} ${r(c1[1])} ${r(c2[0])} ${r(c2[1])} ${r(p2[0])} ${r(p2[1])}`;
  }
  return d + " Z";
}
const polygon = (pts) => `M ${pts.map((p) => `${r(p[0])} ${r(p[1])}`).join(" L ")} Z`;

/** Sutherland-Hodgman: [subject] clipped by the convex polygon [clip]. */
function clipConvex(subject, clip) {
  const area = clip.reduce((s, p, i) => s + p[0] * clip[(i + 1) % clip.length][1] - clip[(i + 1) % clip.length][0] * p[1], 0);
  const sign = Math.sign(area);
  const inside = (p, a, b) => sign * ((b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])) >= 0;
  const cross = (p, q, a, b) => {
    const a1 = b[1] - a[1], b1 = a[0] - b[0], c1 = a1 * a[0] + b1 * a[1];
    const a2 = q[1] - p[1], b2 = p[0] - q[0], c2 = a2 * p[0] + b2 * p[1];
    const det = a1 * b2 - a2 * b1;
    return [(b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det];
  };
  let out = subject;
  for (let i = 0; i < clip.length; i++) {
    const a = clip[i], b = clip[(i + 1) % clip.length];
    const input = out;
    out = [];
    for (let j = 0; j < input.length; j++) {
      const p = input[j], q = input[(j + 1) % input.length];
      if (inside(q, a, b)) {
        if (!inside(p, a, b)) out.push(cross(p, q, a, b));
        out.push(q);
      } else if (inside(p, a, b)) out.push(cross(p, q, a, b));
    }
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ the traced art

const srcSvg = fs.readFileSync(path.join(repo, "apps/pip-app/app/src/main/assets/pip/pip.svg"), "utf8");
const HAT_D = srcSvg.match(/id="hat"[^>]*d="([^"]+)"/)[1];
const FACE_D = srcSvg.match(/id="face"[^>]*d="([^"]+)"/)[1];
const EYES = {
  L: { cx: 252, cy: 420.5, inner: 1 },
  R: { cx: 389, cy: 401.5, inner: -1 },
};
const RX = 21, RY = 34.5; // eye radii

/** PipRenderer.hatWarp: bends the hat towards its tip; the floppy tip (top-left) swings extra. */
const hatWarp = (sway) => (x, y) => {
  const wy = clamp01((420 - y) / 415);
  const bend = wy * wy;
  const tip = clamp01((130 - x) / 130) * clamp01((260 - y) / 260);
  return [x + sway * (38 * bend + 24 * tip), y - Math.abs(sway) * 7 * bend + sway * 10 * tip];
};

/** The face's radial shading (PipRenderer.faceShader, key light top-left) at a point: the lids are painted with it. */
function faceColorAt(x, y) {
  const d = Math.hypot((x - 175) / 360, (y - 400) / 330);
  const stops = [[0, parseHex("#FFF3EB")], [0.55, parseHex("#FDE3D2")], [1, parseHex("#F7D1BE")]];
  for (let i = 1; i < stops.length; i++) {
    if (d <= stops[i][0]) {
      const t = (d - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
      return rgb([0, 1, 2].map((k) => lerp(stops[i - 1][1][k], stops[i][1][k], t)));
    }
  }
  return rgb(stops[2][1]);
}

// ------------------------------------------------------------------------------------------------ SVG building

const groups = []; // art.groups entries
const shapes = []; // art.shapes entries
const ids = new Set();
function claim(id) {
  if (ids.has(id)) throw new Error(`duplicate id ${id}`);
  ids.add(id);
  return id;
}
const attrs = (a) => Object.entries(a).filter(([, v]) => v !== undefined && v !== null).map(([k, v]) => ` ${k}="${v}"`).join("");
/** A group that becomes a rig node. [pivot] is in root SVG units. */
function G(id, a, children, pivot) {
  claim(id);
  groups.push(pivot ? { id, pivot: { x: pivot[0], y: pivot[1] } } : { id });
  return `<g id="${id}"${attrs(a)}>\n${children.flat(Infinity).join("\n")}\n</g>`;
}
/** A plain SVG group (not emitted; only carries a transform or clip for its listed children). */
const g = (a, children) => `<g${attrs(a)}>\n${children.flat(Infinity).join("\n")}\n</g>`;
/** A shape that becomes a Rive shape. */
function S(tag, id, a) {
  claim(id);
  shapes.push({ id });
  return `<${tag} id="${id}"${attrs(a)}/>`;
}
/** A morph target: SVG geometry the clips morph a path into; not drawn. */
function T(id, d) {
  claim(id);
  return `<path id="${id}" d="${d}" fill="none"/>`;
}

const defs = [];
function radial(id, stops, transform) {
  defs.push(
    `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="1" gradientTransform="${transform}">` +
      stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join("") +
      `</radialGradient>`,
  );
}
function linear(id, [x1, y1, x2, y2], stops) {
  defs.push(
    `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}">` +
      stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join("") +
      `</linearGradient>`,
  );
}

// Gradients, in Pip's 540 x 660 units (eye ones in an eye's own frame).
radial("g-ground", [[0, "#3A2A26", 0.37], [0.5, "#3A2A26", 0.22], [1, "#3A2A26", 0]], "translate(270 664.5) scale(200 30)");
radial("g-hat", [[0, "#FB7468"], [0.48, "#F05248"], [1, "#D8443A"]], "translate(165 95) scale(430)");
radial("g-face", [[0, "#FFF3EB"], [0.55, "#FDE3D2"], [1, "#F7D1BE"]], "translate(175 400) scale(360 330)");
// A lid's paint moves with the lid, so each is matched to the face where the lid is usually seen: the upper lid
// part-lowered (heavy lids, blinks), the lower one raised (happy crescents, squints).
// The upper lid's own space starts at its apex (LID_REST). The gradient is a circle sized to give the face's
// colour at the eye: an elliptical one would put the shape in a gradient frame (rivc draws an ellipse gradient
// through an inner, non-uniformly scaled shape), where the lower lid's animated width no longer means pixels.
const LID_SEEN = { up: 28 - (-RY - 6), low: -40 };
for (const [s, e] of Object.entries(EYES)) {
  const dx = e.cx - 175, dy = e.cy - 400;
  const radius = Math.hypot(dx, dy) / Math.hypot(dx / 360, dy / 330);
  for (const [part, shift] of Object.entries(LID_SEEN)) {
    radial(`g-lid-${s}-${part}`, [[0, "#FFF3EB"], [0.55, "#FDE3D2"], [1, "#F7D1BE"]],
      `translate(0 ${-shift}) rotate(8) translate(${-dx} ${-dy}) scale(${r(radius)})`);
  }
}
linear("g-chin", [0, 540, 0, 662], [[0, "#EC9678", 0], [1, "#EC9678", 0.18]]);
radial("g-blush-L", [[0, "#F2706E", 0.67], [0.5, "#F2706E", 0.35], [1, "#F2706E", 0]], "translate(182 500) scale(46 26)");
radial("g-blush-R", [[0, "#F2706E", 0.67], [0.5, "#F2706E", 0.35], [1, "#F2706E", 0]], "translate(468 485) scale(46 26)");
radial("g-eye", [[0, "#A9564B"], [0.5, "#8B3F37"], [1, "#68271F"]], `translate(${-RX * 0.35} ${-RY * 0.4}) scale(${RY * 1.25})`);
radial("g-hand", [[0, "#FFF5EE"], [0.5, "#FDE3D2"], [1, "#F1BFA7"]], "translate(-17 -17) scale(66)");
linear("g-blade", [-12, 0, 12, 0], [[0, "#FBFDFF"], [0.47, "#DCE5EC"], [0.53, "#AEBBC6"], [1, "#E3EAF0"]]);
linear("g-guard", [0, -56, 0, -36], [[0, "#FBE19A"], [0.5, "#DDA840"], [1, "#9C6B1C"]]);
linear("g-phone", [-26, 0, 26, 0], [[0, "#B6C1CB"], [0.45, "#8693A0"], [1, "#5F6B77"]]);
linear("g-screen", [0, -158, 0, -118], [[0, "#D8F1FF"], [0.55, "#7CC0F2"], [1, "#4E95D6"]]);
defs.push(`<clipPath id="clip-hat"><path d="${HAT_D}"/></clipPath>`);
defs.push(`<clipPath id="clip-face"><path d="${FACE_D}"/></clipPath>`);
// Lids are clipped a touch outside the eye, so they cover its antialiased rim completely.
defs.push(`<clipPath id="clip-eye"><ellipse cx="0" cy="0" rx="${RX + 1.5}" ry="${RY + 1.5}"/></clipPath>`);

// --- Lid geometry (eye frame: centre 0,0, rotated -8 degrees). PipRenderer.eye/lidEdge.
// Upper lid: everything above a gently arched edge whose apex sits at the node origin.
const LID_D = "M -90 -400 L 90 -400 L 90 46 Q 0 -46 -90 46 Z";
const LID_REST = -RY - 6; // apex just above the eye
const LID_TRAVEL = 56; // apex down to the meeting line (0.4 ry), a little past it so the lids overlap
const LOW_TOP_REST = RY + 3; // lower lid's top edge at rest, just below the eye
const LOW_RY = RY + 5; // lower lid oval: 2 ry + 10 tall
const LOW_W = 2 * RX * 2.4; // wide and flat; happy narrows it to 2 rx * 1.25 so the cut curves into a crescent
const LOW_W_HAPPY = 2 * RX * 1.25;
const LOW_BLINK = -25; // lower lid rises to meet the upper one
const SQUINT_TRAVEL = -(1.15 * RY + 3); // squint 1 raises the lower lid this far
const MEET = 0.4 * RY;
const CLOSED_D = `M ${-RX * 1.15} ${MEET - 2.8} Q 0 ${MEET + 7} ${RX * 1.15} ${MEET - 2.8}`;
const CLOSED_OWNERS = ["a", "b", "c", "open"];
const HAPPY_LOW_TOP = Math.max(RY * (1 - 1.15 * 0.72) + 0.28 * 3 - RY * 0.6, -RY * 0.45);

// ------------------------------------------------------------------------------------------------ mouths

const MX = 322, MY = 505; // PipRenderer.drawMouth centre
const N_MOUTH = 24;
/** Outline of a round-capped stroke along [at] (t in 0..1): left cap, top side, right cap, bottom side. */
function strokeOutline(at, w) {
  const top = [], bottom = [];
  const K = 11;
  const pt = (t) => at(Math.max(0, Math.min(1, t)));
  const tangent = (t) => {
    const a = pt(t - 0.01), b = pt(t + 0.01);
    const l = Math.hypot(b[0] - a[0], b[1] - a[1]);
    return [(b[0] - a[0]) / l, (b[1] - a[1]) / l];
  };
  for (let i = 0; i < K; i++) {
    const t = 0.02 + (0.96 * i) / (K - 1);
    const p = pt(t), [tx, ty] = tangent(t);
    const n = [ty, -tx]; // points up when the stroke runs left to right
    top.push([p[0] + (n[0] * w) / 2, p[1] + (n[1] * w) / 2]);
    bottom.unshift([p[0] - (n[0] * w) / 2, p[1] - (n[1] * w) / 2]);
  }
  const t0 = tangent(0), t1 = tangent(1), p0 = pt(0), p1 = pt(1);
  const capL = [p0[0] - (t0[0] * w) / 2, p0[1] - (t0[1] * w) / 2];
  const capR = [p1[0] + (t1[0] * w) / 2, p1[1] + (t1[1] * w) / 2];
  return [capL, ...top, capR, ...bottom];
}
const SMILE_AT = (t) => cubicAt([MX - 29, MY - 3], [MX - 16, MY + 20], [MX + 16, MY + 20], [MX + 29, MY - 3], t);
const SHOUT_TOP = (t) => quadAt([MX - 40, MY - 6], [MX, MY - 12], [MX + 40, MY - 10], t);
const SHOUT_BOTTOM = (t) => cubicAt([MX + 40, MY - 10], [MX + 38, MY + 46], [MX - 38, MY + 50], [MX - 40, MY - 6], t);
const shoutPts = [[MX - 40, MY - 6]];
for (let k = 1; k < 12; k++) shoutPts.push(SHOUT_TOP(k / 12));
shoutPts.push([MX + 40, MY - 10]);
for (let k = 1; k < 12; k++) shoutPts.push(SHOUT_BOTTOM(k / 12));
const smileOutline = strokeOutline(SMILE_AT, 11);
const MOUTHS = {
  // index = the Kotlin Mouth ordinal (NONE, SMILE, FROWN, O, SHOUT, FLAT), which the `mouth` channel uses
  none: { i: 0, pts: smileOutline.map(([x, y]) => [MX + (x - MX) * 0.04, MY + 6 + (y - MY - 6) * 0.04]), fill: C.eyeBrown, stroke: false },
  smile: { i: 1, pts: smileOutline, fill: C.eyeBrown, stroke: false },
  frown: {
    i: 2,
    pts: strokeOutline((t) => cubicAt([MX - 25, MY + 24], [MX - 14, MY + 6], [MX + 14, MY + 6], [MX + 25, MY + 24], t), 10),
    fill: C.eyeBrown,
    stroke: false,
  },
  o: { i: 3, pts: ellipsePts(MX, MY + 13, 17, 20, N_MOUTH), fill: C.eyeBrown, stroke: false },
  shout: { i: 4, pts: shoutPts, fill: "#5A211D", stroke: true },
  flat: { i: 5, pts: strokeOutline((t) => quadAt([MX - 22, MY + 8], [MX, MY + 4], [MX + 22, MY + 5], t), 10), fill: C.eyeBrown, stroke: false },
};
for (const [k, m] of Object.entries(MOUTHS)) if (m.pts.length !== N_MOUTH) throw new Error(`mouth ${k}: ${m.pts.length} points`);
const MOUTH_BY_INDEX = Object.fromEntries(Object.entries(MOUTHS).map(([k, m]) => [m.i, k]));

/** One mouth set: the morphing outline plus the details each shape fades in (smile ticks, O's inside, shout's teeth). */
function mouthSet(prefix, mainId) {
  const oInner = ellipsePts(MX, MY + 13.5, 12, 14.5, 40, 0);
  const shout = (() => {
    const pts = [];
    for (let k = 0; k <= 20; k++) pts.push(SHOUT_TOP(k / 20));
    for (let k = 1; k < 20; k++) pts.push(SHOUT_BOTTOM(k / 20));
    return pts;
  })();
  return [
    S("path", mainId, { d: smoothClosed(MOUTHS.none.pts), fill: C.eyeBrown, stroke: "#006E2B26", "stroke-width": 6, "stroke-linejoin": "round" }),
    G(`${prefix}-smile`, { opacity: 0 }, [
      S("path", `${prefix}-ticks`, {
        d: `M ${MX - 29} ${MY - 3} Q ${MX - 37} ${MY - 4} ${MX - 39} ${MY - 12} M ${MX + 29} ${MY - 3} Q ${MX + 37} ${MY - 4} ${MX + 39} ${MY - 12}`,
        fill: "none", stroke: C.eyeBrown, "stroke-opacity": 0.75, "stroke-width": 6.5, "stroke-linecap": "round",
      }),
    ]),
    G(`${prefix}-o`, { opacity: 0 }, [
      S("path", `${prefix}-o-inner`, { d: polygon(oInner), fill: "#4C1C18" }),
      S("path", `${prefix}-o-tongue`, { d: polygon(clipConvex(ellipsePts(MX, MY + 27, 12, 9, 40, 0), oInner)), fill: "#D9746E" }),
      S("ellipse", `${prefix}-o-shine`, { cx: MX + 8, cy: MY - 2, rx: 4, ry: 3, fill: "#FFFFFF", "fill-opacity": 0.27 }),
    ]),
    G(`${prefix}-shout`, { opacity: 0 }, [
      S("path", `${prefix}-shout-teeth`, {
        d: polygon(clipConvex([[MX - 44, MY - 16], [MX + 44, MY - 16], [MX + 44, MY + 1], [MX - 44, MY + 1]], shout)),
        fill: "#FFF7EF",
      }),
      S("path", `${prefix}-shout-tongue`, { d: polygon(clipConvex(ellipsePts(MX, MY + 37, 26, 19, 40, 0), shout)), fill: "#E0807A" }),
      S("path", `${prefix}-shout-groove`, { d: `M ${MX} ${MY + 26} L ${MX} ${MY + 36}`, fill: "none", stroke: "#B95B56", "stroke-width": 4, "stroke-linecap": "round" }),
    ]),
  ];
}

// ------------------------------------------------------------------------------------------------ hands

const rrect = (id, x, y, w, h, rr, a = {}) => S("rect", id, { x, y, width: w, height: h, rx: rr, ry: rr, ...a });
/** Hand outlines as parts; drawn twice (strokes, then fills on top) so overlaps read as one outline. */
function handParts(side, kind) {
  const p = `hand-${side}-${kind}`;
  const parts = {
    fist: [["circle", { cx: 0, cy: 0, r: 36 }]],
    point: [["circle", { cx: 0, cy: 0, r: 35 }], ["rect", { x: -12, y: -100, width: 24, height: 86, rx: 12, ry: 12 }]],
    peace: [
      ["circle", { cx: 0, cy: 0, r: 35 }],
      ["rect", { x: -11, y: -98, width: 22, height: 84, rx: 11, ry: 11, transform: "rotate(-11)" }],
      ["rect", { x: -11, y: -98, width: 22, height: 84, rx: 11, ry: 11, transform: "rotate(15)" }],
    ],
    palm: [
      ["rect", { x: -44, y: -36, width: 88, height: 76, rx: 30, ry: 30 }],
      ...[-80, -92, -88, -74].map((top, f) => ["rect", { x: -33 + f * 22 - 10, y: top, width: 20, height: -14 - top, rx: 10, ry: 10 }]),
      ["rect", { x: -11, y: -52, width: 22, height: 56, rx: 11, ry: 11, transform: "translate(40 2) rotate(58)" }],
    ],
  }[kind];
  const strokes = parts.map(([tag, a], i) => S(tag, `${p}-line${i}`, { ...a, fill: "none", stroke: C.handLine, "stroke-width": 7 }));
  const fills = parts.map(([tag, a], i) => S(tag, `${p}-skin${i}`, { ...a, fill: "url(#g-hand)" }));
  const extra = kind === "fist"
    ? [S("path", `${p}-fingers`, {
        d: "M -12 -34 L -10 -20 M 6 -35 L 6 -21 M 22 -27 L 19 -15 M -30 -2 Q -8 14 16 4",
        fill: "none", stroke: C.handLine, "stroke-opacity": 0.78, "stroke-width": 4, "stroke-linecap": "round",
      })]
    : [];
  return G(p, { opacity: 0 }, [...strokes, ...fills, ...extra]);
}
function sword(side) {
  const p = `prop-${side}-sword`;
  return G(p, { opacity: 0 }, [
    S("path", `${p}-blade`, { d: "M -12 -44 L 12 -44 L 12 -326 L 0 -372 L -12 -326 Z", fill: "url(#g-blade)", stroke: "#7F8C97", "stroke-width": 3 }),
    S("path", `${p}-edge`, { d: "M -7 -64 L -7 -318", fill: "none", stroke: "#FFFFFF", "stroke-opacity": 0.86, "stroke-width": 3 }),
    rrect(`${p}-grip`, -10, -40, 20, 94, 6, { fill: "#3B2A22" }),
    rrect(`${p}-guard`, -42, -56, 84, 20, 10, { fill: "url(#g-guard)", stroke: "#8A5F18", "stroke-width": 2.5 }),
    S("circle", `${p}-guard-l`, { cx: -44, cy: -46, r: 9, fill: "#DDA840" }),
    S("circle", `${p}-guard-r`, { cx: 44, cy: -46, r: 9, fill: "#DDA840" }),
    S("circle", `${p}-pommel`, { cx: 0, cy: 58, r: 13, fill: "#D49B33" }),
    S("circle", `${p}-gem`, { cx: 0, cy: -46, r: 6.5, fill: "#D2453C" }),
  ]);
}
function phone(side) {
  const p = `prop-${side}-phone`;
  return G(p, { opacity: 0 }, [
    S("path", `${p}-antenna`, { d: "M 15 -160 L 15 -178", fill: "none", stroke: "#3E4852", "stroke-width": 5, "stroke-linecap": "round" }),
    rrect(`${p}-base`, -25, -88, 50, 82, 12, { fill: "url(#g-phone)", stroke: "#3E4852", "stroke-width": 3 }),
    rrect(`${p}-lid`, -25, -166, 50, 70, 12, { fill: "url(#g-phone)", stroke: "#3E4852", "stroke-width": 3 }),
    rrect(`${p}-hinge`, -21, -100, 42, 15, 7, { fill: "#56616C" }),
    rrect(`${p}-bezel`, -19, -158, 38, 44, 5, { fill: "#2A3138" }),
    rrect(`${p}-screen`, -16, -155, 32, 38, 3, { fill: "url(#g-screen)" }),
    ...[0, 1, 2].flatMap((row) => [0, 1, 2].map((q) => rrect(`${p}-key${row}${q}`, -14 + q * 14 - 5.5, -58 + row * 12 - 4, 11, 8, 3, { fill: "#E6EBF0" }))),
  ]);
}
const HAND_DEFAULT = { L: { x: 40, y: 560, angle: 0 }, R: { x: 500, y: 560, angle: 0 } };
// index = the Kotlin Hand ordinal (NONE, FIST, POINT, PEACE, PALM, SWORD, PHONE); CUBE isn't drawn
const HAND_KINDS = ["none", "fist", "point", "peace", "palm", "sword", "phone"];
function hand(side) {
  const d = HAND_DEFAULT[side];
  return G(`hand-${side}`, { transform: `translate(${d.x} ${d.y})` }, [
    G(`hand-${side}-turn`, {}, [
      sword(side),
      phone(side),
      g({ transform: side === "L" ? "scale(-1 1)" : undefined }, ["fist", "point", "peace", "palm"].map((k) => handParts(side, k))),
    ]),
  ]);
}

// ------------------------------------------------------------------------------------------------ headwear
// PipRenderer.drawHeadwear: a band along one curve over the brow, drawn after the mouth and tipped with the hat's
// droop. Each kind is a group gated by the `headwear` enum. The ninja tails are drawn at rest: in Kotlin they
// trail the hat sway, which here is a vertex morph the tails can't follow.

const HEADWEAR = ["none", "ninja", "flowers", "sweatband"]; // index = Kotlin Headwear ordinal (inputs control)
linear("g-plate", [0, -17, 0, 17], [[0, "#F3F6F8"], [0.5, "#BCC5CD"], [1, "#8A96A1"]]);
const BAND = [[40, 390], [280, 240], [528, 352]];
const BAND_D = `M ${BAND[0].join(" ")} Q ${BAND[1].join(" ")} ${BAND[2].join(" ")}`;
const bandAt = (t) => quadAt(BAND[0], BAND[1], BAND[2], t);
const bandStroke = (id, dy, color, width) =>
  S("path", id, { d: mapPath(BAND_D, (x, y) => [x, y + dy]), fill: "none", stroke: color, "stroke-width": width, "stroke-linecap": "round", "stroke-linejoin": "round" });
const mixHex = (a, b, t) => rgb([0, 1, 2].map((k) => lerp(parseHex(a)[k], parseHex(b)[k], t)));

/** An ellipse as four cubics through [map] (so many can share one path, one subpath each). */
function ellipseD(cx, cy, rx, ry, map = (x, y) => [x, y]) {
  const k = 0.5523;
  const P = (x, y) => map(cx + x, cy + y).map(r).join(" ");
  return `M ${P(rx, 0)} C ${P(rx, ry * k)} ${P(rx * k, ry)} ${P(0, ry)} C ${P(-rx * k, ry)} ${P(-rx, ry * k)} ${P(-rx, 0)} ` +
    `C ${P(-rx, -ry * k)} ${P(-rx * k, -ry)} ${P(0, -ry)} C ${P(rx * k, -ry)} ${P(rx, -ry * k)} ${P(rx, 0)} Z`;
}
/** translate(tx, ty) scale(k) rotate(deg), as a point map. */
const place = (tx, ty, k, deg) => (x, y) => {
  const c = Math.cos(rad(deg)), s = Math.sin(rad(deg));
  return [tx + k * (x * c - y * s), ty + k * (x * s + y * c)];
};
/** PipRenderer.ribbon: a ribbon tapering from [w0] to [w1] along a quadratic. */
function ribbonD(p0, q, p1, w0, w1) {
  const n = 10, side = [[], []];
  for (let i = 0; i <= n; i++) {
    const t = i / n;
    const [x, y] = quadAt(p0, q, p1, t);
    let tx = 2 * (1 - t) * (q[0] - p0[0]) + 2 * t * (p1[0] - q[0]);
    let ty = 2 * (1 - t) * (q[1] - p0[1]) + 2 * t * (p1[1] - q[1]);
    const len = Math.max(Math.hypot(tx, ty), 1e-3);
    tx /= len; ty /= len;
    const w = (w0 + (w1 - w0) * t) / 2;
    side[0].push([x - ty * w, y + tx * w]);
    side[1].unshift([x + ty * w, y - tx * w]);
  }
  return polygon([...side[0], ...side[1]]);
}
/** Dashes (9 on, 9 off) along the band moved by [dy], as one path of segments; rivc has no dash arrays. */
function dashesD(dy) {
  const pts = Array.from({ length: 401 }, (_, i) => bandAt(i / 400));
  let d = "", run = 0, on = true, prev = pts[0];
  let seg = [prev];
  for (const p of pts.slice(1)) {
    run += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
    if (on) seg.push(p);
    if (run >= 9) {
      if (on) d += `M ${seg.map(([x, y]) => `${r(x)} ${r(y + dy)}`).join(" L ")} `;
      on = !on; run = 0; seg = [p];
    }
    prev = p;
  }
  return d.trim();
}

function ninja() {
  return G("headwear-ninja", { opacity: 0 }, [
    S("path", "ninja-tail-a", { d: ribbonD([44, 382], [-4, 352], [-62, 330], 24, 11), fill: "#1B191B" }),
    S("path", "ninja-tail-b", { d: ribbonD([44, 396], [4, 426], [-48, 452], 22, 10), fill: "#232023" }),
    bandStroke("ninja-band", 0, "#262326", 44),
    bandStroke("ninja-band-top", -13, "#3D393D", 9),
    bandStroke("ninja-band-low", 15, "#141214", 8),
    S("ellipse", "ninja-knot", { cx: 43, cy: 389, rx: 19, ry: 19, fill: "#2E2A2E" }),
    S("ellipse", "ninja-knot-shine", { cx: 40, cy: 381, rx: 8, ry: 5, fill: "#FFFFFF", "fill-opacity": r(60 / 255) }),
    g({ transform: "translate(284 299) rotate(-4.5)" }, [
      S("rect", "ninja-plate", { x: -36, y: -17, width: 72, height: 34, rx: 8, ry: 8, fill: "url(#g-plate)", stroke: "#5E6873", "stroke-width": 3 }),
      S("path", "ninja-plate-mark", { d: "M -14 6 C -8 -10 8 -10 6 0 C 4 7 -4 6 -3 0 M 10 6 L 18 -6", fill: "none", stroke: "#5E6873", "stroke-width": 4, "stroke-linecap": "round", "stroke-linejoin": "round" }),
      S("path", "ninja-plate-rivets", { d: [[-29, -10], [-29, 10], [29, -10], [29, 10]].map(([x, y]) => ellipseD(x, y, 2.6, 2.6)).join(" "), fill: "#6E7883" }),
      S("rect", "ninja-plate-shine", { x: -30, y: -14, width: 60, height: 2, fill: "#FFFFFF", "fill-opacity": r(150 / 255) }),
    ]),
  ]);
}

function flowers() {
  const PETALS = ["#FFD23F", "#EE4266", "#3BCEAC", "#A06CD5", "#FF8C42"];
  const leaves = Array.from({ length: 6 }, (_, i) => place(...bandAt((i + 0.5) / 6), 1, i % 2 === 0 ? -35 : 35));
  const parts = [
    S("path", "flowers-leaves", { d: leaves.map((m) => ellipseD(0, 0, 22, 9, m)).join(" "), fill: "#4FA65D" }),
    S("path", "flowers-veins", { d: leaves.map((m) => `M ${m(-16, 0).map(r).join(" ")} L ${m(16, 0).map(r).join(" ")}`).join(" "), fill: "none", stroke: "#3C8449", "stroke-width": 2.5, "stroke-linecap": "round" }),
  ];
  for (let i = 0; i <= 6; i++) {
    const color = PETALS[i % PETALS.length];
    const m = place(...bandAt(i / 6), i % 2 === 0 ? 1 : 0.8, i * 23);
    const turn = (deg) => (x, y) => m(...place(0, 0, 1, deg)(x, y));
    const ring = (off, cy, rx, ry) => Array.from({ length: 5 }, (_, p) => ellipseD(0, cy, rx, ry, turn(p * 72 + off))).join(" ");
    parts.push(
      S("path", `flower${i}-back`, { d: ring(36, -17, 10, 13), fill: mixHex(color, "#000000", 0.18) }),
      S("path", `flower${i}-front`, { d: ring(0, -14.5, 11, 12.5), fill: color }),
      S("path", `flower${i}-light`, { d: Array.from({ length: 5 }, (_, p) => ellipseD(-1, -18, 4, 6, turn(p * 72))).join(" "), fill: mixHex(color, "#FFFFFF", 0.35) }),
      S("path", `flower${i}-heart`, { d: ellipseD(0, 0, 10, 10, m), fill: "#FFE58C" }),
      S("path", `flower${i}-dots`, { d: Array.from({ length: 6 }, (_, k) => ellipseD(5.5 * Math.cos(rad(k * 60)), 5.5 * Math.sin(rad(k * 60)), 1.8, 1.8, m)).join(" "), fill: "#E0A33B" }),
      S("path", `flower${i}-shine`, { d: ellipseD(-3, -3.5, 2.6, 2.6, m), fill: "#FFFFFF", "fill-opacity": r(150 / 255) }),
    );
  }
  return G("headwear-flowers", { opacity: 0 }, parts);
}

function sweatband() {
  return G("headwear-sweatband", { opacity: 0 }, [
    bandStroke("sweatband-band", 0, "#FF2E9A", 46),
    bandStroke("sweatband-top", -14, "#FF6DBB", 10),
    bandStroke("sweatband-low", 15, "#D3157A", 10),
    bandStroke("sweatband-stripe", 0, "#2EF3FF", 10),
    ...[-18, 18].map((dy, i) => S("path", `sweatband-stitch${i}`, { d: dashesD(dy), fill: "none", stroke: "#FFEBF6", "stroke-opacity": r(185 / 255), "stroke-width": 3.5, "stroke-linecap": "round" })),
  ]);
}

const headwear = () => G("headwear", {}, [G("headwear-droop", {}, [ninja(), flowers(), sweatband()], [300, 430])]);

// ------------------------------------------------------------------------------------------------ the SVG

function eye(s) {
  const e = EYES[s];
  // Lids are painted with the face's own shading, expressed in the eye's frame, so they vanish into the face.
  const lid = (part) => `url(#g-lid-${s}-${part})`;
  const lidChain = ["mood", "chan", "open", "look", "blink-a", "blink-b", "blink-c"];
  const lowChain = ["mood", "chan", "open", "blink-a", "blink-b", "blink-c"];
  const nest = (names, inner) => names.reduceRight((acc, n) => G(n, {}, [acc]), inner);
  const upper = G(`lidTop-${s}-mood`, { transform: `translate(0 ${LID_REST})` }, [
    nest(lidChain.slice(1).map((n) => `lidTop-${s}-${n}`).concat([`lidTop-${s}-tilt`, `lidTop-${s}-tilt2`]), [
      S("path", `lidTop-${s}`, { d: LID_D, fill: lid("up") }),
      // The lash line along the lid's edge; it doubles as the closed eye's line.
      S("path", `lidTop-${s}-lash`, { d: "M 90 46 Q 0 -46 -90 46", fill: "none", stroke: "#4A1E19", "stroke-opacity": 0.8, "stroke-width": 5, "stroke-linecap": "round" }),
    ]),
  ]);
  const lower = nest(lowChain.map((n) => `squint-${s}-${n}`), [
    S("ellipse", `squint-${s}`, { cx: 0, cy: LOW_TOP_REST + LOW_RY, rx: LOW_W / 2, ry: LOW_RY, fill: lid("low") }),
  ]);
  return G(`eye${s}`, { transform: `translate(${e.cx} ${e.cy}) rotate(-8)` }, [
    G(`eye${s}-scale`, {}, [
      G(`eye${s}-react`, {}, [
        S("ellipse", `eye${s}-ball`, { cx: 0, cy: 0, rx: RX, ry: RY, fill: "url(#g-eye)" }),
        G(`eye${s}-hl-look`, {}, [
          G(`eye${s}-hl`, { transform: "translate(-7 -14.5)" }, [S("circle", `eye${s}-hl-dot`, { cx: 0, cy: 0, r: 6.2, fill: "#FFFFFF", "fill-opacity": 0.9 })]),
          G(`eye${s}-hl2`, { transform: "translate(6.5 13.8)" }, [S("circle", `eye${s}-hl2-dot`, { cx: 0, cy: 0, r: 2.6, fill: "#FFFFFF", "fill-opacity": 0.43 })]),
        ]),
        G(`eye${s}-lids`, { "clip-path": "url(#clip-eye)" }, [lower, upper]),
        // Shut eyes read as a soft "content" curve (PipRenderer: opening < 7). One per owner, since opacities
        // multiply: each blink rhythm and the eyesOpen channel show their own.
        ...CLOSED_OWNERS.map((o) => S("path", `closed-${s}-${o}`, { d: CLOSED_D, fill: "none", stroke: C.eyeBrown, "stroke-width": 8, "stroke-linecap": "round", opacity: 0 })),
      ]),
    ]),
  ]);
}

function brow(s) {
  const e = EYES[s];
  const bx = e.cx + e.inner * 2, by = e.cy - RY - 21;
  const d = "M -27 3 Q 0 -9 27 3";
  const st = { fill: "none", stroke: C.eyeBrown, "stroke-width": 12.5, "stroke-linecap": "round" };
  return G(`brow${s}`, { transform: `translate(${bx} ${by}) rotate(-8)` }, [
    G(`brow${s}-mood`, {}, [
      G(`brow${s}-chan`, {}, [
        G(`brow${s}-life`, {}, [
          S("path", `brow${s}-a`, { d, ...st, opacity: 0 }),
          G(`brow${s}-gate`, { opacity: 0 }, [S("path", `brow${s}-b`, { d, ...st })]),
        ]),
      ]),
    ]),
  ]);
}

const happyArch = `M ${-RX * 1.15} ${-RY * 0.4 + 5.6} Q 0 ${-RY * 0.4 - 14} ${RX * 1.15} ${-RY * 0.4 + 5.6}`;
const blushes = (owner, suffix) =>
  ["L", "R"].map((s) =>
    S("ellipse", `blush-${s}${suffix}`, {
      cx: s === "L" ? 182 : 468, cy: s === "L" ? 500 : 485, rx: 46, ry: 26, fill: `url(#g-blush-${s})`, opacity: 0,
    }),
  );

const FEET = [270, 660];
const HAT_PIVOT = [300, 430];
const SHADOW = [270, 664.5];
const svgBody = [
  G("shadow", {}, [G("shadow-alert", {}, [G("shadow-poke", {}, [S("ellipse", "shadow-blob", { cx: 270, cy: 664.5, rx: 200, ry: 30, fill: "url(#g-ground)" })], SHADOW)], SHADOW)], SHADOW),
  G("body-alert", {}, [
    G("body-poke", {}, [
      G("body-breath", {}, [
        G("hat-droop", {}, [
          G("hat-react", {}, [
            G("hat-poke", {}, [
              G("hat-life-a", {}, [G("hat-life-b", {}, [
                S("path", "hat", { d: HAT_D, fill: "url(#g-hat)" }),
                // Occlusion: the hat darkens where it tucks behind the face. The clip turns with the hat; the
                // shading is turned back by the droop so it stays on the face's edge.
                G("hat-shade", { "clip-path": "url(#clip-hat)" }, [
                  G("hat-shade-level", {}, [
                    S("path", "hat-shade-wide", { d: FACE_D, fill: "none", stroke: "#781410", "stroke-opacity": 0.133, "stroke-width": 46 }),
                    S("path", "hat-shade-near", { d: FACE_D, fill: "none", stroke: "#781410", "stroke-opacity": 0.157, "stroke-width": 24 }),
                  ], HAT_PIVOT),
                ]),
              ], [300, 445])], [300, 445]),
            ], HAT_PIVOT),
          ], HAT_PIVOT),
        ], HAT_PIVOT),
        S("path", "face", { d: FACE_D, fill: "url(#g-face)" }),
        S("path", "face-chin", { d: FACE_D, fill: "url(#g-chin)" }),
        // A soft inner shadow under the hat line: three stacked bands (face minus itself moved down).
        G("face-bands", { "clip-path": "url(#clip-face)" }, [7, 15, 26].map((o, i) =>
          S("path", `face-band${i}`, { d: `${FACE_D} ${mapPath(FACE_D, (x, y) => [x, y + o])}`, "fill-rule": "evenodd", fill: "#CD785F", "fill-opacity": 0.078 }),
        )),
        ...blushes("chan", ""),
        ...blushes("mood", "-mood"),
        ...blushes("poke", "-poke"),
        G("eyes-look", {}, [
          G("eyes-dart-a", {}, [
            G("eyes-dart-b", {}, [
              G("eyes-dart-c", {}, [
                G("eyes-poke", {}, [eye("L"), eye("R")]),
                G("eyes-giggle", { opacity: 0 }, ["L", "R"].map((s) =>
                  g({ transform: `translate(${EYES[s].cx} ${EYES[s].cy}) rotate(-8)` }, [
                    S("path", `giggle-${s}`, { d: happyArch, fill: "none", stroke: C.eyeBrown, "stroke-width": 8, "stroke-linecap": "round" }),
                  ]),
                )),
              ]),
            ]),
          ]),
        ]),
        G("brows-look", {}, [brow("L"), brow("R")]),
        G("mouth-look", {}, [
          G("mouth-poke-gate", {}, [
            G("mouth-talk-gate", {}, [
              G("mouth-mood-gate", {}, mouthSet("mouth-mood", "mouth-mood")),
              G("mouth-chan-gate", { opacity: 0 }, mouthSet("mouth-chan", "mouth")),
            ]),
          ]),
          G("mouth-talk", { opacity: 0 }, [
            G("mouth-talk-flap", {}, [
              S("ellipse", "mouth-talk-shape", { cx: MX, cy: MY + 11, rx: 15, ry: 12, fill: "#5A211D", stroke: "#6E2B26", "stroke-width": 3 }),
              S("path", "mouth-talk-tongue", { d: polygon(clipConvex(ellipsePts(MX, MY + 21, 10, 6, 32, 0), ellipsePts(MX, MY + 11, 13.5, 10.5, 40, 0))), fill: "#E0807A" }),
            ], [MX, MY]),
          ]),
          G("mouth-giggle", { opacity: 0 }, (() => {
            const top = (t) => quadAt([MX - 30, MY - 2], [MX, MY + 2], [MX + 30, MY - 2], t);
            const bot = (t) => cubicAt([MX + 30, MY - 2], [MX + 26, MY + 30], [MX - 26, MY + 30], [MX - 30, MY - 2], t);
            const pts = [];
            for (let k = 0; k <= 16; k++) pts.push(top(k / 16));
            for (let k = 1; k < 16; k++) pts.push(bot(k / 16));
            return [
              S("path", "mouth-giggle-shape", {
                d: `M ${MX - 30} ${MY - 2} Q ${MX} ${MY + 2} ${MX + 30} ${MY - 2} C ${MX + 26} ${MY + 30} ${MX - 26} ${MY + 30} ${MX - 30} ${MY - 2} Z`,
                fill: "#5A211D", stroke: "#6E2B26", "stroke-width": 5, "stroke-linejoin": "round",
              }),
              S("path", "mouth-giggle-tongue", { d: polygon(clipConvex(ellipsePts(MX, MY + 21, 15, 7, 32, 0), pts)), fill: "#E0807A" }),
            ];
          })()),
        ]),
        headwear(),
        hand("L"),
        hand("R"),
      ], FEET),
    ], FEET),
  ], FEET),
];

// Morph targets (not drawn): the hat bent left and right, and every mouth.
const targets = [
  T("hat-sway-left", mapPath(HAT_D, hatWarp(-1))),
  T("hat-sway-right", mapPath(HAT_D, hatWarp(1))),
  ...Object.entries(MOUTHS).map(([k, m]) => T(`mouth-is-${k}`, smoothClosed(m.pts))),
];

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 540 660" width="540" height="660">
<!-- Generated by build-pip.mjs from the Kotlin Pip (PipRenderer, PipArt). Feet at (270, 660). -->
<defs>
${defs.join("\n")}
</defs>
${svgBody.join("\n")}
<g id="morph-targets">
${targets.join("\n")}
</g>
</svg>
`;

// ------------------------------------------------------------------------------------------------ pass 1: setup pose

const ARTBOARD = { name: "Pip", width: 540, height: 660, padding: { top: 110, right: 90, bottom: 50, left: 90 } };
const art = { svg: "pip.svg", groups, shapes };
fs.writeFileSync(path.join(here, "pip.svg"), svg);
fs.writeFileSync(path.join(tmp, "pip.svg"), svg);
fs.writeFileSync(path.join(tmp, "art.json"), JSON.stringify({ format: 1, artboard: ARTBOARD, art }));
execFileSync(rivc, ["compile", path.join(tmp, "art.json"), "-o", path.join(tmp, "art.riv")], { stdio: ["ignore", "ignore", "inherit"] });
const dump = execFileSync(rivc, ["dump", path.join(tmp, "art.riv")], { encoding: "utf8" });
const setup = {};
for (const line of dump.split("\n")) {
  const m = line.match(/^#\d+ (Node|Shape) \[\d+\] name="([^"]+)"(.*)$/);
  if (!m) continue;
  const prop = (k, d) => {
    const mm = m[3].match(new RegExp(`\\b${k}=(-?[\\d.eE-]+)`));
    return mm ? +mm[1] : d;
  };
  setup[m[2]] = { x: prop("x", 0), y: prop("y", 0), rotation: prop("rotation", 0), scaleX: prop("scaleX", 1), scaleY: prop("scaleY", 1), opacity: prop("opacity", 1) };
}
const base = (id) => {
  if (!setup[id]) throw new Error(`no node ${id} in the compiled art`);
  return setup[id];
};

// ------------------------------------------------------------------------------------------------ clip building

const FPS = 60;
const sec = (s) => Math.round(s * FPS);

/** A pose: values per (target, property). Translation and rotation are offsets from setup; scale multiplies it. */
function poseTracks(pose, frame = 0) {
  const tracks = [];
  for (const [target, props] of Object.entries(pose)) {
    for (const [prop, v] of Object.entries(props)) tracks.push({ target, property: prop, keys: [key(target, prop, v, frame)] });
  }
  return tracks;
}
function value(target, prop, v) {
  if (prop === "vertices") return { morph: v };
  if (prop === "colorValue") return { color: v };
  if (prop === "width" || prop === "height" || prop === "opacity") return { value: r(v) };
  const b = base(target.split("/")[0]);
  if (prop === "x" || prop === "y" || prop === "rotation") return { value: r(b[prop] + v) };
  if (prop === "scaleX" || prop === "scaleY") return { value: r(b[prop] * v) };
  throw new Error(prop);
}
function key(target, prop, v, frame, ease) {
  const k = { frame, ...value(target, prop, v) };
  if (ease && ease !== "linear") k.ease = ease;
  return k;
}
/** A track from [[frame, value, ease?], ...] in offsets. */
const track = (target, prop, keys) => ({ target, property: prop, keys: keys.map(([f, v, e]) => key(target, prop, v, f, e)) });
const clip = (name, frames, tracks, loop) => ({ name, frames, ...(loop ? { loop } : {}), tracks });

const clips = [];
const layers = [];
const vmProps = [];
const inputs = [];
function number(name, dflt) {
  vmProps.push({ name, type: "number", default: dflt });
  inputs.push({ name, type: "number", default: dflt });
}
/** A layer with one 1D blend on [by]; [poses] maps a value to a pose. */
function blendLayer(layerName, by, poses) {
  const blendClips = Object.entries(poses).map(([v, pose]) => {
    const name = `${layerName}@${v}`;
    clips.push(clip(name, 1, poseTracks(pose)));
    return { clip: name, value: +v };
  });
  layers.push({ name: layerName, states: [{ name: layerName, blend: { by, clips: blendClips } }], transitions: [{ from: "entry", to: layerName }] });
}
const both = (fn) => Object.assign({}, fn("L"), fn("R"));

// ------------------------------------------------------------------------------------------------ Life layer
// PipLife: blinks, darts, breath, hat sway and a brow flicker, each looping on its own period (2.9, 3.1, 1.9,
// 5.3, 5.9, 7.1, 8.9, 9.7, 11.7, 13.3 s) so they never line up. Each rhythm is a 1D blend on `life` between its
// rest pose (0) and its loop (1), so `life` scales the whole idle layer as the Kotlin renderer does.

number("life", 1);
const MOODS = ["neutral", "happy", "sad", "angry", "surprised"]; // index = Kotlin Mood ordinal (inputs control)
const DART_GAIN = { neutral: 1, happy: 1, sad: 0.6, angry: 0.3, surprised: 1 }; // PipRenderer.drawEyes
function lifeLayer(name, frames, tracks, rest, loop = "loop") {
  clips.push(clip(`life-${name}`, frames, tracks, loop));
  clips.push(clip(`life-${name}-rest`, 1, poseTracks(rest)));
  layers.push({
    name: `Life ${name}`,
    states: [{ name, blend: { by: "life", clips: [{ clip: `life-${name}-rest`, value: 0 }, { clip: `life-${name}`, value: 1 }] } }],
    transitions: [{ from: "entry", to: name }],
  });
}
/** Lid keys for blinks inside a loop of [cycle] s: lids snap shut, hold, open more softly (PipLife.blinkIn). */
function blinkKeys(cycle, blinks) {
  const end = sec(cycle);
  const keys = [[0, 0]];
  for (const b of blinks) {
    const { start, close = 0.07, hold = 0.03, open = 0.13, depth = 1 } = b;
    const f0 = Math.round((start / 100) * cycle * FPS);
    const f1 = f0 + Math.max(1, sec(close));
    const f2 = f1 + sec(hold);
    const f3 = f2 + Math.max(2, sec(open));
    keys.push([f0, 0, EASE.in], [f1, depth]);
    if (f2 > f1) keys.push([f2, depth, EASE.outSine]);
    else keys[keys.length - 1][2] = EASE.outSine;
    keys.push([f3, 0]);
  }
  keys.push([end, 0]);
  return keys;
}
/** The closed curve's opacity for blink keys [k]: on from the frame the lids meet, off two frames after they part. */
function closedKeys(k) {
  const out = [[0, 0]];
  for (let i = 1; i < k.length; i++) {
    const [f, v] = k[i];
    if (v < 0.8) continue;
    const o = clamp01((v - 0.8) / 0.2) * 0.5 + 0.5;
    if (k[i - 1][1] < 0.8) out.push([f - 1, 0, EASE.hold]);
    out.push([f, o]);
    if (i + 1 < k.length && k[i + 1][1] < 0.8) out.push([f + 2, 0]);
  }
  out.push([k[k.length - 1][0], 0]);
  return out;
}
function blinkLayer(tag, cycle, blinks) {
  const k = blinkKeys(cycle, blinks);
  const tracks = [];
  const rest = {};
  for (const s of ["L", "R"]) {
    tracks.push(track(`lidTop-${s}-blink-${tag}`, "y", k.map(([f, v, e]) => [f, v * LID_TRAVEL, e])));
    tracks.push(track(`squint-${s}-blink-${tag}`, "y", k.map(([f, v, e]) => [f, v * LOW_BLINK, e])));
    // The closed curve shows only while the lids are (nearly) together.
    tracks.push(track(`closed-${s}-${tag}`, "opacity", closedKeys(k)));
    rest[`closed-${s}-${tag}`] = { opacity: 0 };
    rest[`lidTop-${s}-blink-${tag}`] = { y: 0 };
    rest[`squint-${s}-blink-${tag}`] = { y: 0 };
  }
  lifeLayer(`blink-${tag}`, sec(cycle), tracks, rest);
}
blinkLayer("a", 5.3, [{ start: 58 }]);
// A double blink, the second a touch quicker and shallower: a "thought change" now and then.
blinkLayer("b", 11.7, [{ start: 23 }, { start: 23 + (0.3 / 11.7) * 100, close: 0.06, hold: 0, open: 0.11, depth: 0.9 }]);
// A slower, lazier blink.
blinkLayer("c", 8.9, [{ start: 81, close: 0.08, hold: 0.05, open: 0.21 }]);

/**
 * Saccades: hold, then dart to the next target in about 110 ms (PipLife.dartTo). Look units: x 16, y 14 px.
 * PipRenderer scales the darts by mood (angry eyes lock on, sad ones wander less) on top of `life`. A blend reads
 * one number, so each mood gain is its own state (a `life` blend between rest and the loop at that gain) and the
 * mood picks the state with the same 220 ms crossfade as the mood layer. A switch restarts that rhythm's loop.
 */
function dartLayer(tag, cycle, moves) {
  const end = sec(cycle);
  const node = `eyes-dart-${tag}`;
  const rest = `life-dart-${tag}-rest`;
  clips.push(clip(rest, 1, poseTracks({ [node]: { x: 0, y: 0 } })));
  const states = [];
  const transitions = [];
  for (const gain of [...new Set(MOODS.map((m) => DART_GAIN[m]))]) {
    const kx = [[0, 0]], ky = [[0, 0]];
    let prev = [0, 0];
    for (const [pct, x, y] of moves) {
      const f0 = Math.round((pct / 100) * cycle * FPS), f1 = f0 + sec(0.11);
      kx.push([f0, prev[0] * 16 * gain, EASE.out], [f1, x * 16 * gain]);
      ky.push([f0, prev[1] * 14 * gain, EASE.out], [f1, y * 14 * gain]);
      prev = [x, y];
    }
    kx.push([end, 0]);
    ky.push([end, 0]);
    const name = `dart-${tag}@${gain}`;
    clips.push(clip(`life-${name}`, end, [track(node, "x", kx), track(node, "y", ky)], "loop"));
    states.push({ name, blend: { by: "life", clips: [{ clip: rest, value: 0 }, { clip: `life-${name}`, value: 1 }] } });
    for (const m of MOODS.filter((mm) => DART_GAIN[mm] === gain)) {
      transitions.push({ from: "any", to: name, durationMs: 220, ease: "easeInOut", conditions: [{ on: "mood", is: m }] });
    }
  }
  transitions.unshift({ from: "entry", to: states[0].name });
  layers.push({ name: `Life dart-${tag}`, states, transitions });
}
dartLayer("a", 2.9, [[31, 0.2, -0.06], [66, 0, 0]]);
dartLayer("b", 7.1, [[12, -0.3, 0.05], [39, -0.12, 0.14], [57, 0.16, -0.03], [84, 0, 0]]);
dartLayer("c", 13.3, [[18, 0.08, 0.2], [27, 0, 0], [61, -0.1, -0.16], [70, 0, 0]]);

// Breathing: a slow sine stretch on the feet, about 1.4% at the top of the breath.
lifeLayer("breath", sec(3.1), [
  track("body-breath", "scaleY", [[0, 1, EASE.sine], [sec(3.1), 1.014]]),
  track("body-breath", "scaleX", [[0, 1, EASE.sine], [sec(3.1), 1 - 0.014 * 0.6]]),
], { "body-breath": { scaleX: 1, scaleY: 1 } }, "pingPong");

// Hat: two sways with unrelated periods, so it drifts rather than ticks. The `hatSway` channel owns the hat's
// bend (a vertex morph), so the idle sway turns the whole hat a little about its brim instead.
const SWAY_DEG = 5.5; // degrees per unit of sway
lifeLayer("sway-a", sec(1.9), [track("hat-life-a", "rotation", [[0, rad(-0.15 * SWAY_DEG), EASE.sine], [sec(1.9), rad(0.15 * SWAY_DEG)]])],
  { "hat-life-a": { rotation: 0 } }, "pingPong");
lifeLayer("sway-b", sec(5.9), [track("hat-life-b", "rotation", [[0, rad(-0.09 * SWAY_DEG), EASE.sine], [sec(5.9), rad(0.09 * SWAY_DEG)]])],
  { "hat-life-b": { rotation: 0 } }, "pingPong");

// Brows (seen only when drawn): a tiny lift now and then, as if listening.
{
  const c = 9.7, f = (p) => Math.round((p / 100) * c * FPS);
  const k = [[0, 0], [f(34), 0, EASE.outSine], [f(37), -0.12 * 16, EASE.sine], [f(52), -0.1 * 16, EASE.sine], [f(58), 0], [sec(c), 0]];
  lifeLayer("brow", sec(c), ["L", "R"].map((s) => track(`brow${s}-life`, "y", k)), both((s) => ({ [`brow${s}-life`]: { y: 0 } })));
}

// ------------------------------------------------------------------------------------------------ Look layers
// The gaze moves the eyes 16 x 14 px per unit, the brows 5 x 3 and the mouth 6 x 4; highlights stay with the light,
// shifting a little against the gaze; looking down lowers the upper lids a touch, as real ones do.

number("lookX", 0);
number("lookY", 0);
const lookX = (v) => ({
  "eyes-look": { x: 16 * v },
  "brows-look": { x: 5 * v },
  "mouth-look": { x: 6 * v },
  "eyeL-hl-look": { x: -2.5 * v },
  "eyeR-hl-look": { x: -2.5 * v },
});
const lookY = (v) => ({
  "eyes-look": { y: 14 * v },
  "brows-look": { y: 3 * v },
  "mouth-look": { y: 4 * v },
  "eyeL-hl-look": { y: -2.5 * v },
  "eyeR-hl-look": { y: -2.5 * v },
  "lidTop-L-look": { y: Math.max(0, v) * 0.12 * LID_TRAVEL },
  "lidTop-R-look": { y: Math.max(0, v) * 0.12 * LID_TRAVEL },
});
blendLayer("Look X", "lookX", { "-1": lookX(-1), 1: lookX(1) });
blendLayer("Look Y", "lookY", { "-1": lookY(-1), 0: lookY(0), 1: lookY(1) });

// ------------------------------------------------------------------------------------------------ Mood layer
// PipRenderer per mood: eye size, heavy lids (sad, angry) tilted with the brows, happy crescents (the lower lid
// rises and narrows, the highlight moves up), brows for angry, sad and surprised, and a mouth per mood
// (Kotlin left the mouth to scenes; the app has only the mood, so it picks one).

const MOOD = {
  neutral: { eyeK: 1, lid: 0, tilt: 0, raise: 0, brows: 0, mouth: "none", blush: 0 },
  happy: { eyeK: 1.12, lid: 0, tilt: 0, raise: 0, brows: 0, mouth: "smile", blush: 0.4, happy: true },
  sad: { eyeK: 1, lid: 0.34, tilt: -0.85, raise: 0.2, brows: 1, mouth: "frown", blush: 0 },
  angry: { eyeK: 1, lid: 0.2, tilt: 0.9, raise: -0.45, brows: 1, mouth: "flat", blush: 0 },
  surprised: { eyeK: 1.3, lid: 0, tilt: -0.15, raise: 1, brows: 1, mouth: "o", blush: 0 },
};
/** The pose of one mouth set ([prefix] details, [main] path) showing mouth [kind]. */
function mouthPose(prefix, main, kind) {
  const m = MOUTHS[kind];
  return {
    [main]: { vertices: `mouth-is-${kind}`, opacity: kind === "none" ? 0 : 1 },
    [`${main}/fill`]: { colorValue: m.fill },
    [`${main}/stroke`]: { colorValue: m.stroke ? "#FF6E2B26" : "#006E2B26" },
    [`${prefix}-smile`]: { opacity: kind === "smile" ? 1 : 0 },
    [`${prefix}-o`]: { opacity: kind === "o" ? 1 : 0 },
    [`${prefix}-shout`]: { opacity: kind === "shout" ? 1 : 0 },
  };
}
function moodPose(name) {
  const m = MOOD[name];
  const pose = {};
  for (const s of ["L", "R"]) {
    const inner = EYES[s].inner;
    pose[`eye${s}`] = { scaleX: m.eyeK, scaleY: m.eyeK };
    pose[`lidTop-${s}-mood`] = { y: m.lid * LID_TRAVEL };
    pose[`lidTop-${s}-tilt`] = { rotation: Math.atan(inner * m.tilt * 0.32) };
    pose[`squint-${s}-mood`] = { y: m.happy ? HAPPY_LOW_TOP - LOW_TOP_REST : 0 };
    pose[`squint-${s}/path`] = { width: m.happy ? LOW_W_HAPPY : LOW_W };
    pose[`eye${s}-hl`] = m.happy ? { x: 1, y: -RY * 0.68 + 14.5, scaleX: 4 / 6.2, scaleY: 4 / 6.2 } : { x: 0, y: 0, scaleX: 1, scaleY: 1 };
    pose[`eye${s}-hl2`] = { opacity: m.happy ? 0 : 1 };
    // Brows sit higher on bigger eyes and lower over heavy lids or squints.
    const squint = m.happy ? 0.72 : 0;
    pose[`brow${s}-mood`] = { y: -m.raise * 16 - RY * (m.eyeK - 1) + squint * 5 + m.lid * 5, rotation: rad(inner * m.tilt * 20) };
    pose[`brow${s}-a`] = { opacity: m.brows };
    pose[`brow${s}-b`] = { opacity: 1 - m.brows };
    pose[`blush-${s}-mood`] = { opacity: m.blush };
  }
  Object.assign(pose, mouthPose("mouth-mood", "mouth-mood", m.mouth));
  return pose;
}
for (const name of MOODS) clips.push(clip(`mood-${name}`, 1, poseTracks(moodPose(name))));
vmProps.unshift({ name: "mood", type: "enum", values: MOODS, defaultValue: "neutral" });
inputs.unshift({ name: "mood", type: "number", default: 0 });
layers.push({
  name: "Mood",
  states: MOODS.map((n) => ({ name: n, clip: `mood-${n}` })),
  transitions: [
    { from: "entry", to: "neutral" },
    ...MOODS.map((n) => ({ from: "any", to: n, durationMs: 220, ease: "easeInOut", earlyExit: true, conditions: [{ on: "mood", is: n }] })),
  ],
});

// ------------------------------------------------------------------------------------------------ Channels
// The face values the showreel animates (ShowModel.PipProps), each a 1D blend from its number to a pose.

number("eyesOpen", 1);
const openPose = (open, line) => both((s) => ({
  [`lidTop-${s}-open`]: { y: (1 - open) * LID_TRAVEL },
  [`squint-${s}-open`]: { y: (1 - open) * LOW_BLINK },
  [`closed-${s}-open`]: { opacity: line },
}));
blendLayer("eyesOpen", "eyesOpen", { 0: openPose(0, 1), 0.15: openPose(0.15, 0), 1: openPose(1, 0) });
number("lidTop", 0);
blendLayer("lidTop", "lidTop", { 0: both((s) => ({ [`lidTop-${s}-chan`]: { y: 0 } })), 1: both((s) => ({ [`lidTop-${s}-chan`]: { y: LID_TRAVEL } })) });
number("squint", 0);
blendLayer("squint", "squint", { 0: both((s) => ({ [`squint-${s}-chan`]: { y: 0 } })), 1: both((s) => ({ [`squint-${s}-chan`]: { y: SQUINT_TRAVEL } })) });
number("eyeScale", 1);
blendLayer("eyeScale", "eyeScale", {
  0: both((s) => ({ [`eye${s}-scale`]: { scaleX: 0, scaleY: 0 } })),
  2: both((s) => ({ [`eye${s}-scale`]: { scaleX: 2, scaleY: 2 } })),
});
number("browRaise", 0);
blendLayer("browRaise", "browRaise", {
  "-1.2": both((s) => ({ [`brow${s}-chan`]: { y: 1.2 * 16 } })),
  1.2: both((s) => ({ [`brow${s}-chan`]: { y: -1.2 * 16 } })),
});
number("browTilt", 0);
blendLayer("browTilt", "browTilt", Object.fromEntries([-1, 1].map((t) => [t, both((s) => ({
  [`brow${s}-chan`]: { rotation: rad(EYES[s].inner * t * 20) },
  [`lidTop-${s}-tilt2`]: { rotation: Math.atan(EYES[s].inner * t * 0.32) },
}))])));
number("brows", 0);
blendLayer("brows", "brows", { 0: both((s) => ({ [`brow${s}-gate`]: { opacity: 0 } })), 1: both((s) => ({ [`brow${s}-gate`]: { opacity: 1 } })) });
number("blush", 0);
blendLayer("blush", "blush", { 0: both((s) => ({ [`blush-${s}`]: { opacity: 0 } })), 1: both((s) => ({ [`blush-${s}`]: { opacity: 1 } })) });
number("hatSway", 0);
blendLayer("hatSway", "hatSway", {
  "-1": { hat: { vertices: "hat-sway-left" } },
  0: { hat: { vertices: "hat" } },
  1: { hat: { vertices: "hat-sway-right" } },
});
number("hatDroop", 0);
blendLayer("hatDroop", "hatDroop", {
  0: { "hat-droop": { rotation: 0 }, "hat-shade-level": { rotation: 0 }, "headwear-droop": { rotation: 0 } },
  1: { "hat-droop": { rotation: rad(-14) }, "hat-shade-level": { rotation: rad(14) }, "headwear-droop": { rotation: rad(-14) } },
});
// mouth: -1 (the default) follows the mood; 0..5 is the Kotlin Mouth ordinal and replaces it.
number("mouth", -1);
blendLayer("mouth", "mouth", Object.fromEntries([-1, 0, 1, 2, 3, 4, 5].map((i) => [i, {
  "mouth-mood-gate": { opacity: i < 0 ? 1 : 0 },
  "mouth-chan-gate": { opacity: i < 0 ? 0 : 1 },
  ...mouthPose("mouth-chan", "mouth", i < 0 ? "none" : MOUTH_BY_INDEX[i]),
}])));

// Hands: kind (the Kotlin Hand ordinal) and position in Pip units, angle in degrees, as in PipState.left/right.
for (const s of ["L", "R"]) {
  const d = HAND_DEFAULT[s];
  number(`hand${s}`, 0);
  blendLayer(`hand${s}`, `hand${s}`, Object.fromEntries(HAND_KINDS.map((k, i) => {
    const handShown = k === "sword" || k === "phone" ? "fist" : k;
    const pose = {};
    for (const h of ["fist", "point", "peace", "palm"]) pose[`hand-${s}-${h}`] = { opacity: h === handShown ? 1 : 0 };
    pose[`prop-${s}-sword`] = { opacity: k === "sword" ? 1 : 0 };
    pose[`prop-${s}-phone`] = { opacity: k === "phone" ? 1 : 0 };
    return [i, pose];
  })));
  number(`hand${s}X`, d.x);
  blendLayer(`hand${s}X`, `hand${s}X`, { "-1000": { [`hand-${s}`]: { x: -1000 - d.x } }, 2000: { [`hand-${s}`]: { x: 2000 - d.x } } });
  number(`hand${s}Y`, d.y);
  blendLayer(`hand${s}Y`, `hand${s}Y`, { "-1000": { [`hand-${s}`]: { y: -1000 - d.y } }, 2000: { [`hand-${s}`]: { y: 2000 - d.y } } });
  number(`hand${s}Angle`, 0);
  blendLayer(`hand${s}Angle`, `hand${s}Angle`, { "-360": { [`hand-${s}-turn`]: { rotation: rad(-360) } }, 360: { [`hand-${s}-turn`]: { rotation: rad(360) } } });
  // HandPose.scale, about the hand's own origin after its turn (the Kotlin props grow a sword or phone this way).
  number(`hand${s}Scale`, 1);
  blendLayer(`hand${s}Scale`, `hand${s}Scale`, { 0: { [`hand-${s}-turn`]: { scaleX: 0, scaleY: 0 } }, 4: { [`hand-${s}-turn`]: { scaleX: 4, scaleY: 4 } } });
}

// Headwear: the Kotlin Headwear, as an enum (a number, the ordinal, on the inputs build).
vmProps.push({ name: "headwear", type: "enum", values: HEADWEAR, defaultValue: "none" });
inputs.push({ name: "headwear", type: "number", default: 0 });
for (const k of HEADWEAR) {
  clips.push(clip(`headwear-${k}`, 1, poseTracks(Object.fromEntries(HEADWEAR.slice(1).map((h) => [`headwear-${h}`, { opacity: h === k ? 1 : 0 }])))));
}
layers.push({
  name: "Headwear",
  states: HEADWEAR.map((k) => ({ name: k, clip: `headwear-${k}` })),
  transitions: [
    { from: "entry", to: "none" },
    ...HEADWEAR.map((k) => ({ from: "any", to: k, durationMs: 120, conditions: [{ on: "headwear", is: k }] })),
  ],
});

// ------------------------------------------------------------------------------------------------ Alert, Poke, Talk

// Alert: anticipation squash, a jump with stretch, a squashed landing, then a head shake; eyes pop wide, the
// shadow shrinks under the jump and the hat lags and overshoots (playbook 2.2, 2.5, "surprised" take).
const alertRest = {
  "body-alert": { y: 0, scaleX: 1, scaleY: 1, rotation: 0 },
  "shadow-alert": { scaleX: 1, scaleY: 1, opacity: 1 },
  "eyeL-react": { scaleX: 1, scaleY: 1 },
  "eyeR-react": { scaleX: 1, scaleY: 1 },
  "hat-react": { rotation: 0 },
};
clips.push(clip("alert-rest", 1, poseTracks(alertRest)));
const ALERT_PEAK = 18; // frame of the jump's apex (the golden samples it)
clips.push(clip("alert", 66, [
  track("body-alert", "y", [[0, 0], [6, 4, EASE.out], [ALERT_PEAK, -64, EASE.in], [27, 0], [66, 0]]),
  track("body-alert", "scaleX", [[0, 1, EASE.out], [6, 1.12, EASE.out], [11, 0.88, EASE.out], [ALERT_PEAK, 0.97, EASE.in], [27, 1.15, EASE.out], [31, 0.96, EASE.inOut], [36, 1]]),
  track("body-alert", "scaleY", [[0, 1, EASE.out], [6, 0.88, EASE.out], [11, 1.14, EASE.out], [ALERT_PEAK, 1.03, EASE.in], [27, 0.86, EASE.out], [31, 1.04, EASE.inOut], [36, 1]]),
  track("body-alert", "rotation", [[0, 0], [30, 0, EASE.inOut], [34, rad(6), EASE.inOut], [38, rad(-6), EASE.inOut], [42, rad(4.5), EASE.inOut], [46, rad(-3), EASE.inOut], [51, rad(1.5), EASE.inOut], [57, 0]]),
  track("shadow-alert", "scaleX", [[0, 1], [6, 1.08, EASE.out], [ALERT_PEAK, 0.62, EASE.in], [27, 1.1, EASE.out], [34, 1]]),
  track("shadow-alert", "scaleY", [[0, 1], [6, 1.08, EASE.out], [ALERT_PEAK, 0.62, EASE.in], [27, 1.1, EASE.out], [34, 1]]),
  track("shadow-alert", "opacity", [[0, 1], [ALERT_PEAK, 0.45, EASE.in], [27, 1]]),
  ...["L", "R"].map((s) => track(`eye${s}-react`, "scaleX", [[0, 1], [8, 1.25, EASE.out], [44, 1.22, EASE.inOut], [62, 1]])),
  ...["L", "R"].map((s) => track(`eye${s}-react`, "scaleY", [[0, 1], [5, 0.85, EASE.out], [10, 1.28, EASE.out], [44, 1.24, EASE.inOut], [62, 1]])),
  track("hat-react", "rotation", [[0, 0], [10, rad(2), EASE.inOut], [21, rad(-4), EASE.inOut], [31, rad(7), EASE.inOut], [38, rad(-4), EASE.inOut], [45, rad(2.5), EASE.inOut], [52, rad(-1), EASE.inOut], [60, 0]]),
]));
inputs.push({ name: "alert", type: "trigger" });
vmProps.push({ name: "alert", type: "trigger" });
layers.push({
  name: "Alert",
  states: [{ name: "calm", clip: "alert-rest" }, { name: "alert", clip: "alert" }],
  transitions: [
    { from: "entry", to: "calm" },
    { from: "any", to: "alert", durationMs: 60, earlyExit: true, conditions: [{ on: "alert" }] },
    { from: "alert", to: "calm", exitTimePercent: 100, durationMs: 120, ease: "easeInOut" },
  ],
});

// Poke: a hit squash, a springy rebound and a giggle: he wobbles, scrunches his eyes into happy arches, grins and blushes.
const pokeRest = {
  "body-poke": { scaleX: 1, scaleY: 1, rotation: 0 },
  "shadow-poke": { scaleX: 1 },
  "eyes-poke": { opacity: 1 },
  "eyes-giggle": { opacity: 0 },
  "mouth-poke-gate": { opacity: 1 },
  "mouth-giggle": { opacity: 0 },
  "blush-L-poke": { opacity: 0 },
  "blush-R-poke": { opacity: 0 },
  "hat-poke": { rotation: 0 },
};
clips.push(clip("poke-rest", 1, poseTracks(pokeRest)));
const POKE_PEAK = 4;
const wobble = (amp, start, period, n) => {
  const k = [[0, 0], [start, 0, EASE.inOut]];
  for (let i = 1; i <= n; i++) k.push([start + i * period, rad(amp * (i % 2 ? 1 : -1) * (1 - (i - 1) / n)), EASE.inOut]);
  k.push([start + (n + 1) * period, 0]);
  return k;
};
const show = (a, b, c, d) => [[0, 0], [a, 0, EASE.out], [b, 1], [c, 1, EASE.in], [d, 0]];
clips.push(clip("poke", 66, [
  track("body-poke", "scaleX", [[0, 1, EASE.out], [POKE_PEAK, 1.2, EASE.out], [10, 0.9, EASE.inOut], [16, 1.06, EASE.inOut], [22, 0.98, EASE.inOut], [28, 1]]),
  track("body-poke", "scaleY", [[0, 1, EASE.out], [POKE_PEAK, 0.8, EASE.out], [10, 1.12, EASE.inOut], [16, 0.95, EASE.inOut], [22, 1.02, EASE.inOut], [28, 1]]),
  track("body-poke", "rotation", wobble(4, 8, 5, 8)),
  track("shadow-poke", "scaleX", [[0, 1, EASE.out], [POKE_PEAK, 1.18, EASE.out], [10, 0.92, EASE.inOut], [16, 1.04, EASE.inOut], [24, 1]]),
  track("eyes-poke", "opacity", [[0, 1], [2, 0], [56, 0], [60, 1]]),
  track("eyes-giggle", "opacity", [[0, 0], [2, 1], [56, 1], [60, 0]]),
  track("mouth-poke-gate", "opacity", [[0, 1], [2, 0], [56, 0], [60, 1]]),
  track("mouth-giggle", "opacity", [[0, 0], [2, 1], [56, 1], [60, 0]]),
  ...["L", "R"].map((s) => track(`blush-${s}-poke`, "opacity", show(2, 10, 50, 64))),
  track("hat-poke", "rotation", [[0, 0], [3, 0, EASE.out], [8, rad(-6), EASE.inOut], ...wobble(5, 14, 5, 8).slice(2)]),
]));
inputs.push({ name: "poke", type: "trigger" });
vmProps.push({ name: "poke", type: "trigger" });
layers.push({
  name: "Poke",
  states: [{ name: "still", clip: "poke-rest" }, { name: "poke", clip: "poke" }],
  transitions: [
    { from: "entry", to: "still" },
    { from: "any", to: "poke", durationMs: 40, earlyExit: true, conditions: [{ on: "poke" }] },
    { from: "poke", to: "still", exitTimePercent: 100, durationMs: 120, ease: "easeInOut" },
  ],
});

// Talk: while talking, an open mouth flaps on uneven syllables over whatever mouth he had.
const talkRest = { "mouth-talk": { opacity: 0 }, "mouth-talk-gate": { opacity: 1 }, "mouth-talk-flap": { scaleX: 1, scaleY: 1 } };
clips.push(clip("talk-rest", 1, poseTracks(talkRest)));
{
  // Syllables: [frame, openness]. 1.53 s, uneven, so the loop doesn't read as a beat.
  const syl = [[0, 0.25], [5, 0.9], [10, 0.35], [14, 0.75], [19, 0.2], [25, 1], [31, 0.4], [35, 0.6], [40, 0.15], [47, 0.85], [52, 0.3], [57, 0.7], [62, 0.45], [67, 0.95], [73, 0.2], [80, 0.55], [86, 0.25], [92, 0.25]];
  clips.push(clip("talk", 92, [
    track("mouth-talk", "opacity", [[0, 1]]),
    track("mouth-talk-gate", "opacity", [[0, 0]]),
    track("mouth-talk-flap", "scaleY", syl.map(([f, o]) => [f, 0.2 + 0.8 * o, EASE.inOut])),
    track("mouth-talk-flap", "scaleX", syl.map(([f, o]) => [f, 0.85 + 0.2 * o, EASE.inOut])),
  ], "loop"));
}
vmProps.push({ name: "talking", type: "boolean", defaultBool: false });
inputs.push({ name: "talking", type: "boolean", defaultBool: false });
layers.push({
  name: "Talk",
  states: [{ name: "quiet", clip: "talk-rest" }, { name: "talking", clip: "talk" }],
  transitions: [
    { from: "entry", to: "quiet" },
    { from: "quiet", to: "talking", durationMs: 100, conditions: [{ on: "talking", bool: true }] },
    { from: "talking", to: "quiet", durationMs: 150, conditions: [{ on: "talking", bool: false }] },
  ],
});

// ------------------------------------------------------------------------------------------------ write

// Order the view model as the app reads it: what it drives first, then the showreel's channels.
const order = ["mood", "lookX", "lookY", "talking", "alert", "poke", "eyesOpen", "lidTop", "squint", "eyeScale", "browRaise", "browTilt", "brows",
  "blush", "hatSway", "hatDroop", "mouth", "headwear", "life"];
const rank = (n) => (order.indexOf(n) < 0 ? order.length : order.indexOf(n));
vmProps.sort((a, b) => rank(a.name) - rank(b.name));
inputs.sort((a, b) => rank(a.name) - rank(b.name));

const ENUMS = { mood: MOODS, headwear: HEADWEAR };
const source = (control) => ({
  format: 1,
  artboard: ARTBOARD,
  art,
  clips,
  viewModel: { name: "Pip", instance: "Default", properties: vmProps },
  stateMachine: {
    name: "Pip",
    control,
    inputs,
    layers: control === "viewModel" ? layers : layers.map((l) => ({
      ...l,
      // Inputs have no enums: mood and headwear are the Kotlin ordinals.
      transitions: l.transitions.map((t) => ({
        ...t,
        conditions: t.conditions?.map((c) => (ENUMS[c.on] ? { on: c.on, op: "==", value: ENUMS[c.on].indexOf(c.is) } : c)),
      })),
    })),
  },
});
fs.writeFileSync(path.join(here, "pip.json"), JSON.stringify(source("viewModel"), null, 1) + "\n");
fs.writeFileSync(path.join(here, "pip-inputs.json"), JSON.stringify(source("inputs"), null, 1) + "\n");
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`pip.svg: ${groups.length} nodes, ${shapes.length} shapes; pip.json: ${clips.length} clips, ${layers.length} layers, ${vmProps.length} properties`);
