// Live-call ingestion: the on-device app (or pip-call) pushes transcript chunks here as the call happens.
//
// POST /api/calls/:callId
//   {
//     chunks: [{ speaker?: "user"|"caller", text, t?: seconds, final?: boolean (default true) }],
//     context?: { caller_number?, caller_name?, caller_in_contacts?, direction?, number_type?, call_duration_s?,
//                 local_time?, user_profile?, ... },   // merged into the session's context
//     listen?: "user" | "both",   // default PIP4_LISTEN env, else "both". Sticky per call once set.
//                                 // The on-device app (her voice only) must send "user".
//     scoreEvery?: number,        // Jev re-scores after this many new FINAL lines (default 1 user-only, 2 both)
//     force?: boolean, approach?: "jev"|"keyword"|"llm"|"tj"
//   }
//   listen="user": only the protected user's own voice is transcribed; caller chunks are dropped (counted in
//   `ignored`), chunks without a speaker are the user. Partial chunks (final:false) are NOT stored or sent to
//   Jev, but the demo tripwire (lib/tripwire.ts) checks every chunk, partial or final, before Jev, so it can
//   fire mid-sentence. Tripwire levels are sticky floors on the policy level.
//   Returns { level: calm|watch|alert|takeover, action: none|alert_trusted_contact|takeover, reason, tripwire, latest, ... }
// GET    /api/calls/:callId  → full session (lines, history, partial, tripwire)
// DELETE /api/calls/:callId  → drop the session
// Sessions live in Upstash Redis when KV_REST_API_URL is set, else in memory (lib/sessions.ts).
import { NextResponse } from "next/server";
import { enrichContext } from "@/lib/callMeta";
import { getDetectors } from "@/lib/detectors";
import { defaultListen, parseListen, type ListenMode } from "@/lib/listen";
import { levelRank, type Level } from "@/lib/policy";
import { deleteSession, getSession, recordResult, saveSession, type CallSession } from "@/lib/sessions";
import { checkTripwire, mentionsGiftCard, raisePolicy, tripwireEnabled, type TripwireHit } from "@/lib/tripwire";
import type { ApproachId, CallContext } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ callId: string }> };

const ACTION = { calm: "none", watch: "none", alert: "alert_trusted_contact", takeover: "takeover" } as const;

type Chunk = { speaker?: string; text?: string; t?: number; final?: boolean };

/** Extra per-call fields stored alongside Stuart's CallSession (persisted as part of the same JSON). */
export type LiveSession = CallSession & {
  listen?: ListenMode;
  /** the in-progress (non-final) user utterance, shown on /live */
  partial?: { text: string; t: number } | null;
  /** strongest tripwire hit so far (sticky) */
  tripwire?: (TripwireHit & { atLine: number; at: number; partial: boolean }) | null;
  ignoredChunks?: number;
  /** first gift-card mention in ANY user chunk (partial or final), so the tripwire works even if finals lag or never repeat it */
  giftMention?: { text: string; t: number; partial: boolean } | null;
};

