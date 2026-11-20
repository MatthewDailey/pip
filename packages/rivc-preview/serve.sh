#!/usr/bin/env bash
# Watch Pip's source, recompile on change, serve the previewer and live-reload it.
#
#   packages/rivc-preview/serve.sh <pip.json | pip.svg> [port]
#
# The compile command is RIVC_CMD, a shell template with {src} and {out} placeholders.
# Default: packages/rivc/rivc compile {src} -o {out} when that CLI exists and src is a .json;
# otherwise the spike compiler (PIP_SPIKE_COMPILER, default ../pip-riv-compiler next to this
# repo), which takes an SVG: <compiler> {src} {out}.
#
# Watched files: the source plus every relative file path string found in it (for pip.json:
# its SVG and anything else it references), re-read after each build. Uses fswatch when
# installed, else polls mtimes twice a second.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
src="${1:?usage: serve.sh <pip.json|pip.svg> [port]}"
src="$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
port="${2:-${PORT:-8787}}"
out="$here/pip.riv"
build_json="$here/build.json"

if [[ -z "${RIVC_CMD:-}" ]]; then
  if [[ -x "$repo/packages/rivc/rivc" && "$src" == *.json ]]; then
    RIVC_CMD="'$repo/packages/rivc/rivc' compile {src} -o {out}"
  else
    spike="${PIP_SPIKE_COMPILER:-$repo/../pip-riv-compiler/build/install/pip-riv-compiler/bin/pip-riv-compiler}"
    [[ -x "$spike" ]] || { echo "no compiler: set RIVC_CMD (packages/rivc/rivc and $spike not found)" >&2; exit 1; }
    RIVC_CMD="'$spike' {src} {out}"
  fi
fi
echo "compile: $RIVC_CMD"

# Relative paths referenced by the source (e.g. "svg": "pip.svg" in pip.json), resolved
# against its folder; only files that exist are watched.
watch_list() {
  echo "$src"
  node -e '
    const fs = require("fs"), path = require("path");
    const src = process.argv[1], dir = path.dirname(src);
    const text = fs.readFileSync(src, "utf8");
    const refs = new Set();
    for (const m of text.matchAll(/["\x27]([^"\x27\s<>]+\.(?:svg|json|png|webp|jpe?g|ttf|otf|riv|txt))["\x27]/gi)) refs.add(m[1]);
    for (const r of refs) {
      if (/^[a-z]+:/i.test(r)) continue;
      const p = path.resolve(dir, r);
      if (p !== src && fs.existsSync(p)) console.log(p);
    }
  ' "$src" 2>/dev/null || true
}

version=0
[[ -f "$build_json" ]] && version="$(node -e 'try{console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).version|0)}catch{console.log(0)}' "$build_json")"

compile() {
  local changed="$1" tmp log status
  tmp="$(mktemp "$here/.pip.XXXXXX.riv")"
  log="$(mktemp)"
  # Substitute variable references, not quoted paths: bash 3.2 (macOS /bin/bash) keeps the backslash in
  # "${x//p/\'r\'}", so the paths reached the compiler wrapped in literal quotes.
  local src_ref='"$RIVC_SRC"' out_ref='"$RIVC_OUT"'
  local cmd="${RIVC_CMD//\{src\}/$src_ref}"
  cmd="${cmd//\{out\}/$out_ref}"
  local RIVC_SRC="$src" RIVC_OUT="$tmp"
  local started
  started="$(date +%s)"
  if (cd "$repo" && eval "$cmd") >"$log" 2>&1 && [[ -s "$tmp" ]]; then
    chmod 644 "$tmp"; mv -f "$tmp" "$out"
    status=ok
  else
    rm -f "$tmp"
    status=failed
  fi
  version=$((version + 1))
  node -e '
    const [file, version, ok, changed, logFile, secs] = process.argv.slice(1);
    const log = require("fs").readFileSync(logFile, "utf8").trim();
    const info = { version: +version, ok: ok === "ok", changed, at: new Date().toISOString(), seconds: +secs };
    if (!info.ok) {
      const lines = log.split("\n").map((l) => l.trim()).filter(Boolean);
      info.error = (lines.find((l) => /error|exception|failed/i.test(l)) || lines.at(-1) || "compiler produced no output").slice(0, 300);
    }
    require("fs").writeFileSync(file + ".tmp", JSON.stringify(info) + "\n");
    require("fs").renameSync(file + ".tmp", file);
  ' "$build_json" "$version" "$status" "$changed" "$log" "$(($(date +%s) - started))"
  echo "[$(date +%H:%M:%S)] build #$version $status ($changed)"
  [[ "$status" == ok ]] || sed 's/^/    /' "$log" | tail -20
  rm -f "$log"
}

signature() {
  local f
  for f in "$@"; do
    stat -f '%m %z %N' "$f" 2>/dev/null || stat -c '%Y %s %n' "$f" 2>/dev/null || echo "missing $f"
  done
}

node "$here/server.mjs" --port "$port" --root "$here" &
server_pid=$!
trap 'kill $server_pid 2>/dev/null; rm -f "$here"/.pip.*.riv' EXIT INT TERM
sleep 0.5
kill -0 "$server_pid" 2>/dev/null || { echo "server failed to start (port $port in use?)" >&2; exit 1; }

compile "initial build"

while true; do
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(watch_list)
  if command -v fswatch >/dev/null 2>&1; then
    changed="$(fswatch -1 --latency 0.2 "${files[@]}" | head -1 || true)"
  else
    before="$(signature "${files[@]}")"
    while [[ "$(signature "${files[@]}")" == "$before" ]]; do sleep 0.5; done
    changed="$(diff <(echo "$before") <(signature "${files[@]}") | sed -n 's/^> [^ ]* [^ ]* //p' | head -1 || true)"
  fi
  # Let an editor finish writing (atomic saves replace the file in two steps).
  sleep 0.2
  compile "$(basename "${changed:-change}")"
done
