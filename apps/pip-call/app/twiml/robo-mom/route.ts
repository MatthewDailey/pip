// POST /twiml/robo-mom: voice webhook of the robo-mom number during robo-calls (src/robo.ts).
import { roboMomTwiml } from "@/src/robo";
import { twimlResponse } from "@/src/twiml";
import { readTwilioWebhook } from "@/src/webhook";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const w = await readTwilioWebhook(req, "robo-mom");
  if (!w.ok) return w.response;
  return twimlResponse(roboMomTwiml());
}