export async function POST(req: Request, { params }: Ctx) {
  const t0 = performance.now();
  const { callId } = await params;
  const body = (await req.json().catch(() => null)) as {
    chunks?: Chunk[];
    context?: CallContext;
    listen?: string;
    scoreEvery?: number;
    force?: boolean;
    approach?: ApproachId;
  } | null;
  if (!body) return NextResponse.json({ error: "JSON body required" }, { status: 400 });
  const s = (await getSession(callId))! as LiveSession;
  if (body.context) s.context = { ...s.context, ...body.context };
  s.listen = parseListen(body.listen) ?? s.listen ?? defaultListen();
  const listen = s.listen;

  let ignored = 0;
  let firedNow: TripwireHit | null = null;
  const nowT = (Date.now() - s.createdAt) / 1000;
  for (const ch of body.chunks ?? []) {
    if (!ch?.text) continue;
    const text = String(ch.text);
    const speaker = ch.speaker === "caller" ? "caller" : ch.speaker === "user" || listen === "user" ? "user" : "caller";
    if (listen === "user" && speaker === "caller") {
      ignored++;
      continue;
    }
    const t = typeof ch.t === "number" ? ch.t : nowT;
    const final = ch.final !== false;
    if (final) {
      s.lines.push({ speaker, text, t });
      s.linesSinceScore++;
      s.partial = null;
    } else if (speaker === "user") {
      s.partial = { text, t };
    }
    // tripwire: every user chunk (partial or final), before and independent of Jev
    if (speaker === "user") {
      if (!s.giftMention && mentionsGiftCard(text)) s.giftMention = { text: text.slice(0, 200), t, partial: !final };
      const userTexts = s.lines.filter((l) => l.speaker === "user").map((l) => l.text);
      if (s.giftMention && !userTexts.some(mentionsGiftCard)) userTexts.unshift(s.giftMention.text);
      const hit = checkTripwire(userTexts, text, enrichContext(s.context));
      if (hit && (!s.tripwire || levelRank(hit.level) > levelRank(s.tripwire.level))) {
        s.tripwire = { ...hit, atLine: s.lines.length, at: Date.now(), partial: !final };
        firedNow = hit;
      }
    }
  }
  s.ignoredChunks = (s.ignoredChunks ?? 0) + ignored;
  const applyTripwire = () => {
    if (s.tripwire) s.policy = raisePolicy(s.policy, s.tripwire.level, `Tripwire ${s.tripwire.rule}: ${s.tripwire.reason}`);
  };
  applyTripwire();
  const tripwireMs = performance.now() - t0;

  const every = Math.max(1, body.scoreEvery ?? (listen === "user" ? 1 : 2));
  let scored = false;
  // once taken over (sticky) there is nothing left for Jev to decide; skip the round-trip
  if (s.lines.length && s.policy.level !== "takeover" && (body.force || s.linesSinceScore >= every)) {
    const lastT = s.lines[s.lines.length - 1].t ?? nowT;
    const context = enrichContext(s.context, { call_duration_s: lastT });
    const detector = getDetectors()[body.approach ?? "jev"];
    const result = await detector.analyze({ lines: s.lines, context, listen });
    const prevMax = s.policy.maxLevel;
    recordResult(s, result);
    // optional demo knob: cap how far the model alone can escalate (e.g. PIP4_JEV_MAX_LEVEL=alert so the
    // takeover lands exactly on the tripwire moment). Unset = no cap.
    const cap = process.env.PIP4_JEV_MAX_LEVEL as Level | undefined;
    if (cap && levelRank(cap) >= 0 && levelRank(s.policy.level) > levelRank(cap)) {
      const maxLevel = levelRank(prevMax) > levelRank(cap) ? prevMax : cap;
      s.policy = { ...s.policy, level: cap, maxLevel, reason: `${s.policy.reason} (model capped at ${cap} by PIP4_JEV_MAX_LEVEL)` };
    }
    applyTripwire(); // the tripwire level is a floor Jev's smoothing cannot lower
    scored = true;
  }
  await saveSession(s);
  const latest = s.history[s.history.length - 1]?.result;
  return NextResponse.json({
    callId,
    listen,
    lineCount: s.lines.length,
    ignored,
    scored,
    level: s.policy.level,
    action: ACTION[s.policy.level],
    reason: s.policy.reason,
    smoothed: s.policy.smoothed,
    tripwire: s.tripwire ?? null,
    tripwireFiredNow: firedNow ? { rule: firedNow.rule, level: firedNow.level } : null,
    tripwireEnabled: tripwireEnabled(),
    latencyMs: { tripwire: +tripwireMs.toFixed(2), total: +(performance.now() - t0).toFixed(1) },
    latest: latest && {
      approach: latest.approach,
      label: latest.label,
      risk: latest.risk,
      signals: latest.signals,
      scamType: latest.scamType,
      latencyMs: latest.latencyMs,
      mock: latest.mock,
      error: latest.error,
    },
  });
}

export async function GET(_req: Request, { params }: Ctx) {
  const { callId } = await params;
  const s = await getSession(callId, false);
  if (!s) return NextResponse.json({ error: "unknown callId" }, { status: 404 });
  return NextResponse.json(s);
}

export async function DELETE(_req: Request, { params }: Ctx) {
  const { callId } = await params;
  return NextResponse.json({ deleted: await deleteSession(callId) });
}
