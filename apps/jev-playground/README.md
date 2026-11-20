# Pip · Jev fraud-detection playground

The fraud-detection "brain" for **Pip, the Personal Cyber Defender**, plus a playground to explore it.
A phone call's transcript (eventually: Twilio conference → media stream → speech-to-text) is re-scored every few
lines by the **Jev** decision model; Pip's own escalation policy smooths the scores and decides whether to
**alert a trusted contact** or **take over the call**. Two baselines (regex/keywords and an LLM classifier)
run behind the same interface for comparison.

## Run

```bash
pnpm install                # from the shady-pip repo root
cd apps/jev-playground
# .env.local (git-ignored):
#   JEV_API_KEY=...            # required for real Jev calls; without it the UI runs in clearly labeled MOCK MODE
#   OPENAI_API_KEY=... or ANTHROPIC_API_KEY=...   # optional, enables the LLM baseline
#   LLM_PROVIDER=openai|anthropic  # default openai when OPENAI_API_KEY is set; falls back to the other on billing/quota errors
#   OPENAI_MODEL=gpt-5.4-mini  ANTHROPIC_MODEL=claude-haiku-5-5  LLM_MODEL=... (overrides the primary)
#   JEV_MODEL=... JEV_API_BASE_URL=...            # optional overrides (see below)
pnpm dev           # http://localhost:3100
pnpm eval          # replay the labeled calls through every approach
pnpm typecheck && pnpm build
```

Demo URL params: `/?t=grandparent-bail&speed=1500&autoplay=1` (transcript id from `data/transcripts.ts`).

## Jev API (verified 2026-11-20)

Same request body on both hosted endpoints, `Authorization: Bearer <key>`:

```json
{ "model": "...", "state": "string | object | string[]", "questions": { "<id>": { "type": "noul|choice|score", "instructions": "...", "criteria": {} } } }
```

| | api.typesafe.ai (keys `apikey_...`) | thejevai.com (keys `sk_...`) |
|---|---|---|
| endpoint | `POST https://api.typesafe.ai/v1/systemone` | `POST https://thejevai.com/v1/systemone` |
| model | `jev-latest` (or `jev-preview`; `GET /v1/models`) | `typesafe/jev-1.14` |
| response | `{ model: "jev-1.14.0", answers, usage }` | `{ code, message, data: { result: { answers, usage, elapsedMs }, creditsUsed } }` |

The client auto-picks by key prefix (override with `JEV_API_BASE_URL` / `JEV_MODEL`). Answers:
`noul` → `{ noul: P(yes) }`; `choice` → `{ choice, probabilities, confidence }`;
`score` → `{ score (0-indexed, probability-weighted), legend, probabilities, confidence }`.
Limits per docs: ≤ 8 questions, ≤ 32 KiB JSON. 429/529 are retried with backoff.

## How it works

- **State** (`lib/transcript.ts`): `{ call_context, call_opening (first 3 lines: the pretext), recent_transcript (last ~60 s) }`.
- **Questions** (`lib/detectors/jev.ts`, one request, run in parallel): noul `scam_in_progress`, `payment_request`,
  `urgency_or_secrecy`, `impersonation`, `credential_or_access`; choice `scam_type`
  (government, tech_support, family_emergency, bank_impersonation, romance, prize_lottery, other_scam, none);
  score `risk_level` (4-level rubric). Instant risk = 0.45·scam + 0.35·rubric + 0.2·(1 − P(none)).
- **Policy** (`lib/policy.ts`, all configurable in the UI): asymmetric EMA (rise α 0.6, fall α 0.3);
  watch ≥ 0.3, alert contact ≥ 0.5, takeover ≥ 0.8 **or** smoothed payment & urgency both ≥ 0.65;
  at least 2 scored updates before a takeover; takeover is sticky.
