// Renders one compiled set piece at chosen times with the pinned web runtime (@rive-app/canvas 2.52.0,
// vendored in packages/rivc-preview) on a virtual 60 fps clock, so two builds of a piece can be
// compared frame for frame.
//
//   node packages/showreel-to-rivc/proof/piece-proof.mjs --riv piece.riv --out dir [--anim split] [--times 0,0.25,0.5]
//        [--size 480] [--bg "#1b1d2a"]
//
// Without --anim it runs the piece's state machine `Main` (its free-running loops); with --anim it plays
// that clip alone, as the host's remap would. Writes <out>/t<seconds>.png per time and prints the paths.
// Uses packages/rivc-preview's node_modules (playwright).
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
const rivFile = path.resolve(opt("riv"));
const outDir = path.resolve(opt("out"));
const anim = opt("anim");
const size = Number(opt("size", "480"));
const bg = opt("bg", "#1b1d2a");
const times = opt("times", "0,0.25,0.5,0.75").split(",").map(Number).sort((a, b) => a - b);
fs.mkdirSync(outDir, { recursive: true });

const page = `<!doctype html><html><body style="margin:0;background:${bg}">
<canvas id="c" width="${size}" height="${size}" style="background:${bg}"></canvas>
<script>
const FRAME = 1000 / 60;
const clock = { now: 1e9, q: new Map(), id: 1 };
window.requestAnimationFrame = (cb) => { const id = clock.id++; clock.q.set(id, cb); return id; };
window.cancelAnimationFrame = (id) => clock.q.delete(id);
const flush = () => { const q = clock.q; clock.q = new Map(); q.forEach((cb) => { try { cb(clock.now); } catch (e) { window.__errors.push(String(e && e.stack || e)); } }); };
window.__errors = [];
window.api = {
  async load(anim) {
    await new Promise((res, rej) => { const s = document.createElement("script"); s.src = "/vendor/rive-canvas-2.52.0/rive.js"; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
    rive.RuntimeLoader.setWasmUrl("/vendor/rive-canvas-2.52.0/rive.wasm");
    const buf = await (await fetch("/file.riv")).arrayBuffer();
    await new Promise((res, rej) => {
      const o = { buffer: buf, canvas: document.getElementById("c"), autoplay: true, onLoad: res, onLoadError: (e) => rej(new Error("load error " + (e && e.data))) };
      if (anim) o.animations = anim; else o.stateMachines = "Main";
      window.r = new rive.Rive(o);
      flush();
    });
    return { artboard: r.activeArtboard, animations: r.animationNames, machines: r.stateMachineNames };
  },
  step(n) { for (let i = 0; i < n; i++) { clock.now += FRAME; flush(); } },
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
const browser = await chromium.launch();
const p = await browser.newPage({ viewport: { width: size, height: size } });
const errors = [];
p.on("pageerror", (e) => errors.push(e.message));
p.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
await p.goto(`http://127.0.0.1:${server.address().port}/`);
const info = await p.evaluate((a) => window.api.load(a), anim);
console.log(`loaded ${path.basename(rivFile)}: ${JSON.stringify(info)}`);
let frame = 0;
for (const t of times) {
  const target = Math.round(t * 60);
  if (target > frame) { await p.evaluate((n) => window.api.step(n), target - frame); frame = target; }
  const url = await p.evaluate(() => window.api.shot());
  const file = path.join(outDir, `t${t.toFixed(3)}.png`);
  fs.writeFileSync(file, Buffer.from(url.split(",")[1], "base64"));
  console.log(file);
}
errors.push(...(await p.evaluate(() => window.__errors)));
await browser.close();
server.close();
if (errors.length) { console.log(errors.slice(0, 10).join("\n")); process.exitCode = 1; }
