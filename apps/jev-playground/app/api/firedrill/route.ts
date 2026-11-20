// Optional Firedrill / smoke harness for the call detector.
//
// POST /api/firedrill
//   Firedrill http.v1 (request_template: message-text) sends { message, session }.
//   We treat `message` as the latest caller line, score it through the same
//   session + detector + policy exports as /api/calls/:callId, then reply as a
//   scripted victim (or as Pip, on takeover).
//
// Isolated on purpose: this folder is the only app code that knows about
// Firedrill. It reuses getSession / recordResult / getDetectors, and does not
// change those modules or the /api/calls route. Delete this folder to remove it.
//
// PIP_OFF=1 or ?pip=off disables Pip (the "bad timeline"): no scoring, and after
// a few pressured turns the victim reads out an obviously fake card number.
import { NextResponse } from "next/server";
import fs from "node:fs";
import path from "node:path";
import { getDetectors } from "@/lib/detectors";
import { parseListen } from "@/lib/listen";
import { getSession, recordResult, saveSession, type CallSession } from "@/lib/sessions";
import type { Level } from "@/lib/policy";
import {
  chooseVictimReply,
  isPressure,
  PIP_TAKEOVER_REPLY,
  type VictimState,
} from "./victim";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const ACTION = { calm: "none", watch: "none", alert: "alert_trusted_contact", takeover: "takeover" } as const;

/** Persist the session (Redis when KV_REST_API_* is set, otherwise in-memory), same as /api/calls/:callId. */
async function persist(s: CallSession) {
  await saveSession(s);
}

const DEFAULT_CONTEXT = {
  direction: "inbound" as const,
  caller_in_contacts: false,
  user_profile:
    "An older woman at home, often called Grandma. She is slightly hard of hearing and may ask people to repeat themselves.",
};

function pipDisabled(req: Request): boolean {
  const q = new URL(req.url).searchParams.get("pip")?.trim().toLowerCase();
  if (q === "off" || q === "0" || q === "false") return true;
  const env = process.env.PIP_OFF?.trim().toLowerCase();
  return env === "1" || env === "true" || env === "yes" || env === "off";
}

function appendLine(s: CallSession, speaker: "caller" | "user", text: string) {
  const started = s.lines.length ? (s.lines[0]!.t ?? 0) : 0;
  s.lines.push({
    speaker,
    text,
    t: typeof started === "number" ? (Date.now() - s.createdAt) / 1000 + started : (Date.now() - s.createdAt) / 1000,
  });
  s.linesSinceScore++;
}

function logTurn(entry: Record<string, unknown>) {
  const dir = path.join(process.cwd(), "eval-results", "firedrill");
  fs.mkdirSync(dir, { recursive: true });
  const safe = String(entry.session ?? "unknown").replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "session";
  fs.appendFileSync(path.join(dir, `${safe}.jsonl`), `${JSON.stringify(entry)}\n`, "utf8");
}

function victimState(s: CallSession, pipOff: boolean, lastCaller: string, takeover: boolean): VictimState {
  const callerLines = s.lines.filter((l) => l.speaker === "caller");
  return {
    pipOff,
    takeover,
    callerTurns: callerLines.length,
    pressureTurns: callerLines.filter((l) => isPressure(l.text)).length,
    lastCaller,
    allCallerText: callerLines.map((l) => l.text).join("\n"),
  };
}

export async function POST(req: Request) {
  const t0 = Date.now();
  const body = (await req.json().catch(() => null)) as { message?: unknown; session?: unknown } | null;
  const message = typeof body?.message === "string" ? body.message.trim() : "";
  const sessionKey = body?.session == null ? "" : String(body.session).trim();
  if (!body || !message || !sessionKey) {
    return NextResponse.json({ error: "JSON body with message and session required" }, { status: 400 });
  }

  const pipOff = pipDisabled(req);
  const callId = `fd-${sessionKey}`;
  const s = (await getSession(callId))!;
  if (s.lines.length === 0 && !s.context.direction) s.context = { ...DEFAULT_CONTEXT, ...s.context };

  appendLine(s, "caller", message);

  // Already-sticky takeover: keep repeating Pip's line even if the caller continues.
  if (!pipOff && s.policy.level === "takeover") {
    const action = ACTION.takeover;
    const latency = Date.now() - t0;
    const turn = s.lines.filter((l) => l.speaker === "caller").length;
    await persist(s);
    logTurn({ session: sessionKey, turn, level: "takeover" satisfies Level, action, latency, pipOff });
    return NextResponse.json({ reply: PIP_TAKEOVER_REPLY, level: "takeover", action });
  }

  if (!pipOff) {
    // Firedrill sends the simulated caller's text, so it scores both sides by default. FIREDRILL_LISTEN=user scores
    // only the scripted victim's replies (limitation: victim.ts replies are generic and rarely echo what the caller
    // asks, so user-only drills under-detect).
    const listen = parseListen(process.env.FIREDRILL_LISTEN) ?? "both";
    const result = await getDetectors().jev.analyze({ lines: s.lines, context: s.context, listen });
    recordResult(s, result);
  }

  const level = pipOff ? ("calm" as Level) : s.policy.level;
  const action = pipOff ? ACTION.calm : ACTION[level];
  const takeover = !pipOff && level === "takeover";
  const reply = chooseVictimReply(victimState(s, pipOff, message, takeover));

  // Record the elderly victim's spoken line only. Pip's takeover line is not the user talking.
  if (!takeover) appendLine(s, "user", reply);

  const latency = Date.now() - t0;
  const turn = s.lines.filter((l) => l.speaker === "caller").length;
  await persist(s);
  logTurn({
    session: sessionKey,
    turn,
    level,
    action,
    latency,
    pipOff,
    reason: pipOff ? "pip_off" : s.policy.reason,
  });

  return NextResponse.json({ reply, level, action });
}

