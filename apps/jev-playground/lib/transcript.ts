import type { CallContext, DetectorInput, TranscriptLine } from "./types";

export interface WindowOptions {
  /** seconds of recent conversation to include (default 60) */
  windowSeconds?: number;
  /** always include the first N lines: scammers set up the pretext early ("this is the IRS") */
  openingLines?: number;
  /** used when lines have no timestamps */
  secondsPerLine?: number;
}

const DEFAULTS: Required<WindowOptions> = { windowSeconds: 60, openingLines: 3, secondsPerLine: 5 };

export function withTimes(lines: TranscriptLine[], secondsPerLine = DEFAULTS.secondsPerLine): TranscriptLine[] {
  return lines.map((l, i) => ({ ...l, t: l.t ?? i * secondsPerLine }));
}

export function formatLine(l: TranscriptLine) {
  return `${l.speaker === "caller" ? "CALLER" : l.speaker === "user" ? "USER" : "UNKNOWN SPEAKER"}: ${l.text}`;
}

/** Split into opening lines + rolling recent window (no duplicates). */
export function rollingWindow(lines: TranscriptLine[], opts: WindowOptions = {}) {
  const o = { ...DEFAULTS, ...opts };
  const timed = withTimes(lines, o.secondsPerLine);
  if (timed.length === 0) return { opening: [] as TranscriptLine[], recent: [] as TranscriptLine[], omitted: 0 };
  const now = timed[timed.length - 1].t!;
  let startIdx = timed.findIndex((l) => l.t! >= now - o.windowSeconds);
  if (startIdx < 0) startIdx = 0;
  const openingCount = Math.min(o.openingLines, startIdx);
  return {
    opening: timed.slice(0, openingCount),
    recent: timed.slice(startIdx),
    omitted: startIdx - openingCount,
  };
}

const BOTH_SIDES_TASK =
  "Live phone call transcript from streaming speech-to-text: may contain mis-heard words, missing punctuation, cut-off lines and wrong or missing speaker labels. USER is the person Pip protects.";
const USER_ONLY_TASK =
  "Live phone call, ONE side only: on-device speech-to-text of the protected USER's own voice (an older adult Pip protects). " +
  "The other party is NOT transcribed. Infer what they are saying or asking from her replies, e.g. " +
  "\"okay, which store, Target?\" (told to buy gift cards), \"let me scratch off the back\" (told to reveal the card code), " +
  "\"it's four one one one...\" (reading numbers out), \"no, I won't tell my daughter\" (told to keep it secret). " +
  "Text may be mis-heard or cut off. call_context holds phone metadata (caller number, caller ID, whether the number is in her contacts); caller ID can be spoofed.";

/** The state object sent to Jev (and shown in the UI). Kept small: Jev caps requests at 32 KiB. */
export function buildState(input: DetectorInput, opts: WindowOptions = {}) {
  const userOnly = input.listen === "user";
  const lines = userOnly
    ? input.lines.filter((l) => l.speaker !== "caller").map((l) => ({ ...l, speaker: "user" as const }))
    : input.lines;
  const { opening, recent, omitted } = rollingWindow(lines, opts);
  const state: Record<string, unknown> = {
    task: userOnly ? USER_ONLY_TASK : BOTH_SIDES_TASK,
    call_context: input.context ?? {},
  };
  if (opening.length) {
    state.call_opening = opening.map(formatLine).join("\n");
    if (omitted > 0) state.lines_omitted_between = omitted;
  }
  state.recent_transcript = recent.map(formatLine).join("\n");
  return state;
}

/** Same window, flattened to text (for keyword + LLM baselines). */
export function windowText(input: DetectorInput, opts: WindowOptions = {}) {
  const { opening, recent } = rollingWindow(input.lines, opts);
  return [...opening, ...recent].map(formatLine).join("\n");
}

export function contextText(ctx?: CallContext) {
  return ctx ? JSON.stringify(ctx) : "{}";
}

/** Parse "CALLER: hi" / "USER: hello" lines from a textarea. */
export function parseTranscript(text: string): TranscriptLine[] {
  return text
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const m = s.match(/^(caller|user|unknown speaker|unknown|them|me|scammer|mom|dad)\s*[:\-]\s*(.*)$/i);
      if (!m) return { speaker: "unknown" as const, text: s };
      const who = m[1].toLowerCase();
      const speaker = who.startsWith("unknown") ? "unknown" : who === "user" || who === "me" || who === "mom" || who === "dad" ? "user" : "caller";
      return { speaker, text: m[2] } as TranscriptLine;
    });
}

export function linesToText(lines: TranscriptLine[]) {
  return lines.map(formatLine).join("\n");
}
