// Rung 2b: TJ's app on the emulator places a real Voice SDK call through Pip, no people on it.
//
//   pnpm --filter @shady-pip/pip-call appcall -- --script=irs [--base=https://<your-deployment>] [--no-mom]
//   (or from the repo root: turbo run call:app -- --script=irs)
//
// 1. Registers tools/mock-calls/lines/<script>.txt as a robo-caller run and points the robo-caller
//    number's incoming calls at /twiml/robo-caller for that run (restored afterwards), so the number
//    answers as the scammer and reads the script.
// 2. Tells the Pip app on the emulator to dial that number (am start --es pip_dial).
// 3. While the call is up, speaks Mom's replies into the emulator mic (macOS `say` + tools/mock-calls/emu_mic.py).
// 4. Reads the call's timeline from /debug/calls and prints: the app's params as Pip4 context, both
//    legs joined, transcript lines, verdicts, takeover. Writes .proof/pip3-appcall-<script>.json.
// Needs TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, ROBO_CALLER_NUMBER|TWILIO_ROBO_CALLER, adb with the emulator
// running, and the Pip app installed with a PIP_APP_SECRET (local.properties pip.appSecret or .env).
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import twilio from "twilio";
import { loadConfig } from "../src/config";
import { parseScript, ROBO_MOM_REPLIES } from "../src/robo";
import type { TimelineEvent } from "../src/timeline";

const ROOT = path.resolve(__dirname, "..");
const REPO = path.resolve(ROOT, "../..");
const arg = (name: string, def = "") => process.argv.find((a) => a.startsWith(`--${name}=`))?.split("=").slice(1).join("=") ?? def;
const flag = (name: string) => process.argv.includes(`--${name}`);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const script = arg("script", "irs");
const base = (arg("base") || process.env.PIP_CALL_URL || "http://localhost:3200").replace(/\/$/, "");
const identity = arg("user", process.env.PIP_USER || "mom");
const cfg = loadConfig({ ...process.env, PUBLIC_BASE_URL: base });
if (!cfg.roboCallerNumber) throw new Error("need the robo-caller number (ROBO_CALLER_NUMBER / TWILIO_ROBO_CALLER)");
const robo: string = cfg.roboCallerNumber;
const client = twilio(cfg.twilioAccountSid, cfg.twilioAuthToken);
const adb = process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools/adb") : "adb";
const serial = process.env.ANDROID_SERIAL || "emulator-5554";

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

/** Speaks Mom's replies into the emulator mic, one every ~9 s, until stopped. macOS only (`say`). */
function startMom(): () => void {
  let stopped = false;
  const py = path.join(REPO, "tools/.venv/bin/python");
  const micPy = path.join(REPO, "tools/mock-calls/emu_mic.py");
  if (flag("no-mom") || process.platform !== "darwin" || !fs.existsSync(py)) {
    console.log("  (not speaking Mom's lines: needs macOS `say` and tools/.venv; run a tools/mock-calls task once to create it)");
    return () => {};
  }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "pip-mom-"));
  (async () => {
    await sleep(6000);
    for (const [i, line] of ROBO_MOM_REPLIES.entries()) {
      if (stopped) return;
      const wav = path.join(dir, `mom-${i}.wav`);
      execFileSync("say", ["-v", "Samantha", "-o", wav, "--data-format=LEI16@24000", line]);
      console.log(`\n  mom says: ${line}`);
      await new Promise<void>((res) => spawn(py, [micPy, wav], { stdio: "ignore" }).on("exit", () => res()));
      await sleep(9000);
    }
  })().catch((e) => console.warn("mom:", e.message));
  return () => {
    stopped = true;
  };
}

