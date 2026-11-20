// Runtime proof for rivc's multi-artboard projects: plays examples/project/project.riv in
// headless Chromium with the pinned web runtime (the previewer page in golden mode).
//
//   node project-proof.mjs [--riv ../rivc/examples/project/project.riv] [--out <dir>]
//
// Saves frames at the keyed times (nested moods, blink, text change, banner fade, remapped
// note), frames with the bound mask moved through the view model, and the Rive event log with
// the virtual time each event arrived. Writes proof.json and proof.txt next to the PNGs.
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
const rivFile = path.resolve(opt("riv", path.join(here, "../rivc/examples/project/project.riv")));
const outDir = path.resolve(opt("out", path.join(here, "../../.proof/rivc-project")));
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

// Artboard 1080 x 2424 drawn 1:2 on a 540 x 1212 canvas.
const W = 540, H = 1212, S = 0.5;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1400, height: 1400 }, deviceScaleFactor: 1 });
const pageErrors = [];
const consoleLines = [];
page.on("pageerror", (e) => pageErrors.push(e.message));
page.on("console", (m) => consoleLines.push(`${m.type()}: ${m.text()}`));
await page.goto(`${base}/index.html?golden=1&riv=${encodeURIComponent("/_riv/file.riv")}`);
await page.evaluate(() => window.pipPreview.ready);
await page.evaluate(({ W, H }) => {
  const c = document.getElementById("canvas");
  c.width = W; c.height = H; c.style.width = `${W}px`; c.style.height = `${H}px`;
  window.__events = [];
  new MutationObserver((ms) => {
    for (const m of ms) for (const n of m.addedNodes) {
      const text = n.textContent || "";
      if (/event /.test(text)) window.__events.push(text.trim());
    }
  }).observe(document.getElementById("log"), { childList: true });
}, { W, H });

const describe = await page.evaluate(() => window.pipPreview.describe());

async function render(spec, save) {
  await page.evaluate(() => { document.getElementById("log").innerHTML = ""; window.__events = []; });
  const dataUrl = await page.evaluate((s) => window.pipPreview.renderGolden(s), { ...spec, riv: "/_riv/file.riv" });
  const buf = Buffer.from(dataUrl.split(",")[1], "base64");
  if (save) fs.writeFileSync(path.join(outDir, save), buf);
  // Each log line starts with the page's virtual seconds since load ("  0.517s  event ...").
  const events = (await page.evaluate(() => window.__events)).map((l) => ({ at: parseFloat(l), event: l.replace(/^\S+\s+/, "") }));
  return { png: PNG.sync.read(buf), events };
}

const rgba = (png, ax, ay) => {
  const x = Math.round(ax * S), y = Math.round(ay * S);
  const i = (png.width * y + x) * 4;
  return [png.data[i], png.data[i + 1], png.data[i + 2]];
};
/** Pixels of [r,g,b] (within tol) inside artboard box [x0,y0,x1,y1]. */
function count(png, rgb, box, tol = 18) {
  let n = 0;
  for (let y = Math.round(box[1] * S); y < Math.round(box[3] * S); y++)
    for (let x = Math.round(box[0] * S); x < Math.round(box[2] * S); x++) {
      const i = (png.width * y + x) * 4;
      if (Math.abs(png.data[i] - rgb[0]) <= tol && Math.abs(png.data[i + 1] - rgb[1]) <= tol && Math.abs(png.data[i + 2] - rgb[2]) <= tol) n++;
    }
  return n;
}
const MOODS = { neutral: [0xfd, 0xe3, 0xd2], happy: [0xff, 0xb8, 0xc8], angry: [0xf2, 0x6a, 0x5a], worried: [0xc9, 0xd8, 0xf0] };
/** The mood whose face colour fills the most of [box]. */
function mood(png, box) {
  const scores = Object.entries(MOODS).map(([m, c]) => [m, count(png, c, box, 10)]);
  scores.sort((a, b) => b[1] - a[1]);
  return scores[0][1] > 50 ? `${scores[0][0]} (${scores[0][1]} px)` : `none (${scores[0][0]} ${scores[0][1]} px)`;
}

