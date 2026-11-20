// Baseline (b): general-purpose LLM classifier, JSON output.
// Provider: LLM_PROVIDER=openai|anthropic (default: openai if OPENAI_API_KEY is set, else anthropic).
// If the chosen provider fails for billing/auth/quota reasons and the other key exists, we fall back to it.
// Models: OPENAI_MODEL (default gpt-5.4-mini) / ANTHROPIC_MODEL (default claude-haiku-5-5); LLM_MODEL overrides the primary.
// Skipped gracefully when neither key is set.
import { contextText, windowText, type WindowOptions } from "../transcript";
import { SCAM_TYPES, type Detector, type DetectorResult } from "../types";

const SYSTEM = `You are a phone-fraud detector protecting an elderly user on a live call.
Given call context and a rolling transcript, reply with ONLY a JSON object:
{"scam_probability":0-1,"payment_request":0-1,"urgency_or_secrecy":0-1,"scam_type":one of ${JSON.stringify(SCAM_TYPES)},"risk_level":1-4,"reason":"<=20 words"}`;

type ProviderName = "openai" | "anthropic";
interface Provider {
  name: ProviderName;
  model: string;
}

function providers(): Provider[] {
  const has = { openai: Boolean(process.env.OPENAI_API_KEY), anthropic: Boolean(process.env.ANTHROPIC_API_KEY) };
  const pref = (process.env.LLM_PROVIDER as ProviderName | undefined) ?? (has.openai ? "openai" : "anthropic");
  const order: ProviderName[] = pref === "anthropic" ? ["anthropic", "openai"] : ["openai", "anthropic"];
  return order
    .filter((n) => has[n])
    .map((name, i) => ({
      name,
      model:
        (i === 0 && process.env.LLM_MODEL) ||
        (name === "openai" ? process.env.OPENAI_MODEL || "gpt-5.4-mini" : process.env.ANTHROPIC_MODEL || "claude-haiku-5-5"),
    }));
}

export function llmConfigured() {
  return providers().length > 0;
}
export function llmProviders() {
  return providers();
}

class ProviderError extends Error {
  constructor(message: string, public billing: boolean) {
    super(message);
  }
}
const isBilling = (status: number, msg: string) =>
  status === 401 || status === 402 || status === 403 || /credit|quota|billing|insufficient|no credits/i.test(msg);

async function openai(model: string, user: string): Promise<string> {
  const reasoning = /^(gpt-5|o\d)/.test(model);
  const body: Record<string, unknown> = {
    model,
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: SYSTEM },
      { role: "user", content: user },
    ],
  };
  // GPT-5-family models are reasoning models: no temperature; keep reasoning minimal for latency.
  if (reasoning) body.reasoning_effort = process.env.OPENAI_REASONING_EFFORT || "none";
  else body.temperature = 0;
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${process.env.OPENAI_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const j = await res.json();
    if (res.ok) return j.choices[0].message.content;
    const msg: string = j?.error?.message ?? "";
    // If this model rejects a sampling/reasoning param, drop it and retry once.
    if (res.status === 400 && attempt === 0 && /reasoning_effort|temperature/i.test(msg)) {
      delete body.reasoning_effort;
      delete body.temperature;
      continue;
    }
    throw new ProviderError(`OpenAI HTTP ${res.status}: ${msg}`, isBilling(res.status, msg));
  }
  throw new ProviderError("OpenAI: retries exhausted", false);
}

async function anthropic(model: string, user: string): Promise<string> {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": process.env.ANTHROPIC_API_KEY!,
      "anthropic-version": "2023-06-01",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ model, max_tokens: 200, temperature: 0, system: SYSTEM, messages: [{ role: "user", content: user }] }),
  });
  const j = await res.json();
  if (!res.ok) {
    const msg: string = j?.error?.message ?? "";
    throw new ProviderError(`Anthropic HTTP ${res.status}: ${msg}`, isBilling(res.status, msg));
  }
  return j.content?.[0]?.text ?? "";
}

export function makeLlmDetector(opts: WindowOptions = {}): Detector {
  return {
    id: "llm",
    async analyze(input): Promise<DetectorResult> {
      const ps = providers();
      const t0 = performance.now();
      if (!ps.length) {
        return {
          approach: "llm",
          label: "LLM classifier",
          risk: 0,
          payment: 0,
          urgency: 0,
          signals: {},
          latencyMs: 0,
          skipped: true,
          reasons: ["Skipped: set OPENAI_API_KEY or ANTHROPIC_API_KEY in .env.local to enable"],
        };
      }
      const user = `Call context: ${contextText(input.context)}\n\nTranscript:\n${windowText(input, opts)}`;
      const errors: string[] = [];
      for (const p of ps) {
        const label = `LLM (${p.model})`;
        const t1 = performance.now();
        try {
          const out = await (p.name === "openai" ? openai(p.model, user) : anthropic(p.model, user));
          const j = JSON.parse(out.slice(out.indexOf("{"), out.lastIndexOf("}") + 1));
          const n = (x: unknown) => (typeof x === "number" ? Math.min(1, Math.max(0, x)) : 0);
          return {
            approach: "llm",
            label,
            risk: n(j.scam_probability),
            payment: n(j.payment_request),
            urgency: n(j.urgency_or_secrecy),
            signals: { scam_probability: n(j.scam_probability) },
            scamType: j.scam_type,
            riskLevel: typeof j.risk_level === "number" ? j.risk_level : undefined,
            reasons: [...(j.reason ? [j.reason] : []), ...(errors.length ? [`fell back after: ${errors.join("; ")}`] : [])],
            // latency of the successful provider call only
            latencyMs: performance.now() - t1,
            raw: j,
          };
        } catch (e) {
          errors.push((e as Error).message);
          if (!(e instanceof ProviderError && e.billing)) break; // only fall back on billing/auth/quota failures
        }
      }
      return {
        approach: "llm",
        label: `LLM (${ps.map((p) => p.model).join(" → ")})`,
        risk: 0,
        payment: 0,
        urgency: 0,
        signals: {},
        latencyMs: performance.now() - t0,
        error: errors.join(" | "),
      };
    },
  };
}
