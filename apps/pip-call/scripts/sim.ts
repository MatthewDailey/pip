// Rung 1: the call simulator.
//
//   pnpm --filter @shady-pip/pip-call sim [-- --dataset=clean|messy|all --only=id1,id2 --speed=10
//                                            --concurrency=4 --approach=jev|keyword --pip4-url=... --keep]
//
// Boots pip-call with RecordingTwilioOps (fake Twilio) and Pip4 (jev-playground) unless one is
// already answering on PIP4_URL, then replays Pip4's labelled transcripts through pip-call as
// signed fake Twilio Real-Time Transcription webhooks: two partials then the final for every line,
// caller lines on inbound_track and Mom's on outbound_track, at 5 s/line divided by --speed, then
// transcription-stopped. It reads each call's timeline and Pip4 session back and checks:
//   - every line reached Pip4 once, in order, unmangled (Pip3 loses nothing)
//   - scam calls: hold + mute + warn issued exactly once; normal calls: never
//   - --approach=keyword: each call's max level equals an offline replay of the same detector, the
//     exact plumbing check that works with no Jev key
//   - mock Jev (no JEV_API_KEY): only the IRS call must reach takeover and the sisters chat must not
// Writes .proof/pip3-sim.json and prints a table per call.
import { spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { signParams } from "../src/webhook";
import type { LabeledTranscript } from "../../jev-playground/data/transcripts";
import type { TimelineEvent } from "../src/timeline";

const ROOT = path.resolve(__dirname, "..");
const JEV_DIR = path.resolve(ROOT, "../jev-playground");
const arg = (name: string, def: string) => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=") ?? def;
const flag = (name: string) => process.argv.includes(`--${name}`);

const SECONDS_PER_LINE = 5;
const SIM_TOKEN = "sim-auth-token";
const SIM_PORT = Number(arg("port", "3290"));
const PIP_CALL_URL = `http://localhost:${SIM_PORT}`;
const PIP4_URL = arg("pip4-url", process.env.PIP4_URL || "http://localhost:3100").replace(/\/$/, "");
const approach = arg("approach", process.env.PIP4_APPROACH || "jev");
const speed = Number(arg("speed", "10"));
const concurrency = Number(arg("concurrency", "4"));
const dataset = arg("dataset", "all") as "clean" | "messy" | "all";
const only = arg("only", "").split(",").filter(Boolean);

const children: ChildProcess[] = [];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function up(url: string) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(2000) });
    return r.status < 500;
  } catch {
    return false;
  }
}

async function waitUp(url: string, what: string, ms = 120_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await up(url)) return;
    await sleep(500);
  }
  throw new Error(`${what} did not come up at ${url} within ${ms / 1000}s`);
}

function boot(cwd: string, port: number, env: Record<string, string>, name: string) {
  const bin = path.join(cwd, "node_modules/.bin/next");
  const p = spawn(bin, ["dev", "-p", String(port)], { cwd, env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] });
  const log = fs.createWriteStream(path.join(ROOT, `.proof/${name}.log`));
  p.stdout!.pipe(log);
  p.stderr!.pipe(log);
  children.push(p);
  return p;
}

function cleanup() {
  for (const c of children) c.kill("SIGTERM");
}
process.on("exit", cleanup);
process.on("SIGINT", () => process.exit(130));

type Speaker = "caller" | "user" | "unknown";
const track = (s: Speaker) => (s === "user" ? "outbound_track" : "inbound_track"); // Pip4's endpoint treats unknown as caller

async function postWebhook(params: Record<string, string>) {
  const url = `${PIP_CALL_URL}/twilio/transcription`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", "x-twilio-signature": signParams(SIM_TOKEN, url, params) },
    body: new URLSearchParams(params).toString(),
  });
  if (res.status >= 300) throw new Error(`webhook ${res.status}: ${await res.text()}`);
}

interface CallResult {
  id: string;
  dataset: string;
  label: "scam" | "benign";
  lines: number;
  callSid: string;
  levels: string[];
  maxLevel: string;
  takeoverAtLine?: number;
  takeoverEvents: number;
  takeoverCmds: number;
  linesAtPip4: number;
  linesIntact: boolean;
  outOfOrder: boolean;
  pip4Errors: number;
  msPerLine: number[];
  pip4RoundTripMs: number[];
  jevMs: number[];
  expectedMaxLevel?: string;
  pass: boolean;
  why: string[];
}

