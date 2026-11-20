// Everything pip-call asks Twilio to do goes through TwilioOps, so tests and the simulator can swap
// in RecordingTwilioOps, which writes each command to the call's timeline instead of calling Twilio.
//
// Participants are addressed by conference friendly name + participant label ("caller" / "mom"),
// which Task 2 sets when it builds the conference; the real client resolves the conference SID.
import twilio from "twilio";
import { config, type Config } from "./config";
import { DEFAULT_TTL_MS, getStore } from "./store";
import { logEvent } from "./timeline";

export interface ParticipantUpdate {
  hold?: boolean;
  holdUrl?: string;
  muted?: boolean;
  announceUrl?: string;
  endConferenceOnExit?: boolean;
}

export interface CreateParticipant {
  from: string;
  to: string;
  label: string;
  [extra: string]: unknown;
}

export interface TwilioOps {
  createParticipant(callSid: string, conference: string, p: CreateParticipant): Promise<{ callSid?: string }>;
  updateParticipant(callSid: string, conference: string, label: string, u: ParticipantUpdate): Promise<void>;
  fetchParticipant(callSid: string, conference: string, label: string): Promise<Record<string, unknown> | null>;
  /** hang up one call leg (e.g. Mom's, when the number she dialled never answers) */
  endCall(callSid: string, legSid: string): Promise<void>;
}

/** Fake Twilio: records every command on the timeline (kind "twilio_cmd") and succeeds. */
export class RecordingTwilioOps implements TwilioOps {
  async createParticipant(callSid: string, conference: string, p: CreateParticipant) {
    await logEvent(callSid, "twilio_cmd", { op: "createParticipant", conference, ...p });
    return { callSid: `CAfake${p.label}` };
  }
  async updateParticipant(callSid: string, conference: string, label: string, u: ParticipantUpdate) {
    await logEvent(callSid, "twilio_cmd", { op: "updateParticipant", conference, label, ...u });
  }
  async fetchParticipant(callSid: string, conference: string, label: string) {
    await logEvent(callSid, "twilio_cmd", { op: "fetchParticipant", conference, label });
    return { label, conference, status: "connected" };
  }
  async endCall(callSid: string, legSid: string) {
    await logEvent(callSid, "twilio_cmd", { op: "endCall", legSid });
  }
}

export class RealTwilioOps implements TwilioOps {
  private client;
  constructor(cfg: Config) {
    this.client = twilio(cfg.twilioAccountSid, cfg.twilioAuthToken);
  }
  private async conferenceSid(friendlyName: string) {
    const key = `pip:conf:${friendlyName}`;
    const cached = await getStore().get(key);
    if (cached) return cached;
    const [conf] = await this.client.conferences.list({ friendlyName, status: "in-progress", limit: 1 });
    if (!conf) throw new Error(`no in-progress conference named ${friendlyName}`);
    await getStore().set(key, conf.sid, DEFAULT_TTL_MS);
    return conf.sid;
  }
  private async timed<T>(callSid: string, cmd: Record<string, unknown>, fn: () => Promise<T>) {
    const t0 = performance.now();
    try {
      const r = await fn();
      await logEvent(callSid, "twilio_cmd", { ...cmd, ms: Math.round(performance.now() - t0) });
      return r;
    } catch (e) {
      await logEvent(callSid, "twilio_error", { ...cmd, ms: Math.round(performance.now() - t0), error: String(e) });
      throw e;
    }
  }
  async createParticipant(callSid: string, conference: string, p: CreateParticipant) {
    return this.timed(callSid, { op: "createParticipant", conference, ...p }, async () => {
      // Twilio accepts the friendly name here and creates the conference if it doesn't exist yet,
      // which it usually doesn't: the TwiML that joins the first leg is still being returned.
      const r = await this.client.conferences(conference).participants.create(p as never);
      return { callSid: r.callSid };
    });
  }
  async updateParticipant(callSid: string, conference: string, label: string, u: ParticipantUpdate) {
    await this.timed(callSid, { op: "updateParticipant", conference, label, ...u }, async () => {
      const sid = await this.conferenceSid(conference);
      await this.client.conferences(sid).participants(label).update(u);
    });
  }
  async fetchParticipant(callSid: string, conference: string, label: string) {
    return this.timed(callSid, { op: "fetchParticipant", conference, label }, async () => {
      const sid = await this.conferenceSid(conference);
      const p = await this.client.conferences(sid).participants(label).fetch();
      return p.toJSON() as unknown as Record<string, unknown>;
    });
  }
  async endCall(callSid: string, legSid: string) {
    await this.timed(callSid, { op: "endCall", legSid }, async () => {
      await this.client.calls(legSid).update({ status: "completed" });
    });
  }
}

const g = globalThis as unknown as { __pipTwilio?: TwilioOps };
export function twilioOps(): TwilioOps {
  return (g.__pipTwilio ??= config().twilioFake ? new RecordingTwilioOps() : new RealTwilioOps(config()));
}
export function setTwilioOps(ops: TwilioOps) {
  g.__pipTwilio = ops;
}
