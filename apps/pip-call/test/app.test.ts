import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST as appContext } from "@/app/app/calls/[callSid]/context/route";
import { GET as token } from "@/app/token/route";
import { getContext } from "@/src/callState";
import { loadConfig, resetConfig } from "@/src/config";
import { mintVoiceToken } from "@/src/token";
import { freshWorld } from "./helpers";

const SECRET = "app-secret-123";
const CALL = "CA0123456789abcdef0123456789abcdef";

beforeEach(() => {
  vi.stubEnv("PIP_APP_SECRET", SECRET);
  vi.stubEnv("TWILIO_ACCOUNT_SID", "ACtest");
  vi.stubEnv("TWILIO_API_KEY_SID", "SKtest");
  vi.stubEnv("TWILIO_API_KEY_SECRET", "key-secret");
  vi.stubEnv("TWILIO_TWIML_APP_SID", "APtest");
  freshWorld();
});
afterEach(() => {
  vi.unstubAllEnvs();
  resetConfig();
});

describe("mintVoiceToken", () => {
  it("issues a 1-hour token with a voice grant for the TwiML App, signed with the API key", () => {
    const cfg = loadConfig({ PIP_TWILIO: "fake", TWILIO_AUTH_TOKEN: "t", TWILIO_ACCOUNT_SID: "ACtest", TWILIO_API_KEY: "SKtest", TWILIO_API_SECRET: "key-secret", TWILIO_TWIML_APP_SID: "APtest" });
    const jwt = mintVoiceToken(cfg, "mom-42");
    const payload = JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString());
    expect(payload.iss).toBe("SKtest");
    expect(payload.sub).toBe("ACtest");
    expect(payload.exp - payload.iat).toBe(3600);
    expect(payload.grants.identity).toBe("mom-42");
    expect(payload.grants.voice).toMatchObject({ outgoing: { application_sid: "APtest" }, incoming: { allow: true } });
  });

  it("names what's missing", () => {
    const cfg = loadConfig({ PIP_TWILIO: "fake", TWILIO_AUTH_TOKEN: "t" });
    expect(() => mintVoiceToken(cfg, "x")).toThrow(/TWILIO_API_KEY_SID.*TWILIO_TWIML_APP_SID/);
  });
});

describe("GET /token", () => {
  const req = (user: string, secret?: string) =>
    new Request(`http://localhost:3200/token?user=${encodeURIComponent(user)}`, { headers: secret ? { "x-pip-app-secret": secret } : {} });

  it("returns a token with the right secret", async () => {
    const res = await token(req("mom-42", SECRET));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toMatchObject({ identity: "mom-42", ttl: 3600 });
    expect(body.token.split(".")).toHaveLength(3);
  });

  it("refuses without the secret, and bad identities", async () => {
    expect((await token(req("mom-42"))).status).toBe(401);
    expect((await token(req("mom-42", "wrong"))).status).toBe(401);
    expect((await token(req("has space", SECRET))).status).toBe(400);
  });

  it("refuses when PIP_APP_SECRET isn't configured", async () => {
    vi.stubEnv("PIP_APP_SECRET", "");
    resetConfig();
    expect((await token(req("mom-42", "anything"))).status).toBe(503);
  });
});

describe("POST /app/calls/:callSid/context", () => {
  const req = (body: unknown, secret = SECRET) =>
    new Request(`http://localhost:3200/app/calls/${CALL}/context`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-pip-app-secret": secret },
      body: JSON.stringify(body),
    });
  const params = { params: Promise.resolve({ callSid: CALL }) };

  it("merges the app's contact info into the call context", async () => {
    const res = await appContext(req({ in_contacts: true, contact_name: "Priya (daughter)" }), params);
    expect(res.status).toBe(200);
    expect(await getContext(CALL)).toMatchObject({ caller_in_contacts: true, caller_id: "Priya (daughter)" });
  });

  it("needs the app secret", async () => {
    expect((await appContext(req({ in_contacts: true }, "nope"), params)).status).toBe(401);
  });
});
