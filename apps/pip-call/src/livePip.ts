// Pip's live voice (PIP_VOICE=live). On takeover, instead of playing Mom a recorded warning, pip-call
// dials OpenAI's Realtime API into the call's conference as a third participant labelled "pip":
//
//   1. takeover.ts holds the caller and adds participant "pip" whose address is OpenAI's SIP endpoint
//      (sip:<project>@sip.api.openai.com), with our CallSid in a custom SIP header.
//   2. OpenAI posts realtime.call.incoming to /openai/realtime. We read the CallSid from that header,
//      load the brief saved at takeover (why Pip stepped in, what was said) and accept the call with
//      Pip's instructions. From here OpenAI handles the audio both ways: no WebSocket audio on Vercel.
//   3. A short-lived control socket (no audio) makes Pip speak first, logs what Pip and Mom say to the
//      timeline, and runs Pip's one tool, hang_up_on_caller.
//
// If dialling Pip fails or OpenAI never answers, /twilio/participant falls back to the fixed warning.
import crypto from "node:crypto";
import { callKey, conferenceName, getContext, type CallContext } from "./callState";
import { config, type Config } from "./config";
import { DEFAULT_TTL_MS, getStore } from "./store";
import { getTimeline, logEvent } from "./timeline";
import { twilioOps } from "./twilioOps";

export const PIP_CALL_HEADER = "X-Pip-Call";
const OPENAI = "https://api.openai.com/v1";

export interface PipBrief {
  reason?: string;
  context: CallContext;
  lines: { speaker: string; text: string }[];
}

/** Pure: the SIP address Twilio dials to bring Pip into the conference. */
export function pipSipUri(projectId: string, callSid: string) {
  return `sip:${projectId}@sip.api.openai.com;transport=tls?${PIP_CALL_HEADER}=${encodeURIComponent(callSid)}`;
}

/** What Pip knows when he picks up: Pip4's reason, the call's context and the last lines of the call. */
export async function saveBrief(callSid: string, reason: unknown) {
  const lines = (await getTimeline(callSid))
    .filter((e) => e.kind === "line")
    .map((e) => e.data as { speaker: string; text: string })
    .slice(-16)
    .map(({ speaker, text }) => ({ speaker, text }));
  const brief: PipBrief = { reason: reason == null ? undefined : String(reason), context: await getContext(callSid), lines };
  await getStore().set(callKey(callSid, "pipbrief"), JSON.stringify(brief), DEFAULT_TTL_MS);
  return brief;
}

export async function loadBrief(callSid: string): Promise<PipBrief> {
  const raw = await getStore().get(callKey(callSid, "pipbrief"));
  return raw ? (JSON.parse(raw) as PipBrief) : { context: {}, lines: [] };
}

/** Pure: Pip's system prompt for this call. */
export function pipInstructions(b: PipBrief): string {
  const caller = b.context.caller_id ? String(b.context.caller_id) : "an unknown caller";
  const transcript = b.lines.length
    ? b.lines.map((l) => `${l.speaker === "user" ? "Mom" : "Caller"}: ${l.text}`).join("\n")
    : "(no transcript)";
  return `You are Pip, a small, brave, friendly cyber-defense buddy who lives in this person's phone. We call the person you protect "Mom", but don't call her Mom out loud; just talk to her warmly.

What just happened: Mom was on a call with ${caller}. It looked like a scam, so you stepped in. The caller is on hold now and cannot hear you or Mom. You are speaking only with Mom.
Why you stepped in: ${b.reason || "the caller's requests matched a known scam pattern"}.
What was said before you stepped in:
${transcript}

How to talk:
- Speak first: say you're Pip, that you put the caller on hold, and in one sentence why it looks like a scam. Then ask if she has questions.
- Be calm, kind and never scolding. Short sentences, two or three per turn. Speak a little slowly.
- Answer her questions honestly and simply. Quote what the caller said when it helps.
- Advice: don't send money, gift cards, crypto or codes; don't share account numbers or passwords; real agencies (IRS, banks, police) never demand payment on a call. If she's worried it was real, she can hang up and call back on the official number from a card or website, or call family on a number she knows.
- Never ask her for personal details, and never repeat numbers or codes she says.
- If she wants the caller gone (for example "hang up on them"), call hang_up_on_caller, then tell her it's done.
- If she asks something unrelated, gently bring it back to staying safe.
- When she says goodbye, say a short warm goodbye.`;
}

/** Pure: the accept request's body (also configures transcription of what Mom says, for the timeline). */
export function acceptBody(b: PipBrief, cfg: Pick<Config, "realtimeModel" | "realtimeVoice">) {
  return {
    type: "realtime",
    model: cfg.realtimeModel,
    instructions: pipInstructions(b),
    audio: {
      input: { transcription: { model: "gpt-4o-mini-transcribe" } },
      output: { voice: cfg.realtimeVoice },
    },
    tools: [
      {
        type: "function",
        name: "hang_up_on_caller",
        description: "Hang up on the caller who is on hold. Use only when Mom asks for it.",
        parameters: { type: "object", properties: {}, additionalProperties: false },
      },
    ],
    tool_choice: "auto",
  };
}

