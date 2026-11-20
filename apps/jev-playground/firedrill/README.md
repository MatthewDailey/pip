# Firedrill setup for Pip's call detector

Optional. This folder plus `app/api/firedrill/` and `scripts/firedrill-smoke.ts` are the only Firedrill pieces. The smoke test talks to `POST /api/firedrill` over HTTP and does not need the Firedrill CLI or an account.

The route accepts Firedrill's `http.v1` body `{"message","session"}`, scores the caller's line through the existing session + Jev + policy exports, and replies as a scripted gullible victim — or as Pip, once policy hits takeover. `PIP_OFF=1` or `?pip=off` is the "bad timeline": Pip stays off and, after a few pressured turns, the victim reads out the obviously fake card `4111 1111 1111 1111`.

## Prove the route without Firedrill

Start the playground (port 3100 is the default `pnpm dev`; use another port if that one is taken), then:

```bash
cd apps/jev-playground
pnpm exec next dev -p 3100          # or 3101, etc.
pnpm firedrill:smoke -- --base http://127.0.0.1:3100
```

This runs one scam call and one benign call, each with Pip on and Pip off. It does not call Firedrill and spends no Firedrill credits (it does call Jev when `JEV_API_KEY` is set).

## CLI (once you have a Firedrill account)

Firedrill's CLI (`firedrill`, from the `firedrill-run` package) needs Node **22.23.4+**, **24.11.1+**, or **26.13.1+** (not 23 or 25). Check with `node -v`.

Do not run `apply` or `run` until you have reviewed the credit estimate from `firedrill plan`. `plan` itself spends nothing.

```bash
# 1. Sign up at https://firedrill.run and pick a workspace.
#    Under Billing, apply event code TOKENSAND (1,500 extra credits on top of
#    the free-tier 500; no payment card required). The extra credits do not expire.

# 2. Install the CLI (global; ≥ 3.0.5) and sign in.
npm install --global firedrill-run
firedrill --version     # or: npx firedrill-run --version  (then prefix every command with npx firedrill-run)
firedrill init          # browser sign-in, choose workspace, check this machine

# 3. Enroll this Mac as a runner (once per sign-in).
firedrill runner enroll

# 4. From this folder, fill machine hashes and absolute paths
#    (writes gitignored descriptor.json, plan.local.json, plan.pip-off.local.json).
cd apps/jev-playground/firedrill
node prepare.mjs

# 5. Put JEV_API_KEY in the shell so Firedrill can pass it into each launched process
#    (Next.js will also load ../.env.local itself). Do not print the file.
set -a && source ../.env.local && set +a

# 6. Register the agent. Copy the printed descriptor digest.
firedrill register --descriptor descriptor.json --name "Pip call detector"
node prepare.mjs --descriptor-sha256 <digest>

# 7. Save personas and evaluations. For each command, copy the printed
#    resource/version id, revision, and digest into ids.json
#    (start from ids.example.json).
firedrill library save --kind persona --file personas/irs-arrest.json
firedrill library save --kind persona --file personas/grandson-bail.json
firedrill library save --kind persona --file personas/tech-support-refund.json
firedrill library save --kind persona --file personas/bank-safe-account.json
firedrill library save --kind persona --file personas/medicare-card.json
firedrill library save --kind persona --file personas/bank-real-callback.json
firedrill library save --kind persona --file personas/grandson-birthday.json
firedrill library save --kind persona --file personas/pharmacy-refill.json
firedrill library save --kind evaluation --file evaluations/scam-takeover.json
firedrill library save --kind evaluation --file evaluations/benign-no-takeover.json

# 8. Stitch those IDs into scenario files, then save the scenarios.
node bind.mjs
firedrill library save --kind scenario --file scenarios/irs-arrest.json
firedrill library save --kind scenario --file scenarios/grandson-bail.json
firedrill library save --kind scenario --file scenarios/tech-support-refund.json
firedrill library save --kind scenario --file scenarios/bank-safe-account.json
firedrill library save --kind scenario --file scenarios/medicare-card.json
firedrill library save --kind scenario --file scenarios/bank-real-callback.json
firedrill library save --kind scenario --file scenarios/grandson-birthday.json
firedrill library save --kind scenario --file scenarios/pharmacy-refill.json
# Put the eight scenario ids into ids.json and stitch the world:
node bind.mjs

# 9. Save the world (8 scenarios × 3 repetitions = 24 drills). Nothing starts.
firedrill world save --agent <agent-id> --file world.json

# 10. Review the plan. This changes nothing and spends no credits.
#     Read the credit estimate before continuing.
firedrill plan --world <world-id>

# 11. Apply (reserves credits, creates a campaign) and run on this machine.
#     `next dev` is started per drill on a free PORT; readiness is GET /api/status.
firedrill apply <plan-id> --approve --follow
firedrill run --plan plan.local.json --descriptor descriptor.json --campaign <campaign-id>
firedrill report <campaign-id>
```

`--json` works on every command if you want machine-readable IDs. `npx firedrill-run …` is the same flow without a global install.

To run the "bad timeline" (Pip off, victim eventually reads the fake card), use `plan.pip-off.local.json` instead. That plan posts to `/api/firedrill?pip=off`. Build it as a separate world/campaign so scam evaluations are not scored against a disabled detector.

## Layout

| Path | What |
| --- | --- |
| `descriptor.template.json` / `plan.template.json` | Schemas from [register](https://docs.firedrill.run/connect/register-agent) and [local plan](https://docs.firedrill.run/connect/local-plan). `prepare.mjs` fills hashes and your repo path. |
| `plan.pip-off.template.json` | Same plan, driver path `/api/firedrill?pip=off`. |
| `personas/`, `evaluations/` | Ready for `library save`. |
| `scenarios/*.template.json`, `world.template.json` | Need IDs from earlier saves; `bind.mjs` writes the concrete files. |

The local plan stays out of git (absolute paths). The agent is one `http.v1` service with process state, `reset: fresh-process`, `concurrency: independent`, so drills can run in parallel. Each drill gets a fresh `next dev` on a free `PORT`. For a single already-running playground you can still smoke-test against `pnpm dev` on 3100 without Firedrill.

## Removing Firedrill

See the same-named section in `../README.md`.
