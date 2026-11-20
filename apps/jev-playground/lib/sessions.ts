// Live call sessions for the /api/calls/[callId] endpoint (Pip3 / pip-call pushes transcript lines here).
// With KV_REST_API_URL + KV_REST_API_TOKEN set (an Upstash Redis store attached in Vercel) sessions live
// in Redis, so any serverless instance can serve any call. Without them they stay in this process's
// memory, which keeps the playground and `pnpm eval` working with no setup.
// Pip3 sends one call's lines strictly one request at a time, so read-modify-write per call is safe.
import { Redis } from "@upstash/redis";
import { DEFAULT_POLICY, initialPolicyState, stepPolicy, type PolicyConfig, type PolicyState } from "./policy";
import type { CallContext, DetectorResult, TranscriptLine } from "./types";

export interface CallSession {
  callId: string;
  createdAt: number;
  updatedAt: number;
  context: CallContext;
  lines: TranscriptLine[];
  policy: PolicyState;
  config: PolicyConfig;
  history: { at: number; lineCount: number; result: DetectorResult; level: PolicyState["level"]; smoothedRisk: number }[];
  linesSinceScore: number;
}

export interface SessionSummary {
  callId: string;
  createdAt: number;
  updatedAt: number;
  lineCount: number;
  level: PolicyState["level"];
}

const TTL_S = 24 * 3600;
const key = (id: string) => `jev:session:${id}`;
const INDEX = "jev:sessions";

const g = globalThis as unknown as { __pipSessions?: Map<string, CallSession>; __pipRedis?: Redis | null };
const memory = (g.__pipSessions ??= new Map());

function redis(): Redis | null {
  if (g.__pipRedis !== undefined) return g.__pipRedis;
  const url = process.env.KV_REST_API_URL;
  const token = process.env.KV_REST_API_TOKEN;
  g.__pipRedis = url && token ? new Redis({ url, token }) : null;
  return g.__pipRedis;
}

export const sessionStoreKind = () => (redis() ? "redis" : "memory");

function newSession(callId: string): CallSession {
  const now = Date.now();
  return {
    callId,
    createdAt: now,
    updatedAt: now,
    context: {},
    lines: [],
    policy: initialPolicyState(),
    config: DEFAULT_POLICY,
    history: [],
    linesSinceScore: 0,
  };
}

export async function getSession(callId: string, create = true): Promise<CallSession | undefined> {
  const r = redis();
  let s = r ? ((await r.get<CallSession>(key(callId))) ?? undefined) : memory.get(callId);
  if (!s && create) {
    s = newSession(callId);
    if (!r) memory.set(callId, s);
  }
  return s;
}

/** Persist after changing a session (a no-op for the in-memory store, where the object is live). */
export async function saveSession(s: CallSession) {
  s.updatedAt = Date.now();
  const r = redis();
  if (!r) {
    memory.set(s.callId, s);
    return;
  }
  // detector raw payloads are large and only useful while debugging locally
  const slim = { ...s, history: s.history.map((h) => ({ ...h, result: { ...h.result, raw: undefined } })) };
  await r.pipeline().set(key(s.callId), slim, { ex: TTL_S }).zadd(INDEX, { score: s.updatedAt, member: s.callId }).exec();
}

export async function deleteSession(callId: string) {
  const r = redis();
  if (!r) return memory.delete(callId);
  const [n] = await r.pipeline().del(key(callId)).zrem(INDEX, callId).exec<[number, number]>();
  return n > 0;
}

/** Most recently active first. */
export async function listSessions(limit = 20): Promise<SessionSummary[]> {
  const summary = (s: CallSession): SessionSummary => ({
    callId: s.callId,
    createdAt: s.createdAt,
    updatedAt: s.updatedAt ?? s.createdAt,
    lineCount: s.lines.length,
    level: s.policy.level,
  });
  const r = redis();
  if (!r) return [...memory.values()].map(summary).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, limit);
  const ids = await r.zrange<string[]>(INDEX, 0, limit - 1, { rev: true });
  if (!ids.length) return [];
  const sessions = await r.mget<(CallSession | null)[]>(...ids.map(key));
  const live = sessions.filter((s): s is CallSession => !!s);
  const gone = ids.filter((_, i) => !sessions[i]);
  if (gone.length) await r.zrem(INDEX, ...gone); // expired
  return live.map(summary);
}

export function recordResult(s: CallSession, result: DetectorResult) {
  if (!result.error && !result.skipped) s.policy = stepPolicy(s.policy, result, s.config);
  s.history.push({ at: Date.now(), lineCount: s.lines.length, result, level: s.policy.level, smoothedRisk: s.policy.smoothed.risk });
  s.linesSinceScore = 0;
}
