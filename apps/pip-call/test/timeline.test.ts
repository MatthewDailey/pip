import { beforeEach, describe, expect, it } from "vitest";
import { GET as getCall } from "@/app/debug/calls/[callSid]/route";
import { GET as listCallsRoute } from "@/app/debug/calls/route";
import { getTimeline, listCalls, logEvent } from "@/src/timeline";
import { RecordingTwilioOps } from "@/src/twilioOps";
import { freshWorld } from "./helpers";

const auth = { "x-pip-debug-token": "test-token" };

describe("timeline", () => {
  beforeEach(() => freshWorld());

  it("records events with ms since the call's first event, in order", async () => {
    await logEvent("CA1", "webhook", { a: 1 });
    await new Promise((r) => setTimeout(r, 15));
    await logEvent("CA1", "line", { text: "hi" });
    const tl = await getTimeline("CA1");
    expect(tl.map((e) => e.kind)).toEqual(["webhook", "line"]);
    expect(tl[0].t).toBe(0);
    expect(tl[1].t).toBeGreaterThanOrEqual(10);
  });

  it("lists calls newest first", async () => {
    await logEvent("CA1", "note");
    await logEvent("CA2", "note");
    await logEvent("CA1", "note");
    expect((await listCalls()).map((c) => c.callSid)).toEqual(["CA2", "CA1"]);
  });

  it("GET /debug/calls/test returns events written through RecordingTwilioOps", async () => {
    const ops = new RecordingTwilioOps();
    await ops.updateParticipant("test", "pip-test", "mom", { muted: true });
    const res = await getCall(new Request("http://x/debug/calls/test", { headers: auth }), { params: Promise.resolve({ callSid: "test" }) });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.events[0]).toMatchObject({ kind: "twilio_cmd", data: { op: "updateParticipant", label: "mom", muted: true } });
    const list = await (await listCallsRoute(new Request("http://x/debug/calls", { headers: auth }))).json();
    expect(list.calls[0].callSid).toBe("test");
  });

  it("GET /debug/calls/:unknown is a 404", async () => {
    const res = await getCall(new Request("http://x/debug/calls/nope", { headers: auth }), { params: Promise.resolve({ callSid: "nope" }) });
    expect(res.status).toBe(404);
  });

  it("debug routes refuse without the token and hide the Vercel bypass secret", async () => {
    await logEvent("CAX", "twilio_cmd", { holdUrl: "https://pip.example/twiml/hold?x-vercel-protection-bypass=SECRET123&a=1" });
    const p = { params: Promise.resolve({ callSid: "CAX" }) };
    expect((await getCall(new Request("http://x/debug/calls/CAX"), p)).status).toBe(401);
    expect((await listCallsRoute(new Request("http://x/debug/calls"))).status).toBe(401);
    expect((await getCall(new Request("http://x/debug/calls/CAX", { headers: { "x-pip-debug-token": "wrong-token" } }), p)).status).toBe(401);
    const body = JSON.stringify(await (await getCall(new Request("http://x/debug/calls/CAX", { headers: auth }), p)).json());
    expect(body).not.toContain("SECRET123");
    expect(body).toContain("x-vercel-protection-bypass=[redacted]&a=1");
  });
});
