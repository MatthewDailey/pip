// Renders data/screens/src/specs.mjs to data/screens/img/sNN.png (400x860) and writes data/screens/screens.json.
// Usage: node data/screens/src/build.mjs   (needs Google Chrome installed; macOS path by default, override CHROME_BIN)
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { SPECS } from "./specs.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(here, "..");
const imgDir = path.join(outDir, "img");
const CHROME = process.env.CHROME_BIN || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const W = 400, H = 860;

const CSS = `*{box-sizing:border-box}body{margin:0;width:${W}px;height:${H}px;overflow:hidden;font-family:-apple-system,Roboto,Helvetica,Arial,sans-serif;background:#fff;color:#202124;font-size:15px}
.status{height:26px;background:#f1f3f4;font-size:12px;padding:5px 14px;display:flex;justify-content:space-between}
.url{background:#f1f3f4;padding:6px 10px 10px}.url div{background:#fff;border-radius:18px;padding:7px 12px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.page{padding:16px 20px}.logo{font-size:28px;font-weight:800;margin:6px 0 14px}.alert{background:#c62828;color:#fff;padding:12px;border-radius:8px;margin:8px 0}
.in{border:1px solid #bbb;border-radius:6px;padding:11px;margin:7px 0;min-height:42px}.ph{color:#999}.btn{color:#fff;text-align:center;padding:13px;border-radius:8px;font-weight:700;margin-top:12px}
.row{display:flex;gap:8px}.row>*{flex:1}.small{font-size:12px;color:#666}.card{border:1px solid #e0e0e0;border-radius:10px;padding:12px;margin:8px 0}
.appbar{color:#fff;font-size:20px;font-weight:700;padding:14px 18px;margin:-16px -20px 12px}
.bub{max-width:80%;padding:10px 13px;border-radius:18px;margin:8px 0;line-height:1.35}.bin{background:#e9e9eb}.bout{background:#1a73e8;color:#fff;margin-left:auto}
.thread{padding:12px 14px}.thead{background:#f1f3f4;padding:12px 16px;font-weight:700;font-size:17px}`;

const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");
function content(s) {
  if (s.sms) {
    return `<div class="thead">‹ ${esc(s.sms.from)}</div><div class="thread">${s.sms.msgs.map(([d, t]) => `<div class="bub ${d === "in" ? "bin" : "bout"}">${esc(t)}</div>`).join("")}</div>`;
  }
  if (s.email) {
    return `<div class="page"><div class="small">‹ Inbox</div><h3>${esc(s.email.subject)}</h3><div class="small"><b>${esc(s.email.from)}</b><br>to me · 9:14 AM</div><div style="margin-top:14px;line-height:1.45">${s.email.body}</div></div>`;
  }
  if (s.inbox) {
    return `<div class="page"><div class="appbar" style="background:#fff;color:#111;border-bottom:1px solid #eee">Primary</div>${s.inbox.map(([f, t]) => `<div class="card"><b>${esc(f)}</b><br><span class="small">${esc(t)}</span></div>`).join("")}</div>`;
  }
  return `<div class="page">${s.body}</div>`;
}
function html(s) {
  const url = s.url ? `<div class="url"><div>🔒 ${esc(s.url.replace(/^https:\/\//, ""))}</div></div>` : "";
  return `<!doctype html><html><head><meta charset="utf-8"><style>${CSS}</style></head><body><div class="status"><span>10:42</span><span>▲ ▮ 82%</span></div>${url}${content(s)}</body></html>`;
}
/** Rough stand-in for what Elder Guard's accessibility walk reads: visible text, no placeholders inside password fields. */
function a11yText(s) {
  return content(s)
    .replace(/<span class="ph">Password<\/span>/g, "")
    .replace(/<br\s*\/?>/g, "\n")
    .replace(/<\/(div|p|h\d)>/g, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<")
    .split("\n").map((l) => l.replace(/\s+/g, " ").trim()).filter(Boolean).join("\n")
    .slice(0, 3000);
}

// Deterministic shuffle so ids don't reveal scam vs benign order.
function shuffle(arr, seed = 7) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    seed = (seed * 9301 + 49297) % 233280;
    const j = Math.floor((seed / 233280) * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function render(htmlPath, pngPath) {
  const prof = fs.mkdtempSync(path.join(os.tmpdir(), "scr-chrome-"));
  return new Promise((resolve, reject) =>
    execFile(CHROME, ["--headless=new", "--disable-gpu", "--hide-scrollbars", `--user-data-dir=${prof}`, `--window-size=${W},${H}`, `--screenshot=${pngPath}`, `file://${htmlPath}`],
      { timeout: 30000 }, (err) => { fs.rmSync(prof, { recursive: true, force: true }); err && !fs.existsSync(pngPath) ? reject(err) : resolve(); }),
  );
}

fs.mkdirSync(imgDir, { recursive: true });
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "scr-html-"));
const ordered = shuffle(SPECS);
const rows = ordered.map((s, i) => ({ s, id: `s${String(i + 1).padStart(2, "0")}` }));
for (let i = 0; i < rows.length; i += 6) {
  await Promise.all(rows.slice(i, i + 6).map(async ({ s, id }) => {
    const hp = path.join(tmp, `${id}.html`);
    fs.writeFileSync(hp, html(s));
    await render(hp, path.join(imgDir, `${id}.png`));
  }));
}
fs.rmSync(tmp, { recursive: true, force: true });
const labels = rows.map(({ s, id }) => ({
  id, file: `img/${id}.png`, key: s.key, label: s.label, fraud_type: s.fraud_type, expected_intervene: s.expected_intervene,
  difficulty: s.difficulty, tags: s.tags, a11y_text: a11yText(s), url: s.url ?? null, app_package: s.app,
}));
fs.writeFileSync(path.join(outDir, "screens.json"), JSON.stringify({ version: 1, width: W, height: H, screens: labels }, null, 2) + "\n");
console.log(`rendered ${labels.length} screens -> ${imgDir}`);
