// Shared types for every fraud detector so Jev and the baselines are swappable.

/** "unknown" = speech-to-text diarization could not tell who spoke */
export type Speaker = "caller" | "user" | "unknown";

export interface TranscriptLine {
  speaker: Speaker;
  text: string;
  /** seconds since the call started (optional; we estimate if missing) */
  t?: number;
}

export interface CallContext {
  direction?: "inbound" | "outbound";
  caller_in_contacts?: boolean;
  /** caller ID text as shown on the phone (legacy name; caller_name is preferred) */
  caller_id?: string;
  caller_name?: string;
  /** E.164 if possible, e.g. "+18885550142"; "anonymous" when withheld */
  caller_number?: string;
  /** withheld | international | toll_free | us_local | unknown (derived from caller_number if omitted) */
  number_type?: string;
  call_duration_s?: number;
  local_time?: string;
  user_profile?: string;
  [key: string]: unknown;
}

export interface DetectorInput {
  lines: TranscriptLine[];
  context?: CallContext;
  /** which side(s) were transcribed; "user" = only the protected user's own voice (default "both" for library callers) */
  listen?: "user" | "both";
}

export type ApproachId = "jev" | "keyword" | "llm" | "tj";

export const SCAM_TYPES = [
  "government",
  "tech_support",
  "family_emergency",
  "bank_impersonation",
  "romance",
  "prize_lottery",
  "other_scam",
  "none",
] as const;
export type ScamType = (typeof SCAM_TYPES)[number];

export interface DetectorResult {
  approach: ApproachId;
  /** human label, e.g. "Jev (typesafe/jev-1.14)" */
  label: string;
  /** overall instantaneous fraud risk 0..1 (fed into the escalation policy) */
  risk: number;
  /** P(someone is asking for money / gift cards / wire / crypto / card or account numbers) */
  payment: number;
  /** P(urgency, fear, or secrecy pressure) */
  urgency: number;
  /** other per-question probabilities (noul answers, normalized score, ...) */
  signals: Record<string, number>;
  scamType?: ScamType | string;
  scamTypeProbs?: Record<string, number>;
  /** raw score on the rubric (1..4) if available */
  riskLevel?: number;
  /** short human-readable reasons (keyword hits, LLM rationale) */
  reasons?: string[];
  /** rule-based detectors that already accumulate over the call can set the level directly (skips EMA smoothing) */
  directLevel?: "calm" | "watch" | "alert" | "takeover";
  latencyMs: number;
  /** true when the result is simulated (no API key) */
  mock?: boolean;
  /** true when the approach was skipped (e.g. no LLM key) */
  skipped?: boolean;
  error?: string;
  raw?: unknown;
}

export interface Detector {
  id: ApproachId;
  analyze(input: DetectorInput): Promise<DetectorResult>;
}
