# pip-call (Pip3)

The live call service. It sits inside a real phone call: every call (from the Elder Guard app over the
Twilio Voice SDK, or to Mom's Pip line) becomes a Twilio conference `pip-<CallSid>` with the legs
labelled `caller` and `mom`. Twilio posts transcript lines here as
webhooks, pip-call sends them to Pip4 (`apps/jev-playground`, `POST /api/calls/:callId`), and when
Pip4 says `takeover` it puts the caller on hold, mutes Mom and plays her a warning, or with
`PIP_VOICE=live` brings in Pip's live voice to talk with her (see [Pip's live voice](#pips-live-voice)).


```bash
pnpm --filter @shady-pip/pip-call dev        # http://localhost:3200  (also a pane in `turbo dev`)
pnpm --filter @shady-pip/pip-call test       # rung 0: unit tests
pnpm --filter @shady-pip/pip-call sim        # rung 1: replay Pip4's 50 labelled calls
pnpm --filter @shady-pip/pip-call typecheck
pnpm --filter @shady-pip/pip-call robocall -- --script=irs --base=<deployment URL>   # rung 2: a real call, no people
```

Runs on Vercel (`vercel.json` pins `iad1`, near Twilio). Each webhook is a short request and may hit
any instance, so nothing lives in memory: call state, timelines and locks are in Upstash Redis
(`KV_REST_API_URL` / `KV_REST_API_TOKEN`), with an in-memory stand-in when those are unset.

## Routes

| Route | What |
|---|---|
| `GET /health` | ok, Twilio mode (live or fake), store (redis or memory), Pip4 URL |
| `GET /token?user=<id>` | the app's 1-hour Voice SDK access token (header `x-pip-app-secret: $PIP_APP_SECRET`) |
| `POST /twiml/voice` | voice webhook of the Pip line and the TwiML App: starts transcription and joins the conference (`src/voice.ts`) |
| `POST /app/calls/:callSid/context` | the app adds `in_contacts`, `contact_name` (also `local_time`, `user_profile`) after answering; same header |
| `POST /twilio/conference`, `POST /twilio/participant` | conference events and the dialled party's call status → timeline; hangs up Mom if the number she dialled never answers |
| `GET /twiml/ringback` | what the first leg hears until the other answers (`public/ringback.wav`) |
| `POST /twiml/robo-caller`, `POST /twiml/robo-mom`, `POST /debug/robocall` | rung-2 test parties (`src/robo.ts`) |
| `POST /twilio/transcription` | Twilio Real-Time Transcription callback: partials and finals → lines → Pip4 |
| `POST /openai/realtime` | OpenAI's webhook for Pip's live voice: accepts the SIP call with Pip's instructions (`src/livePip.ts`) |
| `GET /twiml/hold`, `GET /twiml/warn` | hold music for the caller, the warning Mom hears during a takeover |
| `GET /debug/calls` | recent calls (needs `?token=$DEBUG_TOKEN`, or header `x-pip-debug-token` = `DEBUG_TOKEN` or `TWILIO_AUTH_TOKEN`) |
| `GET /debug/calls/:callSid` | the call's timeline (same token; the Vercel bypass secret is redacted): every webhook, line, Pip4 request and verdict, Twilio command, with ms since the call started |

## How a call gets in

| | App outgoing (`From=client:<identity>`) | Pip line (`To=` the Pip line) |
|---|---|---|
| Leg that hits `/twiml/voice`, runs transcription, names the conference | Mom's app | the caller's phone |
| It joins as | `mom` | `caller` |
| pip-call adds (Participants API, by conference name) | the number she dialled (`To`) as `caller`, caller ID = Pip line | `client:$MOM_CLIENT_IDENTITY` if set (caller ID = caller), else `MOM_NUMBER` (caller ID = Pip line) |
| Speakers: inbound / outbound track | Mom (`user`) / caller | caller / Mom (`user`) |
| Pip4 context | `direction` (default outbound), `caller_in_contacts` from `in_contacts`, `caller_id` = `contact_name` or the number, `local_time`, `user_profile` | inbound, not in contacts, `caller_id` = caller's number |

The app's params (`pip_user`, `contact_name`, `in_contacts`, `direction`, `local_time`, `user_profile`) arrive as form
fields of the `/twiml/voice` webhook. Only Pip4's own fields are sent to Pip4 (`src/pip4.ts` `pip4Context`).

## How a line flows

1. `src/webhook.ts` checks `X-Twilio-Signature` (against `PUBLIC_BASE_URL` + path), saves the raw payload with `PIP_RECORD=1`.
2. `src/lines.ts` maps tracks to speakers by which leg is transcribed (table above), keeps the latest partial per speaker, and passes each final line on.
3. `src/scorer.ts` queues the line by Twilio `SequenceId` and, under a short per-call Redis lock, sends queued lines to Pip4 one by one in order. When transcription stops it asks Pip4 to score any trailing lines.
4. `src/pip4.ts` posts to Pip4 (5 s timeout, one retry) and logs the verdict.
5. On `takeover`, `src/takeover.ts` takes a one-shot lock and issues hold caller + mute Mom + warn Mom through `TwilioOps` (participants addressed by label in conference `pip-<CallSid>`), or with `PIP_VOICE=live` hold caller + dial Pip.

`TwilioOps` (`src/twilioOps.ts`) has a real implementation and `RecordingTwilioOps`, which writes each command to the timeline instead (`PIP_TWILIO=fake`).

## Simulator (rung 1)

`pnpm sim` boots Pip4 (unless it already answers on `PIP4_URL`) and pip-call with fake Twilio on
port 3290, then replays every transcript in `apps/jev-playground/data/` as signed Twilio
transcription webhooks (two partials and a final per line, 5 s/line ÷ `--speed`). It checks that
every line reached Pip4 once, in order and unchanged, and that takeover commands were issued
exactly once on scams and never on normal calls. Report: console table + `.proof/pip3-sim.json`.

| Mode | When | What must hold |
|---|---|---|
| live Jev | `JEV_API_KEY` set for jev-playground | 31/31 scams reach takeover, 0/19 normal calls do |
| mock Jev | no key | IRS call reaches takeover, sisters chat doesn't (plumbing only) |
| `--approach=keyword` | any time | every call's max level equals an offline replay of the same detector (exact plumbing check) |

Flags: `--dataset=clean|messy|all`, `--only=id1,id2`, `--speed=10`, `--concurrency=4`, `--approach=jev|keyword`, `--pip4-url=…`, `--keep`.

## Robo-call (rung 2)

`robocall -- --script=<name>` makes the robo-caller number call the Pip line and read
`tools/mock-calls/lines/<name>.txt`, one `<Say>` per request, so pip-call knows when each line finished
playing. The Pip line rings `MOM_NUMBER`; on the deployment under test set that to the robo-caller number,
whose voice URL the script points at `/twiml/robo-mom` for the run (and restores). It prints both legs'
conference joins, the transcript, speech end → final transcript latency per line, Pip4's verdicts and any
takeover, and writes `.proof/pip3-robocall-<name>.json`.

## Pip's live voice

With `PIP_VOICE=live`, takeover holds the caller and, instead of muting Mom and playing the fixed warning,
adds a third participant `pip` to the conference: OpenAI's Realtime API reached over SIP
(`sip:$OPENAI_PROJECT_ID@sip.api.openai.com;transport=tls`, our CallSid in header `X-Pip-Call`). OpenAI
posts `realtime.call.incoming` to `/openai/realtime`; pip-call accepts it with Pip's instructions (why he
stepped in, the last 16 transcript lines) and holds a control socket (no audio) for up to 280 s so Pip speaks
first, both sides are logged to the timeline as `pip_voice`, and his one tool `hang_up_on_caller` works.
OpenAI carries the audio, so nothing streams through Vercel.

