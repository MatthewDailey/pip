// Small per-call facts kept in the store (any instance may serve any webhook).
import { DEFAULT_TTL_MS, getStore } from "./store";

export interface CallContext {
  direction?: "inbound" | "outbound";
  caller_in_contacts?: boolean;
  caller_id?: string;
  [key: string]: unknown;
}

/** The conference that holds both legs of a call, named after the caller leg's CallSid. */
export const conferenceName = (callSid: string) => `pip-${callSid}`;

const key = (callSid: string, what: string) => `pip:call:${callSid}:${what}`;
export const callKey = key;

export async function getContext(callSid: string): Promise<CallContext> {
  const raw = await getStore().get(key(callSid, "context"));
  return raw ? (JSON.parse(raw) as CallContext) : { direction: "inbound", caller_in_contacts: false };
}

export async function setContext(callSid: string, ctx: CallContext) {
  await getStore().set(key(callSid, "context"), JSON.stringify(ctx), DEFAULT_TTL_MS);
}

/** Merge fields into the call's context (the app's params, or what it POSTs after answering). */
export async function mergeContext(callSid: string, patch: CallContext) {
  const raw = await getStore().get(key(callSid, "context"));
  const prev = raw ? (JSON.parse(raw) as CallContext) : {};
  const next = { ...prev, ...Object.fromEntries(Object.entries(patch).filter(([, v]) => v !== undefined)) };
  await setContext(callSid, next);
  return next;
}

/**
 * Which party's leg runs the transcription (the leg whose TwiML started it, and whose CallSid names
 * the conference). Pip line calls transcribe the caller's leg; app calls transcribe Mom's leg.
 * Unset means "caller" (the simulator's and the original scaffold's assumption).
 */
export type Leg = "caller" | "mom";

export async function setTranscribedLeg(callSid: string, leg: Leg) {
  await getStore().set(key(callSid, "leg"), leg, DEFAULT_TTL_MS);
}

export async function getTranscribedLeg(callSid: string): Promise<Leg> {
  return (await getStore().get(key(callSid, "leg"))) === "mom" ? "mom" : "caller";
}
