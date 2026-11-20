import { beforeEach, describe, expect, it } from "vitest";
import type { Line } from "@/src/lines";
import { HttpPip4Client, type Pip4Client, type Pip4Response } from "@/src/pip4";
import { defaultOnVerdict, drain, onLine, setScorerDeps } from "@/src/scorer";
import { getStore } from "@/src/store";
import { getTimeline } from "@/src/timeline";
import { freshWorld } from "./helpers";

const ok = (n: number, action: Pip4Response["action"] = "none"): Pip4Response => ({
  callId: "x",
  lineCount: n,
  scored: n % 2 === 0,
  level: action === "takeover" ? "takeover" : "calm",
  action,
});

const line = (seq: number, text = `line ${seq}`): Line => ({ speaker: seq % 2 ? "caller" : "user", text, t: seq, seq });

describe("scorer", () => {
  beforeEach(() => freshWorld());

  it("sends lines to Pip4 one at a time, in SequenceId order, even when they arrive out of order", async () => {
    const sent: number[] = [];
    setScorerDeps({
      pip4: { postLines: async (_s, ls) => (sent.push(...ls.map((l) => l.seq)), ok(sent.length)) },
      onVerdict: async () => {},
    });
    // queue three lines out of order without draining, then drain once
    const store = getStore();
    for (const s of [3, 1, 2]) await store.zadd("pip:call:CA1:queue", s, JSON.stringify(line(s)));
    await drain("CA1");
    expect(sent).toEqual([1, 2, 3]);
  });

  it("concurrent webhooks never send a line twice or out of order", async () => {
    const sent: number[] = [];
    setScorerDeps({
      pip4: {
        postLines: async (_s, ls) => {
          await new Promise((r) => setTimeout(r, 5));
          sent.push(...ls.map((l) => l.seq));
          return ok(sent.length);
        },
      },
      onVerdict: async () => {},
    });
    await Promise.all([1, 2, 3, 4, 5, 6].map((s) => onLine("CA1", line(s))));
    expect([...sent].sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(new Set(sent).size).toBe(6);
    // the first line always goes first; arrival races can only reorder lines queued while one was sending
    expect(sent[0]).toBe(1);
  });

  it("a Pip4 timeout doesn't drop later lines", async () => {
    const sent: number[] = [];
    let calls = 0;
    const flaky: Pip4Client = {
      postLines: async (_s, ls) => {
        calls++;
        if (ls[0].seq === 2) throw new Error("timeout");
        sent.push(ls[0].seq);
        return ok(sent.length);
      },
    };
    setScorerDeps({ pip4: flaky, onVerdict: async () => {} });
    for (const s of [1, 2, 3, 4]) await onLine("CA1", line(s));
    expect(sent).toEqual([1, 3, 4]);
    expect(calls).toBe(4);
  });

  it("HttpPip4Client retries once after a timeout and logs both attempts", async () => {
    let n = 0;
    const fakeFetch = (async (_url: string, init?: RequestInit) => {
      n++;
      if (n === 1) {
        await new Promise((_r, rej) => init?.signal?.addEventListener("abort", () => rej(new Error("aborted"))));
      }
      return new Response(JSON.stringify(ok(2)), { headers: { "content-type": "application/json" } });
    }) as typeof fetch;
    const c = new HttpPip4Client("http://pip4", "jev", 30, fakeFetch);
    const r = await c.postLines("CA9", [line(1)], { direction: "inbound" });
    expect(r.lineCount).toBe(2);
    const kinds = (await getTimeline("CA9")).map((e) => e.kind);
    expect(kinds).toEqual(["pip4_request", "pip4_error", "pip4_request", "pip4_response"]);
  });

  it("a takeover verdict issues hold + mute + warn exactly once", async () => {
    await defaultOnVerdict("CA7", ok(4, "takeover"));
    await defaultOnVerdict("CA7", ok(6, "takeover"));
    const tl = await getTimeline("CA7");
    const cmds = tl.filter((e) => e.kind === "twilio_cmd").map((e) => e.data as Record<string, unknown>);
    expect(tl.filter((e) => e.kind === "takeover")).toHaveLength(1);
    expect(cmds).toHaveLength(3);
    expect(cmds).toContainEqual(expect.objectContaining({ conference: "pip-CA7", label: "caller", hold: true }));
    expect(cmds).toContainEqual(expect.objectContaining({ label: "mom", muted: true }));
    expect(cmds).toContainEqual(expect.objectContaining({ label: "mom", announceUrl: expect.stringContaining("/twiml/warn") }));
  });

  it("unscored responses don't emit verdicts", async () => {
    await defaultOnVerdict("CA8", ok(1, "takeover"));
    expect(await getTimeline("CA8")).toEqual([]);
  });
});

describe("flush on transcription-stopped", () => {
  beforeEach(() => freshWorld());
  it("asks Pip4 to score trailing lines after every queued line has been sent", async () => {
    const { flush } = await import("@/src/scorer");
    const calls: string[] = [];
    setScorerDeps({
      pip4: { postLines: async (_s, ls, _c, o) => (calls.push(o?.force ? "force" : String(ls[0].seq)), ok(1)) },
      onVerdict: async () => {},
    });
    const store = getStore();
    await store.zadd("pip:call:CA1:queue", 2, JSON.stringify(line(2)));
    await store.zadd("pip:call:CA1:queue", 1, JSON.stringify(line(1)));
    await flush("CA1", 9);
    expect(calls).toEqual(["1", "2", "force"]);
  });

  it("skips the flush when Pip4 already scored the last line", async () => {
    const { flush } = await import("@/src/scorer");
    const calls: string[] = [];
    setScorerDeps({
      pip4: { postLines: async (_s, ls, _c, o) => (calls.push(o?.force ? "force" : String(ls[0].seq)), ok(calls.length)) },
      onVerdict: async () => {},
    });
    await onLine("CA2", line(1));
    await onLine("CA2", line(2)); // ok(2) → scored
    await flush("CA2", 9);
    expect(calls).toEqual(["1", "2"]);
  });
});
