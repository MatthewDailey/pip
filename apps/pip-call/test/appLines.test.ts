import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST as appLines } from "@/app/app/calls/[callSid]/lines/route";
import { getContext } from "@/src/callState";
import { resetConfig } from "@/src/config";
import { cleanContext, cleanLines } from "@/src/appLines";
import type { Pip4Client } from "@/src/pip4";
import { setScorerDeps } from "@/src/scorer";
import { getTimeline } from "@/src/timeline";
import { freshWorld } from "./helpers";

const SECRET = "app-secret-123";
const CALL = "app-7f3a9c";
const sent: { callId: string; lines: unknown[]; context: unknown; force?: boolean }[] = [];
const fakePip4: Pip4Client = {
  async postLines(callId, lines, context, opts) {
    sent.push({ callId, lines, context, force: opts?.force });
    const scam = lines.some((l) => /gift card/i.test(l.text));
    return { callId, lineCount: sent.length, scored: true, level: scam ? "takeover" : "calm", action: scam ? "takeover" : "none", reason: scam ? "gift cards" : undefined, latest: { risk: scam ? 0.97 : 0.05, scamType: scam ? "irs" : undefined, latencyMs: 41.7 } };
  },
};

const req = (body: unknown, secret = SECRET) =>
  new Request(`http://x/app/calls/${CALL}/lines`, { method: "POST", headers: { "content-type": "application/json", "x-pip-app-secret": secret }, body: JSON.stringify(body) });
const params = { params: Promise.resolve({ callSid: CALL }) };

beforeEach(() => {
  vi.stubEnv("PIP_APP_SECRET", SECRET);
  freshWorld();
  sent.length = 0;
  setScorerDeps({ pip4: fakePip4 });
});
afterEach(() => {
  vi.unstubAllEnvs();
  resetConfig();
});

describe("POST /app/calls/:callId/lines", () => {
  it("scores each finished user line at once and returns the verdict in the response", async () => {
    const res = await appLines(req({
      lines: [{ speaker: "user", text: "They want me to buy a Target gift card", t: 4.2, final: true }],
      listen: "user",
      context: { direction: "inbound", caller_in_contacts: false, caller_number: "5551112222", verification: "not_verified" },
    }), params);
    expect(res.status).toBe(200);
    const v = await res.json();
    expect(v).toMatchObject({ callId: CALL, level: "takeover", action: "takeover", risk: 0.97, scamType: "irs", jevMs: 42 });
    expect(typeof v.serverMs).toBe("number");
    expect(sent[0]).toMatchObject({ callId: CALL, force: true, context: { direction: "inbound", caller_in_contacts: false, caller_number: "5551112222", verification: "not_verified", listen: "user", source: "app_transcript" } });
    expect(sent[0].lines).toMatchObject([{ speaker: "user", text: "They want me to buy a Target gift card", t: 4.2, final: true }]);
    const kinds = (await getTimeline(CALL)).map((e) => e.kind);
    expect(kinds).toContain("line");
    expect(kinds).toContain("verdict");
    expect(await getContext(CALL)).toMatchObject({ source: "app_transcript", caller_in_contacts: false, listen: "user" });
  });

  it("posts partials without forcing a Jev rescore", async () => {
    const res = await appLines(req({ lines: [{ speaker: "user", text: "the numbers are four", final: false, t: 8 }] }), params);
    expect(res.status).toBe(200);
    expect(sent[0]).toMatchObject({ force: false });
    expect(sent[0].lines).toMatchObject([{ speaker: "user", text: "the numbers are four", t: 8, final: false }]);
  });

  it("needs the app secret and at least one line", async () => {
    expect((await appLines(req({ lines: [{ text: "hi" }] }, "nope"), params)).status).toBe(401);
    expect((await appLines(req({ lines: [{ text: "  " }] }), params)).status).toBe(400);
  });

  it("answers 502 when Pip4 fails", async () => {
    setScorerDeps({ pip4: { postLines: async () => { throw new Error("down"); } } });
    expect((await appLines(req({ lines: [{ text: "hello" }] }), params)).status).toBe(502);
  });
});

describe("cleaning app input", () => {
  it("maps speakers, drops empty lines, keeps only known context fields", () => {
    expect(cleanLines({ lines: [{ speaker: "mom", text: " hi " }, { text: "" }, { speaker: "caller", text: "x" }] }).map((l) => [l.speaker, l.text])).toEqual([["user", "hi"], ["caller", "x"]]);
    expect(cleanLines({ lines: [{ text: "hi", final: false }] })[0].final).toBe(false);
    expect(cleanContext({ direction: "sideways", caller_in_contacts: true, caller_id: "Priya", verification: "not_verified", caller_number: "555", evil: "x" } as never)).toEqual({
      caller_in_contacts: true,
      caller_id: "Priya",
      caller_name: "Priya",
      verification: "not_verified",
      caller_number: "555",
    });
  });
});
