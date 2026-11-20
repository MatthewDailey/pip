// Twilio Real-Time Transcription webhooks → transcript lines.
//
// Twilio posts one webhook per event to statusCallbackUrl: TranscriptionEvent is
// transcription-started | transcription-content | transcription-stopped | transcription-error.
// Content events carry Track (inbound_track | outbound_track), Final ("true"/"false"),
// TranscriptionData (JSON string: { transcript, confidence }), SequenceId and Timestamp.
// Transcription runs on the leg whose TwiML started it. On the caller's leg (Pip line calls) the
// inbound track is the caller and the outbound track is what the caller hears: Mom ("user" in
// Pip4's vocabulary). On Mom's leg (calls from the app) it's the other way round.
//
// Nothing is kept in memory: the latest partial per speaker and the call's first timestamp live
// in the store. Each final line goes to onLine (the scorer).
import { callKey, getTranscribedLeg, type Leg } from "./callState";
import { DEFAULT_TTL_MS, getStore } from "./store";
import { logEvent } from "./timeline";

export type Speaker = "caller" | "user";

export interface Line {
  speaker: Speaker;
  text: string;
  /** seconds since the first transcript event of the call */
  t: number;
  /** Twilio SequenceId, used to keep lines in order */
  seq: number;
  /** false for in-progress speech; omitted/true is a finished line. Pip4 tripwire-checks both. */
  final?: boolean;
}

export interface TranscriptionEvent {
  event: string;
  callSid: string;
  track?: string;
  speaker?: Speaker;
  final: boolean;
  text: string;
  confidence?: number;
  seq: number;
  timestampMs?: number;
}

export type OnLine = (callSid: string, line: Line) => Promise<void>;
export type OnStopped = (callSid: string, seq: number) => Promise<void>;

/** The swap point if Twilio transcription is too slow: a Media Streams + Deepgram relay produces the same lines. */
export interface TranscriptSource {
  readonly name: string;
}
export const twilioRealtimeTranscription: TranscriptSource = { name: "twilio-realtime-transcription" };

/** inbound = the transcribed leg's own voice, outbound = what that leg hears */
export function trackToSpeaker(track?: string, leg: Leg = "caller"): Speaker | undefined {
  if (!track) return undefined;
  const own: Speaker = leg === "mom" ? "user" : "caller";
  const other: Speaker = leg === "mom" ? "caller" : "user";
  if (track.startsWith("inbound")) return own;
  if (track.startsWith("outbound")) return other;
  return undefined;
}

export function parseTranscriptionWebhook(p: Record<string, string>, leg: Leg = "caller"): TranscriptionEvent {
  let text = "";
  let confidence: number | undefined;
  if (p.TranscriptionData) {
    try {
      const d = JSON.parse(p.TranscriptionData) as { transcript?: string; confidence?: number };
      text = (d.transcript ?? "").trim();
      confidence = typeof d.confidence === "number" ? d.confidence : undefined;
    } catch {
      text = p.TranscriptionData.trim();
    }
  }
  const ts = p.Timestamp ? Date.parse(p.Timestamp) : NaN;
  return {
    event: p.TranscriptionEvent ?? "",
    callSid: p.CallSid ?? "",
    track: p.Track,
    speaker: trackToSpeaker(p.Track, leg),
    final: String(p.Final).toLowerCase() === "true",
    text,
    confidence,
    seq: Number(p.SequenceId ?? 0),
    timestampMs: Number.isFinite(ts) ? ts : undefined,
  };
}

/** Handle one transcription webhook; returns the final line it produced, if any. */
export async function handleTranscription(
  params: Record<string, string>,
  onLine: OnLine,
  onStopped?: OnStopped,
): Promise<Line | undefined> {
  const ev = parseTranscriptionWebhook(params, params.CallSid ? await getTranscribedLeg(params.CallSid) : "caller");
  const sid = ev.callSid;
  if (ev.event !== "transcription-content") {
    await logEvent(sid, "webhook", { route: "transcription", event: ev.event, error: params.TranscriptionError });
    if (ev.event === "transcription-stopped" && onStopped) await onStopped(sid, ev.seq);
    return;
  }
  if (!ev.speaker || !ev.text) return;
  const store = getStore();
  const now = ev.timestampMs ?? Date.now();
  await store.setNX(callKey(sid, "t0"), String(now), DEFAULT_TTL_MS);
  const t0 = Number((await store.get(callKey(sid, "t0"))) ?? now);
  const t = Math.max(0, (now - t0) / 1000);
  if (!ev.final) {
    await store.set(callKey(sid, `partial:${ev.speaker}`), JSON.stringify({ text: ev.text, seq: ev.seq, t }), DEFAULT_TTL_MS);
    await logEvent(sid, "partial", { speaker: ev.speaker, text: ev.text, seq: ev.seq });
    return;
  }
  await store.del(callKey(sid, `partial:${ev.speaker}`));
  const line: Line = { speaker: ev.speaker, text: ev.text, t: Math.round(t * 10) / 10, seq: ev.seq };
  await logEvent(sid, "line", { ...line, confidence: ev.confidence });
  await onLine(sid, line);
  return line;
}
