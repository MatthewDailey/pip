import { NextResponse } from "next/server";
import { setContext, type CallContext } from "@/src/callState";
import { config } from "@/src/config";
import { checkDebugToken, redactSecrets } from "@/src/debugAuth";
import { getTimeline } from "@/src/timeline";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ callSid: string }> };

/** GET /debug/calls/:callSid → the call's timeline */
export async function GET(req: Request, { params }: Ctx) {
  const denied = checkDebugToken(req);
  if (denied) return denied;
  const { callSid } = await params;
  const events = await getTimeline(callSid);
  if (!events.length) return NextResponse.json({ error: "unknown callSid", callSid }, { status: 404 });
  return NextResponse.json({ callSid, events: redactSecrets(events) });
}

/** POST /debug/calls/:callSid { context } → seed the call context (simulator only, fake Twilio mode) */
export async function POST(req: Request, { params }: Ctx) {
  if (!config().twilioFake) return NextResponse.json({ error: "only in PIP_TWILIO=fake mode" }, { status: 403 });
  const { callSid } = await params;
  const body = (await req.json().catch(() => null)) as { context?: CallContext } | null;
  if (body?.context) await setContext(callSid, body.context);
  return NextResponse.json({ ok: true });
}
