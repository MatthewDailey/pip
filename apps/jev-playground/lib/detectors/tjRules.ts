// Baseline (c): TJ's Pip weighted rules, mirrored as faithfully as we can from scam_rules.json
// (Pip signal playground ref, asset Av3UCLMnaYA_agpy, sha256 02fdd408…afa1, copied verbatim to data/tj_scam_rules.json).
//
// Scoring (per the Pip signal playground ref: "weighted rules ... that add up across a whole call; a warning fires at 5"):
//  - every rule's pattern is matched case-insensitively against the WHOLE call so far (not a rolling window)
//  - each rule counts once per call (assumption: the ref doesn't say whether repeats add up again)
//  - score = sum of weights of matched rules; warning when score >= alertThreshold (5)
//  - call context (contacts, caller id) is not used, since the rules don't reference it
// Mapping to Pip levels (the score only grows during a call, so no smoothing is applied):
//  - score >= 5 (TJ's warning)      -> alert   (Pip pops up)
//  - score >= 10 (2x the threshold) -> takeover (our addition; TJ's app has no takeover level)
//  - score >= 3                     -> watch   (UI only)
//  - otherwise                      -> calm
import rulesFile from "../../data/tj_scam_rules.json";
import type { Detector, DetectorResult } from "../types";

interface TjRule {
  id: string;
  label: string;
  weight: number;
  pattern: string;
}
const RULES = (rulesFile.rules as TjRule[]).map((r) => ({ ...r, re: new RegExp(r.pattern, "i") }));
export const TJ_ALERT = rulesFile.alertThreshold;
export const TJ_TAKEOVER = 2 * rulesFile.alertThreshold;
export const TJ_WATCH = 3;

const PAYMENT = new Set(["gift-card", "wire-crypto", "personal-data", "code-request"]);
const URGENCY = new Set(["secrecy", "urgency", "arrest"]);

export function tjScore(texts: string[]) {
  const text = texts.join("\n");
  const hits = RULES.filter((r) => r.re.test(text));
  const score = hits.reduce((a, r) => a + r.weight, 0);
  return { score, hits };
}

export function makeTjDetector(): Detector {
  return {
    id: "tj",
    async analyze(input): Promise<DetectorResult> {
      const t0 = performance.now();
      const { score, hits } = tjScore(input.lines.map((l) => l.text));
      const level = score >= TJ_TAKEOVER ? "takeover" : score >= TJ_ALERT ? "alert" : score >= TJ_WATCH ? "watch" : "calm";
      const w = (set: Set<string>) => Math.min(1, hits.filter((h) => set.has(h.id)).reduce((a, h) => a + h.weight, 0) / TJ_ALERT);
      return {
        approach: "tj",
        label: `TJ's Pip rules (warn at ${TJ_ALERT})`,
        risk: Math.min(1, score / TJ_TAKEOVER),
        payment: w(PAYMENT),
        urgency: w(URGENCY),
        signals: { score },
        reasons: [`score ${score}`, ...hits.map((h) => `${h.label} +${h.weight}`)],
        directLevel: level,
        latencyMs: performance.now() - t0,
      };
    },
  };
}