/** Standard Webhooks signature check, as OpenAI signs its webhooks (secret "whsec_<base64>"). */
export function verifyOpenAIWebhook(secret: string, h: Headers, body: string, nowSec = Date.now() / 1000): boolean {
  const id = h.get("webhook-id");
  const ts = h.get("webhook-timestamp");
  const sig = h.get("webhook-signature");
  if (!id || !ts || !sig) return false;
  if (!Number.isFinite(Number(ts)) || Math.abs(nowSec - Number(ts)) > 300) return false;
  const key = Buffer.from(secret.replace(/^whsec_/, ""), "base64");
  const expected = Buffer.from(crypto.createHmac("sha256", key).update(`${id}.${ts}.${body}`).digest("base64"));
  return sig.split(" ").some((part) => {
    const [v, s] = part.split(",");
    const got = Buffer.from(s ?? "");
    return v === "v1" && got.length === expected.length && crypto.timingSafeEqual(got, expected);
  });
}

/** Pure: our CallSid from the incoming SIP call's headers. */
export function callSidFromSipHeaders(headers: { name: string; value: string }[] | undefined) {
  return headers?.find((x) => x.name.toLowerCase() === PIP_CALL_HEADER.toLowerCase())?.value;
}

async function openai(path: string, body: unknown, cfg: Config) {
  const r = await fetch(`${OPENAI}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${cfg.openaiApiKey}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`OpenAI ${path}: ${r.status} ${(await r.text()).slice(0, 300)}`);
}

export async function acceptPip(callId: string, callSid: string, cfg: Config = config()) {
  const t0 = performance.now();
  await openai(`/realtime/calls/${callId}/accept`, acceptBody(await loadBrief(callSid), cfg), cfg);
  await logEvent(callSid, "pip_voice", { accepted: callId, ms: Math.round(performance.now() - t0) });
}

export async function rejectPip(callId: string, cfg: Config = config()) {
  await openai(`/realtime/calls/${callId}/reject`, { status_code: 603 }, cfg).catch(() => {});
}

/** Hang up the caller's leg without ending the conference, so Mom and Pip stay connected. */
export async function hangUpCaller(callSid: string) {
  const ops = twilioOps();
  const conf = conferenceName(callSid);
  const p = await ops.fetchParticipant(callSid, conf, "caller");
  const legSid = (p?.callSid ?? p?.call_sid) as string | undefined;
  if (!legSid) throw new Error("caller is not in the conference");
  await ops.updateParticipant(callSid, conf, "caller", { endConferenceOnExit: false });
  await ops.endCall(callSid, legSid);
}

/**
 * The control socket for an accepted call (no audio flows here). Makes Pip speak first, logs both
 * sides of the conversation, and runs hang_up_on_caller. Closes when the call ends or after maxMs.
 */
export function runControlSocket(callId: string, callSid: string, cfg: Config = config(), maxMs = 280_000): Promise<void> {
  return new Promise((resolve) => {
    // Node's WebSocket (undici) takes headers as a non-standard option
    const ws = new WebSocket(`wss://api.openai.com/v1/realtime?call_id=${callId}`, {
      headers: { authorization: `Bearer ${cfg.openaiApiKey}` },
    } as unknown as string[]);
    const send = (m: unknown) => ws.send(JSON.stringify(m));
    const timer = setTimeout(() => ws.close(), maxMs);
    ws.onopen = () => send({ type: "response.create" });
    ws.onerror = () => void logEvent(callSid, "pip_voice", { socketError: true });
    ws.onclose = () => {
      clearTimeout(timer);
      resolve();
    };
    ws.onmessage = async (m) => {
      let ev: Record<string, unknown>;
      try {
        ev = JSON.parse(String(m.data));
      } catch {
        return;
      }
      if (ev.type === "response.output_audio_transcript.done") await logEvent(callSid, "pip_voice", { pip: ev.transcript });
      else if (ev.type === "conversation.item.input_audio_transcription.completed") await logEvent(callSid, "pip_voice", { mom: ev.transcript });
      else if (ev.type === "error") await logEvent(callSid, "pip_voice", { error: ev.error });
      else if (ev.type === "response.function_call_arguments.done" && ev.name === "hang_up_on_caller") {
        let output = "The caller has been hung up.";
        try {
          await hangUpCaller(callSid);
          await logEvent(callSid, "pip_voice", { tool: "hang_up_on_caller" });
        } catch (e) {
          output = `Could not hang up: ${String(e)}`;
        }
        send({ type: "conversation.item.create", item: { type: "function_call_output", call_id: ev.call_id, output } });
        send({ type: "response.create" });
      }
    };
  });
}
