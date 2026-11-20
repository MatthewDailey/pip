// POST /twiml/voice: the voice webhook of the Pip line and of the TwiML App the Elder Guard app calls
// through. Routing lives in src/voice.ts.
import { handleVoice } from "@/src/voice";
import { twimlResponse } from "@/src/twiml";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "voice");
  if (!w.ok) return w.response;
  return twimlResponse(await handleVoice(w.params));
}
