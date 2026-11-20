// adb helpers for the scenario runner: the emulator's state (foreground app, call state, notifications) and what Pip
// logged. The emulator is shared with other agents, so nothing here restarts, wipes or clears anything.
import { execFile, execFileSync, spawn } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);
export const PIP = "dev.playground.pip";
export const FIXTURES = "dev.playground.pipfixtures";

export function adb(...args) {
  try {
    return execFileSync("adb", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: 64 << 20 });
  } catch (err) {
    return String(err.stdout ?? "");
  }
}

export async function adbAsync(...args) {
  try {
    return (await run("adb", args, { encoding: "utf8", maxBuffer: 64 << 20 })).stdout;
  } catch (err) {
    return String(err.stdout ?? "");
  }
}

export const shell = (cmd) => adbAsync("shell", cmd);
export const sleep = (s) => new Promise((r) => setTimeout(r, s * 1000));

/** Seconds since the epoch on the device, which is what Pip's events and logcat are stamped with. */
export function deviceNow() {
  return Number(adb("shell", "date", "+%s").trim()) || Math.floor(Date.now() / 1000);
}

/** The resumed activity in front, as "package/activity". */
export async function topActivity() {
  const out = await shell("dumpsys activity activities | grep -m1 topResumedActivity");
  return out.match(/ u\d+ ([\w.]+\/[\w.$]+)/)?.[1] ?? "";
}

export async function foreground() {
  return (await topActivity()).split("/")[0];
}

let launcher;
export function launcherPackage() {
  launcher ??= adb("shell", "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    .trim().split("\n").pop().split("/")[0];
  return launcher;
}

/** 0 idle, 1 ringing, 2 off-hook (the same reading tools/mock-calls uses). */
export async function callState() {
  const out = await shell("dumpsys telephony.registry | grep -m1 mCallState");
  return Number(out.split("=")[1]) || 0;
}

/** Notification counts per package, from `cmd notification list` (userId|pkg|id|tag|uid). */
export async function notifications() {
  const counts = {};
  for (const line of (await shell("cmd notification list")).split("\n")) {
    const pkg = line.split("|")[1];
    if (pkg) counts[pkg] = (counts[pkg] ?? 0) + 1;
  }
  return counts;
}

/** Pip's events since `sinceSec`, read from the app's own JSON-lines log (logcat's ring buffer is too small to rely on). */
export async function pipEvents(sinceSec) {
  const out = await adbAsync("shell", "run-as", PIP, "tail", "-n", "400", "files/events.jsonl");
  return out.split("\n").flatMap((line) => {
    try {
      const e = JSON.parse(line);
      return e.time >= sinceSec * 1000 ? [e] : [];
    } catch {
      return [];
    }
  });
}

/** Streams Pip's overlay and show logs for the whole run, so short-lived lines aren't lost to the ring buffer. */
export function followPipLogs() {
  const lines = [];
  const child = spawn("adb", ["logcat", "-v", "epoch", "-T", "1", "PipRive:I", "PipShow:I", "*:S"], { stdio: ["ignore", "pipe", "ignore"] });
  let buf = "";
  child.stdout.on("data", (chunk) => {
    buf += chunk;
    const parts = buf.split("\n");
    buf = parts.pop();
    for (const p of parts) {
      const m = p.match(/^\s*(\d+)\.\d+\s+\d+\s+\d+\s+\w\s+(\w+)\s*:\s?(.*)$/);
      if (m) lines.push({ t: Number(m[1]), tag: m[2], text: m[3] });
    }
  });
  return { since: (sec) => lines.filter((l) => l.t >= sec), stop: () => child.kill() };
}

export async function screenshot(file) {
  const { writeFile } = await import("node:fs/promises");
  const png = await new Promise((resolve) => {
    execFile("adb", ["exec-out", "screencap", "-p"], { encoding: "buffer", maxBuffer: 64 << 20 }, (_e, out) => resolve(out));
  });
  if (png?.length) await writeFile(file, png);
  return png?.length > 0;
}

/**
 * Done conditions, each armed first so a scenario can't finish before its situation appears:
 *   home                   the launcher is in front again, after something else was
 *   call-ended             the call went idle, after ringing or being answered
 *   notification-gone:pkg  pkg's notifications went away, after one showed up
 *   visited-then-left:pkg  pkg came to the front and then left it
 */
export function makeCondition(spec) {
  const [kind, arg] = spec.split(/:(.*)/s);
  let armed = false;
  let baseline = 0;
  const checks = {
    home: async () => {
      const fg = await foreground();
      if (fg && fg !== launcherPackage()) armed = true;
      return armed && fg === launcherPackage();
    },
    "call-ended": async () => {
      const s = await callState();
      if (s !== 0) armed = true;
      return armed && s === 0;
    },
    "notification-gone": async () => {
      const n = (await notifications())[arg] ?? 0;
      if (n > baseline) armed = true;
      return armed && n <= baseline;
    },
    "visited-then-left": async () => {
      const fg = await foreground();
      if (fg === arg) armed = true;
      return armed && fg !== arg;
    },
  };
  const check = checks[kind];
  if (!check) throw new Error(`unknown done condition "${spec}"`);
  // Notifications already showing before the scenario (an old thread, say) don't count.
  const prime = async () => {
    if (kind === "notification-gone") baseline = (await notifications())[arg] ?? 0;
  };
  return { spec, check, prime, armed: () => armed };
}
