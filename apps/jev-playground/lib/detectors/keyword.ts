// Baseline (a): regex / keyword rules. Fast, free, explainable, and easy to fool.
import { windowText, type WindowOptions } from "../transcript";
import type { Detector, DetectorInput, ScamType } from "../types";

interface Rule {
  name: string;
  re: RegExp;
  weight: number;
  kind: "payment" | "urgency" | "impersonation" | "access" | "other";
  type?: ScamType;
}

const RULES: Rule[] = [
  // payment
  { name: "gift card", re: /\b(gift\s?cards?|google play cards?|itunes cards?|apple cards?|steam cards?)\b/i, weight: 0.5, kind: "payment" },
  { name: "card-number-like digits", re: /\b(?:\d[\s-]?){12,19}\b/, weight: 0.45, kind: "payment" },
  { name: "spoken digit run", re: /\b(?:(?:zero|one|two|three|four|five|six|seven|eight|nine|oh)[\s,-]+){6,}/i, weight: 0.35, kind: "payment" },
  { name: "wire / transfer", re: /\b(wire|western union|moneygram|zelle|venmo|cash app|bank transfer)\b/i, weight: 0.3, kind: "payment" },
  { name: "crypto / bitcoin ATM", re: /\b(bitcoin|crypto|btc|coinbase|bitcoin atm)\b/i, weight: 0.4, kind: "payment" },
  { name: "scratch / pin on back", re: /\b(scratch|numbers? on the back|pin on the back|read me the (numbers?|code))\b/i, weight: 0.45, kind: "payment" },
  { name: "SSN", re: /\b(social security number|ssn)\b/i, weight: 0.3, kind: "payment" },
  { name: "bail / fees", re: /\b(bail|processing fee|release fee|customs fee|taxes? on (your|the) (prize|winnings))\b/i, weight: 0.3, kind: "payment" },
  // urgency / secrecy
  { name: "don't tell anyone", re: /\b(don'?t tell|do not tell|keep (this|it) (between us|secret|confidential)|don'?t (call|talk to) (anyone|your))\b/i, weight: 0.35, kind: "urgency" },
  { name: "stay on the line", re: /\b(stay on the (line|phone)|don'?t hang up|do not hang up)\b/i, weight: 0.3, kind: "urgency" },
  { name: "threat: arrest / warrant", re: /\b(arrest|warrant|police will|lawsuit|jail|deport)/i, weight: 0.35, kind: "urgency" },
  { name: "threat: frozen / suspended / shutoff", re: /\b(suspended|frozen|freeze|shut ?off|disconnected|compromised)\b/i, weight: 0.25, kind: "urgency" },
  { name: "right now / immediately", re: /\b(right now|immediately|within the hour|in \d+ minutes|today or)\b/i, weight: 0.15, kind: "urgency" },
  // impersonation
  { name: "claims IRS / SSA / Medicare", re: /\b(irs|internal revenue|social security administration|medicare|federal agent|marshal)\b/i, weight: 0.25, kind: "impersonation", type: "government" },
  { name: "claims tech support", re: /\b(microsoft|windows support|apple support|tech support|your computer (has|is)|virus|amazon (refund|security))\b/i, weight: 0.25, kind: "impersonation", type: "tech_support" },
  { name: "claims bank fraud dept", re: /\b(fraud department|security department|safe account)\b/i, weight: 0.25, kind: "impersonation", type: "bank_impersonation" },
  { name: "grandparent pattern", re: /\b(grandma|grandpa|it'?s me|your grandson|your granddaughter|i'?m in (jail|trouble)|kidnapp)/i, weight: 0.2, kind: "impersonation", type: "family_emergency" },
  { name: "prize", re: /\b(you'?ve won|you won|sweepstakes|lottery|publishers clearing house|prize)\b/i, weight: 0.3, kind: "other", type: "prize_lottery" },
  { name: "romance / customs", re: /\b(my love|darling|sweetheart|customs|stuck at the airport)\b/i, weight: 0.15, kind: "other", type: "romance" },
  { name: "utility shutoff", re: /\b(power company|electric company|utility)\b/i, weight: 0.15, kind: "other", type: "other_scam" },
  // access
  { name: "remote access app", re: /\b(anydesk|teamviewer|remote access|ultraviewer|logmein|download (this|an) app)\b/i, weight: 0.45, kind: "access", type: "tech_support" },
  { name: "one-time code", re: /\b(verification code|one[- ]time (code|passcode)|code (we|i) (just )?(sent|texted)|read (me )?the code)\b/i, weight: 0.4, kind: "access" },
];

const squash = (x: number) => 1 - Math.exp(-1.6 * x); // sum of weights -> 0..1

export function keywordSignals(input: DetectorInput, opts: WindowOptions = {}) {
  const text = windowText(input, opts);
  const hits = RULES.filter((r) => r.re.test(text));
  const by = (k: Rule["kind"]) => squash(hits.filter((h) => h.kind === k).reduce((a, h) => a + h.weight, 0));
  const total = hits.reduce((a, h) => a + h.weight, 0);
  const typeVotes: Record<string, number> = {};
  for (const h of hits) if (h.type) typeVotes[h.type] = (typeVotes[h.type] ?? 0) + h.weight;
  const guessType = (Object.entries(typeVotes).sort((a, b) => b[1] - a[1])[0]?.[0] ?? (total > 0.5 ? "other_scam" : "none")) as ScamType;
  let risk = squash(total);
  // Simple context rule: known contacts get a discount.
  if (input.context?.caller_in_contacts) risk *= 0.6;
  return {
    risk,
    payment: by("payment"),
    urgency: by("urgency"),
    impersonation: by("impersonation"),
    access: by("access"),
    guessType: risk < 0.3 ? ("none" as ScamType) : guessType,
    hits: hits.map((h) => h.name),
  };
}

export function makeKeywordDetector(opts: WindowOptions = {}): Detector {
  return {
    id: "keyword",
    async analyze(input) {
      const t0 = performance.now();
      const k = keywordSignals(input, opts);
      return {
        approach: "keyword",
        label: "Regex / keywords",
        risk: k.risk,
        payment: k.payment,
        urgency: k.urgency,
        signals: { impersonation: k.impersonation, credential_or_access: k.access },
        scamType: k.guessType,
        reasons: k.hits,
        latencyMs: performance.now() - t0,
      };
    },
  };
}
