// pnpm tripwire:check [-- --url=http://localhost:3100]
// End-to-end checks of the demo tripwire against a running server (partial chunks only, user-only listening).
const url = (process.argv.find((a) => a.startsWith("--url="))?.slice(6) ?? "http://localhost:3100").replace(/\/$/, "");
const ctx = { direction: "inbound", caller_number: "+12025550147", caller_in_contacts: false };

type Step = { text: string; final?: boolean };
const CASES: { name: string; steps: Step[]; expect: (level: string, rule?: string) => boolean; want: string }[] = [
  {
    name: "gift card only in a partial, then a digits partial",
    steps: [{ text: "okay I'm buying the Target gift", final: false }, { text: "okay I'm buying the Target gift cards now", final: false }, { text: "it's four one one one", final: false }],
    expect: (l, r) => l === "takeover" && r === "gift_card_readout",
    want: "takeover via gift_card_readout",
  },
  {
    name: "benign digits partial without any gift card mention",
    steps: [{ text: "my zip code is", final: false }, { text: "my zip code is oh two one three nine", final: false }, { text: "my zip code is oh two one three nine", final: true }],
    expect: (l) => l !== "takeover",
    want: "no takeover",
  },
];

async function runChecks() {
  let failed = 0;
  for (const [i, c] of CASES.entries()) {
    const id = `tw-check-${Date.now().toString(36)}-${i}`;
    let last: { level: string; tripwire?: { rule: string } | null } = { level: "?" };
    for (const [k, st] of c.steps.entries()) {
      const body = { listen: "user", scoreEvery: 99, ...(k === 0 ? { context: ctx } : {}), chunks: [{ speaker: "user", text: st.text, final: st.final ?? false }] };
      const res = await fetch(`${url}/api/calls/${id}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
      last = await res.json();
    }
    await fetch(`${url}/api/calls/${id}`, { method: "DELETE" });
    const ok = c.expect(last.level, last.tripwire?.rule);
    if (!ok) failed++;
    console.log(`${ok ? "PASS" : "FAIL"}  ${c.name}: level=${last.level} rule=${last.tripwire?.rule ?? "-"} (want ${c.want})`);
  }
  if (failed) process.exit(1);
}
runChecks().catch((e) => {
  console.error(e);
  process.exit(1);
});
export {};
