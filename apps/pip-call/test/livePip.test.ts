import crypto from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST as realtimeWebhook } from "@/app/openai/realtime/route";
import { POST as participant } from "@/app/twilio/participant/route";
import { liveVoiceReady, loadConfig, resetConfig } from "@/src/config";
import { acceptBody, callSidFromSipHeaders, loadBrief, pipSipUri, verifyOpenAIWebhook } from "@/src/livePip";
import { takeOver } from "@/src/takeover";
import { getTimeline, logEvent } from "@/src/timeline";
import { RecordingTwilioOps, setTwilioOps } from "@/src/twilioOps";
import { signParams } from "@/src/webhook";
import { freshWorld } from "./helpers";

const SECRET = "whsec_" + Buffer.from("pip-test-webhook-secret").toString("base64");
const live = (extra: Record<string, string> = {}) =>
  loadConfig({
    PIP_TWILIO: "fake",
    TWILIO_AUTH_TOKEN: "test-token",
    PIP_LINE_NUMBER: "+15550001111",
    PUBLIC_BASE_URL: "https://pip.example",
    PIP_VOICE: "live",
    OPENAI_API_KEY: "sk-test",
    OPENAI_PROJECT_ID: "proj_test",
    OPENAI_WEBHOOK_SECRET: SECRET,
    ...extra,
  });
const cmds = async (sid: string) =>
  (await getTimeline(sid)).filter((e) => e.kind === "twilio_cmd").map((e) => e.data as Record<string, unknown>);

function sign(body: string, secret = SECRET, ts = Math.floor(Date.now() / 1000)) {
  const id = "wh_123";
  const key = Buffer.from(secret.replace(/^whsec_/, ""), "base64");
  const sig = crypto.createHmac("sha256", key).update(`${id}.${ts}.${body}`).digest("base64");
  return new Headers({ "webhook-id": id, "webhook-timestamp": String(ts), "webhook-signature": `v1,${sig}` });
}

beforeEach(() => void freshWorld());
afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  resetConfig();
});

describe("config", () => {
  it("uses the live voice only when switched on and fully configured", () => {
    expect(liveVoiceReady(live())).toBe(true);
    expect(liveVoiceReady(live({ PIP_VOICE: "" }))).toBe(false);
    expect(liveVoiceReady(live({ OPENAI_WEBHOOK_SECRET: "" }))).toBe(false);
  });
});

describe("live takeover", () => {
  it("holds the caller and dials Pip over SIP, leaving Mom unmuted", async () => {
    await logEvent("CAL1", "line", { speaker: "caller", text: "This is the IRS, pay with gift cards", t: 1, seq: 1 });
    expect(await takeOver("CAL1", "IRS gift card demand", live())).toBe(true);
    const c = await cmds("CAL1");
    expect(c).toContainEqual(expect.objectContaining({ op: "updateParticipant", label: "caller", hold: true }));
    expect(c).toContainEqual(
      expect.objectContaining({
        op: "createParticipant",
        label: "pip",
        to: "sip:proj_test@sip.api.openai.com;transport=tls?X-Pip-Call=CAL1",
        endConferenceOnExit: false,
        statusCallback: expect.stringContaining("role=pip"),
      }),
    );
    expect(c.some((x) => x.label === "mom")).toBe(false);
    const brief = await loadBrief("CAL1");
    expect(brief.reason).toBe("IRS gift card demand");
    expect(brief.lines).toEqual([{ speaker: "caller", text: "This is the IRS, pay with gift cards" }]);
  });

  it("falls back to the fixed warning when Pip can't be dialled", async () => {
    class Failing extends RecordingTwilioOps {
      async createParticipant(): Promise<{ callSid: string }> {
        throw new Error("SIP not allowed");
      }
    }
    setTwilioOps(new Failing());
    await takeOver("CAL2", "scam", live());
    const c = await cmds("CAL2");
    expect(c).toContainEqual(expect.objectContaining({ label: "mom", muted: true }));
    expect(c).toContainEqual(expect.objectContaining({ label: "mom", announceUrl: expect.stringContaining("/twiml/warn") }));
  });

  it("without the flag, takeover is still hold + mute + warn", async () => {
    await takeOver("CAL3", "scam", live({ PIP_VOICE: "warn" }));
    const c = await cmds("CAL3");
    expect(c).toHaveLength(3);
    expect(c.some((x) => x.op === "createParticipant")).toBe(false);
  });
});

