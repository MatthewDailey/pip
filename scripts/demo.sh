#!/usr/bin/env bash
# turbo demo: get a connected device ready to show Pip to someone, from any state. It builds the app, installs it
# fresh (the old app, its data and pushed .riv files go), grants everything over adb, fetches the speech model,
# resets the demo state (mute rule, experiments, greeting), goes home and prints the demo script.
#
#   turbo demo                        # build and set up the one connected device (a phone wins over an emulator)
#   turbo demo -- --no-build          # reuse the last build
#   turbo demo -- --device <serial>   # pick a device when several are connected (adb devices lists them)
#   turbo demo -- --fixtures          # also install the fixture apps the scenarios open
#   turbo demo:reset                  # only re-arm the first-run greeting on the installed app (scripts/demo.sh reset)
#
# It never boots an emulator: start one with turbo droid first if that's what you want to demo on.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/env.sh"
. "$here/device.sh"
APP="$SHADY_PIP_ROOT/apps/pip-app"
FIXTURES="$SHADY_PIP_ROOT/apps/pip-fixtures"
P=dev.playground.pip
OLD=dev.playground.elderguard

build=1 fixtures=0 reset_only=0 serial="${ANDROID_SERIAL:-}"
[[ "${1:-}" == reset ]] && { reset_only=1; shift; }
while (($#)); do
  case "$1" in
    --no-build) build=0 ;;
    --fixtures) fixtures=1 ;;
    --device) serial="${2:?--device needs a serial (see adb devices)}"; shift ;;
    --device=*) serial="${1#--device=}" ;;
    *) echo "demo: unknown option $1 (see the top of scripts/demo.sh)" >&2; exit 2 ;;
  esac
  shift
done

pick_device "$serial"

# The mute rule and the greeting's receivers live in Pip's accessibility service.
wait_for_service() {
  local _
  for _ in $(seq 1 30); do
    adb shell dumpsys accessibility | grep -q "Bound services:.*label=Pip" && return
    sleep 1
  done
  echo "demo: Pip's accessibility service isn't running; run turbo demo to install and grant it" >&2
  exit 1
}

# pip:greet -- reset's broadcast: clears "greeted" and setup's "done", so the next open shows setup and the one
# after it greets. Sent directly because pip:greet also pushes a .riv into /sdcard/Download.
arm_greeting() { adb shell am broadcast -a $P.GREET -p $P --es greet reset >/dev/null; }

installed() { adb shell pm list packages "$1" | tr -d '\r' | grep -qx "package:$1"; }

if ((reset_only)); then
  installed "$P" || { echo "demo: Pip isn't installed on $ANDROID_SERIAL; run turbo demo" >&2; exit 1; }
  wait_for_service
  arm_greeting
  echo "Intro armed: open Pip for setup, tap Continue, then open it again and Pip greets you"
  exit 0
fi

# --- Build -----------------------------------------------------------------------------------------------------
if ((build)); then
  echo "demo: building the app"
  (cd "$APP" && ./gradlew assembleDebug -q)
  if ((fixtures)); then echo "demo: building the fixture apps"; (cd "$FIXTURES" && ./gradlew assembleDebug -q); fi
fi
apk="$APP/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo "demo: no build at $apk; run without --no-build" >&2; exit 1; }

# --- Fresh install ---------------------------------------------------------------------------------------------
for pkg in "$P" "$OLD"; do
  if installed "$pkg"; then echo "demo: uninstalling $pkg"; adb uninstall "$pkg" >/dev/null; fi
done
echo "demo: clearing .riv files from /sdcard/Download"
adb shell 'rm -f /sdcard/Download/*.riv'
echo "demo: installing"
adb install "$apk" >/dev/null
if ((fixtures)); then
  echo "demo: installing the fixture apps"
  adb install -r "$FIXTURES/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
fi

# --- Grant everything and fetch the speech model ---------------------------------------------------------------
bash "$APP/scripts/setup.sh" --grant-only --no-open

# --- Reset the demo state --------------------------------------------------------------------------------------
# A fresh install already starts clean; these say so explicitly, so the demo never depends on an app default.
# The greeting reset also clears setup's "done": the first open shows setup, the second greets.
wait_for_service
pip() { bash "$APP/scripts/pip.sh" "$@" >/dev/null; }
pip mute-rule default
pip exploratory off
pip voice reset
arm_greeting
echo "demo: reset the mute rule, experiments (off), Pip's voice and the first-run greeting"

# Wake the screen and drop a swipe-only lock screen; a phone with a PIN stays locked for the person to unlock.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_HOME

cat <<'EOF'

Pip is ready to demo:
  1. Open Pip: setup is all ticked; tap Continue.
  2. Go home, then open Pip again: Pip greets you on the home screen.
  3. turbo run call:irs, then turbo run pip:detect-scam-call -- "asked for gift cards":
     Pip mutes the call; tap Unmute me.
EOF
