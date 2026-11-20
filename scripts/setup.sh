#!/usr/bin/env bash
# Runs before every turbo task (turbo.json: every task depends on //#setup). It sets up only what changed:
#   - tools (JDK, Node/pnpm, Android SDK + emulator + AVD, Python venv, ffmpeg...) via scripts/bootstrap.sh
#   - JS dependencies via pnpm install
# and records what it set up in .pip/setup.lock (gitignored). When nothing changed it exits in well under a second.
#
#   bash scripts/setup.sh           # what turbo runs
#   bash scripts/setup.sh --force   # redo everything
set -euo pipefail

# Hosted builds (Vercel, CI) install their own dependencies; the bootstrap is for a developer's Mac.
if [ -n "${VERCEL:-}" ] || [ -n "${CI:-}" ]; then exit 0; fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
lock=".pip/setup.lock"
force=false
[ "${1:-}" = "--force" ] && force=true

hash_of() { cat "$@" 2>/dev/null | shasum -a 256 | cut -c1-16; }

# What each step depends on. A change in any of these files reruns that step.
tools_hash="$(hash_of scripts/bootstrap.sh .nvmrc tools/requirements.txt tools/requirements-art.txt)"
deps_hash="$(hash_of pnpm-lock.yaml package.json $(git ls-files '*/package.json' 2>/dev/null))"

recorded() { [ -f "$lock" ] && grep -o "\"$1\": *\"[^\"]*\"" "$lock" | sed 's/.*"\([^"]*\)"$/\1/'; }

need_tools=$force
need_deps=$force
[ "$(recorded tools)" = "$tools_hash" ] || need_tools=true
[ "$(recorded deps)" = "$deps_hash" ] || need_deps=true
# Things a lock can't see: a deleted node_modules, or tools removed from the machine.
[ -f node_modules/.modules.yaml ] || need_deps=true
if ! $need_tools && ! bash scripts/bootstrap.sh --check >/dev/null 2>&1; then need_tools=true; fi

# `npx turbo dev` works on a machine without turbo; install it globally so later runs are just `turbo dev`.
# The global CLI hands off to this repo's pinned local turbo, so its own version doesn't matter much.
if ! command -v turbo >/dev/null 2>&1; then
  turbo_version="$(node -p "require('./package.json').devDependencies.turbo" 2>/dev/null || echo latest)"
  echo "setup: installing the turbo CLI globally (turbo@$turbo_version) so next time you can run: turbo dev"
  npm install -g "turbo@$turbo_version" >/dev/null 2>&1 || echo "setup: couldn't install turbo globally; keep using npx turbo dev"
fi

if ! $need_tools && ! $need_deps; then
  echo "setup: up to date ($lock)"
  exit 0
fi

if $need_tools; then
  echo "setup: installing or updating tools (scripts/bootstrap.sh)"
  bash scripts/bootstrap.sh
fi
if $need_deps; then
  echo "setup: installing JS dependencies (pnpm install)"
  # shellcheck disable=SC1091
  . scripts/env.sh
  pnpm install --frozen-lockfile
fi

mkdir -p .pip
printf '{\n  "tools": "%s",\n  "deps": "%s",\n  "node": "%s",\n  "updated": "%s"\n}\n' \
  "$tools_hash" "$deps_hash" "$(node --version 2>/dev/null || echo none)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$lock"
echo "setup: done, recorded in $lock"
