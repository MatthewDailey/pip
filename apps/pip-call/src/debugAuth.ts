// The /debug routes show full call transcripts, so they need a secret:
//   header x-pip-debug-token = TWILIO_AUTH_TOKEN (the robocall/appcall scripts) or DEBUG_TOKEN, or
//   ?token=<DEBUG_TOKEN> for opening them in a browser (never the Twilio token in a URL).
// Their output also hides the Vercel protection-bypass secret that webhook URLs carry.
import { timingSafeEqual } from "node:crypto";
import { config } from "./config";

const same = (a: string, b: string | undefined) => {
  if (!b) return false;
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};

export function checkDebugToken(req: Request): Response | undefined {
  const cfg = config();
  const header = req.headers.get("x-pip-debug-token") ?? "";
  const query = new URL(req.url).searchParams.get("token") ?? "";
  const ok =
    (header && (same(header, cfg.twilioAuthToken) || same(header, cfg.debugToken))) || (query && same(query, cfg.debugToken));
  return ok ? undefined : Response.json({ error: "debug routes need x-pip-debug-token or ?token=" }, { status: 401 });
}

/** Pure: replace every x-vercel-protection-bypass value in a JSON-able value. */
export function redactSecrets<T>(value: T): T {
  return JSON.parse(JSON.stringify(value).replace(/(x-vercel-protection-bypass(?:=|%3D|\\":\\"|":"))[^&"\\\s]+/gi, "$1[redacted]"));
}