const lines = [];
const say = (s) => { lines.push(s); console.log(s); };
const proof = { riv: rivFile, runtime: "@rive-app/canvas 2.52.0 (vendored)", describe, frames: [], events: [] };
say(`file ${rivFile} (${fs.statSync(rivFile).size} bytes); state machines ${JSON.stringify(describe.stateMachines)}; props ${JSON.stringify(describe.props)}`);

// Nested Pip A (scale 0.8 at 40,760) face box, Pip B (at x keyed 560 -> 260 -> 560, y 1300) face box.
const pipABox = [40 + 60, 760 + 200, 40 + 380, 760 + 520];
const pipBBox = (x) => [x + 60, 1300 + 200, x + 380, 1300 + 520];
const pipBx = (t) => {
  // easeInOut 560 -> 260 over 0..3 s and back over 3..6 s; only used to aim the face sample.
  const p = t <= 3 ? t / 3 : (6 - t) / 3;
  const e = p < 0.5 ? 2 * p * p : 1 - 2 * (1 - p) * (1 - p);
  return 560 - 300 * e;
};
for (const t of [0.25, 0.53, 0.6, 1.25, 2.25, 3.25, 4.25, 5.5]) {
  const { png } = await render({ values: {}, time: t }, `frame-t${t.toFixed(2)}.png`);
  const row = {
    t,
    pipA: mood(png, pipABox),
    pipB: mood(png, pipBBox(pipBx(t))),
    pipAEyePx: count(png, [0x8b, 0x3f, 0x37], [40, 760, 480, 1290], 12),
    bannerWhitePx: count(png, [255, 255, 255], [40, 1980, 1040, 2340], 8),
    bubbleBluePx: count(png, [0x2f, 0x6f, 0xd0], [180, 330, 900, 630], 20),
    bubbleDarkPx: count(png, [0x1b, 0x23, 0x33], [180, 330, 900, 630], 20),
  };
  proof.frames.push(row);
  say(`t=${t}s pipA ${row.pipA} eyes ${row.pipAEyePx} px | pipB ${row.pipB} | banner white px ${row.bannerWhitePx} | bubble text dark ${row.bubbleDarkPx} blue ${row.bubbleBluePx}`);
}

// Event log over one full loop and a bit (the timeline is 6 s and loops).
{
  const { events } = await render({ values: {}, time: 7.0 }, null);
  proof.events = events;
  say("events (timeline seconds):");
  for (const e of events) say(`  ${e.at.toFixed(3)} ${e.event}`);
}

// Runtime-bound mask: move the hole through the view model; Pip B (happy at 3.25 s) is only visible inside it.
proof.mask = [];
for (const [hx, hy, name] of [[780, 1560, "default"], [480, 1650, "onto-pip"], [900, 500, "away"]]) {
  const { png } = await render({ values: { "vm:holeX": hx, "vm:holeY": hy }, time: 3.25 }, `mask-${name}.png`);
  const inside = count(png, MOODS.happy, [hx - 200, hy - 200, hx + 200, hy + 200], 10);
  const outsideBox = pipBBox(pipBx(3.25));
  const total = count(png, MOODS.happy, outsideBox, 10);
  proof.mask.push({ hole: [hx, hy], faceInsideHole: inside, faceInPipBBox: total });
  say(`mask ${name} hole=(${hx},${hy}): Pip B face px inside hole ${inside}, in Pip B's box ${total}`);
}

proof.pageErrors = pageErrors;
proof.console = consoleLines.filter((l) => !/^debug/.test(l)).slice(0, 40);
if (pageErrors.length) say(`page errors: ${pageErrors.join(" | ")}`);
fs.writeFileSync(path.join(outDir, "proof.json"), JSON.stringify(proof, null, 2));
fs.writeFileSync(path.join(outDir, "proof.txt"), lines.join("\n") + "\n");
await browser.close();
server.close();
