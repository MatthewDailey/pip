#!/usr/bin/env node
// The menu pane in `turbo dev`: pick a one-shot action and it runs as a turbo task, streaming here.
//
//   turbo dev   # emulator, logcat, preview and this menu side by side (select the menu pane, press Enter to type)
//   pnpm menu   # the menu alone, in any terminal
//
// Every entry is a plain `turbo run <task>`, so `pnpm turbo run pip:sampler` from another terminal does the same.
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { intro, isCancel, log, select, text } from "@clack/prompts";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const MOODS = [
  { value: "0", label: "neutral" },
  { value: "1", label: "happy" },
  { value: "2", label: "sad" },
  { value: "3", label: "angry" },
  { value: "4", label: "surprised" },
];

const ACTIONS = [
  { value: "demo", label: "demo", hint: "fresh install on the connected device, everything granted, demo script printed" },
  { value: "demo:reset", label: "demo:reset", hint: "re-arm the first-run greeting only" },
  { value: "app:setup", label: "app:setup", hint: "build, install, grant everything" },
  { value: "pip:sampler", label: "pip:sampler", hint: "Kotlin showreel: a short sampler" },
  { value: "pip:peeks", label: "pip:peeks", hint: "Kotlin showreel: Pip peeking in" },
  { value: "pip:showreel", label: "pip:showreel", hint: "Kotlin showreel: the full show (~3 min)" },
  { value: "pip:hodor", label: "pip:hodor", hint: "Rive: the HODOR scene, compiled by rivc (9 s)" },
  { value: "pip:showreel-rive", label: "pip:showreel-rive", hint: "Rive: the whole showreel, compiled by rivc (~2 min)" },
  { value: "pip:story", label: "pip:story", hint: "Rive: push a story .riv and play it, no reinstall" },
  { value: "pip:roam", label: "pip:roam", hint: "Rive: Pip roams the home screen (hops, peeks, web swings); optional seed" },
  { value: "pip:roam-stop", label: "pip:roam-stop", hint: "stop roaming and bring the overlay Pip back" },
  { value: "scenarios", label: "scenarios", hint: "walk through every scam moment from the re-skin mocks, live; optional id or from=<id>" },
  { value: "scenarios:auto", label: "scenarios:auto", hint: "the same, hands-off: each scenario runs a few seconds, then advances" },
  { value: "scenarios:sheet", label: "scenarios:sheet", hint: "contact sheet of the last scenario run's screenshots" },
  { value: "eval", label: "jev:eval", hint: "jev-playground: replay the labelled calls through Jev and the baselines" },
  { value: "sim", label: "pip3:sim", hint: "pip-call: replay the 50 labelled calls as fake Twilio webhooks" },
  { value: "pip:mood", label: "pip:mood", hint: "Rive overlay: set Pip's mood" },
  { value: "pip:blink", label: "pip:blink", hint: "Rive overlay: fire the blink trigger" },
  { value: "pip:detect-scam-call", label: "pip:detect-scam-call", hint: "during a call: tell Pip it's a scam; he mutes you (rule on) or warns" },
  { value: "pip:act-unmute", label: "pip:act-unmute", hint: "tap Pip's \"Unmute me\" undo" },
  { value: "pip:mute-rule", label: "pip:mute-rule", hint: "turn the \"mute me on scam calls\" rule on or off" },
  { value: "call:irs", label: "call:irs", hint: "scripted IRS scam call into the emulator mic" },
  { value: "call:bank", label: "call:bank", hint: "scripted bank-fraud call" },
  { value: "call:grandson", label: "call:grandson", hint: "scripted grandson-in-jail call" },
  { value: "call:pharmacy", label: "call:pharmacy", hint: "scripted pharmacy call (benign)" },
  { value: "sms:scam", label: "sms:scam", hint: "fake scam SMS" },
  { value: "sms:hello", label: "sms:hello", hint: "fake harmless SMS" },
  { value: "call:fake", label: "call:fake", hint: "fake ring/answer/hang-up, no audio" },
  { value: "@shady-pip/pip-rive#build", label: "pip-rive:build", hint: "compile Pip's rig and the showreels to packages/pip-rive/dist" },
  { value: "pip:push", label: "pip:push", hint: "compile the rig and play it in the app without reinstalling" },
  { value: "pip:bundled", label: "pip:bundled", hint: "back to the .riv bundled in the APK" },
  { value: "rivc:test", label: "rivc:test", hint: "rivc's test suite" },
  { value: "goldens", label: "goldens", hint: "golden screenshots of the previewer (Playwright)" },
  { value: "showreel:record", label: "showreel:record", hint: "record the show with soundtrack into .proof/" },
  { value: "quit", label: "quit" },
];

// Interactive tasks (scenarios reads Enter/s/q) need turbo's TUI; everything else streams into this pane.
const NEEDS_TUI = new Set(["scenarios"]);

function run(task, extra = []) {
  const ui = NEEDS_TUI.has(task) ? "--ui=tui" : "--ui=stream";
  const args = ["turbo", "run", task, ui, "--output-logs=new-only", ...(extra.length ? ["--", ...extra] : [])];
  log.step(`pnpm ${args.join(" ")}`);
  return new Promise((resolve) => {
    const child = spawn("pnpm", args, { cwd: root, stdio: "inherit" });
    child.on("exit", (code) => resolve(code ?? 1));
    child.on("error", (err) => {
      log.error(String(err));
      resolve(1);
    });
  });
}

intro("Pip playground");
let last = "pip:sampler";
for (;;) {
  const task = await select({ message: "Run", options: ACTIONS, initialValue: last, maxItems: 12 });
  if (isCancel(task) || task === "quit") break;
  last = task;
  let extra = [];
  if (task === "pip:mood") {
    const mood = await select({ message: "Mood", options: MOODS, initialValue: "1" });
    if (isCancel(mood)) continue;
    extra = [mood];
  }
  if (task === "pip:story") {
    const file = await text({ message: "Story .riv", initialValue: "packages/pip-rive/dist/showreel/hello.riv" });
    if (isCancel(file)) continue;
    extra = [file];
  }
  if (task === "pip:roam") {
    const seed = await text({ message: "Seed (empty for a random one; the log prints it)", placeholder: "random" });
    if (isCancel(seed)) continue;
    extra = seed ? [seed] : [];
  }
  if (task === "scenarios" || task === "scenarios:auto") {
    const which = await text({ message: "Scenarios (empty for all; ids, from=<id>, or list)", placeholder: "all" });
    if (isCancel(which)) continue;
    extra = which ? which.split(/\s+/) : [];
  }
  if (task === "pip:detect-scam-call") {
    const reason = await text({ message: "What did the caller do?", initialValue: "asked for gift cards" });
    if (isCancel(reason)) continue;
    extra = [reason];
  }
  if (task === "pip:mute-rule") {
    const v = await select({ message: "Mute me on scam calls", options: [{ value: "on", label: "on" }, { value: "off", label: "off" }, { value: "default", label: "build default" }] });
    if (isCancel(v)) continue;
    extra = [v];
  }
  const code = await run(task, extra);
  (code === 0 ? log.success : log.error)(`${task} ${code === 0 ? "done" : `failed (exit ${code})`}`);
}
// Stay alive as a persistent task so turbo doesn't report the menu as finished; Ctrl-C quits.
if (process.env.TURBO_HASH) {
  log.info("menu closed; press Ctrl-C to quit the TUI");
  setInterval(() => {}, 1 << 30);
}
