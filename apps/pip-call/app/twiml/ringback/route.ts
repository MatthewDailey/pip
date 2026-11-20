// The conference waitUrl: a US ringback tone (public/ringback.wav) for whoever joins first, until the
// other party answers. Twilio fetches it with GET.
import { webhookUrl } from "@/src/config";
import { ringbackTwiml, twimlResponse } from "@/src/twiml";

export const dynamic = "force-dynamic";

const reply = () => twimlResponse(ringbackTwiml(webhookUrl("/ringback.wav")));
export const GET = reply;
export const POST = reply;