The fixed warning is the fallback: when the live settings are incomplete, when Twilio can't dial Pip, or when
the `pip` participant fails or isn't answered (`/twilio/participant?role=pip`). `GET /health` shows `voice`.

Setup (one OpenAI project): an API key, its project ID (`proj_...`, Settings → General), and a webhook
(Settings → Webhooks) for event `realtime.call.incoming` at
`https://<deployment>/openai/realtime` (add `?x-vercel-protection-bypass=<secret>` on protected
deployments), whose signing secret is `OPENAI_WEBHOOK_SECRET`.

## Environment

All in the repo-root `.env` (see `.env.example`), passed through turbo. Some have two accepted names (the
plan's and the cloud environment's).

| Variable | Used for |
|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | REST calls, webhook signatures |
| `PIP_LINE_NUMBER` (`TWILIO_PIP_LINE`), `MOM_NUMBER`, `ROBO_CALLER_NUMBER` (`TWILIO_ROBO_CALLER`) | the numbers |
| `TWILIO_API_KEY_SID` (`TWILIO_API_KEY`), `TWILIO_API_KEY_SECRET` (`TWILIO_API_SECRET`), `TWILIO_TWIML_APP_SID` | the app's access tokens |
| `PIP_APP_SECRET` | shared secret for `/token` and `/app/...` |
| `MOM_CLIENT_IDENTITY`, `TWILIO_PUSH_CREDENTIAL_SID` | ring Mom's app instead of her phone on Pip line calls (needs FCM push) |
| `PIP_STT_ENGINE`, `PIP_STT_MODEL` | Twilio transcription engine and model (default `deepgram` / `nova-3`) |
| `PUBLIC_BASE_URL` | where Twilio reaches pip-call; on Vercel, derived from the production or branch URL when unset |
| `VERCEL_AUTOMATION_BYPASS_SECRET` | added to webhook URLs so preview deployments accept Twilio |
| `KV_REST_API_URL`, `KV_REST_API_TOKEN` | Upstash Redis |
| `PIP4_URL` (default `http://localhost:3100`), `PIP4_APPROACH` (default `jev`), `PIP4_BYPASS_SECRET` | Pip4, and its Vercel protection-bypass secret |
| `PIP_VOICE=live`, `OPENAI_API_KEY`, `OPENAI_PROJECT_ID`, `OPENAI_WEBHOOK_SECRET` | Pip's live voice on takeover (all four, else the fixed warning) |
| `PIP_REALTIME_MODEL` (default `gpt-realtime`), `PIP_REALTIME_VOICE` (default `cedar`) | the live voice's model and voice |
| `DEBUG_TOKEN` | opens `/debug/calls` in a browser (`?token=`) |
| `PIP_TWILIO=fake` | RecordingTwilioOps, only `TWILIO_AUTH_TOKEN` required |
| `PIP_RECORD=1` | save raw webhooks to `test/fixtures/twilio/<callSid>/` (Redis list on Vercel) |

The server refuses to start if a required variable is missing and names it.
