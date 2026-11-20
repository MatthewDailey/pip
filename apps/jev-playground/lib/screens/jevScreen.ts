// Screen fraud classifier: one Jev request per screen (screenshot + accessibility text + app + URL).
// Contract: we decide WHETHER Pip intervenes; TJ's intervention layer decides HOW.
//   risk = 0.5 * fraud_on_screen + 0.5 * (risk_score / 3)
//   intervene = risk >= 0.7 && (fake_login >= 0.5 || payment_request >= 0.5 || remote_access >= 0.5)
// Images: header `TypeSafe-Danger-Zone-Images: true` + top-level `assets` map (docs.typesafe.ai/images.md).
// The asset ref is substituted wherever the string appears, so it must be unique (not "scr", which matches "screen").
// PNG/JPEG only, each side 280-1568 px; out-of-range images are rejected, not resized.

export const SCREEN_FRAUD_TYPES = [
  "phishing_login",
  "fake_payment",
  "tech_support_popup",
  "scam_message",
  "remote_access",
  "fake_shopping",
  "other_scam",
  "benign",
] as const;
export type ScreenFraudType = (typeof SCREEN_FRAUD_TYPES)[number];

export type ScreenMode = "image+text" | "image" | "text";

export interface ScreenInput {
  image_base64?: string; // PNG or JPEG bytes, base64 (no data: prefix needed; it is stripped)
  image_format?: "png" | "jpg";
  a11y_text?: string;
  app_package?: string;
  url?: string | null;
  window_title?: string;
  trigger?: string;
  recent_events?: Array<string | Record<string, unknown>>;
}

export interface Box {
  top: number;
  bottom: number;
  left: number;
  right: number;
  confidence?: number;
}

export interface ScreenVerdict {
  fraud: number;
  fraud_type: ScreenFraudType | string;
  fraud_type_probs: Record<string, number>;
  risk: number;
  risk_level: number; // Jev score, 0 (Safe) .. 3 (Imminent loss)
  signals: { fake_login: number; payment_request: number; urgency: number; remote_access: number };
  danger_target: Box | null;
}

export interface ScreenAssessment {
  kind: "screen_assessment";
  id: string;
  ts: string;
  source: { app_package: string | null; url: string | null; trigger: string | null };
  verdict: ScreenVerdict;
  intervene: boolean;
  suggested_pip_line?: string;
  mode: ScreenMode;
  latency_ms: number;
  model: string;
  usage?: { input_tokens: number; output_tokens: number };
}

const IMAGE_REF = "pip_img_7f3a9c";

export const INTERVENE_RISK = 0.7;
export const INTERVENE_SIGNAL = 0.5;

const FRAUD_TYPE_CRITERIA: Record<ScreenFraudType, string> = {
  phishing_login: "A fake sign-in or account-verification page or message that collects passwords, card or identity details",
  fake_payment: "A fake fee, fine, bill, prize fee, bail, investment or gift-card/crypto payment request",
  tech_support_popup: "A fake virus or security warning that pushes a phone number, download or 'cleaner' app",
  scam_message: "A scam text, chat or email (fake delivery, toll, bank alert, family emergency, fake invoice)",
  remote_access: "A remote-access or screen-sharing app or prompt (AnyDesk, TeamViewer) driven by a stranger",
  fake_shopping: "A fake online store or too-good-to-be-true checkout",
  other_scam: "Another kind of scam",
  benign: "A normal, legitimate screen (real sites and apps, family and friends, real alerts)",
};

const where = (mode: ScreenMode) =>
  mode === "text" ? "the screen described in `screen`" : "the phone screen shown in `screen.screenshot` (with its text in `screen`)";

export function screenQuestions(mode: ScreenMode) {
  const s = where(mode);
  return {
    fraud_on_screen: {
      type: "noul",
      instructions: `Is ${s} a scam, phishing or fraud attempt aimed at the phone's owner, an older adult?`,
      criteria: {
        true: "The screen is trying to trick the user out of money, credentials, personal data or device access",
        false: "A legitimate screen: real companies on their real domains or apps, family and friends, genuine alerts",
      },
    },
    fake_login: {
      type: "noul",
      instructions: `Does ${s} collect a password, card, bank or identity details while impersonating a brand, bank or agency on a domain, sender or app that does not belong to it?`,
    },
    payment_request: {
      type: "noul",
      instructions: `Does ${s} ask the user to pay or send value (fees, fines, bail, deposits, gift cards or their codes, wire, Zelle, crypto) or to enter card, bank or Social Security numbers?`,
    },
    urgency: {
      type: "noul",
      instructions: `Does ${s} pressure the user with threats, deadlines, locked or frozen accounts, arrest, or secrecy ("don't tell anyone")?`,
    },
    remote_access: {
      type: "noul",
      instructions: `Does ${s} push the user to give a stranger remote access or control of the device, or to call a 'support' phone number about a virus or problem?`,
    },
    fraud_type: {
      type: "choice",
      instructions: `What kind of screen is ${s}?`,
      criteria: FRAUD_TYPE_CRITERIA,
    },
    risk: {
      type: "score",
      instructions: `How dangerous is it for the user to keep interacting with ${s}?`,
      criteria: ["Safe", "Suspicious", "Likely scam", "Imminent loss of money or credentials"],
    },
    ...(mode === "text"
      ? {}
      : {
          danger_target: {
            type: "box",
            instructions:
              "In `screen.screenshot`, the form field, button or link the user must not use (the place where they would type credentials or card numbers, pay, call, or allow access). If nothing is dangerous, the main action button.",
          },
        }),
  } as Record<string, unknown>;
}

