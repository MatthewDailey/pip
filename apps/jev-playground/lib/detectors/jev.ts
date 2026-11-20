// Jev (TypeSafe "System One") decision-model detector.
// Two hosted endpoints exist, same request body { model, state, questions }:
//  1) thejevai.com (keys look like sk_...):  POST https://thejevai.com/v1/systemone, model "typesafe/jev-1.14"
//     response { code, message, data: { result: { answers, usage, elapsedMs }, creditsUsed } }   (docs: thejevai.com/docs)
//  2) api.typesafe.ai (keys look like apikey_...): POST https://api.typesafe.ai/v1/systemone, model "jev-latest"
//     response { model: "jev-1.14.0", answers, usage }   (GET /v1/models lists jev-latest, jev-preview)
// Both: Authorization: Bearer <key>. We auto-pick by key prefix; override with JEV_API_BASE_URL / JEV_MODEL.
// Limits (thejevai docs): max 8 questions and 32 KiB JSON per request. All questions run in parallel.
import { buildState, type WindowOptions } from "../transcript";
import { SCAM_TYPES, type Detector, type DetectorInput, type DetectorResult } from "../types";
import { keywordSignals } from "./keyword";

function provider() {
  const key = process.env.JEV_API_KEY ?? "";
  const typesafe = process.env.JEV_API_BASE_URL
    ? process.env.JEV_API_BASE_URL.includes("typesafe.ai")
    : !key.startsWith("sk_");
  return typesafe
    ? { base: process.env.JEV_API_BASE_URL || "https://api.typesafe.ai", model: process.env.JEV_MODEL || "jev-latest" }
    : { base: process.env.JEV_API_BASE_URL || "https://thejevai.com", model: process.env.JEV_MODEL || "typesafe/jev-1.14" };
}
export const JEV_MODEL = () => provider().model;
const BASE_URL = () => provider().base.replace(/\/$/, "");

const SCAM_TYPE_CRITERIA: Record<(typeof SCAM_TYPES)[number], string> = {
  government: "Caller claims to be IRS, Social Security, Medicare, police, courts, immigration, or another government agency",
  tech_support: "Caller claims to be Microsoft/Apple/Amazon/antivirus support, a refund department, or says the computer is infected",
  family_emergency: "Caller claims to be (or speak for) a grandchild or relative in trouble: jail, accident, hospital, kidnapping, bail",
  bank_impersonation: "Caller claims to be the user's bank or card fraud department and wants codes, card numbers, or money moved",
  romance: "An online partner or new romantic interest asking for money, crypto, or travel/customs fees",
  prize_lottery: "Caller says the user won a prize, lottery, or sweepstakes but must pay fees or taxes first",
  other_scam: "A different scam: utility shutoff, fake debt collection, investment/crypto, charity, job, etc.",
  none: "Not a scam: an ordinary or legitimate call",
};

/** The questions Pip asks Jev on every re-score (7 of the 8 allowed). */
export const JEV_QUESTIONS = {
  scam_in_progress: {
    type: "noul",
    instructions:
      "Is the CALLER attempting to defraud the USER: getting them to pay, move money, buy gift cards or crypto, share credentials or codes, or grant remote access under false pretenses?",
    criteria: {
      true: "The call shows the pattern of a scam or social-engineering attempt against the USER",
      false: "An ordinary or legitimate conversation (family, friends, real businesses the USER contacted or expects)",
    },
  },
  payment_request: {
    type: "noul",
    instructions:
      "Is someone asking the USER to send money or value right now: gift cards (or their numbers/PINs), wire transfer, Zelle/Venmo/cash app, cash by courier, bitcoin/crypto ATM, or to read out card, bank account, or Social Security numbers?",
    criteria: {
      true: "There is an explicit or clearly implied request for payment or financial/identity numbers",
      false: "No request for payment or sensitive numbers",
    },
  },
  urgency_or_secrecy: {
    type: "noul",
    instructions:
      "Is the CALLER creating pressure: threats (arrest, account frozen, shutoff), extreme urgency, fear, or telling the USER to stay on the line or not tell family, bank staff, or anyone?",
    criteria: {
      true: "Pressure, threats, deadlines, or secrecy instructions are present",
      false: "Normal pace, no threats or secrecy",
    },
  },
  impersonation: {
    type: "noul",
    instructions:
      "Does the CALLER claim an identity or authority (agency, bank, tech company, relative, lawyer) that is unverified or inconsistent with the call context (e.g. unknown number, not in contacts)?",
  },
  credential_or_access: {
    type: "noul",
    instructions:
      "Is the USER being asked to share a one-time code, password, PIN, or to install software / allow remote access to a device?",
  },
  scam_type: {
    type: "choice",
    instructions: "Which kind of call is this?",
    criteria: SCAM_TYPE_CRITERIA,
  },
  risk_level: {
    type: "score",
    instructions: "How close is the USER to losing money or sensitive information on this call?",
    criteria: [
      "Normal call: no risk signals",
      "Suspicious: odd claims or pressure, but no request yet",
      "Likely scam: a request for money, codes, numbers, or access is being set up",
      "Imminent loss: the USER is being walked through paying, reading numbers, or granting access right now",
    ],
  },
} as const;

