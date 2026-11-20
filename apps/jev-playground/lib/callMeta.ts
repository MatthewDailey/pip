// Call metadata the phone can send alongside the transcript, plus cheap derived hints for Jev.
import type { CallContext } from "./types";

const ORG_WORDS =
  /\b(irs|internal revenue|social security|ssa|medicare|police|sheriff|fbi|court|bank|chase|wells|citi|bofa|amazon|apple|microsoft|target|walmart|paypal|customer (care|service)|support|fraud|security|refund)\b/i;

/** Classify a caller number: withheld, international, toll_free, us_local, or unknown. */
export function numberType(raw: unknown): string | undefined {
  if (raw === undefined || raw === null) return undefined;
  const s = String(raw).trim();
  if (!s || /^(anonymous|private|unknown|restricted|withheld|blocked)$/i.test(s)) return "withheld";
  const digits = s.replace(/\D/g, "");
  if (s.startsWith("+") && !s.startsWith("+1")) return "international";
  if (digits.startsWith("011")) return "international";
  const nanp = digits.length === 11 && digits.startsWith("1") ? digits.slice(1) : digits;
  if (nanp.length !== 10) return "unknown";
  if (/^8(00|33|44|55|66|77|88)/.test(nanp)) return "toll_free";
  return "us_local";
}

/**
 * Normalize + enrich the context sent to Jev. Accepted keys (all optional):
 * caller_number, caller_name (caller ID as shown on the phone), caller_id (legacy alias), caller_in_contacts,
 * direction, number_type, call_duration_s, local_time, user_profile.
 */
export function enrichContext(ctx: CallContext = {}, extra: { call_duration_s?: number } = {}): CallContext {
  const out: CallContext = { ...ctx };
  if (out.caller_name === undefined && typeof out.caller_id === "string") out.caller_name = out.caller_id;
  if (out.number_type === undefined) {
    const nt = numberType(out.caller_number);
    if (nt) out.number_type = nt;
  }
  if (extra.call_duration_s !== undefined && out.call_duration_s === undefined) out.call_duration_s = Math.round(extra.call_duration_s);
  const name = typeof out.caller_name === "string" ? out.caller_name : "";
  if (out.caller_in_contacts !== true && name && ORG_WORDS.test(name)) {
    out.caller_id_note = "caller ID claims an organization but the number is not a saved contact (caller ID can be spoofed)";
  }
  return out;
}
