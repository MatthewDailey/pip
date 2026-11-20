// Screen classifier eval: pnpm eval:screens [--modes=image+text,image,text,tj] [--concurrency=4] [--only=s01,s02] [--tag=sms]
// --no-context drops url + app_package from what Jev sees (the screenshot still shows the URL bar).
// Scores data/screens/screens.json with Jev (image+text, image only, text only) and TJ's rules on the accessibility text.
// Writes eval-results/screens/<timestamp>.json.
import fs from "node:fs";
import path from "node:path";
import { loadEnvLocal } from "../lib/env";
import { assessScreen, decideIntervene, type ScreenAssessment, type ScreenMode } from "../lib/screens/jevScreen";
import { TJ_ALERT, tjScore } from "../lib/detectors/tjRules";

loadEnvLocal();

interface Screen {
  id: string;
  file: string;
  key: string;
  label: "scam" | "benign";
  fraud_type: string;
  expected_intervene: boolean;
  difficulty: string;
  tags: string[];
  a11y_text: string;
  url: string | null;
  app_package: string;
}
type Mode = ScreenMode | "tj";

const arg = (name: string) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=");
const modes = (arg("modes") ?? "image+text,image,text,tj").split(",") as Mode[];
const noContext = process.argv.includes("--no-context"); // drop url + app_package (image still shows the URL bar)
const concurrency = Number(arg("concurrency") ?? 4);
const root = path.resolve(__dirname, "..");
const data = JSON.parse(fs.readFileSync(path.join(root, "data/screens/screens.json"), "utf8")) as { screens: Screen[] };
let screens = data.screens;
if (arg("only")) screens = screens.filter((s) => arg("only")!.split(",").includes(s.id));
if (arg("tag")) screens = screens.filter((s) => s.tags.includes(arg("tag")!));

interface Row {
  id: string;
  key: string;
  label: string;
  fraud_type: string;
  expected_intervene: boolean;
  difficulty: string;
  flagged: boolean;
  intervene: boolean;
  pred_type: string;
  fraud: number;
  risk: number;
  signals?: ScreenAssessment["verdict"]["signals"];
  latency_ms: number;
  input_tokens?: number;
  error?: string;
  tj_score?: number;
}

async function scoreOne(s: Screen, mode: Mode): Promise<Row> {
  const base = { id: s.id, key: s.key, label: s.label, fraud_type: s.fraud_type, expected_intervene: s.expected_intervene, difficulty: s.difficulty };
  const text = [noContext ? "" : s.url ?? "", s.a11y_text].join("\n");
  if (mode === "tj") {
    const t0 = performance.now();
    const { score } = tjScore([text]);
    const flagged = score >= TJ_ALERT;
    return { ...base, flagged, intervene: flagged, pred_type: flagged ? "scam" : "benign", fraud: score, risk: score, latency_ms: performance.now() - t0, tj_score: score };
  }
  const image_base64 = fs.readFileSync(path.join(root, "data/screens", s.file)).toString("base64");
  try {
    const a = await assessScreen({ image_base64, image_format: "png", a11y_text: s.a11y_text, app_package: noContext ? undefined : s.app_package, url: noContext ? null : s.url, trigger: "eval" }, mode);
    return {
      ...base,
      flagged: a.verdict.fraud >= 0.5,
      intervene: decideIntervene(a.verdict),
      pred_type: String(a.verdict.fraud_type),
      fraud: a.verdict.fraud,
      risk: a.verdict.risk,
      signals: a.verdict.signals,
      latency_ms: a.latency_ms,
      input_tokens: a.usage?.input_tokens,
    };
  } catch (e) {
    return { ...base, flagged: false, intervene: false, pred_type: "error", fraud: 0, risk: 0, latency_ms: 0, error: String((e as Error).message ?? e) };
  }
}

async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>) {
  const out: R[] = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) {
      const k = i++;
      out[k] = await fn(items[k]);
    }
  }));
  return out;
}

const pct = (a: number, b: number) => (b ? `${Math.round((100 * a) / b)}%` : "—");
const q = (xs: number[], p: number) => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : 0;
};