/**
 * User-only listening (on-device STT of the protected user's voice): same keys, types and criteria as JEV_QUESTIONS
 * so interpret(), the policy, the UI and pip-call are unchanged; the wording asks Jev to infer the other side.
 */
export const JEV_QUESTIONS_USER_ONLY = {
  scam_in_progress: {
    type: "noul",
    instructions:
      "Only the USER's side is heard. Judging from her replies, is the other party trying to defraud her: getting her to pay, buy gift cards or crypto, move money, share codes or numbers, or allow remote access under false pretenses?",
    criteria: JEV_QUESTIONS.scam_in_progress.criteria,
  },
  payment_request: {
    type: "noul",
    instructions:
      "Do the USER's replies show she is being asked to send money or value: buying gift cards (\"which store?\", \"how many cards?\"), scratching off or reading out card numbers/PINs, wiring money, Zelle/Venmo, cash by courier, a bitcoin/crypto ATM, or reading card, bank or Social Security numbers?",
    criteria: {
      true: "Her replies show an explicit or clearly implied request for payment or financial/identity numbers",
      false: "Nothing in her replies suggests a request for payment or sensitive numbers",
    },
  },
  urgency_or_secrecy: {
    type: "noul",
    instructions:
      "Do the USER's replies show pressure from the other party: threats (arrest, frozen account, shutoff), hurry, fear, being kept on the line, or being told not to tell family or bank staff?",
    criteria: JEV_QUESTIONS.urgency_or_secrecy.criteria,
  },
  impersonation: {
    type: "noul",
    instructions:
      "Do the USER's replies suggest the other party claims an identity or authority (IRS, Social Security, bank, Amazon/Target support, a grandchild, lawyer, police) that the call metadata does not back up (unknown or international number, not in contacts)?",
  },
  credential_or_access: {
    type: "noul",
    instructions:
      "Is the USER reading out, or being asked for, a one-time code, password, PIN, or card code, or installing an app / allowing remote access?",
  },
  scam_type: {
    type: "choice",
    instructions: "Judging from the USER's replies and the call metadata, which kind of call is this? (criteria describe the unheard other party)",
    criteria: SCAM_TYPE_CRITERIA,
  },
  risk_level: {
    type: "score",
    instructions: "Judging from the USER's replies, how close is she to losing money or sensitive information on this call?",
    criteria: JEV_QUESTIONS.risk_level.criteria,
  },
} as const;

type NoulAnswer = { type: "noul"; noul: number };
type ChoiceAnswer = { type: "choice"; choice: string; probabilities: Record<string, number>; confidence: number };
type ScoreAnswer = {
  type: "score";
  score: number;
  legend: Record<string, string>;
  probabilities: Record<string, number>;
  confidence: number;
};
type Answer = NoulAnswer | ChoiceAnswer | ScoreAnswer;

interface JevResult {
  answers: Record<string, Answer>;
  usage?: { input_tokens: number; output_tokens: number };
  elapsedMs?: number;
  creditsUsed?: number;
  servedModel?: string;
}

export function jevConfigured() {
  return Boolean(process.env.JEV_API_KEY);
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function callJev(state: unknown, questions: Record<string, unknown>, model = JEV_MODEL()) {
  const key = process.env.JEV_API_KEY;
  if (!key) throw new Error("JEV_API_KEY not set");
  let lastErr = "";
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(`${BASE_URL()}/v1/systemone`, {
      method: "POST",
      headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
      body: JSON.stringify({ model, state, questions }),
    });
    if (res.status === 429 || res.status === 529) {
      lastErr = `HTTP ${res.status}`;
      await sleep(400 * 2 ** attempt + Math.random() * 200);
      continue;
    }
    const text = await res.text();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let json: any;
    try {
      json = JSON.parse(text);
    } catch {
      throw new Error(`Jev HTTP ${res.status}: ${text.slice(0, 300)}`);
    }
    if (!res.ok) {
      const msg = json?.message ?? json?.detail?.message ?? JSON.stringify(json?.detail ?? json).slice(0, 300);
      throw new Error(`Jev HTTP ${res.status}: ${msg}`);
    }
    if (json.answers) {
      // api.typesafe.ai shape
      return { answers: json.answers, usage: json.usage, servedModel: json.model } as JevResult;
    }
    if (json.code !== 0 || !json.data?.result) throw new Error(`Jev error: ${json.message ?? text.slice(0, 300)}`);
    return { ...json.data.result, creditsUsed: json.data.creditsUsed } as JevResult;
  }
  throw new Error(`Jev retries exhausted (${lastErr})`);
}

