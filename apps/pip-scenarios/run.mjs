#!/usr/bin/env node
// Walks a person through every scam moment from the Pip re-skin mocks, live on the emulator: each scenario sets up the
// real situation (a call, a text, a page in Chrome, a fixture app), waits while you play with it, and moves on when
// you close it. Pip's reaction is recorded as an expected-vs-actual line and a screenshot in .proof/scenarios/.
//
//   turbo run scenarios                         # all of them, in order
//   turbo run scenarios -- list                 # the list, with ids
//   turbo run scenarios -- <id> [<id>...]       # just these
//   turbo run scenarios -- from=<id>            # from this one on
//   turbo run scenarios:auto [-- auto=12]       # hands-off: each scenario runs 8 s (calls get their own length), then advances
//
// Keys while a scenario runs (focus the scenarios pane in turbo's TUI): Enter = done, s = skip, q = quit.
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assess } from "./lib/report.mjs";
import { adb, deviceNow, FIXTURES, followPipLogs, makeCondition, PIP, pipEvents, screenshot, shell, sleep } from "./lib/device.mjs";
import { stopServer } from "./lib/server.mjs";
import { newContext, restoreChrome, runSteps, teardown } from "./lib/steps.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const proof = path.join(root, ".proof/scenarios");
const data = JSON.parse(await readFile(path.join(here, "scenarios.json"), "utf8"));
const all = data.scenarios;

const c = process.stdout.isTTY ? (n) => (s) => `\x1b[${n}m${s}\x1b[0m` : () => (s) => s;
const bold = c(1), dim = c(2), red = c(31), green = c(32), yellow = c(33), cyan = c(36);
const log = (s = "") => console.log(s);

// ---- arguments -------------------------------------------------------------------------------------------------
const args = process.argv.slice(2).filter((a) => a !== "--");
let auto = 0;
let from = null;
const ids = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a === "list") ids.push("list");
  else if (a === "auto") auto ||= 8;
  else if (a.startsWith("auto=")) auto = Number(a.slice(5)) || 8;
  else if (a === "--auto") auto = Number(args[i + 1]) > 0 ? Number(args[++i]) : 8;
  else if (a.startsWith("from=")) from = a.slice(5);
  else ids.push(a);
}
if (process.env.SCENARIOS_AUTO) auto = Number(process.env.SCENARIOS_AUTO) || 8;

if (ids[0] === "list") {
  let group;
  for (const s of all) {
    if (s.group !== group) log(bold(`\n${(group = s.group)}`));
    log(`  ${s.id.padEnd(26)} ${s.title} ${dim(`[${s.fidelity}]`)}`);
  }
  log(`\n${all.length} scenarios. Run one with: turbo run scenarios -- <id>`);
  process.exit(0);
}
const unknown = [...ids, ...(from ? [from] : [])].filter((id) => !all.some((s) => s.id === id));
if (unknown.length) {
  console.error(`Unknown scenario ${unknown.join(", ")}. See: turbo run scenarios -- list`);
  process.exit(1);
}
let queue = ids.length ? all.filter((s) => ids.includes(s.id)) : all;
if (from) queue = queue.slice(queue.findIndex((s) => s.id === from));

// ---- keys ------------------------------------------------------------------------------------------------------
// Keys need a terminal: turbo's TUI gives an interactive task one (focus its pane). Under --ui=stream a task has no
// terminal, and reading /dev/tty from turbo's background process group would stop the process, so there are no keys.
let pendingKey = null;
const keys = auto ? null : openKeys();
keys?.on("data", (b) => {
  const k = b.toString();
  if (k === "\u0003" || k === "q") pendingKey = "quit";
  else if (k === "\r" || k === "\n") pendingKey = "done";
  else if (k === "s") pendingKey = "skip";
});

function openKeys() {
  try {
    if (!process.stdin.isTTY) return null;
    process.stdin.setRawMode(true);
    process.stdin.resume();
    return process.stdin;
  } catch {
    return null;
  }
}

// ---- preflight ---------------------------------------------------------------------------------------------------
if (!adb("get-state").includes("device")) {
  console.error("No emulator. Start one with: turbo droid");
  process.exit(1);
}
await mkdir(proof, { recursive: true });
const a11y = adb("shell", "settings", "get", "secure", "enabled_accessibility_services").trim();
if (!a11y.includes(`${PIP}/`)) log(yellow(`Pip's accessibility service is off, so Pip can't react. Run: turbo run app:setup`));
// The scenarios stage Pip's exploratory reactions (generic warnings, bubbles), which the shipping app keeps off.
await shell(`am broadcast -a ${PIP}.FLAGS -n ${PIP}/.sources.FlagsReceiver --ez exploratory true`);
// Roam takes Pip off the overlay; scenarios need the overlay Pip back.
await shell(`am broadcast -a ${PIP}.SHOW --es show stop`);
const apk = path.join(root, "apps/pip-fixtures/app/build/outputs/apk/debug/app-debug.apk");
const installed = adb("install", "-r", apk);
if (!installed.includes("Success")) log(yellow(`Couldn't install the fixtures app (${apk}); build it with turbo run build --filter=@shady-pip/pip-fixtures`));
adb("shell", "pm", "grant", FIXTURES, "android.permission.POST_NOTIFICATIONS");

const pipLogs = followPipLogs();
let quitting = false;
let current = null;
const cleanup = async () => {
  if (current) await teardown(current).catch(() => {});
  await restoreChrome().catch(() => {});
  stopServer();
  pipLogs.stop();
  keys?.setRawMode(false);
  keys?.pause();
};
process.on("SIGINT", async () => {
  if (quitting) process.exit(130);
  quitting = true;
  await cleanup();
  process.exit(130);
});

