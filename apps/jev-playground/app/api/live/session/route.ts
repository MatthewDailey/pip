// Talk to Pip: starts a GPT-Live voice session for the app's "Talk to Pip" screen.
//
// POST /api/live/session  { sdp: <WebRTC offer>, context?: string, opening?: string, callId?: string }
//   → 201 { session: { id }, transport: { type: "webrtc", sdp: <answer> } }
//
// GPT-Live has no client token: the device sends its SDP offer here, we post it to OpenAI with the API key and
// Pip's instructions, and hand back the SDP answer. The device then talks to OpenAI directly over WebRTC, with
// its data channel labelled "oai-events". The app's TalkToPipRequest fills the optional fields: `context` is what
// Pip should know (e.g. what just happened on the phone), `opening` is what he should open with, and `callId` adds
// that call's transcript and verdict from /api/calls. buildInstructions puts them after Pip's own instructions.
// https://developers.openai.com/api/docs/guides/voice-webrtc?api=live
import { NextResponse } from "next/server";
import { getSession, type CallSession } from "@/lib/sessions";

export const dynamic = "force-dynamic";

const MODEL = process.env.OPENAI_LIVE_MODEL || "gpt-live-1.1";
const VOICE = process.env.PIP_LIVE_VOICE || "ballad";

const PIP_INSTRUCTIONS = `You are Pip, a small, friendly cyber-defence helper who lives on an older person's phone.
Speak warmly, simply and briefly, a sentence or two at a time, like a kind grandchild who knows about scams.
Never ask for passwords, codes, card numbers or money. If someone may be being scammed, say so plainly,
explain why in one sentence, and suggest one safe next step: hang up, call the real number from the card or
statement, or ask a trusted family member. Start by saying hi and asking how you can help.`;

function callContext(s: CallSession): string {
  const lines = s.lines.slice(-30).map((l) => `${l.speaker === "user" ? "Them" : "Caller"}: ${l.text}`);
  return [
    "",
    `A phone call just happened. Pip's scam check ended at level "${s.policy.maxLevel}" (${s.policy.reason}).`,
    ...callerLines(s),
    "Its transcript, most recent lines last:",
    ...lines,
    "Open by gently explaining what you noticed on that call and what to do now.",
  ].join("\n");
}

/** Who was on the other end, from the app's call metadata (Pip4 merges it into the session's context). */
function callerLines(session: CallSession): string[] {
  // /api/calls stores these on the session too (its own LiveSession type); only what we read is typed here.
  const s = session as CallSession & { tripwire?: { reason?: string } | null; listen?: string };
  const c = s.context as Record<string, unknown>;
  const who = [c.caller_name && c.caller_name !== c.caller_number ? c.caller_name : undefined, c.caller_number].filter(Boolean).join(", ");
  const out: string[] = [];
  if (who) out.push(`The ${c.direction === "outbound" ? "number she called" : "caller"}: ${who}${c.caller_in_contacts === false ? " (not in her contacts)" : ""}.`);
  if (s.tripwire?.reason) out.push(`What gave it away: ${s.tripwire.reason}.`);
  if (s.listen === "user") out.push("Only her side of the call was transcribed.");
  return out;
}

const MAX_TEXT = 4000;
const field = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim().slice(0, MAX_TEXT) : undefined);

/** Pip's instructions, then what the app told us about this conversation. */
function buildInstructions(o: { context?: string; opening?: string; call?: CallSession }): string {
  const parts = [PIP_INSTRUCTIONS];
  if (o.call) parts.push(callContext(o.call));
  if (o.context) parts.push(`\nWhat the Pip app knows about why they're talking to you now:\n${o.context}`);
  if (o.opening) parts.push(`\nHow to open this conversation: ${o.opening}`);
  return parts.join("\n");
}

export async function POST(req: Request) {
  const key = process.env.OPENAI_API_KEY;
  if (!key) return NextResponse.json({ error: "OPENAI_API_KEY is not set on the server" }, { status: 503 });

  let body: { sdp?: unknown; callId?: unknown; context?: unknown; opening?: unknown };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Expected JSON { sdp, context?, opening?, callId? }" }, { status: 400 });
  }
  if (typeof body.sdp !== "string" || !body.sdp.trim()) {
    return NextResponse.json({ error: "A WebRTC SDP offer is required in `sdp`" }, { status: 400 });
  }

  const call = typeof body.callId === "string" && body.callId ? await getSession(body.callId, false) : undefined;
  const instructions = buildInstructions({
    context: field(body.context),
    opening: field(body.opening),
    call: call?.lines.length ? call : undefined,
  });

  const upstream = await fetch("https://api.openai.com/v1/live/sessions", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      session: { model: MODEL, instructions, audio: { output: { voice: VOICE } } },
      transport: { type: "webrtc", sdp: body.sdp },
    }),
    signal: AbortSignal.timeout(15_000),
  }).catch((e: unknown) => e as Error);

  if (upstream instanceof Error) {
    return NextResponse.json({ error: `OpenAI request failed: ${upstream.message}` }, { status: 502 });
  }
  const text = await upstream.text();
  if (!upstream.ok) {
    console.error(`[live] OpenAI ${upstream.status}: ${text.slice(0, 300)}`);
    return NextResponse.json({ error: `OpenAI ${upstream.status}`, detail: text.slice(0, 300) }, { status: 502 });
  }
  return new NextResponse(text, { status: 201, headers: { "Content-Type": "application/json" } });
}
