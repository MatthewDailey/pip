// npm run eval [-- --dataset=clean|messy|demo|all --no-context --listen=both|user --approaches=jev,keyword,llm,tripwire,jevtw --every=2 --concurrency=4 --only=id1,id2 --tag=x]
//   --listen=user: derive user-only transcripts (caller lines dropped, as with on-device STT of her voice only); line
//   numbers then count her lines. Eval-only approaches: tripwire (lib/tripwire.ts alone, checked on every line) and
//   jevtw (Jev at checkpoints + tripwire floor on every line, i.e. what /api/calls does).
// Replays each labeled transcript as a live call: every N lines, each approach re-scores the rolling
// window and the escalation policy updates. Reports per-dataset, per-difficulty and per-tag accuracy,
// misses, false alarms, time-to-alert, and latency. Writes eval-results/<timestamp>.json.
import fs from "node:fs";
import path from "node:path";
import { loadEnvLocal } from "../lib/env";

loadEnvLocal(path.resolve(__dirname, ".."));

import type { ApproachId, DetectorResult } from "../lib/types";
import type { Level } from "../lib/policy";
import type { LabeledTranscript } from "../data/transcripts";

const arg = (name: string, def: string) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=")[1] ?? def;
const SECONDS_PER_LINE = 5;

async function main() {
  const { getDetectors, status } = await import("../lib/detectors");
  const { DEFAULT_POLICY, initialPolicyState, stepPolicy, levelRank } = await import("../lib/policy");
  const { CLEAN_TRANSCRIPTS } = await import("../data/transcripts");
  const { MESSY_TRANSCRIPTS } = await import("../data/transcripts-messy");
  const { DEMO_CALLS } = await import("../data/demo-calls");
  const { checkTripwire, raisePolicy } = await import("../lib/tripwire");
  const { enrichContext } = await import("../lib/callMeta");
  const DEMO: LabeledTranscript[] = DEMO_CALLS.map((d) => ({
    id: d.id, title: d.title, label: d.label, scamType: d.label === "scam" ? "government" : "none", note: d.expect,
    context: d.context, lines: d.lines.map((l) => ({ speaker: "user" as const, text: l.text, t: l.t })),
    dataset: "demo" as LabeledTranscript["dataset"], difficulty: "medium", tags: ["demo"],
  }));

  const dataset = arg("dataset", "clean") as "clean" | "messy" | "demo" | "all";
  const listen = arg("listen", "both") as "both" | "user";
  const every = Number(arg("every", "2"));
  const concurrency = Number(arg("concurrency", "4"));
  const only = arg("only", "").split(",").filter(Boolean);
  const tagFilter = arg("tag", "");
  // ablation: drop call_context (caller_in_contacts, caller_id, direction...) to show the transcript alone carries the signal
  const noContext = process.argv.includes("--no-context");
  const st = status();
  const approaches = arg("approaches", "jev,keyword,tj,llm").split(",") as ApproachId[];
  if (!st.llm.configured && approaches.includes("llm")) approaches.splice(approaches.indexOf("llm"), 1);

  const raw: LabeledTranscript[] =
    dataset === "clean" ? CLEAN_TRANSCRIPTS : dataset === "messy" ? MESSY_TRANSCRIPTS : dataset === "demo" ? DEMO : [...CLEAN_TRANSCRIPTS, ...MESSY_TRANSCRIPTS];
  // user-only: keep her lines (unknown-speaker lines count as hers: only her mic is transcribed); remap scam onset
  const pool = listen === "user"
    ? raw.map((t) => ({
        ...t,
        lines: t.lines.filter((l) => l.speaker !== "caller").map((l) => ({ ...l, speaker: "user" as const })),
        scamStartsAt: t.scamStartsAt === undefined ? undefined : t.lines.slice(0, t.scamStartsAt).filter((l) => l.speaker !== "caller").length,
      }))
    : raw;
  const set = pool.filter((t) => (!only.length || only.includes(t.id)) && (!tagFilter || t.tags?.includes(tagFilter)));

  console.log(`· dataset ${dataset}: ${set.length} transcripts (${set.filter((t) => t.label === "scam").length} scam / ${set.filter((t) => t.label === "benign").length} benign)`);
  console.log(`· Jev: ${st.jev.mock ? "MOCK MODE (JEV_API_KEY not set) — Jev numbers below are NOT real" : `live, model ${st.jev.model}`}`);
  console.log(`· LLM: ${st.llm.configured ? "configured" : "not configured (skipped)"}`);
  if (noContext) console.log("· ABLATION: call context removed (transcript only)");
  if (listen === "user") console.log("· USER-ONLY: caller lines dropped; line numbers count the user's lines");
  console.log(`· re-score every ${every} lines (~${SECONDS_PER_LINE}s/line), policy ${JSON.stringify(DEFAULT_POLICY)}\n`);

  const detectors = getDetectors();

  type Row = {
    id: string;
    dataset: string;
    label: "scam" | "benign";
    difficulty: string;
    tags: string[];
    approach: ApproachId;
    maxLevel: Level;
    firstAlertLine?: number;
    firstTakeoverLine?: number;
    scamStartsAt?: number;
    lines: number;
    peakRisk: number;
    finalScamType?: string;
    latencies: number[];
    errors: string[];
    trace: { n: number; raw: number; smoothed: number; level: Level }[];
  };
  const rows: Row[] = [];

  async function runOne(t: LabeledTranscript, a: ApproachId) {
    let ps = initialPolicyState();
    const name = a as string;
    const useTripwire = name === "tripwire" || name === "jevtw";
    const det = name === "jevtw" ? detectors.jev : name === "tripwire" ? null : detectors[a];
    const ctx = noContext ? undefined : enrichContext(t.context);
    const row: Row = {
      id: t.id, dataset: t.dataset ?? "clean", label: t.label, difficulty: t.difficulty ?? "medium", tags: t.tags ?? [],
      approach: a, maxLevel: "calm", lines: t.lines.length, scamStartsAt: t.scamStartsAt, peakRisk: 0, latencies: [], errors: [], trace: [],
    };
    const checkpoints: number[] = [];
    for (let n = every; n < t.lines.length; n += every) checkpoints.push(n);
    checkpoints.push(t.lines.length);
    let tripLevel: Level | null = null;
    for (let n = 1; n <= t.lines.length; n++) {
      if (useTripwire && t.lines[n - 1].speaker !== "caller") {
        const userTexts = t.lines.slice(0, n).filter((l) => l.speaker !== "caller").map((l) => l.text);
        const hit = checkTripwire(userTexts, t.lines[n - 1].text, ctx ?? {});
        if (hit && (!tripLevel || levelRank(hit.level) > levelRank(tripLevel))) tripLevel = hit.level;
        if (tripLevel) ps = raisePolicy(ps, tripLevel, "tripwire");
        if (levelRank(ps.level) >= levelRank("alert") && row.firstAlertLine === undefined) row.firstAlertLine = n;
        if (ps.level === "takeover" && row.firstTakeoverLine === undefined) row.firstTakeoverLine = n;
      }
      if (!det || !checkpoints.includes(n)) continue;
      const r: DetectorResult = await det.analyze({ lines: t.lines.slice(0, n), context: ctx, listen });
      if (r.error) {
        row.errors.push(r.error);
        continue;
      }
      row.latencies.push(r.latencyMs);
      row.peakRisk = Math.max(row.peakRisk, r.risk);
      row.finalScamType = r.scamType;
      ps = stepPolicy(ps, r, DEFAULT_POLICY);
      if (tripLevel) ps = raisePolicy(ps, tripLevel, "tripwire");
      row.trace.push({ n, raw: +r.risk.toFixed(3), smoothed: +ps.smoothed.risk.toFixed(3), level: ps.level });
      if (levelRank(ps.level) >= levelRank("alert") && row.firstAlertLine === undefined) row.firstAlertLine = n;
      if (ps.level === "takeover" && row.firstTakeoverLine === undefined) row.firstTakeoverLine = n;
    }
    row.maxLevel = ps.maxLevel;
    rows.push(row);
  }

  const jobs = set.flatMap((t) => approaches.map((a) => () => runOne(t, a)));
  let i = 0;
  await Promise.all(
    Array.from({ length: Math.min(concurrency, jobs.length) }, async () => {
      while (i < jobs.length) await jobs[i++]();
    }),
  );

  const pct = (x: number) => `${(100 * x).toFixed(0)}%`;
  const q = (xs: number[], p: number) => {
    if (!xs.length) return NaN;
    const s = [...xs].sort((a, b) => a - b);
    return s[Math.min(s.length - 1, Math.floor(p * s.length))];
  };
  const flagged = (x: Row) => levelRank(x.maxLevel) >= levelRank("alert");
  const correct = (x: Row) => (x.label === "scam" ? flagged(x) : !flagged(x));

  function metrics(r: Row[]) {
    const scams = r.filter((x) => x.label === "scam");
    const benign = r.filter((x) => x.label === "benign");
    const lat = r.flatMap((x) => x.latencies);
    const caught = scams.filter((x) => x.firstAlertLine);
    // lines of conversation after the scam actually began until Pip alerted
    const afterOnset = caught.map((x) => x.firstAlertLine! - (x.scamStartsAt ?? 0));
    return {
      n: r.length,
      accuracy: r.length ? r.filter(correct).length / r.length : NaN,
      scamsFlagged: `${scams.filter(flagged).length}/${scams.length}`,
      scamsTakenOver: `${scams.filter((x) => x.maxLevel === "takeover").length}/${scams.length}`,
      falseAlerts: `${benign.filter(flagged).length}/${benign.length}`,
      falseTakeovers: `${benign.filter((x) => x.maxLevel === "takeover").length}/${benign.length}`,
      missed: scams.filter((x) => !flagged(x)).map((x) => `${x.id} (${x.maxLevel})`),
      falseAlarms: benign.filter(flagged).map((x) => `${x.id} (${x.maxLevel})`),
      prematureAlerts: caught.filter((x) => x.scamStartsAt !== undefined && x.firstAlertLine! <= x.scamStartsAt).map((x) => x.id),
      timeToAlert: caught.length
        ? {
            meanLine: caught.reduce((a, x) => a + x.firstAlertLine!, 0) / caught.length,
            meanSeconds: (SECONDS_PER_LINE * caught.reduce((a, x) => a + x.firstAlertLine!, 0)) / caught.length,
            meanLinesAfterOnset: afterOnset.reduce((a, b) => a + b, 0) / afterOnset.length,
            meanFractionOfCall: caught.reduce((a, x) => a + x.firstAlertLine! / x.lines, 0) / caught.length,
          }
        : null,
      timeToTakeover: (() => {
        const tk = scams.filter((x) => x.firstTakeoverLine);
        if (!tk.length) return null;
        return {
          meanLine: tk.reduce((a, x) => a + x.firstTakeoverLine!, 0) / tk.length,
          meanLinesAfterOnset: tk.reduce((a, x) => a + x.firstTakeoverLine! - (x.scamStartsAt ?? 0), 0) / tk.length,
          meanFractionOfCall: tk.reduce((a, x) => a + x.firstTakeoverLine! / x.lines, 0) / tk.length,
        };
      })(),
      latencyMs: { calls: lat.length, mean: lat.reduce((a, b) => a + b, 0) / (lat.length || 1), p50: q(lat, 0.5), p95: q(lat, 0.95) },
      errors: r.flatMap((x) => x.errors),
    };
  }

  const summary: Record<string, unknown> = {};
  const datasets = [...new Set(set.map((t) => t.dataset ?? "clean"))];
  for (const a of approaches) {
    const r = rows.filter((x) => x.approach === a);
    const m = metrics(r);
    const tag = `${a.toUpperCase()}${a === "jev" && st.jev.mock ? " (MOCK)" : ""}`;
    if (!m.latencyMs.calls && m.errors.length) {
      summary[a] = { failed: true, errors: m.errors.slice(0, 3) };
      console.log(`━━ ${tag}: ALL ${m.errors.length} CALLS FAILED, not scored\n  ${m.errors[0]}\n`);
      continue;
    }
    const perDataset = Object.fromEntries(datasets.map((d) => [d, metrics(r.filter((x) => x.dataset === d))]));
    const perDifficulty = Object.fromEntries(["easy", "medium", "hard"].map((d) => [d, metrics(r.filter((x) => x.difficulty === d))]));
    const tags = [...new Set(r.flatMap((x) => x.tags))].sort();
    const perTag = Object.fromEntries(tags.map((t) => [t, metrics(r.filter((x) => x.tags.includes(t)))]));
    summary[a] = { overall: m, perDataset, perDifficulty, perTag };

    console.log(`━━ ${tag}`);
    const line = (name: string, x: ReturnType<typeof metrics>) =>
      console.log(
        `  ${name.padEnd(14)} n=${String(x.n).padEnd(3)} acc ${pct(x.accuracy).padStart(4)} | scams flagged ${x.scamsFlagged} taken over ${x.scamsTakenOver} | false alerts ${x.falseAlerts} false takeovers ${x.falseTakeovers}`,
      );
    line("overall", m);
    if (datasets.length > 1) for (const d of datasets) line(`[${d}]`, perDataset[d]);
    for (const d of ["easy", "medium", "hard"]) if (perDifficulty[d].n) line(`(${d})`, perDifficulty[d]);
    console.log(`  missed: ${m.missed.join(", ") || "none"}`);
    console.log(`  false alarms: ${m.falseAlarms.join(", ") || "none"}`);
    if (m.prematureAlerts.length) console.log(`  alerted before scam onset (during small talk): ${m.prematureAlerts.join(", ")}`);
    if (m.timeToAlert)
      console.log(
        `  time-to-alert: line ${m.timeToAlert.meanLine.toFixed(1)} (~${m.timeToAlert.meanSeconds.toFixed(0)}s), ${m.timeToAlert.meanLinesAfterOnset.toFixed(1)} lines after scam onset, ${pct(m.timeToAlert.meanFractionOfCall)} into the call`,
      );
    if (m.timeToTakeover)
      console.log(
        `  time-to-takeover: line ${m.timeToTakeover.meanLine.toFixed(1)}, ${m.timeToTakeover.meanLinesAfterOnset.toFixed(1)} lines after scam onset, ${pct(m.timeToTakeover.meanFractionOfCall)} into the call`,
      );
    console.log(`  latency: ${m.latencyMs.calls} calls, mean ${m.latencyMs.mean.toFixed(1)}ms, p50 ${m.latencyMs.p50.toFixed(1)}ms, p95 ${m.latencyMs.p95.toFixed(1)}ms`);
    if (m.errors.length) console.log(`  errors (${m.errors.length}): ${m.errors[0]}`);
    console.log();
  }

  // per-tag table
  const scored = approaches.filter((a) => !(summary[a] as { failed?: boolean })?.failed);
  if (scored.length) {
    console.log("Per-tag accuracy (correct/total):");
    const tags = [...new Set(set.flatMap((t) => t.tags ?? []))].sort();
    console.log("tag".padEnd(26) + scored.map((a) => a.padEnd(10)).join(""));
    for (const t of tags) {
      const cells = scored.map((a) => {
        const r = rows.filter((x) => x.approach === a && x.tags.includes(t));
        return `${r.filter(correct).length}/${r.length}`.padEnd(10);
      });
      console.log(t.padEnd(26) + cells.join(""));
    }
    console.log("\nPer-transcript max level (level@line of first alert):");
    console.log("transcript".padEnd(34) + "label".padEnd(8) + "diff".padEnd(8) + scored.map((a) => a.padEnd(14)).join(""));
    for (const t of set) {
      const cells = scored.map((a) => {
        const r = rows.find((x) => x.id === t.id && x.approach === a)!;
        const mark = correct(r) ? "" : "✗";
        return `${mark}${r.maxLevel}${r.firstAlertLine ? `@${r.firstAlertLine}` : ""}`.padEnd(14);
      });
      console.log(t.id.padEnd(34) + t.label.padEnd(8) + (t.difficulty ?? "").padEnd(8) + cells.join(""));
    }
  }

  const outDir = path.resolve(__dirname, "../eval-results");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, `${dataset}${listen === "user" ? "-useronly" : ""}${noContext ? "-nocontext" : ""}-${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  fs.writeFileSync(out, JSON.stringify({ dataset, listen, noContext, status: st, every, policy: DEFAULT_POLICY, summary, rows }, null, 2));
  console.log(`\nSaved ${path.relative(process.cwd(), out)}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