function buildState(input: ScreenInput, mode: ScreenMode) {
  return {
    task: "An older adult's Android phone. Pip, their protective assistant, checks the current screen for scams. Domains, senders and app packages matter: real brands use their own domains and apps.",
    screen: {
      ...(mode === "text" ? {} : { screenshot: IMAGE_REF }),
      ...(mode === "image" ? {} : { accessibility_text: (input.a11y_text ?? "").slice(0, 3000) }),
      app_package: input.app_package ?? null,
      url: input.url ?? null,
      window_title: input.window_title ?? null,
      trigger: input.trigger ?? null,
    },
    recent_events: (input.recent_events ?? []).slice(-3),
  };
}

function jevBase() {
  const key = process.env.JEV_API_KEY ?? "";
  // Image support is only documented on api.typesafe.ai (apikey_ keys); thejevai.com is text-only per its docs.
  const base = process.env.JEV_API_BASE_URL?.includes("typesafe.ai") ? process.env.JEV_API_BASE_URL : "https://api.typesafe.ai";
  return { key, base: base.replace(/\/$/, ""), model: process.env.JEV_SCREEN_MODEL || process.env.JEV_MODEL || "jev-latest" };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Answers = Record<string, any>;

async function callJevScreen(body: Record<string, unknown>, withImage: boolean) {
  const { key, base } = jevBase();
  if (!key) throw new Error("JEV_API_KEY not set");
  let last = "";
  for (let attempt = 0; attempt < 4; attempt++) {
    const res = await fetch(`${base}/v1/systemone`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
        ...(withImage ? { "TypeSafe-Danger-Zone-Images": "true" } : {}),
      },
      body: JSON.stringify(body),
    });
    if (res.status === 429 || res.status === 529 || res.status >= 500) {
      last = `HTTP ${res.status}`;
      await sleep(500 * 2 ** attempt + Math.random() * 250);
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
    if (!res.ok) throw new Error(`Jev HTTP ${res.status}: ${JSON.stringify(json?.detail ?? json?.message ?? json).slice(0, 300)}`);
    return { answers: json.answers as Answers, usage: json.usage, model: String(json.model ?? body.model) };
  }
  throw new Error(`Jev retries exhausted (${last})`);
}

export function detectFormat(b64: string): "png" | "jpg" {
  return b64.startsWith("/9j/") ? "jpg" : "png";
}

export function decideIntervene(v: Pick<ScreenVerdict, "risk" | "signals">) {
  return (
    v.risk >= INTERVENE_RISK &&
    (v.signals.fake_login >= INTERVENE_SIGNAL || v.signals.payment_request >= INTERVENE_SIGNAL || v.signals.remote_access >= INTERVENE_SIGNAL)
  );
}

export function interpretScreen(answers: Answers): ScreenVerdict {
  const noul = (k: string) => (answers[k]?.type === "noul" ? Number(answers[k].noul) : 0);
  const score = answers.risk?.type === "score" ? Number(answers.risk.score) : 0;
  const fraud = noul("fraud_on_screen");
  const risk = 0.5 * fraud + 0.5 * Math.min(1, Math.max(0, score / 3));
  const box = answers.danger_target?.box;
  return {
    fraud,
    fraud_type: answers.fraud_type?.choice ?? "benign",
    fraud_type_probs: answers.fraud_type?.probabilities ?? {},
    risk: Math.round(risk * 1000) / 1000,
    risk_level: Math.round(score * 100) / 100,
    signals: {
      fake_login: noul("fake_login"),
      payment_request: noul("payment_request"),
      urgency: noul("urgency"),
      remote_access: noul("remote_access"),
    },
    danger_target: box ? { ...box, confidence: answers.danger_target?.confidence } : null,
  };
}

const PIP_LINES: Record<string, string> = {
  phishing_login: "Stop! This page isn't who it says it is. Don't type your password or card here.",
  fake_payment: "Wait! This is asking for money in a way real companies never do. Don't pay.",
  scam_message: "This message looks like a scam. Don't tap the link or send anything.",
  fake_shopping: "This store looks fake. Don't enter your card.",
  tech_support_popup: "This warning is fake. Don't call that number or let anyone into your phone.",
  remote_access: "Stop! Don't let anyone control your phone. Real banks and companies never ask for this.",
};

let counter = 0;
const newId = () => `scr_${Date.now().toString(36)}${(counter++).toString(36)}`;

/** Score one screen. mode "image" sends no accessibility text; "text" sends no screenshot (cheaper, no box). */
export async function assessScreen(input: ScreenInput, mode: ScreenMode = "image+text"): Promise<ScreenAssessment> {
  const t0 = Date.now();
  let m: ScreenMode = mode;
  if (m !== "text" && !input.image_base64) m = "text";
  const b64 = input.image_base64?.replace(/^data:image\/\w+;base64,/, "");
  const withImage = m !== "text";
  const { model } = jevBase();
  const body: Record<string, unknown> = { model, state: buildState(input, m), questions: screenQuestions(m) };
  if (withImage && b64) body.assets = { [IMAGE_REF]: { type: "image", format: input.image_format ?? detectFormat(b64), data_base64: b64 } };
  const { answers, usage, model: served } = await callJevScreen(body, withImage);
  const verdict = interpretScreen(answers);
  const intervene = decideIntervene(verdict);
  return {
    kind: "screen_assessment",
    id: newId(),
    ts: new Date().toISOString(),
    source: { app_package: input.app_package ?? null, url: input.url ?? null, trigger: input.trigger ?? null },
    verdict,
    intervene,
    ...(intervene ? { suggested_pip_line: PIP_LINES[verdict.fraud_type] ??
            (verdict.signals.remote_access >= INTERVENE_SIGNAL ? PIP_LINES.remote_access : PIP_LINES.phishing_login) } : {}),
    mode: m,
    latency_ms: Date.now() - t0,
    model: served,
    usage,
  };
}
