// POST /twilio/conference: conference status callback (start, end, join, leave, hold, mute). Logged to
// the timeline of the call that named the conference, and the conference SID is cached for takeover.
import { DEFAULT_TTL_MS, getStore } from "@/src/store";
import { logEvent } from "@/src/timeline";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "conference");
  if (!w.ok) return w.response;
  const p = w.params;
  const name = p.FriendlyName ?? "";
  const callSid = name.startsWith("pip-") ? name.slice(4) : p.CallSid || "unknown";
  if (p.ConferenceSid && name && p.StatusCallbackEvent !== "conference-end") {
    await getStore().set(`pip:conf:${name}`, p.ConferenceSid, DEFAULT_TTL_MS);
  }
  await logEvent(callSid, "conference", {
    event: p.StatusCallbackEvent,
    label: p.ParticipantLabel || undefined,
    legSid: p.CallSid || undefined,
    hold: p.Hold || undefined,
    muted: p.Muted || undefined,
    reason: p.ReasonConferenceEnded || undefined,
  });
  return new Response(null, { status: 204 });
}
