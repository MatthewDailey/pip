#!/usr/bin/env bash
# One-time (and re-runnable) environment setup for this repo. macOS first, Apple Silicon assumed.
#
#   bash scripts/bootstrap.sh          # check everything, install what's missing
#   bash scripts/bootstrap.sh --check  # check only; exit 1 listing what's missing
#
# Installs, when missing:
#   Homebrew packages   openjdk@21, node@24 (per .nvmrc), python, ffmpeg, potrace, cairo
#   pnpm                via corepack, at the version in package.json's packageManager
#   Android SDK         command-line tools, platform-tools, emulator, build-tools;37.0.0, platforms;android-36,
#                       system-images;android-37.0;google_apis_playstore;arm64-v8a  (asks you to accept licences)
#   AVD                 $AVD_NAME (default Pip_API_37): mic in, speaker off, hardware keyboard on
#   Python venv         tools/.venv with tools/requirements.txt and tools/requirements-art.txt
#   Node deps           pnpm install, plus Playwright's Chromium for the golden tests
#
# Everything already present is left alone, so a second run changes nothing.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
check_only=0
[[ "${1:-}" == --check ]] && check_only=1

if [[ -t 1 ]]; then B=$'\e[1m' G=$'\e[32m' Y=$'\e[33m' R=$'\e[31m' N=$'\e[0m'; else B="" G="" Y="" R="" N=""; fi
ok()      { echo "  ${G}ok${N}       $*"; }
doing()   { echo "  ${Y}install${N}  $*"; }
missing() { echo "  ${R}missing${N}  $*"; MISSING+=("$1"); }
section() { echo; echo "${B}$*${N}"; }
die()     { echo "${R}bootstrap: $*${N}" >&2; exit 1; }
MISSING=()

# need <label> <test command...>: true when present; otherwise records it (check mode) or returns 1 (install).
need() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$label"; return 0; fi
  if [[ $check_only == 1 ]]; then missing "$label"; return 0; fi
  doing "$label"; return 1
}

[[ "$(uname -s)" == Darwin ]] || echo "${Y}bootstrap: written for macOS; on other systems install the same tools by hand${N}"
[[ "$(uname -m)" == arm64 ]] || echo "${Y}bootstrap: the AVD uses an arm64 system image; Intel Macs need an x86_64 image instead${N}"

