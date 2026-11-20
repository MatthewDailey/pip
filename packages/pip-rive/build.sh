#!/usr/bin/env bash
# Compiles Pip's Rive sources with rivc into dist/, laid out the way the app's assets expect:
#   pip/pip.json            -> dist/pip/pip.riv            (the overlay's Pip rig)
#   showreel/showreel.json  -> dist/showreel/showreel.riv  (the whole showreel)
#   showreel/hodor.json     -> dist/showreel/hodor.riv     (the HODOR scene)
#   stories/<name>/<name>.json -> dist/showreel/<name>.riv (short stories; the app plays them like HODOR)
#   roam/roam.json          -> dist/roam/roam.riv          (the roam pieces the app's roam director plays)
# The Android app adds dist/ as an assets folder, so its build depends on this one (turbo.json).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rivc="$here/../rivc/rivc"
cd "$here"
rm -rf dist
mkdir -p dist/pip dist/showreel dist/roam
compile() { bash "$rivc" compile "$1" -o "$2" >/dev/null && echo "pip-rive: $1 -> $2 ($(wc -c <"$2" | tr -d ' ') bytes)"; }
compile pip/pip.json dist/pip/pip.riv
compile showreel/showreel.json dist/showreel/showreel.riv
compile showreel/hodor.json dist/showreel/hodor.riv
compile roam/roam.json dist/roam/roam.riv
for story in stories/*/; do
  name="$(basename "$story")"
  compile "$story$name.json" "dist/showreel/$name.riv"
done