const RANK = ["calm", "watch", "alert", "takeover"];
const runId = Date.now().toString(36);

async function runCall(t: LabeledTranscript, expected: Map<string, string>, mockJev: boolean): Promise<CallResult> {
  const callSid = `CAsim${runId}${t.id}`.replace(/[^A-Za-z0-9]/g, "");
  await fetch(`${PIP_CALL_URL}/debug/calls/${callSid}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ context: t.context }),
  });
  const start = Date.parse("2026-01-01T15:00:00Z");
  let seq = 0;
  const base = { AccountSid: "ACsim", CallSid: callSid, TranscriptionSid: `GT${callSid}`, LanguageCode: "en-US" };
  await postWebhook({ ...base, TranscriptionEvent: "transcription-started", SequenceId: String(++seq), Timestamp: new Date(start).toISOString() });
  const msPerLine: number[] = [];
  for (const [i, l] of t.lines.entries()) {
    const ts = new Date(start + i * SECONDS_PER_LINE * 1000).toISOString();
    const words = l.text.split(/\s+/);
    const content = (text: string, final: boolean) => ({
      ...base,
      TranscriptionEvent: "transcription-content",
      Track: track(l.speaker as Speaker),
      TranscriptionData: JSON.stringify({ transcript: text, confidence: final ? 0.92 : 0.6 }),
      Final: String(final),
      ...(final ? {} : { Stability: "0.5" }),
      SequenceId: String(++seq),
      Timestamp: ts,
    });
    const t0 = performance.now();
    for (const frac of [1 / 3, 2 / 3]) {
      const part = words.slice(0, Math.max(1, Math.round(words.length * frac))).join(" ");
      await postWebhook(content(part, false));
    }
    await postWebhook(content(l.text, true));
    msPerLine.push(Math.round(performance.now() - t0));
    const wait = (SECONDS_PER_LINE * 1000) / speed - (performance.now() - t0);
    if (wait > 0 && i < t.lines.length - 1) await sleep(wait);
  }
  await postWebhook({ ...base, TranscriptionEvent: "transcription-stopped", SequenceId: String(++seq), Timestamp: new Date(start + t.lines.length * 5000).toISOString() });

  const tl = ((await (await fetch(`${PIP_CALL_URL}/debug/calls/${callSid}`, { headers: { "x-pip-debug-token": SIM_TOKEN } })).json()) as { events: TimelineEvent[] }).events;
  const session = (await (await fetch(`${PIP4_URL}/api/calls/${callSid}`)).json()) as { lines: { speaker: string; text: string }[] };
  const data = (e: TimelineEvent) => e.data as Record<string, unknown>;
  const verdicts = tl.filter((e) => e.kind === "verdict").map(data);
  const levels = verdicts.map((v) => String(v.level));
  const maxLevel = levels.reduce((m, l) => (RANK.indexOf(l) > RANK.indexOf(m) ? l : m), "calm");
  const takeover = verdicts.find((v) => v.action === "takeover");
  const cmds = tl.filter((e) => e.kind === "twilio_cmd").map(data);
  const takeoverCmds = cmds.filter((c) => c.hold === true || c.muted === true || typeof c.announceUrl === "string").length;
  const sentSeqs = tl.filter((e) => e.kind === "pip4_request").flatMap((e) => (data(e).seqs as number[]) ?? []);
  const outOfOrder = sentSeqs.some((s, i) => i > 0 && s < sentSeqs[i - 1]);
  const speakerOf = (s: string) => (s === "user" ? "user" : "caller");
  const linesIntact =
    session.lines?.length === t.lines.length &&
    session.lines.every((l, i) => l.text === t.lines[i].text && l.speaker === speakerOf(t.lines[i].speaker));

  const r: CallResult = {
    id: t.id,
    dataset: t.dataset ?? "clean",
    label: t.label,
    lines: t.lines.length,
    callSid,
    levels,
    maxLevel,
    takeoverAtLine: takeover ? Number(takeover.lineCount) : undefined,
    takeoverEvents: tl.filter((e) => e.kind === "takeover").length,
    takeoverCmds,
    linesAtPip4: session.lines?.length ?? 0,
    linesIntact,
    outOfOrder,
    pip4Errors: tl.filter((e) => e.kind === "pip4_error").length,
    msPerLine,
    pip4RoundTripMs: tl.filter((e) => e.kind === "pip4_response").map((e) => Number(data(e).roundTripMs)),
    jevMs: tl.filter((e) => e.kind === "pip4_response" && data(e).jevMs != null).map((e) => Math.round(Number(data(e).jevMs))),
    expectedMaxLevel: expected.get(t.id),
    pass: true,
    why: [],
  };
  const fail = (w: string) => ((r.pass = false), r.why.push(w));
  if (!linesIntact) fail(`Pip4 got ${r.linesAtPip4}/${r.lines} lines or they differ`);
  if (outOfOrder) fail("lines reached Pip4 out of order");
  if (r.pip4Errors) fail(`${r.pip4Errors} Pip4 errors`);
  const tookOver = r.takeoverEvents > 0;
  if (tookOver && (r.takeoverEvents !== 1 || r.takeoverCmds !== 3)) fail(`takeover issued ${r.takeoverEvents}× with ${r.takeoverCmds} commands`);
  if (approach === "keyword") {
    if (r.expectedMaxLevel !== maxLevel) fail(`max level ${maxLevel} ≠ offline replay ${r.expectedMaxLevel}`);
  } else if (mockJev) {
    if (t.id === "irs-gift-cards" && !tookOver) fail("IRS call did not reach takeover");
    if (t.id === "m-neg-sisters-discuss-scam" && tookOver) fail("sisters chat reached takeover");
  } else {
    if (t.label === "scam" && !tookOver) fail(`scam never reached takeover (max ${maxLevel})`);
    if (t.label === "benign" && tookOver) fail("false takeover on a normal call");
  }
  return r;
}

/** What Pip4 itself decides for a transcript with the same detector, offline (deterministic detectors only). */
async function offlineReplay(set: LabeledTranscript[]) {
  const { getDetectors } = await import("../../jev-playground/lib/detectors");
  const { initialPolicyState, stepPolicy, DEFAULT_POLICY } = await import("../../jev-playground/lib/policy");
  const det = getDetectors()[approach as "keyword"];
  const out = new Map<string, string>();
  for (const t of set) {
    const lines = t.lines.map((l, i) => ({ ...l, speaker: l.speaker === "user" ? ("user" as const) : ("caller" as const), t: i * SECONDS_PER_LINE }));
    let ps = initialPolicyState();
    const cps: number[] = [];
    for (let n = 2; n < lines.length; n += 2) cps.push(n);
    cps.push(lines.length);
    for (const n of cps) ps = stepPolicy(ps, await det.analyze({ lines: lines.slice(0, n), context: t.context }), DEFAULT_POLICY);
    out.set(t.id, ps.maxLevel);
  }
  return out;
}

async function main() {
  fs.mkdirSync(path.join(ROOT, ".proof"), { recursive: true });
  const { CLEAN_TRANSCRIPTS } = await import("../../jev-playground/data/transcripts");
  const { MESSY_TRANSCRIPTS } = await import("../../jev-playground/data/transcripts-messy");
  const pool = dataset === "clean" ? CLEAN_TRANSCRIPTS : dataset === "messy" ? MESSY_TRANSCRIPTS : [...CLEAN_TRANSCRIPTS, ...MESSY_TRANSCRIPTS];
  const set = pool.filter((t) => !only.length || only.includes(t.id));
  if (!set.length) throw new Error(`no transcripts match --only=${only.join(",")}`);

  if (!(await up(`${PIP4_URL}/api/status`))) {
    if (!PIP4_URL.startsWith("http://localhost:")) throw new Error(`Pip4 is not answering at ${PIP4_URL}`);
    console.log(`· booting Pip4 (jev-playground) on ${PIP4_URL}`);
    boot(JEV_DIR, Number(new URL(PIP4_URL).port), {}, "sim-pip4");
    await waitUp(`${PIP4_URL}/api/status`, "Pip4");
  }
  const st = (await (await fetch(`${PIP4_URL}/api/status`)).json()) as { jev?: { mock?: boolean; model?: string } };
  const mockJev = approach === "jev" && !!st.jev?.mock;

  console.log(`· booting pip-call (fake Twilio) on ${PIP_CALL_URL}`);
  boot(ROOT, SIM_PORT, {
    PIP_TWILIO: "fake",
    TWILIO_AUTH_TOKEN: SIM_TOKEN,
    PUBLIC_BASE_URL: PIP_CALL_URL,
    PIP4_URL,
    PIP4_APPROACH: approach,
    PIP_QUIET: "1",
    PIP_RECORD: "",
  }, "sim-pip-call");
  await waitUp(`${PIP_CALL_URL}/health`, "pip-call");
  // compile the routes once before timing anything
  await fetch(`${PIP_CALL_URL}/debug/calls/warmup`).catch(() => {});

  const expected = approach === "keyword" ? await offlineReplay(set) : new Map<string, string>();
  const mode = approach === "keyword" ? "keyword baseline (exact plumbing check vs offline replay)" : mockJev ? "Jev MOCK (no JEV_API_KEY): plumbing only" : `live Jev ${st.jev?.model ?? ""}`;
  console.log(`· ${set.length} calls (${set.filter((t) => t.label === "scam").length} scam / ${set.filter((t) => t.label === "benign").length} normal), ${mode}, ${speed}× speed, concurrency ${concurrency}\n`);

  const results: CallResult[] = [];
  let next = 0;
  const t0 = Date.now();
  await Promise.all(
    Array.from({ length: Math.min(concurrency, set.length) }, async () => {
      while (next < set.length) {
        const t = set[next++];
        try {
          const r = await runCall(t, expected, mockJev);
          results.push(r);
          process.stdout.write(r.pass ? "." : "✗");
        } catch (e) {
          results.push({ id: t.id, dataset: t.dataset ?? "clean", label: t.label, pass: false, why: [String(e)] } as CallResult);
          process.stdout.write("E");
        }
      }
    }),
  );
  console.log("\n");
  results.sort((a, b) => set.findIndex((t) => t.id === a.id) - set.findIndex((t) => t.id === b.id));

  const pad = (s: unknown, n: number) => String(s ?? "").padEnd(n);
  console.log(pad("call", 36) + pad("label", 8) + pad("max", 10) + pad("takeover@", 11) + pad("cmds", 6) + pad("lines", 8) + pad("pip4 ms", 9) + "result");
  for (const r of results) {
    const rt = r.pip4RoundTripMs?.length ? Math.round(r.pip4RoundTripMs.reduce((a, b) => a + b, 0) / r.pip4RoundTripMs.length) : "";
    console.log(
      pad(r.id, 36) + pad(r.label, 8) + pad(r.maxLevel, 10) + pad(r.takeoverAtLine ? `line ${r.takeoverAtLine}` : "-", 11) + pad(r.takeoverCmds, 6) +
        pad(r.linesAtPip4 != null ? `${r.linesAtPip4}/${r.lines}` : "", 8) + pad(rt, 9) + (r.pass ? "ok" : `FAIL ${r.why.join("; ")}`),
    );
  }
  const scams = results.filter((r) => r.label === "scam");
  const normal = results.filter((r) => r.label === "benign");
  const flagged = (r: CallResult) => RANK.indexOf(r.maxLevel) >= RANK.indexOf("alert");
  const rts = results.flatMap((r) => r.pip4RoundTripMs ?? []).sort((a, b) => a - b);
  const summary = {
    mode,
    approach,
    speed,
    concurrency,
    calls: results.length,
    passed: results.filter((r) => r.pass).length,
    scamsTakenOver: `${scams.filter((r) => r.takeoverEvents > 0).length}/${scams.length}`,
    scamsFlagged: `${scams.filter(flagged).length}/${scams.length}`,
    falseTakeovers: `${normal.filter((r) => r.takeoverEvents > 0).length}/${normal.length}`,
    falseAlerts: `${normal.filter(flagged).length}/${normal.length}`,
    linesIntact: `${results.filter((r) => r.linesIntact).length}/${results.length}`,
    outOfOrderCalls: results.filter((r) => r.outOfOrder).length,
    pip4RoundTripMs: rts.length ? { p50: rts[Math.floor(rts.length / 2)], p95: rts[Math.floor(rts.length * 0.95)] } : null,
    wallSeconds: Math.round((Date.now() - t0) / 1000),
  };
  console.log(`\n${JSON.stringify(summary, null, 2)}`);
  fs.writeFileSync(path.join(ROOT, ".proof/pip3-sim.json"), JSON.stringify({ summary, results }, null, 2));
  console.log(`\nwrote .proof/pip3-sim.json`);
  const ok = summary.passed === results.length;
  console.log(ok ? "SIM PASS" : "SIM FAIL");
  if (flag("keep")) {
    console.log("--keep: servers left running, Ctrl-C to stop");
    await new Promise(() => {});
  }
  process.exit(ok ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
