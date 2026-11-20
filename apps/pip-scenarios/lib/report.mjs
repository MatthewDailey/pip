// Turns what Pip logged during a scenario into one expected-vs-actual line.
//
// Today's app has one reaction: Guard.warn, which shows an urgent speech bubble and posts a "This may be a scam"
// heads-up when a signal scores at or over the threshold, at most once a minute per source key. There is no level,
// reason or action in that warning yet; the re-skin needs a structured event (level, source, reason, one action).

const THRESHOLD = 5;
const THROTTLE_MS = 60_000;
const lastWarn = new Map();

/** Guard.report keys warnings by "$source:$app"; transcripts by call. Mirrors that to spot throttled warnings. */
function warnKey(e, script) {
  if (e.source === "TRANSCRIPT") return e.title === "Call speech" ? "call:speech" : `sim:mock-${script}`;
  if (e.source === "CALL_SCREEN") return `screen:${e.detail.match(/number=([^,]*)/)?.[1] ?? ""}`;
  return `${e.source}:${e.app ?? "null"}`;
}

function callRisk(e) {
  return Number(e.detail?.match(/\[call so far: risk (\d+)\]/)?.[1] ?? e.score ?? 0);
}

export function assess(scenario, events, logs) {
  const scored = events.filter((e) => e.score > 0 || callRisk(e) > 0);
  const maxScore = Math.max(0, ...events.map(callRisk));
  const rules = [...new Set(scored.flatMap((e) => e.rules ?? []))];
  const script = scenario.steps.find((s) => s.do === "mock-call")?.script;
  const warned = [];
  let throttled = 0;
  for (const e of events) {
    if (callRisk(e) < THRESHOLD) continue;
    const key = warnKey(e, script);
    if ((lastWarn.get(key) ?? 0) > e.time - THROTTLE_MS) { throttled++; continue; }
    lastWarn.set(key, e.time);
    warned.push(e);
  }
  const bubbles = logs.filter((l) => l.tag === "PipRive" && l.text.startsWith("bubble closed")).length;
  const speech = events.some((e) => e.title === "Call speech");
  const sources = [...new Set(events.map((e) => e.source.toLowerCase().replace("_", " ")))];

  let actual;
  if (warned.length) {
    actual = `generic warning: urgent bubble + "This may be a scam" heads-up (${rules.slice(0, 3).join("; ")}), score ${maxScore}`;
  } else if (throttled) {
    actual = `scored ${maxScore} but the warning was throttled (same source warned under a minute ago)`;
  } else if (maxScore > 0) {
    actual = `logged only, score ${maxScore} (${rules.join("; ")})`;
  } else if (events.length) {
    actual = `checked, score 0 (${sources.join(", ")})`;
  } else {
    actual = "nothing logged";
  }
  if (speech) actual += "; heard the call through on-device speech";

  const quiet = /^nothing/i.test(scenario.expect.pip);
  let verdict;
  if (quiet) verdict = warned.length ? "false alarm" : "quiet, as expected";
  else if (warned.length && scenario.expect.level === "act") verdict = "generic warning, no action taken";
  else if (warned.length) verdict = scenario.expect.level === "danger" ? "generic warning (not the mock yet)" : "warns louder than the mock's caution";
  else if (throttled) verdict = "throttled (no repeat warning within a minute)";
  else verdict = "no Pip reaction yet";

  return { maxScore, rules, warned: warned.length, throttled, bubbles, sources, actual, verdict };
}