function summarize(mode: Mode, rows: Row[]) {
  const ok = rows.filter((r) => !r.error);
  const scams = ok.filter((r) => r.label === "scam");
  const benign = ok.filter((r) => r.label === "benign");
  const correct = ok.filter((r) => r.flagged === (r.label === "scam")).length;
  const tp = ok.filter((r) => r.intervene && r.expected_intervene).length;
  const fp = ok.filter((r) => r.intervene && !r.expected_intervene).length;
  const fn = ok.filter((r) => !r.intervene && r.expected_intervene).length;
  const typeOk = mode === "tj" ? null : ok.filter((r) => r.pred_type === r.fraud_type).length;
  const lat = ok.map((r) => r.latency_ms);
  return {
    mode,
    n: rows.length,
    errors: rows.length - ok.length,
    accuracy: pct(correct, ok.length),
    scams_flagged: `${scams.filter((r) => r.flagged).length}/${scams.length}`,
    false_alarms: `${benign.filter((r) => r.flagged).length}/${benign.length}`,
    intervene_precision: pct(tp, tp + fp),
    intervene_recall: pct(tp, tp + fn),
    benign_intervened: `${benign.filter((r) => r.intervene).length}/${benign.length}`,
    fraud_type_acc: typeOk === null ? "—" : pct(typeOk, ok.length),
    latency_p50_ms: Math.round(q(lat, 0.5)),
    latency_p95_ms: Math.round(q(lat, 0.95)),
    mean_input_tokens: mode === "tj" ? null : Math.round(ok.reduce((a, r) => a + (r.input_tokens ?? 0), 0) / (ok.length || 1)),
  };
}

function confusion(rows: Row[]) {
  const m: Record<string, Record<string, number>> = {};
  for (const r of rows.filter((x) => !x.error)) {
    m[r.fraud_type] ??= {};
    m[r.fraud_type][r.pred_type] = (m[r.fraud_type][r.pred_type] ?? 0) + 1;
  }
  return m;
}

(async () => {
  if (!process.env.JEV_API_KEY && modes.some((m) => m !== "tj")) {
    console.error("JEV_API_KEY not set (.env.local); only --modes=tj can run.");
    process.exit(1);
  }
  console.log(`Scoring ${screens.length} screens (${screens.filter((s) => s.label === "scam").length} scam / ${screens.filter((s) => s.label === "benign").length} benign), modes: ${modes.join(", ")}${noContext ? " (no url/app context)" : ""}, concurrency ${concurrency}\n`);
  const results: Record<string, Row[]> = {};
  for (const mode of modes) {
    results[mode] = await pool(screens, mode === "tj" ? 1 : concurrency, (s) => scoreOne(s, mode));
  }
  const summary = modes.map((m) => summarize(m, results[m]));
  console.table(summary);
  for (const mode of modes) {
    const rows = results[mode];
    const misses = rows.filter((r) => r.error || r.flagged !== (r.label === "scam") || r.intervene !== r.expected_intervene);
    if (!misses.length) continue;
    console.log(`\n${mode}: misses (flag ≠ label, or intervene ≠ expected)`);
    for (const r of misses) {
      console.log(
        `  ${r.id} ${r.key.padEnd(20)} ${r.label}/${r.fraud_type} exp_int=${r.expected_intervene} -> flagged=${r.flagged} int=${r.intervene} type=${r.pred_type} fraud=${r.fraud.toFixed?.(2)} risk=${r.risk.toFixed?.(2)}` +
          (r.signals ? ` login=${r.signals.fake_login.toFixed(2)} pay=${r.signals.payment_request.toFixed(2)}` : "") +
          (r.error ? ` ERROR ${r.error}` : ""),
      );
    }
  }
  const outDir = path.join(root, "eval-results/screens");
  fs.mkdirSync(outDir, { recursive: true });
  const file = path.join(outDir, `${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  fs.writeFileSync(file, JSON.stringify({ when: new Date().toISOString(), modes, noContext, summary, confusion: Object.fromEntries(modes.filter((m) => m !== "tj").map((m) => [m, confusion(results[m])])), results }, null, 2));
  console.log(`\nSaved ${path.relative(root, file)}`);
})();