async function main() {
  const lines = parseScript(fs.readFileSync(path.join(REPO, `tools/mock-calls/lines/${script}.txt`), "utf8"));
  const health = await fetch(withBypass("/health"), { headers: bypassHeaders }).then((r) => r.json());
  console.log("deployment:", base, JSON.stringify(health));

  const reg = await fetch(withBypass("/debug/robocall"), {
    method: "POST",
    headers: { ...bypassHeaders, "content-type": "application/json", "x-pip-debug-token": cfg.twilioAuthToken },
    body: JSON.stringify({ lines }),
  });
  if (!reg.ok) throw new Error(`POST /debug/robocall: HTTP ${reg.status} ${await reg.text()}`);
  const { run } = (await reg.json()) as { run: string };

  // the robo-caller number answers as the scammer for this run
  const [num] = await client.incomingPhoneNumbers.list({ phoneNumber: robo, limit: 1 });
  if (!num) throw new Error(`${robo} is not a number on this Twilio account`);
  const prev = { voiceUrl: num.voiceUrl, voiceMethod: num.voiceMethod };
  await client.incomingPhoneNumbers(num.sid).update({ voiceUrl: withBypass(`/twiml/robo-caller?run=${run}&i=0`), voiceMethod: "POST" });

  const startedAfter = new Date(Date.now() - 5000);
  let appSid = "";
  let stopMom = () => {};
  try {
    console.log(`app dials ${robo} (robo-caller reading ${lines.length} lines of ${script})`);
    execFileSync(adb, ["-s", serial, "shell", "am", "start", "-n", "dev.playground.pip/.MainActivity", "--es", "pip_dial", robo], { stdio: "inherit" });

    // the app's call is the parent call from client:<identity>
    const t0 = Date.now();
    while (!appSid && Date.now() - t0 < 30_000) {
      await sleep(2000);
      const calls = await client.calls.list({ from: `client:${identity}`, startTimeAfter: startedAfter, limit: 5 });
      appSid = calls.find((c) => !c.parentCallSid)?.sid ?? "";
    }
    if (!appSid) throw new Error(`no call from client:${identity} within 30 s: is the app installed with PIP_APP_SECRET, and RECORD_AUDIO granted? (adb logcat -s PipVoice)`);
    console.log(`app call ${appSid}`);
    stopMom = startMom();
    for (;;) {
      await sleep(3000);
      const c = await client.calls(appSid).fetch();
      process.stdout.write(`\r  ${c.status} ${Math.round((Date.now() - t0) / 1000)}s   `);
      if (["completed", "failed", "busy", "no-answer", "canceled"].includes(c.status)) break;
      if (Date.now() - t0 > 300_000) {
        await client.calls(appSid).update({ status: "completed" });
        break;
      }
    }
    console.log();
  } finally {
    stopMom();
    await client.incomingPhoneNumbers(num.sid).update(prev);
  }

  await sleep(4000); // trailing webhooks
  const tl = await timeline(appSid);
  const voice = tl.find((e) => e.kind === "webhook" && (e.data as { route?: string }).route === "voice")?.data as { call?: string } | undefined;
  const joins = tl.filter((e) => e.kind === "conference" && (e.data as { event?: string }).event === "participant-join").map((e) => (e.data as { label?: string }).label);
  const transcript = tl.filter((e) => e.kind === "line").map((e) => ({ t: e.t, ...(e.data as { speaker: string; text: string }) }));
  const verdicts = tl.filter((e) => e.kind === "verdict").map((e) => ({ t: e.t, ...(e.data as { level: string; action: string }) }));
  const takeover = tl.find((e) => e.kind === "takeover");
  const context = (tl.find((e) => e.kind === "note" && (e.data as { context?: unknown }).context)?.data as { context?: Record<string, unknown> } | undefined)?.context;

  console.log(`\napp call ${appSid}: routed as ${voice?.call ?? "?"}`);
  console.log(`app params as Pip4 context: ${JSON.stringify(context ?? null)}`);
  console.log(`conference joins: ${joins.join(", ") || "none"}`);
  console.log(`\ntranscript (${transcript.length} finals):`);
  for (const l of transcript) console.log(`  ${(l.t / 1000).toFixed(1).padStart(6)}s  ${l.speaker.padEnd(6)} ${l.text}`);
  console.log(`\nverdicts: ${verdicts.map((v) => `${(v.t / 1000).toFixed(1)}s ${v.level}`).join(", ") || "none"}`);
  console.log(`takeover: ${takeover ? `${(takeover.t / 1000).toFixed(1)}s` : "none"}`);

  const summary = {
    script,
    run,
    appCallSid: appSid,
    routedAs: voice?.call,
    context,
    bothJoined: joins.includes("caller") && joins.includes("mom"),
    finals: transcript.length,
    callerFinals: transcript.filter((l) => l.speaker === "caller").length,
    momFinals: transcript.filter((l) => l.speaker !== "caller").length,
    verdicts: verdicts.length,
    takeover: takeover ? { atMs: takeover.t } : null,
  };
  console.log("\n" + JSON.stringify(summary, null, 2));
  fs.mkdirSync(path.join(REPO, ".proof"), { recursive: true });
  const out = path.join(REPO, `.proof/pip3-appcall-${script}.json`);
  fs.writeFileSync(out, JSON.stringify({ summary, timeline: tl }, null, 2));
  console.log(`wrote ${path.relative(process.cwd(), out)}`);
  const pass = summary.routedAs === "app_outgoing" && summary.bothJoined;
  console.log(pass ? (takeover ? "APPCALL PASS (takeover)" : "APPCALL PASS (connected, no takeover)") : "APPCALL FAIL");
  process.exit(pass ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
