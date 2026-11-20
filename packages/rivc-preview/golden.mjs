// Golden-image tests for Pip's .riv: render named states in headless Chromium with the same
// pinned runtime and page as the previewer, then compare against goldens/<name>.png.
//
//   node golden.mjs                 compare; exit 1 with <name>.actual.png / .diff.png on failure
//   node golden.mjs --update        (re)write goldens/<name>.png
//   node golden.mjs --riv out.riv   render a different file than goldens.json's "riv"
//   node golden.mjs --only blink-mid --max-diff 0 --spec goldens.json
//
// Each golden in goldens.json sets view-model values (or legacy inputs; "input:mood" picks the
// input when both exist), fires triggers at triggerAt seconds, and advances to time seconds in
// fixed 1/60 s steps, so renders are deterministic.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const opt = (name) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv[i + 1] : undefined;
};

const specPath = path.resolve(opt("spec") || path.join(here, "goldens.json"));
const spec = JSON.parse(fs.readFileSync(specPath, "utf8"));
const outDir = path.resolve(opt("out") || path.join(here, "goldens"));
const update = flag("update");
const maxDiff = Number(opt("max-diff") ?? spec.maxDiffPixels ?? 0);
const only = opt("only");

// The page fetches the .riv over HTTP, so a file outside the folder is served under /_riv/.
const rivArg = opt("riv");
const rivFile = rivArg ? path.resolve(rivArg) : null;

const types = { ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm", ".json": "application/json" };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const file =
    url.pathname === "/_riv/file.riv" && rivFile
      ? rivFile
      : path.join(here, path.normalize(decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname)));
  if (file !== rivFile && !file.startsWith(here + path.sep)) return res.writeHead(403).end();
  fs.readFile(file, (err, body) => {
    if (err) return res.writeHead(404).end();
    res.writeHead(200, { "content-type": types[path.extname(file)] || "application/octet-stream" });
    res.end(body);
  });
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
let failures = 0;
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
  const firstRiv = rivFile ? "/_riv/file.riv" : spec.riv || "pip.riv";
  await page.goto(`${base}/index.html?golden=1&riv=${encodeURIComponent(firstRiv)}`);
  await page.evaluate(() => window.pipPreview.ready);
  const info = await page.evaluate(() => window.pipPreview.describe());
  console.log(`riv ${rivFile || spec.riv}: ${info.kind}, state machines [${info.stateMachines}], ` +
    `props ${info.props.map((p) => `${p.name}:${p.type}`).join(", ")}`);

  fs.mkdirSync(outDir, { recursive: true });
  for (const g of spec.goldens) {
    if (only && g.name !== only) continue;
    const riv = rivFile ? "/_riv/file.riv" : g.riv || spec.riv || "pip.riv";
    const dataUrl = await page.evaluate((s) => window.pipPreview.renderGolden(s), { ...g, riv });
    const actual = PNG.sync.read(Buffer.from(dataUrl.split(",")[1], "base64"));
    const goldenPath = path.join(outDir, `${g.name}.png`);
    const actualPath = path.join(outDir, `${g.name}.actual.png`);
    const diffPath = path.join(outDir, `${g.name}.diff.png`);

    if (update || !fs.existsSync(goldenPath)) {
      fs.writeFileSync(goldenPath, PNG.sync.write(actual));
      for (const p of [actualPath, diffPath]) fs.rmSync(p, { force: true });
      console.log(`  wrote   ${g.name}`);
      continue;
    }
    const expected = PNG.sync.read(fs.readFileSync(goldenPath));
    let diffPixels;
    const diff = new PNG({ width: expected.width, height: expected.height });
    if (expected.width !== actual.width || expected.height !== actual.height) {
      diffPixels = Infinity;
    } else {
      // threshold is per-pixel colour distance (0..1); antialiasing differences are not counted.
      diffPixels = pixelmatch(expected.data, actual.data, diff.data, expected.width, expected.height, {
        threshold: 0.1,
        includeAA: false,
      });
    }
    if (diffPixels > maxDiff) {
      failures++;
      fs.writeFileSync(actualPath, PNG.sync.write(actual));
      if (Number.isFinite(diffPixels)) fs.writeFileSync(diffPath, PNG.sync.write(diff));
      const why = Number.isFinite(diffPixels)
        ? `${diffPixels} px differ (max ${maxDiff})`
        : `size ${actual.width}x${actual.height} != ${expected.width}x${expected.height}`;
      console.log(`  FAIL    ${g.name}: ${why}\n          diff ${diffPath}\n          actual ${actualPath}`);
    } else {
      for (const p of [actualPath, diffPath]) fs.rmSync(p, { force: true });
      console.log(`  ok      ${g.name}${diffPixels ? ` (${diffPixels} px within tolerance)` : ""}`);
    }
  }
  if (errors.length) console.log(`page errors:\n  ${errors.join("\n  ")}`);
} finally {
  await browser.close();
  server.close();
}

if (failures) {
  console.log(`${failures} golden(s) failed. If the change is intended, run: node golden.mjs --update`);
  process.exit(1);
}
console.log(update ? "goldens updated" : "all goldens match");
