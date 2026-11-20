// The Elder Guard app's routes (/token, /app/calls/:callSid/context) are guarded by a shared secret
// in the x-pip-app-secret header (hackathon auth). Without PIP_APP_SECRET configured they refuse.
import { timingSafeEqual } from "node:crypto";
import { config } from "./config";

export function checkAppSecret(req: Request): Response | undefined {
  const want = config().appSecret;
  if (!want) return Response.json({ error: "PIP_APP_SECRET is not configured on pip-call" }, { status: 503 });
  const got = req.headers.get("x-pip-app-secret") ?? "";
  const a = Buffer.from(got);
  const b = Buffer.from(want);
  if (a.length !== b.length || !timingSafeEqual(a, b)) return Response.json({ error: "bad x-pip-app-secret" }, { status: 401 });
  return undefined;
}