log(bold(`Pip scenarios: ${queue.length} of ${all.length}`) + (auto ? dim(`  (auto: ${auto} s each)`) : keys ? dim("  (Enter = done, s = skip, q = quit)") : dim("  (no terminal for keys: close each scenario on the emulator)")));

// ---- run ---------------------------------------------------------------------------------------------------------
const results = [];
for (const [i, s] of queue.entries()) {
  if (quitting) break;
  const mockUrl = data.refs[s.mock.ref] ?? s.mock.ref;
  log();
  log(bold(cyan(`[${i + 1}/${queue.length}] ${s.title}`)) + dim(`  ${s.id} · ${s.group}`));
  log(`  mock     ${s.mock.name} ${dim(mockUrl)}`);
  log(`  trigger  ${s.trigger}`);
  log(`  expect   ${levelColor(s.expect.level)(s.expect.level)} · ${s.expect.pip} · ${s.expect.surface}`);
  log(`  staging  ${s.fidelity === "real" ? green("real") : yellow("approximated")}${s.why ? dim(`: ${s.why}`) : ""}`);
  log(`  ${bold("→")} ${s.hint}`);

  const ctx = newContext(root, { auto: !!auto, log: (l) => log(dim(l)) });
  current = ctx;
  const conditions = s.done.map(makeCondition);
  for (const cond of conditions) await cond.prime();
  const t0 = deviceNow();
  const shotFile = path.join(proof, `${s.id}.png`);
  let outcome = "done";
  let shot = false;
  try {
    await runSteps(ctx, s.steps);
    if (auto) await runSteps(ctx, s.auto);
    const started = Date.now();
    const limit = auto ? Math.max(auto, s.autoSeconds ?? 0) : Infinity;
    const shotAt = Math.min(s.shotAt ?? 6, limit - 1);
    pendingKey = null;
    for (;;) {
      const elapsed = (Date.now() - started) / 1000;
      if (!shot && elapsed >= shotAt) shot = await screenshot(shotFile);
      if (pendingKey) { outcome = pendingKey; break; }
      if (elapsed >= limit) { outcome = "auto"; break; }
      if (await anyDone(conditions)) break;
      await sleep(0.7);
    }
    if (!shot) shot = await screenshot(shotFile);
  } catch (err) {
    outcome = "error";
    log(red(`  ! ${err.message}`));
  }
  await sleep(1.5);
  const events = await pipEvents(t0);
  await teardown(ctx);
  current = null;
  if (outcome === "quit") { quitting = true; if (!events.length) break; }
  const r = assess(s, events, pipLogs.since(t0));
  results.push({ id: s.id, title: s.title, group: s.group, fidelity: s.fidelity, why: s.why, mock: s.mock.name, mockUrl,
    trigger: s.trigger, expect: s.expect, seam: s.seam, outcome, screenshot: shot ? path.relative(root, shotFile) : null, ...r });
  log(`  actual   ${r.actual}`);
  log(`  result   ${verdictColor(r.verdict)(r.verdict)}${outcome === "skip" ? dim(" (skipped)") : ""}${r.verdict === "no Pip reaction yet" && s.seam ? dim(`\n           seam: ${s.seam}`) : ""}`);
  // A warning bubble stays up for 9 s; let it close so it isn't in the next scenario's screenshot.
  if (r.warned && !quitting) await waitForBubble(t0);
  await sleep(1);
}

await cleanup();

// ---- summary -----------------------------------------------------------------------------------------------------
log();
log(bold("Summary"));
const w = Math.max(...results.map((r) => r.id.length), 4);
log(dim(`${"id".padEnd(w)}  ${"staging".padEnd(12)} ${"expected".padEnd(8)} ${"score".padStart(5)}  result`));
for (const r of results) {
  log(`${r.id.padEnd(w)}  ${(r.fidelity === "real" ? green : yellow)(r.fidelity.padEnd(12))} ${r.expect.level.padEnd(8)} ${String(r.maxScore).padStart(5)}  ${verdictColor(r.verdict)(r.verdict)}`);
}
const count = (v) => results.filter((r) => r.verdict === v).length;
log(dim(`\n${results.length} run · ${count("no Pip reaction yet")} no reaction yet · ${results.filter((r) => r.verdict.startsWith("generic")).length} generic warning · ${results.filter((r) => r.verdict.startsWith("throttled")).length} throttled · ${count("quiet, as expected")} quiet · ${count("false alarm")} false alarm`));
await writeFile(path.join(proof, "summary.json"), JSON.stringify({ at: new Date().toISOString(), auto, results }, null, 2));
log(dim(`Screenshots and summary.json in ${path.relative(root, proof)}/`));
process.exit(0);

async function waitForBubble(since) {
  const end = Date.now() + 10_000;
  while (Date.now() < end && !pipLogs.since(since).some((l) => l.text.startsWith("bubble closed"))) await sleep(0.5);
}

async function anyDone(conds) {
  for (const cond of conds) if (await cond.check()) return true;
  return false;
}
function levelColor(level) {
  return { act: red, danger: red, caution: yellow, info: green }[level] ?? dim;
}
function verdictColor(v) {
  if (v.startsWith("quiet")) return green;
  if (v.startsWith("generic") || v.startsWith("warns") || v.startsWith("throttled")) return yellow;
  return red;
}