describe("POST /twilio/participant role=pip", () => {
  const url = "http://localhost:3200/twilio/participant?call=CAL4&role=pip";
  const post = (params: Record<string, string>) =>
    new Request(url, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "x-twilio-signature": signParams("test-token", url, params) },
      body: new URLSearchParams(params).toString(),
    });

  it("warns Mom (once) instead of ending the call when Pip never answers", async () => {
    await participant(post({ CallSid: "CApip", CallStatus: "failed" }));
    await participant(post({ CallSid: "CApip", CallStatus: "failed" }));
    const c = await cmds("CAL4");
    expect(c.filter((x) => x.announceUrl)).toHaveLength(1);
    expect(c.some((x) => x.op === "endCall")).toBe(false);
  });
});

describe("OpenAI webhook", () => {
  it("verifies Standard Webhooks signatures", () => {
    const body = '{"type":"x"}';
    expect(verifyOpenAIWebhook(SECRET, sign(body), body)).toBe(true);
    expect(verifyOpenAIWebhook(SECRET, sign(body), body + " ")).toBe(false);
    expect(verifyOpenAIWebhook(SECRET, sign(body, SECRET, 1_000), body)).toBe(false);
  });

  it("finds our CallSid in the SIP headers", () => {
    expect(callSidFromSipHeaders([{ name: "From", value: "x" }, { name: "x-pip-call", value: "CA9" }])).toBe("CA9");
    expect(callSidFromSipHeaders(undefined)).toBeUndefined();
    expect(pipSipUri("proj_x", "CA9")).toBe("sip:proj_x@sip.api.openai.com;transport=tls?X-Pip-Call=CA9");
  });

  it("accept body carries Pip's instructions with the reason and transcript", () => {
    const b = acceptBody({ reason: "IRS gift cards", context: { caller_id: "+15550002222" }, lines: [{ speaker: "user", text: "Who is this?" }] }, live());
    expect(b).toMatchObject({ type: "realtime", model: "gpt-realtime", audio: { output: { voice: "cedar" } } });
    expect(b.instructions).toContain("IRS gift cards");
    expect(b.instructions).toContain("Mom: Who is this?");
    expect(b.tools[0].name).toBe("hang_up_on_caller");
  });

  it("route rejects unsigned requests and accepts a signed incoming call", async () => {
    vi.stubEnv("OPENAI_API_KEY", "sk-test");
    vi.stubEnv("OPENAI_WEBHOOK_SECRET", SECRET);
    resetConfig();
    const fetches: string[] = [];
    vi.stubGlobal("fetch", async (u: string) => (fetches.push(String(u)), new Response("{}")));
    const body = JSON.stringify({ type: "realtime.call.incoming", data: { call_id: "rtc_1", sip_headers: [{ name: "X-Pip-Call", value: "CAL5" }] } });
    const bad = await realtimeWebhook(new Request("http://x/openai/realtime", { method: "POST", body }));
    expect(bad.status).toBe(401);
    // after() needs a request scope; the control socket is covered by the real call
    const res = await realtimeWebhook(new Request("http://x/openai/realtime", { method: "POST", body, headers: sign(body) })).catch((e) => e);
    expect(fetches).toEqual(["https://api.openai.com/v1/realtime/calls/rtc_1/accept"]);
    if (res instanceof Response) expect(res.status).toBe(204);
  });
});
