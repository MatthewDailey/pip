// Rung 2: a real phone call with no people on it.
//
//   pnpm --filter @shady-pip/pip-call robocall -- --script=irs [--base=https://<your-deployment>] [--keep-mom-url]
//
// The robo-caller number calls the Pip line and reads tools/mock-calls/lines/<script>.txt; the Pip
// line rings MOM_NUMBER, which on the deployment under test is the robo-caller number itself (one
// number can place a call and receive one at the same time), whose voice URL this script points at
// /twiml/robo-mom for the run. Afterwards it reads both calls' timelines from /debug/calls and prints:
//   - did the caller and mom legs join the same conference
//   - every transcript line with its speaker
//   - speech end → final transcript latency per caller line (the robo-caller marks when each line
//     finished playing), the number that decides Vercel + Twilio transcription vs Fly + Media Streams
//   - Pip4's verdicts and any takeover
// Writes .proof/pip3-robocall-<script>.json. Needs TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, the Pip line and
// robo-caller numbers (PIP_LINE_NUMBER|TWILIO_PIP_LINE, ROBO_CALLER_NUMBER|TWILIO_ROBO_CALLER).
import fs from "node:fs";
import path from "node:path";
import twilio from "twilio";
import { loadConfig } from "../src/config";
import { parseScript } from "../src/robo";
import type { TimelineEvent } from "../src/timeline";

const ROOT = path.resolve(__dirname, "..");
const arg = (name: string, def = "") => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=") ?? def;
const flag = (name: string) => process.argv.includes(`--${name}`);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const script = arg("script", "irs");
const base = (arg("base") || process.env.PUBLIC_BASE_URL || "").replace(/\/$/, "");
if (!base) throw new Error("pass --base=<deployment URL> or set PUBLIC_BASE_URL");
const cfg = loadConfig({ ...process.env, PUBLIC_BASE_URL: base });
const robo = cfg.roboCallerNumber;
const pipLine = cfg.pipLineNumber;
if (!robo || !pipLine) throw new Error("need the robo-caller and Pip line numbers (ROBO_CALLER_NUMBER / TWILIO_ROBO_CALLER, PIP_LINE_NUMBER / TWILIO_PIP_LINE)");
const client = twilio(cfg.twilioAccountSid, cfg.twilioAuthToken);

const withBypass = (p: string) => {
  const u = new URL(p, base + "/");
  if (cfg.vercelBypassSecret) u.searchParams.set("x-vercel-protection-bypass", cfg.vercelBypassSecret);
  return u.toString();
};
const bypassHeaders: Record<string, string> = cfg.vercelBypassSecret ? { "x-vercel-protection-bypass": cfg.vercelBypassSecret } : {};

async function timeline(callSid: string): Promise<TimelineEvent[]> {
  const r = await fetch(withBypass(`/debug/calls/${callSid}`), { headers: { ...bypassHeaders, "x-pip-debug-token": cfg.twilioAuthToken } });
  if (r.status === 404) return [];
  if (!r.ok) throw new Error(`GET /debug/calls/${callSid}: HTTP ${r.status}`);
  return ((await r.json()) as { events: TimelineEvent[] }).events;
}

const pct = (xs: number[], p: number) => {
  if (!xs.length) return undefined;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
};

