// Client for Pip4's live-call endpoint: POST {PIP4_URL}/api/calls/:callId.
// Times out (PIP4_TIMEOUT_MS, default 5 s) and retries once; every request, response and error is
// written to the call's timeline with Pip4's own scoring latency and our round-trip time.
import type { CallContext } from "./callState";
import { config } from "./config";
import type { Line } from "./lines";
import { logEvent } from "./timeline";

export type Pip4Level = "calm" | "watch" | "alert" | "takeover";
export type Pip4Action = "none" | "alert_trusted_contact" | "takeover";

export interface Pip4Response {
  callId: string;
  lineCount: number;
  scored: boolean;
  level: Pip4Level;
  action: Pip4Action;
  reason?: string;
  latest?: { risk?: number; scamType?: string; latencyMs?: number; mock?: boolean; error?: string };
  tripwire?: { rule?: string; level?: Pip4Level; reason?: string; partial?: boolean };
  tripwireFiredNow?: { rule?: string; level?: Pip4Level } | null;
}

export interface Pip4Client {
  postLines(callSid: string, lines: Line[], context: CallContext, opts?: { force?: boolean }): Promise<Pip4Response>;
}

/**
 * Fields Pip4 uses for scoring and the demo tripwire. Bookkeeping such as `source` stays in pip-call.
 * `caller_name` is preferred; `caller_id` / `contact_name` are copied across if name is missing.
 */
export function pip4Context(c: CallContext): CallContext {
  const keep = [
    "direction",
    "caller_in_contacts",
    "caller_id",
    "caller_number",
    "caller_name",
    "verification",
    "local_time",
    "user_profile",
    "listen",
  ] as const;
  const out = Object.fromEntries(keep.filter((k) => c[k] !== undefined).map((k) => [k, c[k]])) as CallContext;
  if (out.caller_name === undefined) {
    const alias = c.caller_id ?? c.contact_name;
    if (typeof alias === "string") out.caller_name = alias;
  }
  return out;
}

export class HttpPip4Client implements Pip4Client {
  constructor(
    private base = config().pip4Url,
    private approach = config().pip4Approach,
    private timeoutMs = config().pip4TimeoutMs,
    private fetchImpl: typeof fetch = fetch,
    private bypassSecret = config().pip4BypassSecret,
  ) {}

  async postLines(callSid: string, lines: Line[], context: CallContext, opts: { force?: boolean } = {}): Promise<Pip4Response> {
    const ctx = pip4Context(context);
    const listen = typeof context.listen === "string" ? context.listen : undefined;
    const body = {
      chunks: lines.map(({ speaker, text, t, final }) => ({
        speaker,
        text,
        t,
        ...(final === false ? { final: false } : { final: true }),
      })),
      context: ctx,
      ...(listen ? { listen } : {}),
      scoreEvery: listen === "user" ? 1 : 2,
      approach: this.approach,
      ...(opts.force ? { force: true } : {}),
    };
    let lastErr: unknown;
    for (let attempt = 1; attempt <= 2; attempt++) {
      const t0 = performance.now();
      await logEvent(callSid, "pip4_request", { attempt, seqs: lines.map((l) => l.seq), lines: body.chunks, force: opts.force || undefined });
      try {
        const res = await this.fetchImpl(`${this.base}/api/calls/${encodeURIComponent(callSid)}`, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            ...(this.bypassSecret ? { "x-vercel-protection-bypass": this.bypassSecret } : {}),
          },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(this.timeoutMs),
        });
        if (!res.ok) throw new Error(`Pip4 HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
        const r = (await res.json()) as Pip4Response;
        await logEvent(callSid, "pip4_response", {
          attempt,
          lineCount: r.lineCount,
          scored: r.scored,
          level: r.level,
          action: r.action,
          risk: r.latest?.risk,
          scamType: r.latest?.scamType,
          jevMs: r.latest?.latencyMs,
          mock: r.latest?.mock,
          roundTripMs: Math.round(performance.now() - t0),
        });
        return r;
      } catch (e) {
        lastErr = e;
        await logEvent(callSid, "pip4_error", { attempt, error: String(e), ms: Math.round(performance.now() - t0) });
      }
    }
    throw lastErr;
  }
}