AVD_NAME="${AVD_NAME:-Pip_API_37}"
SYSTEM_IMAGE="system-images;android-37.0;google_apis_playstore;arm64-v8a"
SDK_PACKAGES=("platform-tools" "emulator" "build-tools;37.0.0" "platforms;android-36" "$SYSTEM_IMAGE")
PNPM_VERSION="$(node -p 'require("./package.json").packageManager.split("@")[1]' 2>/dev/null || sed -n 's/.*"packageManager": "pnpm@\([^"]*\)".*/\1/p' package.json)"
NODE_MAJOR="$(tr -dc '0-9' < .nvmrc)"

brew_install() {
  command -v brew >/dev/null || die "Homebrew is required to install $*: https://brew.sh"
  brew install "$@"
}

section "Homebrew packages"
if ! command -v brew >/dev/null; then
  if [[ $check_only == 1 ]]; then missing "Homebrew (https://brew.sh)"; else echo "  ${Y}note${N}     Homebrew not found; tools that are missing can't be installed automatically"; fi
fi
jdk_ok() { [[ -x /opt/homebrew/opt/openjdk@21/bin/java || -x /usr/local/opt/openjdk@21/bin/java ]] || /usr/libexec/java_home -v 21 >/dev/null 2>&1; }
need "openjdk@21" jdk_ok || brew_install openjdk@21
node_ok() { command -v node && [[ "$(node -p 'process.versions.node.split(".")[0]')" -ge "$NODE_MAJOR" ]]; }
if ! need "node >= $NODE_MAJOR (.nvmrc)" node_ok; then
  if command -v node >/dev/null; then
    die "node $(node -v) is older than $NODE_MAJOR. Switch with your version manager (nvm use / volta install node@$NODE_MAJOR) or brew install node@$NODE_MAJOR && brew link --overwrite node@$NODE_MAJOR"
  fi
  brew_install "node@$NODE_MAJOR"
  brew link --overwrite "node@$NODE_MAJOR" || true
fi
need "python3" command -v python3 || brew_install python
need "ffmpeg" command -v ffmpeg || brew_install ffmpeg
need "potrace" command -v potrace || brew_install potrace
cairo_ok() { [[ -e /opt/homebrew/lib/libcairo.2.dylib || -e /usr/local/lib/libcairo.2.dylib ]]; }
need "cairo (for cairosvg in tools/pip-art)" cairo_ok || brew_install cairo

section "pnpm"
if ! need "pnpm" command -v pnpm; then
  command -v corepack >/dev/null || die "corepack not found; install pnpm $PNPM_VERSION yourself (https://pnpm.io/installation)"
  corepack enable pnpm
  corepack prepare "pnpm@$PNPM_VERSION" --activate
fi

# Env for everything below (JAVA_HOME, ANDROID_HOME, PATH).
# shellcheck disable=SC1091
. "$root/scripts/env.sh"

section "Android SDK ($ANDROID_HOME)"
sdkmanager_bin=""
for c in "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$(command -v sdkmanager || true)"; do
  [[ -n "$c" && -x "$c" ]] && sdkmanager_bin="$c" && break
done
if ! need "command-line tools (sdkmanager)" test -n "$sdkmanager_bin"; then
  brew install --cask android-commandlinetools
  sdkmanager_bin="$(command -v sdkmanager)" || die "sdkmanager still not on PATH after installing android-commandlinetools"
fi
sdk_path() { local p="${1//;//}"; echo "$ANDROID_HOME/$p"; }
to_install=()
for pkg in "${SDK_PACKAGES[@]}"; do
  if [[ -d "$(sdk_path "$pkg")" ]]; then ok "$pkg"
  elif [[ $check_only == 1 ]]; then missing "$pkg"
  else doing "$pkg"; to_install+=("$pkg"); fi
done
if [[ ${#to_install[@]} -gt 0 ]]; then
  mkdir -p "$ANDROID_HOME"
  echo "  Accept the Android SDK licences when asked."
  "$sdkmanager_bin" --sdk_root="$ANDROID_HOME" --licenses
  "$sdkmanager_bin" --sdk_root="$ANDROID_HOME" --install "cmdline-tools;latest" "${to_install[@]}"
fi

section "Emulator ($AVD_NAME)"
avd_dir="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD_NAME.avd"
if ! need "AVD $AVD_NAME" test -f "$avd_dir/config.ini"; then
  avdmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
  [[ -x "$avdmanager_bin" ]] || avdmanager_bin="$(command -v avdmanager)" || die "avdmanager not found"
  echo no | "$avdmanager_bin" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_9
fi
if [[ -f "$avd_dir/config.ini" ]]; then
  # The emulator's call audio is a fake busy tone, so callers are injected into the mic: audio in on, out off.
  # Render on the Mac's GPU: with hw.gpu off the emulator draws in software and Pip runs at ~3 fps instead of 60.
  for kv in hw.audioInput=yes hw.audioOutput=no hw.keyboard=yes hw.gpu.enabled=yes hw.gpu.mode=host; do
    key="${kv%%=*}"
    if grep -qx "$kv" "$avd_dir/config.ini"; then ok "$kv"
    elif [[ $check_only == 1 ]]; then missing "$kv in $avd_dir/config.ini"
    else
      doing "$kv"
      grep -v "^$key=" "$avd_dir/config.ini" >"$avd_dir/config.ini.tmp" || true
      echo "$kv" >>"$avd_dir/config.ini.tmp"
      mv "$avd_dir/config.ini.tmp" "$avd_dir/config.ini"
    fi
  done
fi

section "Python venv (tools/.venv)"
venv="$root/tools/.venv"
req_sum="$(cat tools/requirements.txt tools/requirements-art.txt | shasum -a 256 | cut -d' ' -f1)"
venv_ok() { [[ -x "$venv/bin/python" && "$(cat "$venv/.requirements.sha" 2>/dev/null)" == "$req_sum" ]]; }
if ! need "tools/.venv with tools/requirements*.txt" venv_ok; then
  [[ -x "$venv/bin/python" ]] || python3 -m venv "$venv"
  "$venv/bin/pip" install -q --upgrade pip
  "$venv/bin/pip" install -q -r tools/requirements.txt -r tools/requirements-art.txt
  echo "$req_sum" >"$venv/.requirements.sha"
fi

section "Node packages"
deps_ok() { [[ -f node_modules/.modules.yaml && ! pnpm-lock.yaml -nt node_modules/.modules.yaml ]]; }
need "pnpm install" deps_ok || pnpm install --frozen-lockfile
chromium_ok() {
  (cd packages/rivc-preview && node -e 'const fs=require("fs");const p=require("playwright").chromium.executablePath();process.exit(fs.existsSync(p)?0:1)')
}
need "Playwright Chromium (goldens)" chromium_ok || (cd packages/rivc-preview && pnpm exec playwright install chromium)

echo
if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "${R}${B}bootstrap: ${#MISSING[@]} missing:${N}"
  for m in "${MISSING[@]}"; do echo "  - $m"; done
  echo "Run bash scripts/bootstrap.sh (without --check) to install them."
  exit 1
fi
cat <<EOF
${G}${B}bootstrap: all set.${N}
Shell environment (or just: . scripts/env.sh):
  export JAVA_HOME="$JAVA_HOME"
  export ANDROID_HOME="$ANDROID_HOME"
  export PATH="\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$ANDROID_HOME/cmdline-tools/latest/bin:\$JAVA_HOME/bin:\$PATH"
Next: turbo dev
EOF
