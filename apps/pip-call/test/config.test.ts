import { describe, expect, it } from "vitest";
import { ConfigError, loadConfig, webhookUrl } from "@/src/config";

const live = {
  TWILIO_ACCOUNT_SID: "AC1",
  TWILIO_AUTH_TOKEN: "tok",
  PIP_LINE_NUMBER: "+15550000001",
  MOM_NUMBER: "+15550000002",
  PUBLIC_BASE_URL: "https://pip-call-test.vercel.app/",
};

describe("config", () => {
  it("names every missing variable", () => {
    const { TWILIO_AUTH_TOKEN: _, ...rest } = live;
    expect(() => loadConfig(rest)).toThrow(ConfigError);
    expect(() => loadConfig(rest)).toThrow(/TWILIO_AUTH_TOKEN/);
    try {
      loadConfig({});
    } catch (e) {
      expect((e as ConfigError).missing).toEqual(["TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN", "PIP_LINE_NUMBER", "PUBLIC_BASE_URL"]);
    }
  });

  it("accepts the cloud environment's variable names", () => {
    const { PIP_LINE_NUMBER: _, ...rest } = live;
    const cfg = loadConfig({ ...rest, TWILIO_PIP_LINE: "+15550000009", TWILIO_API_KEY_SID: "SK1", TWILIO_API_KEY_SECRET: "sec", TWILIO_ROBO_CALLER: "+15550000003" });
    expect(cfg).toMatchObject({ pipLineNumber: "+15550000009", twilioApiKeySid: "SK1", twilioApiKeySecret: "sec", roboCallerNumber: "+15550000003" });
  });

  it("derives PUBLIC_BASE_URL on Vercel", () => {
    const { PUBLIC_BASE_URL: _, ...rest } = live;
    expect(loadConfig({ ...rest, VERCEL_ENV: "production", VERCEL_PROJECT_PRODUCTION_URL: "pip-call-test.vercel.app" }).publicBaseUrl).toBe("https://pip-call-test.vercel.app");
    expect(loadConfig({ ...rest, VERCEL_ENV: "preview", VERCEL_BRANCH_URL: "pip-call-test-git-x.vercel.app", VERCEL_URL: "pip-call-test-abc.vercel.app" }).publicBaseUrl).toBe("https://pip-call-test-git-x.vercel.app");
  });

  it("fake Twilio mode only needs the auth token", () => {
    expect(loadConfig({ PIP_TWILIO: "fake", TWILIO_AUTH_TOKEN: "x" }).twilioFake).toBe(true);
    expect(() => loadConfig({ PIP_TWILIO: "fake" })).toThrow(/TWILIO_AUTH_TOKEN/);
  });

  it("adds the Vercel bypass secret to webhook URLs", () => {
    const cfg = loadConfig({ ...live, VERCEL_AUTOMATION_BYPASS_SECRET: "s3cret" });
    expect(webhookUrl("/twilio/transcription", cfg)).toBe(
      "https://pip-call-test.vercel.app/twilio/transcription?x-vercel-protection-bypass=s3cret",
    );
    expect(webhookUrl("/twiml/hold", loadConfig(live))).toBe("https://pip-call-test.vercel.app/twiml/hold");
  });
});
