// The steps a scenario's "steps" and "auto" lists can use. Each records what it started in `ctx`, so teardown can undo
// exactly that and nothing else on the shared emulator.
//
//   home                          press Home
//   wait        s                 pause
//   ring        from              an incoming call (adb emu gsm call)
//   accept      from              answer it
//   mock-call   script, from, hold?, wait?   a scripted caller from tools/mock-calls, spoken into the emulator mic
//   proximity   after, for        cover the proximity sensor (phone at your ear) after `after` s, for `for` s
//   contact     name, number      a temporary contact, deleted at teardown
//   sms         from, text        an incoming text (adb emu sms send)
//   thread      number            open that Messages thread
//   notify      title, text       a chat notification from the fixtures app (ExampleChat); each x in title is a random digit
//   scroll                        a short swipe up, as a reader would
//   url         path              open a page from web/ in Chrome
//   fixture     activity, extras  open a fixtures screen (apps/pip-fixtures)
//   pip-app                       open Pip's own app
//   shade                         pull down the notification shade
//   type        text              type into the focused field (auto mode stands in for the person)
//   tap-pip                       tap the overlay Pip
import { spawn } from "node:child_process";
import path from "node:path";
import { adb, adbAsync, callState, FIXTURES, PIP, shell, sleep, topActivity } from "./device.mjs";
import { ensureServer } from "./server.mjs";

/** Quotes one argument for the device shell (adb shell joins its arguments and the device re-parses them). */
const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

export function newContext(root, { auto, log }) {
  return { root, auto, log, calls: new Set(), children: [], timers: [], contacts: [], proximity: false, fixtures: false };
}

async function waitFor(pred, seconds) {
  const end = Date.now() + seconds * 1000;
  while (Date.now() < end) {
    if (await pred()) return true;
    await sleep(0.5);
  }
  return false;
}

