#!/usr/bin/env bash
# Runs tools/mock-calls/mock_call.py (scripted `say` caller injected into the emulator mic) in the tools virtualenv.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -x "$here/../.venv/bin/python" ]]; then
  python3 -m venv "$here/../.venv"
  "$here/../.venv/bin/pip" install -q -r "$here/../requirements.txt"
fi
exec "$here/../.venv/bin/python" "$here/mock_call.py" "$@"