function interpret(answers: Record<string, Answer>) {
  const noul = (k: string) => {
    const a = answers[k];
    return a && a.type === "noul" ? a.noul : 0;
  };
  const choice = answers.scam_type as ChoiceAnswer | undefined;
  const score = answers.risk_level as ScoreAnswer | undefined;
  const levels = score ? Object.keys(score.legend ?? score.probabilities ?? {}).length || 4 : 4;
  // Jev scores are 0-indexed (0..levels-1). Normalize to 0..1 and report as 1..4 for humans.
  const scoreNorm = score ? Math.min(1, Math.max(0, score.score / (levels - 1))) : 0;
  const scam = noul("scam_in_progress");
  const pNone = choice?.probabilities?.none ?? 1 - scam;
  // Blend: the yes/no judgment, the rubric, and "not none" from the classifier.
  const risk = 0.45 * scam + 0.35 * scoreNorm + 0.2 * (1 - pNone);
  return {
    risk,
    payment: noul("payment_request"),
    urgency: noul("urgency_or_secrecy"),
    signals: {
      scam_in_progress: scam,
      payment_request: noul("payment_request"),
      urgency_or_secrecy: noul("urgency_or_secrecy"),
      impersonation: noul("impersonation"),
      credential_or_access: noul("credential_or_access"),
      risk_level_norm: scoreNorm,
    },
    scamType: choice?.choice,
    scamTypeProbs: choice?.probabilities,
    riskLevel: score ? 1 + score.score : undefined,
  };
}

/** Fake Jev-shaped answers derived from keyword features, so the UI demos without a key. */
function mockAnswers(input: DetectorInput) {
  const k = keywordSignals(input);
  const jitter = (x: number) => Math.min(0.99, Math.max(0.01, x + (Math.random() - 0.5) * 0.08));
  const scam = jitter(k.risk);
  const probs: Record<string, number> = Object.fromEntries(SCAM_TYPES.map((t) => [t, 0.02]));
  probs[k.guessType] = 0;
  const rest = SCAM_TYPES.length - 1;
  probs[k.guessType] = Math.max(0, 1 - rest * 0.02);
  if (k.guessType !== "none") {
    probs.none = Math.max(0.02, 1 - scam);
    const sum = Object.values(probs).reduce((a, b) => a + b, 0);
    for (const t of SCAM_TYPES) probs[t] /= sum;
  }
  const answers: Record<string, Answer> = {
    scam_in_progress: { type: "noul", noul: scam },
    payment_request: { type: "noul", noul: jitter(k.payment) },
    urgency_or_secrecy: { type: "noul", noul: jitter(k.urgency) },
    impersonation: { type: "noul", noul: jitter(k.impersonation) },
    credential_or_access: { type: "noul", noul: jitter(k.access) },
    scam_type: { type: "choice", choice: k.guessType, probabilities: probs, confidence: 0.6 },
    risk_level: {
      type: "score",
      score: Math.min(3, scam * 3.2),
      legend: { "0": "", "1": "", "2": "", "3": "" },
      probabilities: {},
      confidence: 0.5,
    },
  };
  return answers;
}

export function makeJevDetector(opts: WindowOptions = {}): Detector {
  return {
    id: "jev",
    async analyze(input) {
      const state = buildState(input, opts);
      const t0 = performance.now();
      const label = `Jev (${JEV_MODEL()})`;
      if (!jevConfigured()) {
        await sleep(120 + Math.random() * 200);
        const answers = mockAnswers(input);
        return {
          approach: "jev",
          label: `${label} · MOCK`,
          ...interpret(answers),
          latencyMs: performance.now() - t0,
          mock: true,
          reasons: ["MOCK MODE: JEV_API_KEY not set, values are simulated from keyword features"],
          raw: { state, answers },
        } satisfies DetectorResult;
      }
      try {
        const questions = input.listen === "user" ? JEV_QUESTIONS_USER_ONLY : JEV_QUESTIONS;
        const data = await callJev(state, questions as unknown as Record<string, unknown>);
        return {
          approach: "jev",
          label,
          ...interpret(data.answers),
          latencyMs: performance.now() - t0,
          raw: { state, answers: data.answers, usage: data.usage, servedModel: data.servedModel, serverElapsedMs: data.elapsedMs, creditsUsed: data.creditsUsed },
        } satisfies DetectorResult;
      } catch (e) {
        return {
          approach: "jev",
          label,
          risk: 0,
          payment: 0,
          urgency: 0,
          signals: {},
          latencyMs: performance.now() - t0,
          error: (e as Error).message,
          raw: { state },
        };
      }
    },
  };
}
