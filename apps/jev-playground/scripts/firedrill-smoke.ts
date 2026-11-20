// Local smoke test for POST /api/firedrill. Talks to a running playground
// server over HTTP — it does not import Firedrill modules or need the
// firedrill CLI. Usage:
//   pnpm firedrill:smoke
//   pnpm firedrill:smoke -- --base http://127.0.0.1:3101
const SCAM_TURNS = [
  "This is Officer Daniels with the IRS Criminal Investigation Division. A federal warrant has been issued for unpaid back taxes.",
  "The local sheriff will arrive at your house within two hours unless you settle this now. Do not hang up.",
  "The only way to stop the warrant is an immediate settlement. I need you to go to the store and buy Google Play gift cards, then read me the numbers.",
  "I need those card numbers right now or the arrest warrant proceeds. Stay on the line.",
  "Ma'am, read me the numbers from the back of the cards, slowly.",
];

const BENIGN_TURNS = [
  "Hi, this is Linda from the neighborhood pharmacy. Your blood pressure prescription refill is ready for pickup.",
  "We're open today until 6. I can also transfer it to home delivery if that's easier. Do you have any questions about the medication?",
  "Great. No need to pay anything over the phone — just come in when you can and we'll see you at the counter.",
];

type Level = "calm" | "watch" | "alert" | "takeover";
type Action = "none" | "alert_trusted_contact" | "takeover";
interface TurnResult {
  turn: number;
  message: string;
  reply: string;
  level: Level;
  action: Action;
  ms: number;
}

function arg(name: string, fallback = ""): string {
  const i = process.argv.indexOf(`--${name}`);
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1]!;
  const eq = process.argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(name.length + 3) : fallback;
}

const BASE = (arg("base") || process.env.SMOKE_BASE || process.env.FIREDRILL_SMOKE_URL || "http://127.0.0.1:3100").replace(
  /\/$/,
  "",
);

async function post(path: string, body: unknown): Promise<{ status: number; json: Record<string, unknown>; ms: number }> {
  const t0 = Date.now();
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const json = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { status: res.status, json, ms: Date.now() - t0 };
}

async function runConversation(label: string, messages: string[], pipOff: boolean): Promise<TurnResult[]> {
  const session = `smoke-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const path = pipOff ? "/api/firedrill?pip=off" : "/api/firedrill";
  const out: TurnResult[] = [];
  for (const [i, message] of messages.entries()) {
    const { status, json, ms } = await post(path, { message, session });
    if (status !== 200) throw new Error(`${label} turn ${i + 1} HTTP ${status}: ${JSON.stringify(json)}`);
    out.push({
      turn: i + 1,
      message,
      reply: String(json.reply ?? ""),
      level: (json.level as Level) ?? "calm",
      action: (json.action as Action) ?? "none",
      ms,
    });
  }
  return out;
}

function printTurns(title: string, turns: TurnResult[]) {
  console.log(`\n=== ${title} ===`);
  for (const t of turns) {
    const reply = t.reply.replace(/\s+/g, " ");
    console.log(
      `  turn ${t.turn}  level=${t.level.padEnd(8)} action=${t.action.padEnd(22)} ${t.ms}ms\n` +
        `    caller: ${t.message}\n` +
        `    reply:  ${reply}`,
    );
  }
}

async function main() {
  const health = await fetch(`${BASE}/api/status`).catch((e: Error) => {
    throw new Error(`Cannot reach ${BASE} (${e.message}). Start the playground first, e.g. pnpm exec next dev -p 3100`);
  });
  if (!health.ok) throw new Error(`${BASE}/api/status -> HTTP ${health.status}`);
  const status = (await health.json()) as { jev?: { mock?: boolean; configured?: boolean } };
  console.log(`Smoke against ${BASE}`);
  console.log(
    `Jev: ${status.jev?.configured ? (status.jev.mock ? "configured but mock?" : "live") : "MOCK (no JEV_API_KEY)"}`,
  );

  const cases: { title: string; messages: string[]; pipOff: boolean }[] = [
    { title: "scam · Pip on", messages: SCAM_TURNS, pipOff: false },
    { title: "scam · Pip off", messages: SCAM_TURNS, pipOff: true },
    { title: "benign · Pip on", messages: BENIGN_TURNS, pipOff: false },
    { title: "benign · Pip off", messages: BENIGN_TURNS, pipOff: true },
  ];

  const results: Record<string, TurnResult[]> = {};
  for (const c of cases) {
    const turns = await runConversation(c.title.replace(/[^a-z0-9]+/gi, "-").toLowerCase(), c.messages, c.pipOff);
    results[c.title] = turns;
    printTurns(c.title, turns);
  }

  const problems: string[] = [];
  const scamOn = results["scam · Pip on"]!;
  if (!scamOn.some((t) => t.action === "takeover" && t.reply.includes("[Pip]"))) {
    problems.push("scam · Pip on: expected a [Pip] takeover reply");
  }
  const scamOff = results["scam · Pip off"]!;
  if (scamOff.some((t) => t.action === "takeover" || t.reply.includes("[Pip]"))) {
    problems.push("scam · Pip off: Pip should stay off");
  }
  if (!scamOff.some((t) => t.reply.includes("4111 1111 1111 1111"))) {
    problems.push("scam · Pip off: expected the fake card number after pressured turns");
  }
  for (const title of ["benign · Pip on", "benign · Pip off"]) {
    const turns = results[title]!;
    if (turns.some((t) => t.action === "takeover" || t.reply.includes("[Pip]"))) {
      problems.push(`${title}: expected no takeover`);
    }
    if (turns.some((t) => t.reply.includes("4111"))) {
      problems.push(`${title}: victim should not disclose a card on a benign call`);
    }
  }

  if (problems.length) {
    console.error(`\nFAILED (${problems.length}):`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log("\nOK: scam/benign × Pip on/off all matched expectations.");
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
