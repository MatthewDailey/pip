#!/usr/bin/env bash
# Runs contact_sheet.py in the shared tools virtualenv, adding Pillow to it the first time.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
venv="$here/../../tools/.venv"
if [[ ! -x "$venv/bin/python" ]]; then
  python3 -m venv "$venv"
  "$venv/bin/pip" install -q -r "$here/../../tools/requirements.txt"
fi
"$venv/bin/python" -c "import PIL" 2>/dev/null || "$venv/bin/pip" install -q pillow
exec "$venv/bin/python" "$here/contact_sheet.py" "$@"
