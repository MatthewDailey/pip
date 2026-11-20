// Plays the compiled showreel from start to end in headless Chromium with the pinned web runtime
// (@rive-app/canvas 2.52.0, vendored in packages/rivc-preview), on a virtual 60 fps clock, and saves
// frames at the requested show times plus the Rive event log and any runtime errors.
//
//   node packages/showreel-to-rivc/proof/showreel-proof.mjs [--riv packages/pip-rive/dist/showreel/showreel.riv]
//        [--out .proof/showreel-rive] [--times 0.5,3,...]   (default: the start and middle of every scene)
//
// Uses packages/rivc-preview's node_modules (playwright, pngjs): run `npm install` there once.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const preview = path.join(repo, "packages/rivc-preview");
const require = createRequire(path.join(preview, "package.json"));
const { chromium } = require("playwright");

const argv = process.argv.slice(2);
const opt = (n, d) => { const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : d; };
const rivFile = path.resolve(opt("riv", path.join(repo, "packages/pip-rive/dist/showreel/showreel.riv")));
const outDir = path.resolve(opt("out", path.join(repo, ".proof/showreel-rive")));
const exportFile = path.join(repo, "apps/pip-app/app/build/showreel/showreel.json");
fs.mkdirSync(path.join(outDir, "rive"), { recursive: true });

const show = JSON.parse(fs.readFileSync(exportFile, "utf8"));
// --scene <name>: the file holds only that scene, its clip starting at the scene's start. Times stay show times.
const only = opt("scene") ? show.scenes.find((s) => s.name === opt("scene")) : null;
const t0 = only ? only.start : 0;
let times = opt("times") ? opt("times").split(",").map(Number) : null;
if (!times) {
  times = [];
  for (const s of show.scenes.filter((s) => !only || s === only)) times.push(+(s.start + 0.25).toFixed(3), +(s.start + s.duration / 2).toFixed(3));
}
times.sort((a, b) => a - b);
const end = only ? only.end : show.duration;

const page = `<!doctype html><html><body style="margin:0;background:#888">
<canvas id="c" width="540" height="1212"></canvas>
<script>
const FRAME = 1000 / 60;
const clock = { now: 1e9, q: new Map(), id: 1 };
window.requestAnimationFrame = (cb) => { const id = clock.id++; clock.q.set(id, cb); return id; };
window.cancelAnimationFrame = (id) => clock.q.delete(id);
const flush = () => { const q = clock.q; clock.q = new Map(); q.forEach((cb) => { try { cb(clock.now); } catch (e) { window.__errors.push(String(e && e.stack || e)); } }); };
window.__errors = []; window.__events = []; window.__frame = 0;
window.api = {
  async load() {
    await new Promise((res, rej) => { const s = document.createElement("script"); s.src = "/vendor/rive-canvas-2.52.0/rive.js"; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
    rive.RuntimeLoader.setWasmUrl("/vendor/rive-canvas-2.52.0/rive.wasm");
    const buf = await (await fetch("/file.riv")).arrayBuffer();
    await new Promise((res, rej) => {
      window.r = new rive.Rive({ buffer: buf, canvas: document.getElementById("c"), stateMachines: "Main", autoplay: true,
        onLoad: res, onLoadError: (e) => rej(new Error("load error " + (e && e.data))) });
      flush();
    });
    r.on(rive.EventType.RiveEvent, (e) => window.__events.push({ name: e.data.name, frame: window.__frame }));
    return { artboard: r.activeArtboard, machines: r.stateMachineNames };
  },
  step(n) { for (let i = 0; i < n; i++) { clock.now += FRAME; window.__frame++; flush(); } },
  shot() { flush(); return document.getElementById("c").toDataURL("image/png"); },
};
</script></body></html>`;

const server = http.createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  if (u.pathname === "/") return res.writeHead(200, { "content-type": "text/html" }).end(page);
  const file = u.pathname === "/file.riv" ? rivFile : path.join(preview, path.normalize(decodeURIComponent(u.pathname)));
  if (file !== rivFile && !file.startsWith(preview + path.sep)) return res.writeHead(403).end();
  fs.readFile(file, (err, body) => {
    if (err) return res.writeHead(404).end();
    const type = file.endsWith(".wasm") ? "application/wasm" : file.endsWith(".js") ? "text/javascript" : "application/octet-stream";
    res.writeHead(200, { "content-type": type }).end(body);
  });
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();
const p = await browser.newPage({ viewport: { width: 600, height: 1300 } });
const pageErrors = [];
p.on("pageerror", (e) => pageErrors.push(e.message));
p.on("console", (m) => { if (m.type() === "error") pageErrors.push(m.text()); });
await p.goto(base + "/");
const tLoad = Date.now();
const info = await p.evaluate(() => window.api.load());
console.log(`loaded ${path.basename(rivFile)} (${fs.statSync(rivFile).size} bytes) in ${Date.now() - tLoad} ms: ${JSON.stringify(info)}`);

let frame = 0;
const shots = [];
const lastFrame = Math.round((end - t0) * 60) + 30;
const t1 = Date.now();
for (const t of [...times, end + 0.4]) {
  const target = Math.round((t - t0) * 60);
  while (frame < target) { const n = Math.min(120, target - frame); await p.evaluate((n) => window.api.step(n), n); frame += n; }
  if (t > end) break;
  const url = await p.evaluate(() => window.api.shot());
  const name = `rive-t${t.toFixed(2).padStart(6, "0")}.png`;
  fs.writeFileSync(path.join(outDir, "rive", name), Buffer.from(url.split(",")[1], "base64"));
  shots.push({ t, file: `rive/${name}` });
}
while (frame < lastFrame) { await p.evaluate(() => window.api.step(60)); frame += 60; }
const ms = Date.now() - t1;
const events = (await p.evaluate(() => window.__events)).map((e) => ({ name: e.name, t: +(t0 + e.frame / 60).toFixed(3) }));
const errors = [...pageErrors, ...(await p.evaluate(() => window.__errors))];
await browser.close();
server.close();

// Event timing against the export's cues (frame-rounded).
const expected = show.cues.filter((c) => !only || c.scene === only.name).map((c) => ({ name: c.name, t: c.t }));
let matched = 0, worst = 0;
const used = new Set();
for (const c of expected) {
  const i = events.findIndex((e, k) => !used.has(k) && e.name === c.name && Math.abs(e.t - c.t) < 0.1);
  if (i >= 0) { used.add(i); matched++; worst = Math.max(worst, Math.abs(events[i].t - c.t)); }
}
const proof = { riv: rivFile, bytes: fs.statSync(rivFile).size, played: `${t0} -> ${(t0 + lastFrame / 60).toFixed(2)} s in ${frame} frames (${ms} ms wall)`,
  errors, events: events.length, cues: expected.length, cuesMatched: matched, worstCueError: +worst.toFixed(3), shots, eventLog: events };
fs.writeFileSync(path.join(outDir, "rive-proof.json"), JSON.stringify(proof, null, 2));
console.log(`played ${proof.played}; errors ${errors.length}; events ${events.length}, cues matched ${matched}/${expected.length} (worst ${proof.worstCueError} s)`);
if (errors.length) { console.log(errors.slice(0, 10).join("\n")); process.exitCode = 1; }
