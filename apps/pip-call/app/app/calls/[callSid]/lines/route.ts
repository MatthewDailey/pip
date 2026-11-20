// POST /app/calls/:callId/lines: the Pip app's on-device transcript, finished lines and throttled partials
//   { lines: [{ speaker: "user"|"caller", text, t?, final? }], listen?: "user", context?: { direction, caller_in_contacts, caller_number, caller_name, verification, ... } }
// → { level: calm|watch|alert|takeover, action, risk, scamType, reason, lineCount, pip4Ms, jevMs }
// The verdict comes back in the response (no WebSocket): the app mutes Mom and shows its warning from it.
// Header x-pip-app-secret must equal PIP_APP_SECRET. callId is the app's own id for the call.
import { checkAppSecret } from "@/src/appAuth";
import { handleAppLines, type AppLinesBody } from "@/src/appLines";
import { getDeps } from "@/src/scorer";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request, { params }: { params: Promise<{ callSid: string }> }) {
  const t0 = performance.now();
  const denied = checkAppSecret(req);
  if (denied) return denied;
  const { callSid: callId } = await params;
  if (!/^[A-Za-z0-9_-]{6,64}$/.test(callId)) return Response.json({ error: "callId must be 6-64 chars of A-Z a-z 0-9 _ -" }, { status: 400 });
  const body = ((await req.json().catch(() => null)) ?? {}) as AppLinesBody;
  if (!body.lines?.some((l) => typeof l?.text === "string" && l.text.trim())) return Response.json({ error: "lines must hold at least one { text }" }, { status: 400 });
  try {
    const verdict = await handleAppLines(callId, body, getDeps().pip4);
    return Response.json({ ...verdict, serverMs: Math.round(performance.now() - t0) }, { headers: { "cache-control": "no-store" } });
  } catch (e) {
    return Response.json({ error: `Pip4 unavailable: ${String(e).slice(0, 200)}` }, { status: 502 });
  }
}
