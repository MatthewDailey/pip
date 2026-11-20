// What pip-call does when Pip4 says "takeover": hold the caller, then either
//   warn (default)   mute Mom and play a fixed warning only she hears, or
//   live (PIP_VOICE=live, see livePip.ts)   dial Pip's live voice into the conference to talk with Mom.
// A one-shot lock in the store makes sure this happens exactly once per call, even if two webhooks
// on different instances both see the takeover verdict. If Pip can't be dialled, Mom gets the warning.
import { callKey, conferenceName } from "./callState";
import { config, liveVoiceReady, webhookUrl, type Config } from "./config";
import { pipSipUri, saveBrief } from "./livePip";
import { DEFAULT_TTL_MS, getStore } from "./store";
import { logEvent } from "./timeline";
import { twilioOps, type CreateParticipant, type ParticipantUpdate } from "./twilioOps";

export interface PlannedCommand {
  label: "caller" | "mom";
  update: ParticipantUpdate;
}

/** Pure: the participant updates that make up a (warn) takeover. */
export function planTakeover(urls: { hold: string; warn: string }): PlannedCommand[] {
  return [
    { label: "caller", update: { hold: true, holdUrl: urls.hold } },
    { label: "mom", update: { muted: true } },
    { label: "mom", update: { announceUrl: urls.warn } },
  ];
}

/** Pure: participant "pip", Pip's live voice reached over SIP. Mom stays unmuted so Pip can hear her. */
export function pipParticipant(callSid: string, cfg: Config): CreateParticipant {
  return {
    label: "pip",
    from: cfg.pipLineNumber || "pip",
    to: pipSipUri(cfg.openaiProjectId!, callSid),
    startConferenceOnEnter: true,
    endConferenceOnExit: false,
    beep: "false",
    timeout: 15,
    statusCallback: webhookUrl(`/twilio/participant?call=${encodeURIComponent(callSid)}&role=pip`, cfg),
    statusCallbackEvent: ["initiated", "ringing", "answered", "completed"],
  };
}

/** Mute Mom and play her the fixed warning (the default takeover, and the live voice's fallback). Once per call. */
export async function warnMom(callSid: string) {
  if (!(await getStore().setNX(callKey(callSid, "warned"), "1", DEFAULT_TTL_MS))) return;
  const [, mute, warn] = planTakeover({ hold: "", warn: webhookUrl("/twiml/warn") });
  const ops = twilioOps();
  const conf = conferenceName(callSid);
  await ops
    .updateParticipant(callSid, conf, mute.label, mute.update)
    .then(() => ops.updateParticipant(callSid, conf, warn.label, warn.update))
    .catch(async (e) => logEvent(callSid, "note", { warnFailed: String(e) }));
}

export async function takeOver(callSid: string, reason: unknown, cfg: Config = config()): Promise<boolean> {
  const won = await getStore().setNX(callKey(callSid, "takeover"), String(Date.now()), DEFAULT_TTL_MS);
  if (!won) return false;
  const live = liveVoiceReady(cfg);
  await logEvent(callSid, "takeover", { reason, voice: live ? "live" : "warn" });
  const conf = conferenceName(callSid);
  const ops = twilioOps();
  const hold = planTakeover({ hold: webhookUrl("/twiml/hold", cfg), warn: "" })[0];
  const holdCaller = ops.updateParticipant(callSid, conf, hold.label, hold.update).catch(async (e) => {
    await logEvent(callSid, "note", { takeoverFailed: String(e) });
  });
  if (!live) {
    await Promise.all([holdCaller, warnMom(callSid)]);
    return true;
  }
  const dialPip = saveBrief(callSid, reason)
    .then(() => ops.createParticipant(callSid, conf, pipParticipant(callSid, cfg)))
    .catch(async (e) => {
      await logEvent(callSid, "note", { pipDialFailed: String(e) });
      await warnMom(callSid);
    });
  await Promise.all([holdCaller, dialPip]);
  return true;
}
