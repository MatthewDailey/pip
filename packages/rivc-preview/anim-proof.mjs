// Runtime proof for rivc's animation pass: plays examples/anim/anim.riv in headless Chromium
// with the pinned web runtime (the previewer page in golden mode) and measures pixels.
//
//   node anim-proof.mjs [--riv ../rivc/examples/anim/anim.riv] [--out <dir>]
//
// Checks: the bob clip's eased curve, the mood (enum) and alert (trigger) transitions'
// blend times, clip and state events reaching the runtime's event listener, the 1D blend on
// lookX, and the direct blend on smile. Writes proof.json, proof.txt and frame PNGs to --out.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { PNG } from "pngjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const opt = (name, d) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv[i + 1] : d;
};
const rivFile = path.resolve(opt("riv", path.join(here, "../rivc/examples/anim/anim.riv")));
const outDir = path.resolve(opt("out", path.join(here, "../../.proof/rivc-anim")));
fs.mkdirSync(outDir, { recursive: true });

const types = { ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json" };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const file = url.pathname === "/_riv/file.riv" ? rivFile : path.join(here, path.normalize(decodeURIComponent(url.pathname)));
  if (file !== rivFile && !file.startsWith(here + path.sep)) return res.writeHead(403).end();
  fs.readFile(file, (err, body) => {
    if (err) return res.writeHead(404).end();
    res.writeHead(200, { "content-type": types[path.extname(file)] || "application/octet-stream" });
    res.end(body);
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

// Cubic-bezier easing as the runtime evaluates it: solve x(t) = progress, return y(t).
function cubic(x1, y1, x2, y2, x) {
  const bx = (t) => 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3;
  const by = (t) => 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3;
  let lo = 0, hi = 1;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    if (bx(mid) < x) lo = mid;
    else hi = mid;
  }
  return by((lo + hi) / 2);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(e.message));
await page.goto(`${base}/index.html?golden=1&riv=${encodeURIComponent("/_riv/file.riv")}`);
await page.evaluate(() => window.pipPreview.ready);

const describe = await page.evaluate(() => window.pipPreview.describe());

/** Renders a golden-style spec; returns the decoded PNG and the event log lines. */
async function render(spec, save) {
  await page.evaluate(() => (document.getElementById("log").innerHTML = ""));
  const dataUrl = await page.evaluate((s) => window.pipPreview.renderGolden(s), { ...spec, riv: "/_riv/file.riv" });
  const buf = Buffer.from(dataUrl.split(",")[1], "base64");
  if (save) fs.writeFileSync(path.join(outDir, save), buf);
  const log = await page.evaluate(() => [...document.querySelectorAll("#log div")].map((d) => d.textContent));
  return { png: PNG.sync.read(buf), log };
}

// Artboard (200 x 200, Fit.Contain, centred) to canvas pixels.
function mapper(png) {
  const s = Math.min(png.width, png.height) / 200;
  const ox = (png.width - 200 * s) / 2;
  const oy = (png.height - 200 * s) / 2;
  return { s, toPx: (x, y) => [Math.round(ox + x * s), Math.round(oy + y * s)], toAb: (px, py) => [(px - ox) / s, (py - oy) / s] };
}
const rgba = (png, x, y) => {
  const i = (png.width * y + x) * 4;
  return [png.data[i], png.data[i + 1], png.data[i + 2], png.data[i + 3]];
};
/** Centroid (artboard units) of pixels matching pred inside artboard box [x0,y0,x1,y1]. */
function centroid(png, pred, box) {
  const m = mapper(png);
  const [px0, py0] = m.toPx(box[0], box[1]);
  const [px1, py1] = m.toPx(box[2], box[3]);
  let sx = 0, sy = 0, n = 0;
  for (let y = Math.max(0, py0); y < Math.min(png.height, py1); y++)
    for (let x = Math.max(0, px0); x < Math.min(png.width, px1); x++) {
      if (pred(rgba(png, x, y))) { sx += x; sy += y; n++; }
    }
  if (!n) return null;
  const [ax, ay] = m.toAb(sx / n, sy / n);
  return { x: ax, y: ay, n };
}
const isRed = ([r, g, b, a]) => a > 200 && r > 200 && g < 60 && b < 60;
const isDark = ([r, g, b, a]) => a > 200 && r < 60 && g < 60 && b < 60;
const isMouth = ([r, g, b, a]) => a > 200 && Math.abs(r - 0x80) < 30 && Math.abs(g - 0x20) < 30 && Math.abs(b - 0x40) < 30;
const r3 = (v) => Math.round(v * 1000) / 1000;

const proof = { riv: rivFile, runtime: "@rive-app/canvas 2.52.0 (vendored)", describe, checks: {} };
const lines = [];
const say = (s) => { lines.push(s); console.log(s); };

// 1. Bob: easeInOut from y=0 (frame 0) to y=100 (frame 60).
{
  const samples = [];
  for (let f = 0; f <= 60; f += 6) {
    const { png } = await render({ values: {}, time: f / 60 }, f % 30 === 0 ? `bob-f${f}.png` : null);
    samples.push({ frame: f, c: centroid(png, isRed, [0, 0, 40, 140]) });
  }
  const y0 = samples[0].c.y;
  const y1 = samples[samples.length - 1].c.y;
  const rows = samples.map(({ frame, c }) => {
    const p = frame / 60;
    const measured = (c.y - y0) / (y1 - y0);
    return { frame, y: r3(c.y), measured: r3(measured), easeInOut: r3(cubic(0.42, 0, 0.58, 1, p)), linear: r3(p) };
  });
  const errEase = Math.max(...rows.map((r) => Math.abs(r.measured - r.easeInOut)));
  const errLinear = Math.max(...rows.map((r) => Math.abs(r.measured - r.linear)));
  const pass = errEase < 0.02 && errLinear > 0.08;
  proof.checks.bob = { pass, travel: r3(y1 - y0), maxErrorVsEaseInOut: r3(errEase), maxErrorVsLinear: r3(errLinear), rows };
  say(`bob: dot travels ${r3(y1 - y0)} artboard units in 1 s; max |measured - easeInOut| = ${r3(errEase)}, ` +
    `max |measured - linear| = ${r3(errLinear)} -> ${pass ? "PASS" : "FAIL"}`);
  for (const r of rows) say(`  frame ${String(r.frame).padStart(2)}  y=${r.y.toFixed(2).padStart(7)}  progress ${r.measured.toFixed(3)}  easeInOut ${r.easeInOut.toFixed(3)}  linear ${r.linear.toFixed(3)}`);
}

// 2. mood enum: default calm (#88CC88); set upset (#3366FF) at t=0, 500 ms mix.
{
  const rows = [];
  for (const ms of [0, 100, 200, 250, 300, 400, 500, 600]) {
    const { png } = await render({ values: { mood: "upset" }, time: ms / 1000 }, `mood-${ms}ms.png`);
    const [px, py] = mapper(png).toPx(100, 125);
    const [r, g, b] = rgba(png, px, py);
    // Fraction of the way from calm to upset, by the blue channel (0x88 -> 0xFF).
    rows.push({ ms, rgb: [r, g, b], mix: r3((b - 0x88) / (0xff - 0x88)) });
  }
  const mid = rows.find((r) => r.ms === 250).mix;
  const done = rows.find((r) => r.ms === 600).mix;
  const pass = rows[0].mix < 0.1 && mid > 0.3 && mid < 0.7 && done > 0.97 && rows.every((r, i) => i === 0 || r.mix >= rows[i - 1].mix - 0.01);
  proof.checks.moodEnum = { pass, durationMs: 500, rows };
  say(`mood enum calm -> upset (500 ms mix): mix at 250 ms = ${mid}, at 600 ms = ${done} -> ${pass ? "PASS" : "FAIL"}`);
  for (const r of rows) say(`  ${String(r.ms).padStart(3)} ms  rgb(${r.rgb.join(",")})  mix ${r.mix}`);
}

// 3. alert trigger at 0.5 s: quiet (flash opacity 0) -> alert (opacity 1), 200 ms mix; events.
{
  const rows = [];
  let log = [];
  for (const ms of [500, 550, 600, 650, 700, 750, 800]) {
    const out = await render({ values: {}, triggers: ["alert"], triggerAt: 0.5, time: ms / 1000 }, `alert-${ms}ms.png`);
    const [px, py] = mapper(out.png).toPx(180, 20);
    rows.push({ ms, sinceTrigger: ms - 500, alpha: r3(rgba(out.png, px, py)[3] / 255) });
    log = out.log;
  }
  const at100 = rows.find((r) => r.sinceTrigger === 100).alpha;
  const pass = rows[0].alpha < 0.1 && at100 > 0.3 && at100 < 0.7 && rows.find((r) => r.sinceTrigger === 250).alpha > 0.97;
  proof.checks.alertTrigger = { pass, durationMs: 200, rows };
  say(`alert trigger (200 ms mix): flash opacity 100 ms after = ${at100} -> ${pass ? "PASS" : "FAIL"}`);
  for (const r of rows) say(`  +${String(r.sinceTrigger).padStart(3)} ms  opacity ${r.alpha}`);

  // Events: the clip fires "alerted" at frame 10 of the alert clip; entering the alert state fires "alertStarted".
  const events = log.filter((l) => l.includes("event "));
  const clipEvent = events.find((l) => l.includes("event alerted"));
  const stateEvent = events.find((l) => l.includes("event alertStarted"));
  const evPass = !!clipEvent && !!stateEvent;
  proof.checks.events = { pass: evPass, log: events, stateChanges: log.filter((l) => l.includes("state →")) };
  say(`events reaching rive.on(EventType.RiveEvent): ${evPass ? "PASS" : "FAIL"}`);
  for (const e of events) say(`  ${e.trim()}`);
}

// 4. 1D blend on lookX: eyes node x = 30 * lookX between look_left (-1) and look_right (1).
{
  const rows = [];
  for (const v of [-1, -0.5, 0, 0.5, 1]) {
    const { png } = await render({ values: { lookX: v }, time: 0.1 }, `look-${v}.png`);
    rows.push({ lookX: v, eyesX: centroid(png, isDark, [30, 80, 170, 130]).x });
  }
  const x0 = rows.find((r) => r.lookX === 0).eyesX;
  const out = rows.map((r) => ({ lookX: r.lookX, offset: r3(r.eyesX - x0), expected: 30 * r.lookX }));
  const err = Math.max(...out.map((r) => Math.abs(r.offset - r.expected)));
  const pass = err < 0.75;
  proof.checks.blend1D = { pass, maxError: r3(err), rows: out };
  say(`1D blend lookX: eye offset vs 30*lookX, max error ${r3(err)} units -> ${pass ? "PASS" : "FAIL"}`);
  for (const r of out) say(`  lookX ${String(r.lookX).padStart(4)}  eyes moved ${r.offset.toFixed(2).padStart(6)}  expected ${r.expected}`);
}

// 5. Direct blend on smile (0..100): the mouth morphs toward mouth_smile (centroid drops).
{
  const rows = [];
  for (const v of [0, 50, 100]) {
    const { png } = await render({ values: { smile: v }, time: 0.1 }, `smile-${v}.png`);
    rows.push({ smile: v, mouthY: r3(centroid(png, isMouth, [60, 125, 140, 175]).y) });
  }
  const [a, b, c] = rows.map((r) => r.mouthY);
  const pass = c > a + 3 && Math.abs((b - a) / (c - a) - 0.5) < 0.15;
  proof.checks.directBlend = { pass, rows };
  say(`direct blend smile: mouth centroid y ${a} -> ${b} -> ${c} -> ${pass ? "PASS" : "FAIL"}`);
}

proof.pageErrors = pageErrors;
if (pageErrors.length) say(`page errors:\n  ${pageErrors.join("\n  ")}`);
fs.writeFileSync(path.join(outDir, "proof.json"), JSON.stringify(proof, null, 2));
fs.writeFileSync(path.join(outDir, "proof.txt"), lines.join("\n") + "\n");
await browser.close();
server.close();
const failed = Object.entries(proof.checks).filter(([, c]) => !c.pass).map(([k]) => k);
console.log(failed.length ? `FAILED: ${failed.join(", ")}` : `all checks pass; proof in ${outDir}`);
process.exit(failed.length ? 1 : 0);
