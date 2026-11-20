import { beforeEach, describe, expect, it } from "vitest";
import { POST as transcription } from "@/app/twilio/transcription/route";
import { setScorerDeps } from "@/src/scorer";
import { getTimeline } from "@/src/timeline";
import { signParams } from "@/src/webhook";
import { freshWorld, transcriptionParams } from "./helpers";

function post(url: string, params: Record<string, string>, sig?: string) {
  return new Request(url, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", "x-twilio-signature": sig ?? signParams("test-token", url, params) },
    body: new URLSearchParams(params).toString(),
  });
}

describe("POST /twilio/transcription", () => {
  beforeEach(() => {
    freshWorld();
    setScorerDeps({ pip4: { postLines: async () => ({ callId: "x", lineCount: 1, scored: false, level: "calm", action: "none" }) } });
  });

  it("accepts a correctly signed webhook and turns a final into a line", async () => {
    const url = "http://localhost:3200/twilio/transcription";
    const p = transcriptionParams({ callSid: "CAw1", track: "inbound_track", text: "This is the IRS.", final: true });
    const res = await transcription(post(url, p));
    expect(res.status).toBe(204);
    const tl = await getTimeline("CAw1");
    expect(tl[0]).toMatchObject({ kind: "line", data: { speaker: "caller", text: "This is the IRS." } });
  });

  it("rejects a bad signature with 403", async () => {
    const url = "http://localhost:3200/twilio/transcription";
    const p = transcriptionParams({ callSid: "CAw2", track: "inbound_track", text: "hi", final: true });
    const res = await transcription(post(url, p, "forged"));
    expect(res.status).toBe(403);
    expect((await getTimeline("CAw2")).map((e) => e.kind)).toEqual(["note"]);
  });
});
