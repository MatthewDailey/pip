// Tiny key-value store with the handful of Redis commands pip-call needs.
// On Vercel every webhook can land on a different instance, so call state lives in Upstash Redis
// (KV_REST_API_URL / KV_REST_API_TOKEN). Without them (unit tests, the simulator, local dev) an
// in-memory store with the same semantics is used.
import { Redis } from "@upstash/redis";

export interface Store {
  /** SET key value NX PX ttl → true if the key was set */
  setNX(key: string, value: string, ttlMs: number): Promise<boolean>;
  get(key: string): Promise<string | null>;
  set(key: string, value: string, ttlMs?: number): Promise<void>;
  del(key: string): Promise<void>;
  /** delete key only if it still holds value (safe lock release) */
  delIfEquals(key: string, value: string): Promise<boolean>;
  rpush(key: string, value: string, ttlMs?: number): Promise<void>;
  lrange(key: string, start: number, stop: number): Promise<string[]>;
  /** LPUSH + LTRIM to keep a capped most-recent-first list */
  lpushCapped(key: string, value: string, cap: number): Promise<void>;
  zadd(key: string, score: number, member: string, ttlMs?: number): Promise<void>;
  /** remove and return every member, lowest score first */
  zpopAll(key: string): Promise<{ member: string; score: number }[]>;
  zcard(key: string): Promise<number>;
}

const DAY_MS = 24 * 3600 * 1000;
export const DEFAULT_TTL_MS = DAY_MS;

export class MemoryStore implements Store {
  private kv = new Map<string, { v: unknown; exp?: number }>();
  private live<T>(key: string): T | undefined {
    const e = this.kv.get(key);
    if (!e) return undefined;
    if (e.exp && e.exp < Date.now()) {
      this.kv.delete(key);
      return undefined;
    }
    return e.v as T;
  }
  private put(key: string, v: unknown, ttlMs?: number) {
    const prev = this.kv.get(key);
    this.kv.set(key, { v, exp: ttlMs ? Date.now() + ttlMs : prev?.exp });
  }
  async setNX(key: string, value: string, ttlMs: number) {
    if (this.live(key) !== undefined) return false;
    this.put(key, value, ttlMs);
    return true;
  }
  async get(key: string) {
    const v = this.live<unknown>(key);
    return typeof v === "string" ? v : null;
  }
  async set(key: string, value: string, ttlMs?: number) {
    this.kv.set(key, { v: value, exp: ttlMs ? Date.now() + ttlMs : undefined });
  }
  async del(key: string) {
    this.kv.delete(key);
  }
  async delIfEquals(key: string, value: string) {
    if (this.live(key) !== value) return false;
    this.kv.delete(key);
    return true;
  }
  async rpush(key: string, value: string, ttlMs?: number) {
    const l = this.live<string[]>(key) ?? [];
    l.push(value);
    this.put(key, l, ttlMs);
  }
  async lrange(key: string, start: number, stop: number) {
    const l = this.live<string[]>(key) ?? [];
    return l.slice(start, stop === -1 ? undefined : stop + 1);
  }
  async lpushCapped(key: string, value: string, cap: number) {
    const l = this.live<string[]>(key) ?? [];
    l.unshift(value);
    this.put(key, l.slice(0, cap));
  }
  async zadd(key: string, score: number, member: string, ttlMs?: number) {
    const z = this.live<Map<string, number>>(key) ?? new Map();
    z.set(member, score);
    this.put(key, z, ttlMs);
  }
  async zpopAll(key: string) {
    const z = this.live<Map<string, number>>(key);
    this.kv.delete(key);
    if (!z) return [];
    return [...z.entries()].map(([member, score]) => ({ member, score })).sort((a, b) => a.score - b.score);
  }
  async zcard(key: string) {
    return this.live<Map<string, number>>(key)?.size ?? 0;
  }
}

export class RedisStore implements Store {
  constructor(private r: Redis) {}
  async setNX(key: string, value: string, ttlMs: number) {
    return (await this.r.set(key, value, { nx: true, px: ttlMs })) === "OK";
  }
  async get(key: string) {
    const v = await this.r.get<unknown>(key);
    return v == null ? null : typeof v === "string" ? v : JSON.stringify(v);
  }
  async set(key: string, value: string, ttlMs?: number) {
    if (ttlMs) await this.r.set(key, value, { px: ttlMs });
    else await this.r.set(key, value);
  }
  async del(key: string) {
    await this.r.del(key);
  }
  async delIfEquals(key: string, value: string) {
    const n = await this.r.eval<[string], number>(
      'if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("del", KEYS[1]) else return 0 end',
      [key],
      [value],
    );
    return n === 1;
  }
  async rpush(key: string, value: string, ttlMs?: number) {
    const p = this.r.pipeline().rpush(key, value);
    if (ttlMs) p.pexpire(key, ttlMs);
    await p.exec();
  }
  async lrange(key: string, start: number, stop: number) {
    const l = await this.r.lrange<unknown>(key, start, stop);
    return l.map((v) => (typeof v === "string" ? v : JSON.stringify(v)));
  }
  async lpushCapped(key: string, value: string, cap: number) {
    await this.r.pipeline().lpush(key, value).ltrim(key, 0, cap - 1).exec();
  }
  async zadd(key: string, score: number, member: string, ttlMs?: number) {
    const p = this.r.pipeline().zadd(key, { score, member });
    if (ttlMs) p.pexpire(key, ttlMs);
    await p.exec();
  }
  async zpopAll(key: string) {
    // MULTI so a member added between the read and the delete is never lost
    const [items] = (await this.r.multi().zrange(key, 0, -1, { withScores: true }).del(key).exec()) as [unknown[], number];
    const out: { member: string; score: number }[] = [];
    for (let i = 0; i < items.length; i += 2) {
      const m = items[i];
      out.push({ member: typeof m === "string" ? m : JSON.stringify(m), score: Number(items[i + 1]) });
    }
    return out;
  }
  async zcard(key: string) {
    return this.r.zcard(key);
  }
}

const g = globalThis as unknown as { __pipStore?: Store };

export function getStore(): Store {
  if (g.__pipStore) return g.__pipStore;
  const url = process.env.KV_REST_API_URL;
  const token = process.env.KV_REST_API_TOKEN;
  g.__pipStore = url && token ? new RedisStore(new Redis({ url, token, automaticDeserialization: false })) : new MemoryStore();
  return g.__pipStore;
}

/** tests swap in a fresh store */
export function setStore(s: Store) {
  g.__pipStore = s;
}

export const storeKind = () => (process.env.KV_REST_API_URL && process.env.KV_REST_API_TOKEN ? "redis" : "memory");
