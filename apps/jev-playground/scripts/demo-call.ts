// pnpm demo:call [-- --url=http://localhost:3100 --call=demo-ssa-target|all --speed=1 --no-partials --listen=user --id=my-call]
// Streams a user-only demo call (data/demo-calls.ts) to POST {url}/api/calls/:id in real time, like the on-device
// app would: each line arrives as growing partial chunks (final:false) and then the final line. Open {url}/live
// to watch. Prints the level after every chunk, which rule fired, and latencies.
// Vercel deployment protection: set VERCEL_AUTOMATION_BYPASS_SECRET (or PIP4_BYPASS_SECRET) in your shell.
import { DEMO_CALLS, type DemoCall } from "../data/demo-calls";

const arg = (name: string, def: string) => process.argv.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3) ?? def;
const url = arg("url", "http://localhost:3100").replace(/\/$/, "");
const which = arg("call", "demo-ssa-target");
const speed = Number(arg("speed", "1"));
const listen = arg("listen", "user");
const partials = !process.argv.includes("--no-partials");
const bypass = process.env.VERCEL_AUTOMATION_BYPASS_SECRET || process.env.PIP4_BYPASS_SECRET;
const headers: Record<string, string> = { "content-type": "application/json", ...(bypass ? { "x-vercel-protection-bypass": bypass } : {}) };
const sleep = (ms: number) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const WORD_S = 0.32; // speaking rate for spreading partials over a line

type Resp = {
  level: string;
  action: string;
  reason?: string;
  scored: boolean;
  lineCount: number;
  ignored: number;
  tripwire?: { rule: string; level: string } | null;
  tripwireFiredNow?: { rule: string; level: string } | null;
  latencyMs?: { tripwire: number; total: number };
  latest?: { risk?: number; latencyMs?: number; mock?: boolean; error?: string };
};

async function play(call: DemoCall, callId: string) {
  console.log(`\n▶ ${call.id} (${call.label}, expect ${call.expect}) → ${url}/live?call=${encodeURIComponent(callId)}`);
  console.log(`  ${call.title}`);
  await fetch(`${url}/api/calls/${encodeURIComponent(callId)}`, { method: "DELETE", headers }).catch(() => {});
  const t0 = Date.now();
  let first = true;
  let takeoverAt: string | null = null;
  const send = async (text: string, t: number, final: boolean) => {
    const body = { chunks: [{ speaker: "user", text, t, final }], listen, ...(first ? { context: call.context } : {}) };
    first = false;
    const r0 = performance.now();
    const res = await fetch(`${url}/api/calls/${encodeURIComponent(callId)}`, { method: "POST", headers, body: JSON.stringify(body) });
    const rtt = performance.now() - r0;
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const r = (await res.json()) as Resp;
    const fired = r.tripwireFiredNow ? ` ⚡ ${r.tripwireFiredNow.rule}→${r.tripwireFiredNow.level}` : "";
    const jev = r.scored ? ` jev risk ${r.latest?.risk?.toFixed(2)} ${r.latest?.latencyMs?.toFixed(0)}ms${r.latest?.mock ? " MOCK" : ""}${r.latest?.error ? " ERR " + r.latest.error.slice(0, 60) : ""}` : "";
    console.log(
      `  ${String(t).padStart(5)}s ${final ? "FINAL  " : "partial"} ${r.level.toUpperCase().padEnd(8)} rtt ${rtt.toFixed(0).padStart(4)}ms${jev}${fired}  "${text.length > 70 ? text.slice(0, 67) + "..." : text}"`,
    );
    if (r.level === "takeover" && !takeoverAt) takeoverAt = `${final ? "final" : "partial"} "${text}" (t=${t}s, rtt ${rtt.toFixed(0)}ms)`;
    return r;
  };
  let last: Resp | undefined;
  for (const line of call.lines) {
    const words = line.text.split(/\s+/);
    const startMs = (line.t * 1000) / speed;
    if (partials && words.length > 3) {
      for (let n = 3; n < words.length; n += 3) {
        await sleep(t0 + startMs + (n * WORD_S * 1000) / speed - Date.now());
        last = await send(words.slice(0, n).join(" "), +(line.t + n * WORD_S).toFixed(1), false);
      }
    }
    await sleep(t0 + startMs + (words.length * WORD_S * 1000) / speed - Date.now());
    last = await send(line.text, +(line.t + words.length * WORD_S).toFixed(1), true);
  }
  console.log(`  ⇒ final level ${last?.level}; ${takeoverAt ? `takeover at ${takeoverAt}` : "no takeover"}; reason: ${last?.reason}`);
  return last;
}

async function main() {
  const calls = which === "all" ? DEMO_CALLS : DEMO_CALLS.filter((c) => c.id === which);
  if (!calls.length) throw new Error(`unknown --call=${which}; one of: all, ${DEMO_CALLS.map((c) => c.id).join(", ")}`);
  const stamp = Date.now().toString(36).slice(-4);
  for (const c of calls) await play(c, arg("id", `${c.id}-${stamp}`));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
