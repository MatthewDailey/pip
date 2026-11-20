// POST /debug/robocall { lines: string[] } → { run }: registers a robo-caller script for one test call.
// Header x-pip-debug-token must equal TWILIO_AUTH_TOKEN (only the robocall script has it).
import { timingSafeEqual } from "node:crypto";
import { config } from "@/src/config";
import { saveRun } from "@/src/robo";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const got = Buffer.from(req.headers.get("x-pip-debug-token") ?? "");
  const want = Buffer.from(config().twilioAuthToken);
  if (got.length !== want.length || !timingSafeEqual(got, want)) return Response.json({ error: "bad x-pip-debug-token" }, { status: 401 });
  const body = (await req.json().catch(() => null)) as { lines?: unknown } | null;
  const lines = Array.isArray(body?.lines) ? body.lines.filter((l): l is string => typeof l === "string" && !!l.trim()).slice(0, 40) : [];
  if (!lines.length) return Response.json({ error: "lines must be a non-empty string array" }, { status: 400 });
  const run = crypto.randomUUID().slice(0, 8);
  await saveRun(run, lines);
  return Response.json({ run, lines: lines.length });
}
