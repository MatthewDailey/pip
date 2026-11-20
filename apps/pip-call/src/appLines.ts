// On-device transcription path: the Pip app transcribes the call on the phone and posts each line
// here (finished or a throttled partial); we score it with Pip4 and answer with the verdict in the
// same response, so the app can mute Mom and show its warning without a WebSocket. No Twilio involved:
// the callId is the app's own id.
import { mergeContext, type CallContext } from "./callState";
import type { Line, Speaker } from "./lines";
import type { Pip4Client, Pip4Level } from "./pip4";
import { logEvent } from "./timeline";

export interface AppLinesBody {
  lines?: { speaker?: string; text?: string; t?: number; final?: boolean }[];
  context?: CallContext;
  listen?: "user" | "both";
}

export interface AppVerdict {
  callId: string;
  level: Pip4Level;
  action: string;
  risk?: number;
  scamType?: string;
  reason?: string;
  lineCount: number;
  /** Pip4 round trip and Jev's own scoring time, so the app can show where the time went */
  pip4Ms: number;
  jevMs?: number;
  tripwireRule?: string;
  tripwireFiredNow?: boolean;
}

const str = (v: unknown, max = 500) => (typeof v === "string" && v.trim() ? v.trim().slice(0, max) : undefined);

/** Pure: the lines worth sending (non-empty, capped), with the app's speaker labels mapped to Pip4's. */
export function cleanLines(body: AppLinesBody, now = Date.now(), start = now): Line[] {
  return (body.lines ?? [])
    .slice(0, 20)
    .map((l, i) => ({
      speaker: (l.speaker === "caller" ? "caller" : "user") as Speaker,
      text: str(l.text) ?? "",
      t: typeof l.t === "number" ? l.t : (now - start) / 1000,
      seq: i,
      final: l.final !== false,
    }))
    .filter((l) => l.text);
}

/** Pure: only the context fields the app may set. */
export function cleanContext(c: CallContext | undefined): CallContext {
  if (!c) return {};
  const out: CallContext = {};
  if (c.direction === "inbound" || c.direction === "outbound") out.direction = c.direction;
  if (typeof c.caller_in_contacts === "boolean") out.caller_in_contacts = c.caller_in_contacts;
  if (c.listen === "user" || c.listen === "both") out.listen = c.listen;
  for (const k of ["caller_id", "caller_number", "caller_name", "contact_name", "verification", "local_time", "user_profile"] as const) {
    const v = str(c[k], 200);
    if (v) out[k] = v;
  }
  if (!out.caller_name && (out.caller_id || out.contact_name)) {
    out.caller_name = (out.caller_id ?? out.contact_name) as string;
  }
  return out;
}

export async function handleAppLines(callId: string, body: AppLinesBody, pip4: Pip4Client): Promise<AppVerdict> {
  const lines = cleanLines(body);
  const listen = body.listen === "both" || body.context?.listen === "both" ? "both" : "user";
  const context = await mergeContext(callId, { source: "app_transcript", listen, ...cleanContext(body.context) });
  for (const l of lines) await logEvent(callId, "line", { speaker: l.speaker, text: l.text, final: l.final !== false, source: "app" });
  const t0 = performance.now();
  // Finals are scored immediately (force). Partials are still posted so the tripwire can fire mid-sentence,
  // but they should not force a Jev rescore of already-stored finals.
  const hasFinal = lines.some((l) => l.final !== false);
  const r = await pip4.postLines(callId, lines, context, { force: hasFinal });
  const pip4Ms = Math.round(performance.now() - t0);
  if (r.scored || r.tripwireFiredNow) {
    await logEvent(callId, "verdict", {
      level: r.level,
      action: r.action,
      reason: r.reason,
      lineCount: r.lineCount,
      tripwire: r.tripwireFiredNow ?? r.tripwire,
      source: "app",
    });
  }
  return {
    callId,
    level: r.level,
    action: r.action,
    risk: r.latest?.risk,
    scamType: r.latest?.scamType,
    reason: r.reason,
    lineCount: r.lineCount,
    pip4Ms,
    jevMs: r.latest?.latencyMs === undefined ? undefined : Math.round(r.latest.latencyMs),
    tripwireRule: r.tripwireFiredNow?.rule ?? r.tripwire?.rule,
    tripwireFiredNow: Boolean(r.tripwireFiredNow),
  };
}