- **Baselines**: `lib/detectors/keyword.ts` (regex: digit runs, spoken digits, gift cards, wire/crypto, threats, secrecy, remote-access apps…),
  `lib/detectors/tjRules.ts` (TJ's Pip weighted rules, `data/tj_scam_rules.json` copied verbatim from the Pip signal playground ref, sha256 02fdd408…afa1: each rule counted once per call over the whole call so far, warning at 5 → alert, ≥ 10 → takeover (our addition), ≥ 3 → watch; no smoothing, no call context),
  `lib/detectors/llm.ts` (OpenAI or Anthropic JSON classifier, selectable via LLM_PROVIDER with billing fallback; skipped without a key).

## API

- `POST /api/analyze` `{ lines: [{speaker:"caller"|"user", text}], context?, approaches? }` → `{ results: DetectorResult[] }` (stateless; the playground uses this).
- `POST /api/calls/:callId` `{ chunks: [{speaker, text, t?}], context?, scoreEvery? = 2, force?, approach? = "jev" }` →
  `{ level, action: "none"|"alert_trusted_contact"|"takeover", reason, smoothed, latest }`. For the Twilio stream service.
  `GET` returns the session, `DELETE` drops it. Sessions are in-memory (per server instance; use KV/Redis on Vercel).
- `GET /api/status` → whether Jev is live or mock, and whether the LLM baseline is on.

```bash
curl -s -X POST localhost:3100/api/calls/demo-1 -H 'content-type: application/json' \
  -d '{"chunks":[{"speaker":"caller","text":"This is the IRS, there is a warrant for your arrest."},{"speaker":"user","text":"Oh no"}],"context":{"caller_in_contacts":false}}'
```

## Eval

`pnpm eval --dataset=clean|messy|all --no-context --every=2 --approaches=jev,keyword,llm --concurrency=4 --only=id1,id2 --tag=late-onset`

- **clean** (`data/transcripts.ts`, 20): scripted, punctuated calls (12 scam / 8 benign).
- **messy** (`data/transcripts-messy.ts`, 30): looks like streaming speech-to-text: lowercase, fillers, crosstalk,
  mis-transcriptions ("gift cars", "western onion", "google plate cards"), numbers as words, cut-off lines,
  wrong/missing speaker labels, late-onset scams after small talk; plus hard negatives (real bank alert, family buying
  gift cards, tax preparer, grandson legitimately asking for money, telemarketers, sisters gossiping *about* a scam).
  19 scam / 11 benign, each tagged with difficulty and tags; `scamStartsAt` marks late onsets.
- `--no-context` drops the call-context JSON (caller in contacts, caller id, direction) to show the transcript alone carries the signal.

Each transcript is replayed as a live call, re-scoring every N lines through the policy. "Flagged" = reached alert or
takeover. Reports overall / per-dataset / per-difficulty / per-tag accuracy, misses, false alerts and takeovers,
alerts fired before scam onset, time-to-alert (line, ~seconds at 5 s/line, lines after onset), and latency
(mean/p50/p95). Full results incl. per-update traces go to `eval-results/` (git-ignored).

Caveat: all transcripts were written for this project. The messy set imitates real STT, but it's still authored
by us, so treat results as a smoke test. Add real (consented) call transcripts for numbers you can trust.

## Layout

```
app/page.tsx, components/Playground.tsx, components/Pip.tsx   UI
app/api/{analyze,calls/[callId],status}                       API routes
lib/detectors/{jev,keyword,llm,index}.ts                      detectors (same interface)
lib/policy.ts  lib/transcript.ts  lib/sessions.ts  lib/env.ts
data/transcripts.ts   scripts/eval.ts
```

## Live calls: user-only listening + demo tripwire

The phone transcribes only the protected user's own voice on-device; the caller is not heard. That app sends `listen:"user"`; the server default stays both-sides for pip-call.

`POST /api/calls/:callId`

```json
{
  "listen": "user",
  "context": {
    "direction": "inbound",
    "caller_number": "+12025550147",
    "caller_name": "SOCIAL SEC ADMIN",
    "caller_in_contacts": false,
    "local_time": "Friday 2:41 PM",
    "user_profile": "Margaret, 81, lives alone; trusted contact Karen"
  },
  "chunks": [{ "speaker": "user", "text": "Okay, the first one, it's four one one", "t": 101.9, "final": false }]
}
```

- `listen`: send `"user"` explicitly from the on-device app (and `pnpm demo:call` does). It drops caller chunks and asks Jev to work out the other side from her replies. Without `listen` the server uses `PIP4_LISTEN`, else `"both"`, so pip-call (which transcribes both legs and sends no `listen`) is unchanged. The mode sticks for the call once it is set.
- `final: false` marks a partial chunk. It is not stored or sent to Jev, but the tripwire checks it, so takeover can land mid-sentence. Jev re-scores after each final user line (`scoreEvery` defaults to 1 in user mode and 2 in both mode).
- `context` is merged into the call. `number_type` (withheld/international/toll_free/us_local) is derived from `caller_number`, and `call_duration_s` from line times.
- The response has the same fields as before (`level`, `action`, `reason`, `latest`, ...) plus `listen`, `ignored`, `tripwire` (the sticky strongest hit: `rule`, `level`, `reason`, `evidence`, `atLine`, `partial`), `tripwireFiredNow`, and `latencyMs`.

Tripwire (`lib/tripwire.ts`, `DEMO_TRIPWIRE=0` turns it off). It runs on every user chunk before Jev, and its level is a sticky floor:
- `gift_card_readout` triggers **takeover**. It needs a gift card mention earlier in the call (from any user chunk, partial or final; remembered on the session as `giftMention`) ("gift card(s)", "gif card", "Target card", "Apple/Google Play/... card") plus a current chunk that reads a number: at least 4 numerals (money, times and years are ignored), a run of at least 3 spoken digit words (one…nine, oh, zero, double), or "the number/code/PIN is".
- `gift_card_unknown_caller` triggers **alert**. It needs a gift card mention on an inbound call where `caller_in_contacts` is not true.
- `PIP4_JEV_MAX_LEVEL=alert` is optional. It caps how far Jev alone can escalate, so in a scripted demo the takeover lands exactly on the read-out.

Check: `pnpm tripwire:check -- --url=http://localhost:3100` (a gift card mentioned only in a partial, then a digits partial, must take over; digits with no gift card must not).

Demo: `pnpm demo:call -- --url=http://localhost:3100 [--call=demo-ssa-target|demo-birthday-card|demo-store-hours|demo-church-collection|all] [--speed=1] [--no-partials]`. It streams `data/demo-calls.ts` in real time; open `/live` to watch.

Eval: `pnpm eval -- --dataset=all --listen=user --every=1 --approaches=jev,tripwire,jevtw [--no-context]`. User-only versions drop the caller lines. `jevtw` is Jev plus the tripwire floor, which matches what the route does.

## Firedrill (optional)

A local HTTP harness so [Firedrill](https://docs.firedrill.run) can throw simulated scam and legitimate callers at Pip's live-call detector. It is fully removable and is not imported by any other app code.

- **Route:** `POST /api/firedrill` accepts Firedrill's `{"message","session"}` body and returns `{reply, level, action}`. Session id is stored as call `fd-<session>`.
- **Without Firedrill:** start the playground, then `pnpm firedrill:smoke -- --base http://127.0.0.1:3100` (use another port if 3100 is taken: `pnpm exec next dev -p 3101`). This does not need a Firedrill account.
- **With a Firedrill account:** follow the exact CLI steps in [`firedrill/README.md`](./firedrill/README.md) (`init`, `runner enroll`, `register`, `library save`, `world save`, `plan`, `apply --approve`, `run`, `report`). Event credit code: `TOKENSAND` (apply under Billing at [firedrill.run](https://firedrill.run); no card required). `plan` is free to run; `apply` reserves credits.

### Removing Firedrill

Delete these, then drop the `firedrill:smoke` script from `package.json` and this section:

- `app/api/firedrill/` (the route and the scripted-victim helper)
- `firedrill/` (descriptor, plan, personas, scenarios, helper scripts)
- `scripts/firedrill-smoke.ts`

Nothing else in the app imports those files. Local logs under `eval-results/firedrill/` are already git-ignored with the rest of `eval-results/`.

## Screen classifier (Pip screen check)

Decides **whether** Pip intervenes on a phone screen; TJ's intervention layer decides **how** (contract: Ref `xieJxRW7aSrS48Rr`).
One Jev request per screen with the screenshot (header `TypeSafe-Danger-Zone-Images: true` + `assets` map) plus accessibility text, app package and URL; 8 questions (5 yes/no, `fraud_type` choice, `risk` score, `danger_target` box).

- `risk = 0.5 · fraud_on_screen + 0.5 · risk_score/3`; `intervene = risk ≥ 0.7 && (fake_login ≥ 0.5 || payment_request ≥ 0.5 || remote_access ≥ 0.5)`.
- `POST /api/screens` — JSON `{ image_base64 | image_url, a11y_text, app_package, url, window_title, trigger, recent_events, mode }` or multipart with an `image` file. `mode`: `image+text` (default), `image`, `text` (no screenshot, no box). Returns `{ kind: "screen_assessment", id, ts, source, verdict: { fraud, fraud_type, fraud_type_probs, risk, risk_level, signals, danger_target }, intervene, suggested_pip_line?, mode, latency_ms, model, usage }`.
- UI: `/screens` (pick a dataset screen or upload one; `?id=s19&run=1` deep link). Images must be PNG/JPEG with each side 280–1568 px.
- Dataset: `data/screens/screens.json` + `data/screens/img/sNN.png` (40 phone-size mocks: 22 scam, 18 benign incl. hard negatives). Rebuild with `node data/screens/src/build.mjs` (needs Google Chrome; edit `src/specs.mjs`).
- Eval: `pnpm eval:screens [--modes=image+text,image,text,tj] [--no-context] [--only=s01,s02] [--tag=sms] [--concurrency=4]` → `eval-results/screens/*.json`. `--no-context` hides URL and app package (the screenshot still shows the URL bar).
- Code: `lib/screens/jevScreen.ts`, `app/api/screens/`, `components/ScreenLab.tsx`, `app/screens/page.tsx`, `scripts/eval-screens.ts`.
