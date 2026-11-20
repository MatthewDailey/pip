#!/usr/bin/env bash
# Runs tools/pip-voice/render_greeting.py (Pip's greeting lines in a GPT-Live voice) in the shared tools virtualenv,
# creating it on first use. Arguments pass through: --force re-renders every line, --voice/--style override the
# defaults, --dry-run lists what would be rendered.
#
# The key comes from OPENAI_API_KEY (environment or .env at the repo root). Without it, it is read from GCP Secret
# Manager for this run only: secret OPENAI_SECRET (default OPENAI_API_KEY) in project OPENAI_SECRET_PROJECT, using
# your gcloud login. There is no default project. See .env.example.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
if [[ -f "$root/.env" ]]; then set -a; . "$root/.env"; set +a; fi
if [[ ! -x "$here/../.venv/bin/python" ]]; then
  python3 -m venv "$here/../.venv"
  "$here/../.venv/bin/pip" install -q -r "$here/../requirements.txt"
fi
if [[ -z "${OPENAI_API_KEY:-}" && " $* " != *" --dry-run "* ]]; then
  project="${OPENAI_SECRET_PROJECT:-}"
  secret="${OPENAI_SECRET:-OPENAI_API_KEY}"
  if [[ -z "$project" ]]; then
    echo "voice:greeting: set OPENAI_API_KEY, or OPENAI_SECRET_PROJECT (and optionally OPENAI_SECRET) to read it from GCP Secret Manager. See .env.example." >&2
    exit 1
  fi
  command -v gcloud >/dev/null || { echo "voice:greeting: gcloud not found; set OPENAI_API_KEY instead" >&2; exit 1; }
  OPENAI_API_KEY="$(gcloud secrets versions access latest --secret="$secret" --project="$project" 2>/dev/null)" || {
    echo "voice:greeting: couldn't read secret '$secret' in project '$project'. Run 'gcloud auth login', or set OPENAI_API_KEY." >&2
    exit 1
  }
  export OPENAI_API_KEY
fi
exec "$here/../.venv/bin/python" "$here/render_greeting.py" "$@"
