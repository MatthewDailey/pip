// Which side(s) of a call Pip hears.
//  "user": only the protected user's own voice (on-device speech-to-text on her phone). Caller chunks are dropped.
//  "both": both legs (e.g. pip-call's Twilio conference transcribing both sides).
// Default comes from PIP4_LISTEN, else "both" (so pip-call, which transcribes both legs and sends no `listen`, keeps
// working). The on-device app and `pnpm demo:call` send listen:"user" explicitly.
import type { TranscriptLine } from "./types";

export type ListenMode = "user" | "both";

export function defaultListen(): ListenMode {
  return process.env.PIP4_LISTEN === "user" ? "user" : "both";
}

export function parseListen(v: unknown): ListenMode | undefined {
  return v === "user" || v === "both" ? v : undefined;
}

/** In user-only mode, lines without a speaker label (or "unknown") are treated as the user: only her mic is transcribed. */
export function isUserLine(l: Pick<TranscriptLine, "speaker">) {
  return l.speaker !== "caller";
}

export function linesFor(lines: TranscriptLine[], listen: ListenMode): TranscriptLine[] {
  return listen === "user" ? lines.filter(isUserLine).map((l) => ({ ...l, speaker: "user" as const })) : lines;
}