async function main() {
  const lines = parseScript(fs.readFileSync(path.resolve(ROOT, `../../tools/mock-calls/lines/${script}.txt`), "utf8"));
  const health = await fetch(withBypass("/health"), { headers: bypassHeaders }).then((r) => r.json());
  console.log("deployment:", base, JSON.stringify(health));

  const reg = await fetch(withBypass("/debug/robocall"), {
    method: "POST",
    headers: { ...bypassHeaders, "content-type": "application/json", "x-pip-debug-token": cfg.twilioAuthToken },
    body: JSON.stringify({ lines }),
  });
  if (!reg.ok) throw new Error(`POST /debug/robocall: HTTP ${reg.status} ${await reg.text()}`);
  const { run } = (await reg.json()) as { run: string };

  // robo-mom: point the robo-caller number's incoming calls at /twiml/robo-mom for this run
  const [num] = await client.incomingPhoneNumbers.list({ phoneNumber: robo, limit: 1 });
  if (!num) throw new Error(`${robo} is not a number on this Twilio account`);
  const prev = { voiceUrl: num.voiceUrl, voiceMethod: num.voiceMethod };
  await client.incomingPhoneNumbers(num.sid).update({ voiceUrl: withBypass("/twiml/robo-mom"), voiceMethod: "POST" });

  const startedAfter = new Date(Date.now() - 5000);
  let outSid = "";
  try {
    const call = await client.calls.create({
      from: robo!,
      to: pipLine!,
      url: withBypass(`/twiml/robo-caller?run=${run}&i=0`),
      method: "POST",
      timeLimit: 240,
    });
    outSid = call.sid;
    console.log(`robo-caller ${outSid}: ${lines.length} lines of ${script}`);
    const t0 = Date.now();
    for (;;) {
      await sleep(3000);
      const c = await client.calls(outSid).fetch();
      process.stdout.write(`\r  ${c.status} ${Math.round((Date.now() - t0) / 1000)}s   `);
      if (["completed", "failed", "busy", "no-answer", "canceled"].includes(c.status)) break;
      if (Date.now() - t0 > 300_000) {
        await client.calls(outSid).update({ status: "completed" });
        break;
      }
    }
    console.log();
  } finally {
    if (!flag("keep-mom-url")) await client.incomingPhoneNumbers(num.sid).update(prev);
  }

  // the Pip line's own leg (its CallSid names the conference)
  const inbound = (await client.calls.list({ to: pipLine, from: robo, startTimeAfter: startedAfter, limit: 5 })).find((c) => c.direction === "inbound");
  if (!inbound) throw new Error("no inbound call to the Pip line found: is its voice URL pointed at /twiml/voice?");
  await sleep(4000); // trailing webhooks
  const [callTl, roboTl] = await Promise.all([timeline(inbound.sid), timeline(outSid)]);

  const joins = callTl.filter((e) => e.kind === "conference" && (e.data as { event?: string }).event === "participant-join").map((e) => (e.data as { label?: string }).label);
  const transcriptLines = callTl
    .filter((e) => e.kind === "line")
    .map((e) => ({ ...(e.data as { speaker: string; text: string; seq: number }), at: e.at, t: e.t }));
  const partials = callTl.filter((e) => e.kind === "partial");
  const saidEnds = roboTl.filter((e) => e.kind === "robo" && (e.data as { saidLine?: number }).saidLine !== undefined).map((e) => ({ at: e.at, ...(e.data as { saidLine: number; text: string }) }));

  // Each said line can come back as one or more finals. A final belongs to the said line it shares most
  // of its words with; the line's latency is from when it finished playing to its last final.
  const words = (x: string) => new Set(x.toLowerCase().match(/[a-z']+/g) ?? []);
  const callerFinals = transcriptLines.filter((l) => l.speaker === "caller");
  const latencies: { line: number; text: string; ms: number }[] = [];
  for (const s of saidEnds) {
    const said = words(s.text);
    const mine = callerFinals.filter((f) => {
      const w = [...words(f.text)];
      return w.length > 0 && w.filter((x) => said.has(x)).length >= Math.max(1, 0.6 * w.length) && f.at > s.at - 20_000 && f.at < s.at + 8_000;
    });
    if (mine.length) latencies.push({ line: s.saidLine, text: s.text, ms: Math.max(...mine.map((f) => f.at)) - s.at });
  }
  const verdicts = callTl.filter((e) => e.kind === "verdict").map((e) => e.data);
  const takeover = callTl.find((e) => e.kind === "takeover");
  const errors = callTl.filter((e) => e.kind.endsWith("error") || (e.kind === "webhook" && (e.data as { error?: string }).error));

  console.log(`\nPip line call ${inbound.sid} (${inbound.status}, ${inbound.duration}s)`);
  console.log(`conference joins: ${joins.join(", ") || "none"}`);
  console.log(`\ntranscript (${transcriptLines.length} finals, ${partials.length} partials):`);
  for (const l of transcriptLines) console.log(`  ${(l.t / 1000).toFixed(1).padStart(6)}s  ${l.speaker.padEnd(6)} ${l.text}`);
  console.log(`\nspeech end → final transcript, caller lines:`);
  for (const l of latencies) console.log(`  line ${l.line}: ${l.ms} ms  ${l.text.slice(0, 60)}`);
  const ms = latencies.map((l) => l.ms);
  const summary = {
    script,
    run,
    pipLineCallSid: inbound.sid,
    roboCallerSid: outSid,
    bothJoined: joins.includes("caller") && joins.includes("mom"),
    finals: transcriptLines.length,
    callerFinals: callerFinals.length,
    momFinals: transcriptLines.filter((l) => l.speaker === "user").length,
    partials: partials.length,
    linesSaid: saidEnds.length,
    linesMatched: latencies.length,
    speechEndToFinalMs: { p50: pct(ms, 50), p90: pct(ms, 90), max: ms.length ? Math.max(...ms) : undefined },
    verdicts: verdicts.length,
    takeover: takeover ? { atMs: takeover.t } : null,
    errors: errors.map((e) => e.data),
  };
  console.log("\n" + JSON.stringify(summary, null, 2));
  fs.mkdirSync(path.join(ROOT, ".proof"), { recursive: true });
  const out = path.join(ROOT, `.proof/pip3-robocall-${script}.json`);
  fs.writeFileSync(out, JSON.stringify({ summary, latencies, callTimeline: callTl, roboTimeline: roboTl }, null, 2));
  console.log(`wrote ${path.relative(process.cwd(), out)}`);
  const pass = summary.bothJoined && summary.callerFinals > 0;
  console.log(pass ? "ROBOCALL PASS" : "ROBOCALL FAIL");
  process.exit(pass ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
