// POST /twiml/robo-caller?run=<id>&i=<n>: the robo-caller's TwiML, one script line per request (src/robo.ts).
import { webhookUrl } from "@/src/config";
import { loadRun, roboCallerTwiml } from "@/src/robo";
import { logEvent } from "@/src/timeline";
import { sayAndHangUpTwiml, twimlResponse } from "@/src/twiml";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "robo-caller");
  if (!w.ok) return w.response;
  const q = new URL(req.url).searchParams;
  const run = q.get("run") ?? "";
  const i = Number(q.get("i") ?? 0);
  const lines = await loadRun(run);
  if (!lines) return twimlResponse(sayAndHangUpTwiml("Unknown robo-call run."));
  // request i arrives when line i-1 has finished playing
  await logEvent(w.params.CallSid || "unknown", "robo", { run, step: i, saidLine: i > 0 ? i - 1 : undefined, text: i > 0 ? lines[i - 1] : undefined });
  const next = (n: number) => webhookUrl(`/twiml/robo-caller?run=${encodeURIComponent(run)}&i=${n}`);
  return twimlResponse(roboCallerTwiml(lines, i, next));
}
