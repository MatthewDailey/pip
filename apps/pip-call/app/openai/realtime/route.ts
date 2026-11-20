// POST /openai/realtime: OpenAI's webhook for Pip's live voice (set it in the OpenAI project's webhook
// settings, event realtime.call.incoming). When Twilio dials Pip in on takeover, OpenAI asks here
// whether to take the call: we accept it with Pip's instructions for this call (src/livePip.ts), then
// keep a control socket open after responding so Pip speaks first and his tool works.
import { after } from "next/server";
import { config } from "@/src/config";
import { acceptPip, callSidFromSipHeaders, rejectPip, runControlSocket, verifyOpenAIWebhook } from "@/src/livePip";
import { logEvent } from "@/src/timeline";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
/** the control socket lives as long as this function (Vercel Pro allows up to 800 s) */
export const maxDuration = 300;

interface IncomingCall {
  type: string;
  data?: { call_id?: string; sip_headers?: { name: string; value: string }[] };
}

export async function POST(req: Request) {
  const cfg = config();
  const body = await req.text();
  if (!cfg.openaiWebhookSecret || !verifyOpenAIWebhook(cfg.openaiWebhookSecret, req.headers, body)) {
    return new Response("bad signature", { status: 401 });
  }
  const ev = JSON.parse(body) as IncomingCall;
  if (ev.type !== "realtime.call.incoming" || !ev.data?.call_id) return new Response(null, { status: 204 });
  const callId = ev.data.call_id;
  const callSid = callSidFromSipHeaders(ev.data.sip_headers);
  if (!callSid) {
    // no CallSid header: not a call we placed; keep the headers for debugging
    await logEvent("openai-unknown", "note", { rejected: callId, sipHeaders: ev.data.sip_headers });
    await rejectPip(callId, cfg);
    return new Response(null, { status: 204 });
  }
  try {
    await acceptPip(callId, callSid, cfg);
  } catch (e) {
    // rejecting makes Twilio report the pip participant as failed, which plays Mom the fixed warning
    await logEvent(callSid, "pip_voice", { acceptFailed: String(e) });
    await rejectPip(callId, cfg);
    return new Response(null, { status: 204 });
  }
  after(() => runControlSocket(callId, callSid, cfg, (maxDuration - 20) * 1000));
  return new Response(null, { status: 204 });
}
