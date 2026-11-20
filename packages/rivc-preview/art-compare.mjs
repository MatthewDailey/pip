// Art check for rivc's SVG import: compiles each pip.json with packages/rivc/rivc, renders the .riv's
// setup pose with the pinned web runtime and the same SVG with Chromium's own SVG renderer, both
// at the artboard's size, then compares them pixel by pixel.
//
//   node art-compare.mjs --out ../../.proof/rivc-art a.json b.json ...
//   options: --scale 2 (render scale), --threshold 0.1 (pixelmatch colour distance)
//
// For each source it writes <name>.svg.png, <name>.rive.png, <name>.diff.png and a side-by-side
// <name>.sheet.png (SVG | Rive | diff, over a checkerboard), and prints the differing pixel count
// as a share of the pixels either renderer painted. Exit 1 if a source fails to compile or load.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";

const here = path.dirname(fileURLToPath(import.meta.url));
const rivc = path.resolve(here, "../rivc/rivc");
const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  if (i < 0) return dflt;
  const v = argv[i + 1];
  argv.splice(i, 2);
  return v;
};
const outDir = path.resolve(opt("out", path.join(here, "art-out")));
const scale = Number(opt("scale", "2"));
const threshold = Number(opt("threshold", "0.1"));
const sources = argv.map((p) => path.resolve(p));
if (!sources.length) {
  console.error("usage: node art-compare.mjs [--out dir] [--scale n] [--threshold t] <pip.json>...");
  process.exit(2);
}
fs.mkdirSync(outDir, { recursive: true });

