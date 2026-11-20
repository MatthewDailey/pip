// POST /twilio/participant?call=<CallSid>[&role=pip]: call status of a party we dialled into the conference.
// If the other party never picks up (busy, no answer, failed), hang up the leg that is waiting in the
// conference so Mom isn't left listening to ringback forever. If Pip's live voice (role=pip) never
// picks up, Mom gets the fixed warning instead.
import { twilioOps } from "@/src/twilioOps";
import { logEvent } from "@/src/timeline";
import { warnMom } from "@/src/takeover";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const NEVER_ANSWERED = new Set(["busy", "no-answer", "failed", "canceled"]);

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "participant");
  if (!w.ok) return w.response;
  const p = w.params;
  const q = new URL(req.url).searchParams;
  const callSid = q.get("call") || p.ParentCallSid || p.CallSid || "unknown";
  const role = q.get("role") || undefined;
  await logEvent(callSid, "participant", { role, status: p.CallStatus, legSid: p.CallSid, to: p.To, duration: p.CallDuration || undefined });
  if (NEVER_ANSWERED.has(p.CallStatus) && callSid !== "unknown") {
    if (role === "pip") await warnMom(callSid);
    else await twilioOps().endCall(callSid, callSid).catch(() => {});
  }
  return new Response(null, { status: 204 });
}
