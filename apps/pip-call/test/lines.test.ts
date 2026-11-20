import { beforeEach, describe, expect, it } from "vitest";
import fixture from "./fixtures/twilio/synthetic/transcription-call.json";
import { handleTranscription, parseTranscriptionWebhook, trackToSpeaker, type Line } from "@/src/lines";
import { getTimeline } from "@/src/timeline";
import { freshWorld } from "./helpers";

describe("transcription webhooks → lines", () => {
  beforeEach(() => freshWorld());

  it("maps tracks on the caller's leg to speakers", () => {
    expect(trackToSpeaker("inbound_track")).toBe("caller");
    expect(trackToSpeaker("outbound_track")).toBe("user");
    expect(trackToSpeaker(undefined)).toBeUndefined();
  });

  it("parses TranscriptionData JSON, Final and SequenceId", () => {
    const ev = parseTranscriptionWebhook(fixture[2] as Record<string, string>);
    expect(ev).toMatchObject({ event: "transcription-content", speaker: "caller", final: true, seq: 3, confidence: 0.93 });
    expect(ev.text).toBe("Hello, this is Officer Daniels with the IRS.");
  });

  it("emits only finals as lines, with seconds since the first transcript event", async () => {
    const got: Line[] = [];
    for (const p of fixture) await handleTranscription(p as Record<string, string>, async (_sid, l) => void got.push(l));
    expect(got.map((l) => [l.speaker, l.seq, l.t])).toEqual([
      ["caller", 3, 1.5],
      ["user", 5, 4],
      ["caller", 4, 3],
    ]);
    const kinds = (await getTimeline("CAsynth1")).map((e) => e.kind);
    expect(kinds.filter((k) => k === "partial")).toHaveLength(1);
    expect(kinds.filter((k) => k === "line")).toHaveLength(3);
    expect(kinds).toContain("webhook"); // started / stopped
  });
});

describe("speaker labels follow the transcribed leg", () => {
  it("caller's leg (Pip line): inbound is the caller, outbound is Mom", () => {
    expect(trackToSpeaker("inbound_track", "caller")).toBe("caller");
    expect(trackToSpeaker("outbound_track", "caller")).toBe("user");
  });
  it("Mom's leg (app call): inbound is Mom, outbound is the caller", () => {
    expect(trackToSpeaker("inbound_track", "mom")).toBe("user");
    expect(trackToSpeaker("outbound_track", "mom")).toBe("caller");
  });
});
