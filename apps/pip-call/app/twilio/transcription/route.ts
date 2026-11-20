// POST /twilio/transcription: Twilio Real-Time Transcription status callback.
import { handleTranscription } from "@/src/lines";
import { flush, onLine } from "@/src/scorer";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "transcription");
  if (!w.ok) return w.response;
  await handleTranscription(w.params, onLine, flush);
  return new Response(null, { status: 204 });
}
