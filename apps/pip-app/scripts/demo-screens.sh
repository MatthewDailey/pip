#!/usr/bin/env bash
# End-to-end screen-fraud demo on emulator-5554.
# Assumes jev-playground is serving on localhost:3107 and the Pip debug APK is installed.
# Usage (from apps/pip-app): ./scripts/demo-screens.sh
# Optional: RECORD=0 ./scripts/demo-screens.sh   # skip screenrecord
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"
SERIAL="${SERIAL:-emulator-5554}"
PORT="${PIP_SERVER_PORT:-3107}"
OUT="${OUT:-$HOME/code/pip-screens-demo.mp4}"
RECORD="${RECORD:-1}"

adb() { "$ADB" -s "$SERIAL" "$@"; }

echo "device: $(adb get-state 2>/dev/null || true)"
if ! adb get-state >/dev/null 2>&1; then
  echo "emulator $SERIAL is not online" >&2
  exit 1
fi

# Don't stomp a recording already in progress (the call-path demo may be using this emulator).
if adb shell pidof screenrecord >/dev/null 2>&1; then
  echo "screenrecord is already running on $SERIAL; not starting a demo" >&2
  exit 2
fi

echo "adb reverse tcp:$PORT tcp:$PORT"
adb reverse tcp:"$PORT" tcp:"$PORT"
# Port 80 so lookalike hosts look like http://chase-secure-verify.co/login
adb reverse tcp:80 tcp:"$PORT" || true

echo "grant notifications + enable accessibility"
adb shell pm grant dev.playground.pip android.permission.POST_NOTIFICATIONS || true
adb shell settings put secure enabled_accessibility_services dev.playground.pip/dev.playground.pip.sources.GuardAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Lookalike hosts -> emulator loopback (then adb reverse to the host machine).
# Requires a writable system image (typical for the userdebug emulator).
if adb root >/dev/null 2>&1; then
  adb remount >/dev/null 2>&1 || true
  adb shell 'toybox grep -q chase-secure-verify.co /system/etc/hosts || \
    (printf "\\n127.0.0.1 chase-secure-verify.co security-alert-check.xyz usps-redelivery-help.com\\n" >> /system/etc/hosts)' || true
fi

SCAM_URL="http://chase-secure-verify.co/login"
BENIGN_URL="http://127.0.0.1/demo/screens/usps_tracking"
# Fallback if port-80 reverse or hosts failed:
if ! adb shell "toybox grep -q chase-secure-verify.co /system/etc/hosts" >/dev/null 2>&1; then
  SCAM_URL="http://127.0.0.1:${PORT}/demo/screens/chase_fake"
  BENIGN_URL="http://127.0.0.1:${PORT}/demo/screens/usps_tracking"
fi

REMOTE_MP4="/sdcard/Download/pip-screens-demo.mp4"
adb shell rm -f "$REMOTE_MP4" || true

if [ "$RECORD" = "1" ]; then
  echo "recording -> $REMOTE_MP4"
  adb shell screenrecord --time-limit 60 "$REMOTE_MP4" &
  REC_PID=$!
  sleep 1
fi

# Launch Chrome (or the stock browser) on the scam page, wait for Jev, then open a benign page.
open_url() {
  local url="$1"
  adb shell am start -a android.intent.action.VIEW -d "$url" \
    -n com.android.chrome/com.google.android.apps.chrome.Main >/dev/null 2>&1 \
  || adb shell am start -a android.intent.action.VIEW -d "$url" >/dev/null
}

echo "open scam: $SCAM_URL"
open_url "$SCAM_URL"
sleep 8
echo "open benign: $BENIGN_URL"
open_url "$BENIGN_URL"
sleep 8

if [ "$RECORD" = "1" ]; then
  # screenrecord --time-limit will exit on its own; stop early if still running.
  adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
  wait "$REC_PID" 2>/dev/null || true
  mkdir -p "$(dirname "$OUT")"
  adb pull "$REMOTE_MP4" "$OUT"
  echo "saved $OUT"
fi

echo "logcat (ScreenGuard/ScreenClient):"
adb logcat -d -s ScreenGuard:I ScreenClient:I PipEvents:I PipServer:W | tail -n 80 || true
