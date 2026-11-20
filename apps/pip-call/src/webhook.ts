// Shared handling for every Twilio webhook: parse the form body, check X-Twilio-Signature,
// record the raw payload (PIP_RECORD=1) and log it to the call's timeline.
import twilio from "twilio";
import { config } from "./config";
import { recordWebhook } from "./record";
import { logEvent } from "./timeline";

export type TwilioParams = Record<string, string>;

/** The URL Twilio signed: our public base URL + the path and query it called. */
export function signedUrl(req: Request) {
  const u = new URL(req.url);
  return config().publicBaseUrl + u.pathname + u.search;
}

export async function readTwilioWebhook(
  req: Request,
  route: string,
): Promise<{ ok: true; params: TwilioParams } | { ok: false; response: Response }> {
  const form = await req.formData();
  const params: TwilioParams = {};
  for (const [k, v] of form.entries()) params[k] = String(v);
  const cfg = config();
  const url = signedUrl(req);
  const sig = req.headers.get("x-twilio-signature") ?? "";
  if (!cfg.skipSignature && !twilio.validateRequest(cfg.twilioAuthToken, sig, url, params)) {
    await logEvent(params.CallSid || "unknown", "note", { route, error: "bad X-Twilio-Signature", url });
    return { ok: false, response: new Response("bad signature", { status: 403 }) };
  }
  const callSid = params.CallSid || "unknown";
  await recordWebhook(callSid, {
    route,
    url,
    at: Date.now(),
    headers: { "x-twilio-signature": sig, "i-twilio-idempotency-token": req.headers.get("i-twilio-idempotency-token") ?? "" },
    params,
  });
  return { ok: true, params };
}

/** Used by the simulator and tests to sign fake webhooks exactly as Twilio would. */
export function signParams(authToken: string, url: string, params: TwilioParams) {
  return twilio.getExpectedTwilioSignature(authToken, url, params);
}
