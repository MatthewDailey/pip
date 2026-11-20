// GET /token?user=<identity>: a 1-hour Twilio Voice access token for the Elder Guard app.
// Header x-pip-app-secret must equal PIP_APP_SECRET.
import { checkAppSecret } from "@/src/appAuth";
import { config } from "@/src/config";
import { mintVoiceToken, TOKEN_TTL_S, TokenConfigError, validIdentity } from "@/src/token";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const denied = checkAppSecret(req);
  if (denied) return denied;
  const identity = new URL(req.url).searchParams.get("user") ?? "";
  if (!validIdentity(identity)) return Response.json({ error: "user must be 1-121 chars of A-Z a-z 0-9 _ . @ + -" }, { status: 400 });
  try {
    const token = mintVoiceToken(config(), identity);
    return Response.json({ identity, token, ttl: TOKEN_TTL_S }, { headers: { "cache-control": "no-store" } });
  } catch (e) {
    if (e instanceof TokenConfigError) return Response.json({ error: e.message }, { status: 503 });
    throw e;
  }
}
