import { beforeEach, describe, expect, it } from "vitest";
import twilio from "twilio";
import { POST as voice } from "@/app/twiml/voice/route";
import { POST as participant } from "@/app/twilio/participant/route";
import { getContext, getTranscribedLeg } from "@/src/callState";
import { loadConfig } from "@/src/config";
import { pip4Context } from "@/src/pip4";
import { getTimeline } from "@/src/timeline";
import { classifyVoice, handleVoice } from "@/src/voice";
import { signParams } from "@/src/webhook";
import { freshWorld } from "./helpers";

const PIP_LINE = "+15550001111";
const cfg = (extra: Record<string, string> = {}) =>
  loadConfig({
    TWILIO_ACCOUNT_SID: "ACtest",
    TWILIO_AUTH_TOKEN: "test-token",
    PIP_LINE_NUMBER: PIP_LINE,
    PUBLIC_BASE_URL: "https://pip.example",
    ...extra,
  });

const appCall = {
  CallSid: "CAapp1",
  From: "client:mom-42",
  Caller: "client:mom-42",
  To: "+1 (555) 000-2222",
  pip_user: "mom-42",
  contact_name: "Chase Bank",
  in_contacts: "false",
  direction: "outbound",
  local_time: "10:20",
};
const pipLineCall = { CallSid: "CAline1", From: "+15550003333", To: PIP_LINE, Called: PIP_LINE };

const cmds = async (sid: string) =>
  (await getTimeline(sid)).filter((e) => e.kind === "twilio_cmd").map((e) => e.data as Record<string, unknown>);

describe("classifyVoice", () => {
  it("tells app calls, Pip line calls and strays apart", () => {
    expect(classifyVoice(appCall, cfg())).toEqual({ kind: "app_outgoing", identity: "mom-42", to: "+15550002222" });
    expect(classifyVoice(pipLineCall, cfg())).toEqual({ kind: "pip_line", from: "+15550003333" });
    expect(classifyVoice({ ...appCall, To: "" }, cfg()).kind).toBe("reject");
    expect(classifyVoice({ From: "+1555", To: "+15559999999" }, cfg()).kind).toBe("reject");
  });
});

describe("handleVoice", () => {
  beforeEach(() => void freshWorld());

  it("app outgoing: Mom's leg joins as mom with transcription, the dialled number is added as caller", async () => {
    const xml = await handleVoice(appCall, cfg());
    expect(xml).toMatchSnapshot();
    expect(xml).toContain('participantLabel="mom"');
    expect(xml).toContain(">pip-CAapp1</Conference>");
    expect(await cmds("CAapp1")).toEqual([
      expect.objectContaining({ op: "createParticipant", conference: "pip-CAapp1", label: "caller", from: PIP_LINE, to: "+15550002222", endConferenceOnExit: true }),
    ]);
    expect(await getTranscribedLeg("CAapp1")).toBe("mom");
    const ctx = await getContext("CAapp1");
    expect(ctx).toMatchObject({ direction: "outbound", caller_in_contacts: false, caller_id: "Chase Bank", pip_user: "mom-42", local_time: "10:20" });
    expect(pip4Context(ctx)).toEqual({ direction: "outbound", caller_in_contacts: false, caller_id: "Chase Bank", caller_number: "+15550002222", caller_name: "Chase Bank", local_time: "10:20" });
  });

  it("Pip line with Mom's app registered: caller joins, Mom's app is rung with the caller's number", async () => {
    const xml = await handleVoice(pipLineCall, cfg({ MOM_CLIENT_IDENTITY: "mom-42", MOM_NUMBER: "+15550004444" }));
    expect(xml).toMatchSnapshot();
    expect(xml).toContain('participantLabel="caller"');
    expect(await cmds("CAline1")).toEqual([
      expect.objectContaining({ op: "createParticipant", label: "mom", from: "+15550003333", to: "client:mom-42" }),
    ]);
    expect(await getTranscribedLeg("CAline1")).toBe("caller");
    expect(await getContext("CAline1")).toMatchObject({ direction: "inbound", caller_in_contacts: false, caller_id: "+15550003333" });
  });

  it("Pip line without the app: Mom's phone rings showing the Pip line", async () => {
    await handleVoice(pipLineCall, cfg({ MOM_NUMBER: "+15550004444" }));
    expect(await cmds("CAline1")).toEqual([expect.objectContaining({ label: "mom", from: PIP_LINE, to: "+15550004444" })]);
  });

  it("Pip line with nobody to ring says sorry and hangs up", async () => {
    const xml = await handleVoice(pipLineCall, cfg());
    expect(xml).toContain("<Hangup/>");
    expect(await cmds("CAline1")).toEqual([]);
  });
});

describe("POST /twiml/voice", () => {
  beforeEach(() => void freshWorld());
  const url = "http://localhost:3200/twiml/voice";
  const post = (params: Record<string, string>, sig?: string) =>
    new Request(url, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "x-twilio-signature": sig ?? signParams("test-token", url, params) },
      body: new URLSearchParams(params).toString(),
    });

  it("answers a signed webhook with TwiML", async () => {
    const res = await voice(post(appCall));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("text/xml");
    expect(await res.text()).toContain("<Conference");
  });

  it("rejects a bad signature with 403 and dials nobody", async () => {
    const res = await voice(post(appCall, "forged"));
    expect(res.status).toBe(403);
    expect(await cmds("CAapp1")).toEqual([]);
  });
});

describe("POST /twilio/participant", () => {
  beforeEach(() => void freshWorld());
  const url = "http://localhost:3200/twilio/participant?call=CAapp1";
  const post = (params: Record<string, string>) =>
    new Request(url, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "x-twilio-signature": signParams("test-token", url, params) },
      body: new URLSearchParams(params).toString(),
    });

  it("hangs up Mom's waiting leg when the dialled number doesn't answer", async () => {
    await participant(post({ CallSid: "CAother", CallStatus: "no-answer", To: "+15550002222" }));
    expect(await cmds("CAapp1")).toEqual([{ op: "endCall", legSid: "CAapp1" }]);
  });

  it("leaves the call alone when they answer", async () => {
    await participant(post({ CallSid: "CAother", CallStatus: "in-progress" }));
    expect(await cmds("CAapp1")).toEqual([]);
  });
});

it("twilio helper can sign and validate the voice URL (sanity)", () => {
  const p = { CallSid: "CA1" };
  expect(twilio.validateRequest("t", signParams("t", "https://a/b", p), "https://a/b", p)).toBe(true);
});