const files = new Map(); // url path -> file
const types = { ".js": "text/javascript", ".wasm": "application/wasm", ".svg": "image/svg+xml" };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname === "/") {
    res.writeHead(200, { "content-type": "text/html" });
    return res.end(`<!doctype html><meta charset="utf-8"><body style="margin:0"><script src="vendor/rive-canvas-2.52.0/rive.js"></script>`);
  }
  const file = files.get(url.pathname) ?? path.join(here, path.normalize(decodeURIComponent(url.pathname)));
  if (!files.has(url.pathname) && !file.startsWith(here + path.sep)) return res.writeHead(403).end();
  fs.readFile(file, (err, body) => {
    if (err) return res.writeHead(404).end();
    res.writeHead(200, { "content-type": types[path.extname(file)] || "application/octet-stream" });
    res.end(body);
  });
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let failed = 0;
const rows = [];
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
  await page.goto(base);
  await page.evaluate(() => window.rive.RuntimeLoader.setWasmUrl("vendor/rive-canvas-2.52.0/rive.wasm"));

  for (const [i, src] of sources.entries()) {
    const name = path.basename(src, ".json");
    const pip = JSON.parse(fs.readFileSync(src, "utf8"));
    const ab = pip.artboard;
    const pad = { top: 0, right: 0, bottom: 0, left: 0, ...(ab.padding || {}) };
    const W = ab.width + pad.left + pad.right;
    const H = ab.height + pad.top + pad.bottom;
    const svgFile = path.resolve(path.dirname(src), pip.art.svg);
    const rivFile = path.join(outDir, `${name}.riv`);
    try {
      execFileSync(rivc, ["compile", src, "-o", rivFile], { stdio: ["ignore", "ignore", "pipe"] });
    } catch (e) {
      console.log(`FAIL    ${name}: rivc compile failed\n${e.stderr}`);
      failed++;
      continue;
    }
    files.set(`/_src/${i}.riv`, rivFile);
    files.set(`/_src/${i}.svg`, svgFile);

    const shots = await page.evaluate(
      async ({ riv, svg, W, H, pad, art, scale }) => {
        const make = () => {
          const c = document.createElement("canvas");
          c.width = Math.round(W * scale);
          c.height = Math.round(H * scale);
          return c;
        };
        // Chromium's SVG renderer, placed where the art sits inside the padded artboard.
        const img = new Image();
        img.src = svg;
        await img.decode();
        const svgCanvas = make();
        svgCanvas.getContext("2d").drawImage(img, pad.left * scale, pad.top * scale, art.w * scale, art.h * scale);

        // The pinned Rive runtime, artboard filling the canvas 1:1 (times scale).
        const riveCanvas = make();
        const buffer = await (await fetch(riv)).arrayBuffer();
        // A file with a state machine draws nothing until one is instanced, so probe for it first.
        const open = (opts) =>
          new Promise((resolve, reject) => {
            const r = new window.rive.Rive({
              buffer: buffer.slice(0),
              layout: new window.rive.Layout({ fit: window.rive.Fit.Fill, alignment: window.rive.Alignment.TopLeft }),
              ...opts,
              onLoad: () => resolve(r),
              onLoadError: (e) => reject(new Error(`rive could not load the file: ${e?.data ?? e}`)),
            });
          });
        const probe = await open({ canvas: document.createElement("canvas"), autoplay: false });
        const machines = probe.stateMachineNames;
        probe.cleanup();
        // Paused at time 0: the setup pose, which is what the SVG shows.
        const r = await open({ canvas: riveCanvas, autoplay: false, ...(machines.length ? { stateMachines: machines[0] } : {}) });
        r.drawFrame?.();
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        return { svg: svgCanvas.toDataURL("image/png"), rive: riveCanvas.toDataURL("image/png") };
      },
      { riv: `/_src/${i}.riv`, svg: `/_src/${i}.svg`, W, H, pad, art: { w: ab.width, h: ab.height }, scale },
    );

    const decode = (u) => PNG.sync.read(Buffer.from(u.split(",")[1], "base64"));
    const a = decode(shots.svg);
    const b = decode(shots.rive);
    const diff = new PNG({ width: a.width, height: a.height });
    const diffPixels = pixelmatch(a.data, b.data, diff.data, a.width, a.height, { threshold, includeAA: false, alpha: 0.2 });
    // Stricter: every pixel (antialiased edges included) whose channels differ by more than 24/255.
    let painted = 0;
    let strict = 0;
    for (let p = 0; p < a.data.length; p += 4) {
      if (a.data[p + 3] > 0 || b.data[p + 3] > 0) painted++;
      let m = 0;
      for (let k = 0; k < 4; k++) m = Math.max(m, Math.abs(a.data[p + k] - b.data[p + k]));
      if (m > 24) strict++;
    }
    const share = painted ? (100 * diffPixels) / painted : 0;
    for (const [suffix, png] of [["svg", a], ["rive", b], ["diff", diff]]) {
      fs.writeFileSync(path.join(outDir, `${name}.${suffix}.png`), PNG.sync.write(png));
    }
    const toUrl = (png) => "data:image/png;base64," + PNG.sync.write(png).toString("base64");
    const sheet = await page.evaluate(
      async ({ urls, title, note }) => {
        const imgs = await Promise.all(urls.map(async (u) => { const im = new Image(); im.src = u; await im.decode(); return im; }));
        const w = imgs[0].width, h = imgs[0].height, gap = 16, head = 44;
        const c = document.createElement("canvas");
        c.width = w * 3 + gap * 4;
        c.height = h + head + gap;
        const ctx = c.getContext("2d");
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, c.width, c.height);
        ["SVG (Chromium)", "Rive (rivc, @rive-app/canvas 2.52.0)", "diff (red: differs)"].forEach((label, k) => {
          const x = gap + k * (w + gap);
          for (let yy = 0; yy < h; yy += 16) for (let xx = 0; xx < w; xx += 16) {
            ctx.fillStyle = ((xx + yy) / 16) % 2 ? "#e6e6e6" : "#f7f7f7";
            ctx.fillRect(x + xx, head + yy, Math.min(16, w - xx), Math.min(16, h - yy));
          }
          ctx.drawImage(imgs[k], x, head);
          ctx.strokeStyle = "#999";
          ctx.strokeRect(x - 0.5, head - 0.5, w + 1, h + 1);
          ctx.fillStyle = "#222";
          ctx.font = "14px sans-serif";
          ctx.fillText(label, x, head - 10);
        });
        ctx.font = "bold 15px sans-serif";
        ctx.fillText(`${title}   ${note}`, gap, 20);
        return c.toDataURL("image/png");
      },
      {
        urls: [toUrl(a), toUrl(b), toUrl(diff)],
        title: name,
        note: `${diffPixels} px differ of ${painted} painted (${share.toFixed(2)}%); counting antialiased edges ${strict} px; scale ${scale}x`,
      },
    );
    fs.writeFileSync(path.join(outDir, `${name}.sheet.png`), Buffer.from(sheet.split(",")[1], "base64"));
    rows.push({ name, diffPixels, strict, painted, share, strictShare: (100 * strict) / Math.max(painted, 1) });
    console.log(
      `${name.padEnd(28)} ${String(diffPixels).padStart(7)} px differ of ${String(painted).padStart(8)} painted (${share.toFixed(3)}%)` +
        `; with edges ${strict} px (${((100 * strict) / Math.max(painted, 1)).toFixed(3)}%)`,
    );
  }
  if (errors.length) {
    console.log(`page errors:\n  ${errors.join("\n  ")}`);
    failed++;
  }
} finally {
  await browser.close();
  server.close();
}
fs.writeFileSync(path.join(outDir, "results.json"), JSON.stringify({ scale, threshold, rows }, null, 2));
process.exit(failed ? 1 : 0);
