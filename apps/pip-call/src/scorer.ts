// onLine: queue each final line and send queued lines to Pip4 in SequenceId order.
//
// Webhooks for one call may land on different instances at the same time, so a short per-call
// lock (SET NX PX) makes one of them the sender: it drains the queue line by line, then releases
// the lock and re-checks for lines that arrived meanwhile. Pip4 therefore sees each line once,
// in order. A failed Pip4 request is logged and the sender moves on to the next line.
import { callKey, getContext } from "./callState";
import type { Line, OnLine } from "./lines";
import { HttpPip4Client, type Pip4Client, type Pip4Response } from "./pip4";
import { DEFAULT_TTL_MS, getStore } from "./store";
import { logEvent } from "./timeline";
import { takeOver } from "./takeover";

const LOCK_TTL_MS = 20_000;

/** a final line, or a request to score whatever Pip4 has not scored yet (sent when transcription stops) */
type QueueItem = Line | { flush: true; seq: number };

export interface ScorerDeps {
  pip4: Pip4Client;
  onVerdict: (callSid: string, r: Pip4Response) => Promise<void>;
}

export async function defaultOnVerdict(callSid: string, r: Pip4Response) {
  if (!r.scored) return;
  await logEvent(callSid, "verdict", { level: r.level, action: r.action, reason: r.reason, lineCount: r.lineCount });
  if (r.action === "takeover") await takeOver(callSid, r.reason);
}

let deps: ScorerDeps | undefined;
export const getDeps = () => (deps ??= { pip4: new HttpPip4Client(), onVerdict: defaultOnVerdict });
export function setScorerDeps(d: Partial<ScorerDeps>) {
  deps = { ...getDeps(), ...d };
}

export async function drain(callSid: string) {
  const store = getStore();
  const lockKey = callKey(callSid, "scorelock");
  const queueKey = callKey(callSid, "queue");
  for (;;) {
    const token = `${process.pid}-${Math.random().toString(36).slice(2)}`;
    if (!(await store.setNX(lockKey, token, LOCK_TTL_MS))) return; // someone else is sending
    try {
      for (;;) {
        const items = await store.zpopAll(queueKey);
        if (!items.length) break;
        for (const { member } of items) {
          const item = JSON.parse(member) as QueueItem;
          const { pip4, onVerdict } = getDeps();
          try {
            const ctx = await getContext(callSid);
            const pendingKey = callKey(callSid, "unscored");
            if ("flush" in item && (await store.get(pendingKey)) !== "1") continue; // Pip4 already scored every line
            const r = "flush" in item ? await pip4.postLines(callSid, [], ctx, { force: true }) : await pip4.postLines(callSid, [item], ctx);
            await store.set(pendingKey, r.scored ? "0" : "1", DEFAULT_TTL_MS);
            await onVerdict(callSid, r);
          } catch {
            // already on the timeline as pip4_error; keep going so later lines still reach Pip4
          }
        }
      }
    } finally {
      await store.delIfEquals(lockKey, token);
    }
    if ((await store.zcard(queueKey)) === 0) return;
  }
}

export const onLine: OnLine = async (callSid, line) => {
  await getStore().zadd(callKey(callSid, "queue"), line.seq, JSON.stringify(line), DEFAULT_TTL_MS);
  await drain(callSid);
};

/** Transcription stopped (call ended): make Pip4 score any trailing lines it was holding for scoreEvery. */
export async function flush(callSid: string, seq: number) {
  await getStore().zadd(callKey(callSid, "queue"), seq, JSON.stringify({ flush: true, seq }), DEFAULT_TTL_MS);
  await drain(callSid);
}
