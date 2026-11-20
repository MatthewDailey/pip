// Per-call event log: every webhook, transcript line, Pip4 verdict and Twilio command, with
// milliseconds since the call started. Stored as a Redis list per call (24 h expiry) and logged as
// JSON lines. Read it at GET /debug/calls/:callSid; GET /debug/calls lists recent calls.
import { DEFAULT_TTL_MS, getStore } from "./store";

export type EventKind =
  | "webhook"
  | "partial"
  | "line"
  | "pip4_request"
  | "pip4_response"
  | "pip4_error"
  | "verdict"
  | "takeover"
  | "twilio_cmd"
  | "twilio_error"
  | "conference"
  | "participant"
  | "robo"
  | "pip_voice"
  | "note";

export interface TimelineEvent {
  /** ms since the first event of this call */
  t: number;
  /** wall-clock epoch ms */
  at: number;
  kind: EventKind;
  data?: unknown;
}

const k = {
  events: (sid: string) => `pip:tl:${sid}`,
  start: (sid: string) => `pip:tl:${sid}:start`,
  calls: "pip:calls",
};

const quiet = () => process.env.PIP_QUIET === "1" || process.env.VITEST === "true";

export async function logEvent(callSid: string, kind: EventKind, data?: unknown): Promise<TimelineEvent> {
  const store = getStore();
  const at = Date.now();
  const isNew = await store.setNX(k.start(callSid), String(at), DEFAULT_TTL_MS);
  if (isNew) await store.lpushCapped(k.calls, JSON.stringify({ callSid, startedAt: at }), 200);
  const start = isNew ? at : Number((await store.get(k.start(callSid))) ?? at);
  const ev: TimelineEvent = { t: at - start, at, kind, data };
  await store.rpush(k.events(callSid), JSON.stringify(ev), DEFAULT_TTL_MS);
  if (!quiet()) console.log(JSON.stringify({ callSid, ...ev }));
  return ev;
}

export async function getTimeline(callSid: string): Promise<TimelineEvent[]> {
  const raw = await getStore().lrange(k.events(callSid), 0, -1);
  return raw.map((r) => JSON.parse(r) as TimelineEvent);
}

export async function listCalls(limit = 50): Promise<{ callSid: string; startedAt: number }[]> {
  const raw = await getStore().lrange(k.calls, 0, limit - 1);
  return raw.map((r) => JSON.parse(r));
}