const STEPS = {
  async home() {
    await shell("input keyevent KEYCODE_HOME");
  },
  async wait(ctx, { s = 1 }) {
    await sleep(s);
  },
  async ring(ctx, { from }) {
    await callSetup(ctx);
    ctx.calls.add(from);
    await adbAsync("emu", "gsm", "call", from);
    await waitFor(async () => (await callState()) === 1, 10);
  },
  async accept(ctx, { from }) {
    // The modem can take a moment to offer the call to Telecom; an accept sent too early is dropped.
    for (let tries = 0; tries < 4; tries++) {
      await sleep(1.5);
      await adbAsync("emu", "gsm", "accept", from);
      if (await waitFor(async () => (await callState()) === 2, 4)) return;
    }
    ctx.log("  ! the call wasn't answered");
  },
  async "mock-call"(ctx, { script, from, hold = false, wait = false }) {
    await callSetup(ctx);
    ctx.calls.add(from);
    const args = [path.join(ctx.root, "tools/mock-calls/mock_call.sh"), script, "--auto", "--pipe", "--number", from];
    if (hold) args.push("--hold");
    if (ctx.auto) args.push("--quiet");
    const child = spawn("bash", args, { stdio: ["ignore", "pipe", "pipe"], env: process.env });
    const echo = (chunk) => String(chunk).split("\n").filter((l) => l && !l.startsWith("[notice]")).forEach((l) => ctx.log(`  call │ ${l}`));
    child.stdout.on("data", echo);
    child.stderr.on("data", echo);
    const exited = new Promise((resolve) => child.on("exit", resolve));
    ctx.children.push(child);
    if (wait) await exited;
    else await waitFor(async () => (await callState()) === 2, 20);
  },
  async proximity(ctx, { after = 10, for: hold = 8 }) {
    ctx.proximity = true;
    ctx.timers.push(setTimeout(() => adbAsync("emu", "sensor", "set", "proximity", "0"), after * 1000));
    ctx.timers.push(setTimeout(() => adbAsync("emu", "sensor", "set", "proximity", "1"), (after + hold) * 1000));
  },
  async contact(ctx, { name, number }) {
    await shell(`content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:`);
    const ids = (await shell("content query --uri content://com.android.contacts/raw_contacts --projection _id")).match(/_id=(\d+)/g) ?? [];
    const id = Math.max(...ids.map((s) => Number(s.slice(4))));
    if (!Number.isFinite(id)) return ctx.log("  ! could not add the contact");
    ctx.contacts.push(id);
    const data = "--uri content://com.android.contacts/data --bind raw_contact_id:i:" + id;
    await shell(`content insert ${data} --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:${q(name)}`);
    await shell(`content insert ${data} --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:${q(number)} --bind data2:i:2`);
  },
  async sms(ctx, { from, text }) {
    await adbAsync("emu", "sms", "send", from, ...text.split(" "));
  },
  async thread(ctx, { number }) {
    await wake();
    await shell(`am start -W -a android.intent.action.SENDTO -d smsto:${number} -p com.google.android.apps.messaging`);
  },
  async notify(ctx, { title, text }) {
    ctx.fixtures = true;
    // Pip's notification listener skips a title and text it has seen before, so each run comes from a fresh number.
    const from = title.replace(/x/g, () => String(Math.floor(Math.random() * 10)));
    await shell(`am broadcast -n ${FIXTURES}/.NotifyReceiver --es title ${q(from)} --es text ${q(text)}`);
  },
  async scroll() {
    await shell("input swipe 540 1500 540 1200 400");
  },
  async url(ctx, { path: page }) {
    await wake();
    const port = await ensureServer(path.join(ctx.root, "apps/pip-scenarios/web"));
    await chromeReady(port);
    await shell(`am start -W -a android.intent.action.VIEW -d ${q(`http://localhost:${port}${page}`)} -p com.android.chrome --ez create_new_tab true`);
  },
  async fixture(ctx, { activity, extras = {} }) {
    await wake();
    ctx.fixtures = true;
    const flags = Object.entries(extras)
      .map(([k, v]) => (typeof v === "boolean" ? `--ez ${k} ${v}` : `--es ${k} ${q(v)}`))
      .join(" ");
    await shell(`am start -W -S -n ${FIXTURES}/.${activity} ${flags}`);
  },
  async "pip-app"() {
    await wake();
    await shell(`am start -W -n ${PIP}/.MainActivity`);
  },
  async shade() {
    await shell("cmd statusbar expand-notifications");
  },
  async type(ctx, { text }) {
    await shell(`input text ${q(text.replace(/ /g, "%s"))}`);
  },
  async "tap-pip"(ctx) {
    const at = await pipOnScreen();
    if (at) await shell(`input tap ${at.x} ${at.y}`);
    else ctx.log("  ! couldn't find Pip's overlay window to tap");
  },
};

/**
 * Before a call: turn the screen off, so the call rings full screen the way it does in a pocket (with the screen on,
 * Android shows only a heads-up and the call screen stays behind the home screen). During the call, decline the
 * system's "Download English (US) update" prompt, which Pip's speech recognizer raises on every call while the
 * on-device model is missing and which would cover the call screen.
 */
async function callSetup(ctx) {
  if (ctx.callWatch) return;
  await shell("input keyevent KEYCODE_SLEEP");
  await sleep(1);
  let told = false;
  ctx.callWatch = setInterval(async () => {
    if (!(await topActivity()).includes("languagepack.ui.DownloadActivity")) return;
    await shell("input keyevent KEYCODE_BACK");
    if (!told) ctx.log("  declined the speech-model download prompt Pip's recognizer raised");
    told = true;
  }, 1000);
  ctx.timers.push(ctx.callWatch);
}

export async function runSteps(ctx, steps = []) {
  for (const step of steps) {
    const fn = STEPS[step.do];
    if (!fn) throw new Error(`unknown step "${step.do}"`);
    await fn(ctx, step);
  }
}

/** Undoes what the scenario started, in reverse order of how much it would get in the next scenario's way. */
export async function teardown(ctx) {
  ctx.timers.forEach((t) => { clearTimeout(t); clearInterval(t); });
  // A scripted caller must close its mic stream before the call ends (an open injection when Android stops
  // recording crashes the emulator), so it hangs up itself and only a call it leaves behind is cancelled here.
  for (const child of ctx.children) {
    if (child.exitCode !== null) continue;
    const exited = new Promise((resolve) => child.once("exit", resolve));
    child.kill("SIGTERM");
    await Promise.race([exited, sleep(10)]);
  }
  for (const n of ctx.calls) await adbAsync("emu", "gsm", "cancel", n);
  if (ctx.calls.size) await waitForIdle();
  if (ctx.proximity) await adbAsync("emu", "sensor", "set", "proximity", "1");
  for (const id of ctx.contacts) {
    await shell(`content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where _id=${id}`);
  }
  // Force-stopping the fixtures app also clears its notifications.
  if (ctx.fixtures) await shell(`am force-stop ${FIXTURES}`);
  await shell("cmd statusbar collapse");
  await shell("input keyevent KEYCODE_WAKEUP");
  await shell("input keyevent KEYCODE_HOME");
}

/** Screens opened after a call would otherwise come up behind a sleeping display. */
async function wake() {
  await shell("input keyevent KEYCODE_WAKEUP");
}

async function waitForIdle() {
  await waitFor(async () => (await callState()) === 0, 10);
}

/**
 * Chrome on a fresh emulator opens its first-run screens, which would bury the page. Chrome reads extra flags from
 * /data/local/tmp/chrome-command-line only while it is the debug app, so both are set for the run and put back by
 * restoreChrome() at the end.
 */
let chromeSaved = null;
async function chromeReady(port) {
  await adbAsync("reverse", `tcp:${port}`, `tcp:${port}`);
  if (chromeSaved) return;
  chromeSaved = {
    debugApp: (await shell("settings get global debug_app")).trim(),
    flags: (await shell("cat /data/local/tmp/chrome-command-line 2>/dev/null")).trim(),
    port,
  };
  await shell(`echo ${q("_ --disable-fre --no-default-browser-check --no-first-run")} > /data/local/tmp/chrome-command-line`);
  await shell("am set-debug-app --persistent com.android.chrome");
  await shell("am force-stop com.android.chrome");
}

export async function restoreChrome() {
  if (!chromeSaved) return;
  const { debugApp, flags, port } = chromeSaved;
  chromeSaved = null;
  if (!debugApp || debugApp === "null") await shell("am clear-debug-app");
  else await shell(`am set-debug-app --persistent ${debugApp}`);
  if (flags) await shell(`echo ${q(flags)} > /data/local/tmp/chrome-command-line`);
  else await shell("rm -f /data/local/tmp/chrome-command-line");
  await adbAsync("reverse", "--remove", `tcp:${port}`);
}

/** Where Pip's overlay window sits, from the input dispatcher's list of touchable windows. */
async function pipOnScreen() {
  const out = adb("shell", "dumpsys", "window", "windows");
  const m = out.match(new RegExp(`${PIP.replace(/\./g, "\\.")}, frame=\\[Rect\\((\\d+), (\\d+) - (\\d+), (\\d+)\\)\\]`));
  if (!m) return null;
  const [l, t, r, b] = m.slice(1).map(Number);
  // The speech bubble sits above Pip in the same window; aim at the lower part, where his face is.
  return { x: Math.round((l + r) / 2), y: Math.round(t + (b - t) * 0.75) };
}
