// POST /app/calls/:callSid/context: the app adds what it knows about the other party after answering
// (JSON or form: in_contacts, contact_name, local_time, user_profile). Merged into the context Pip4
// receives with the call's next scored line. Header x-pip-app-secret must equal PIP_APP_SECRET.
import { checkAppSecret } from "@/src/appAuth";
import { mergeContext, type CallContext } from "@/src/callState";
import { logEvent } from "@/src/timeline";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

async function body(req: Request): Promise<Record<string, unknown>> {
  if ((req.headers.get("content-type") ?? "").includes("json")) return ((await req.json().catch(() => ({}))) ?? {}) as Record<string, unknown>;
  const f = await req.formData().catch(() => undefined);
  return f ? Object.fromEntries([...f.entries()].map(([k, v]) => [k, String(v)])) : {};
}

const bool = (v: unknown) => (typeof v === "boolean" ? v : typeof v === "string" ? ["true", "1", "yes"].includes(v.toLowerCase()) : undefined);
const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim().slice(0, 200) : undefined);

export async function POST(req: Request, { params }: { params: Promise<{ callSid: string }> }) {
  const denied = checkAppSecret(req);
  if (denied) return denied;
  const { callSid } = await params;
  if (!/^CA[0-9a-f]{32}$/i.test(callSid)) return Response.json({ error: "callSid must be a Twilio CallSid" }, { status: 400 });
  const b = await body(req);
  const contactName = str(b.contact_name);
  const patch: CallContext = {
    caller_in_contacts: bool(b.in_contacts),
    contact_name: contactName,
    caller_id: contactName,
    local_time: str(b.local_time),
    user_profile: str(b.user_profile),
  };
  const context = await mergeContext(callSid, patch);
  await logEvent(callSid, "note", { appContext: patch });
  return Response.json({ callSid, context });
}
