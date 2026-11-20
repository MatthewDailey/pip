#!/usr/bin/env bash
# Pip's live scam check without audio: an emulator phone call, with Mom's side of a scam call
# (tools/mock-calls/mom/<script>.txt) sent to the app as if the phone had transcribed it (SimTranscriptReceiver). The app posts each line to pip-call, gets Pip4's verdict back
# and mutes + shows the warning screen. Prints the app's verdict log (with round-trip times) and saves a screenshot.
#   scripts/live-call.sh [irs] [seconds between lines, default 3]
# For the real thing with speech, use tools/mock-calls (call:irs): the same lines go through the mic and recognizer.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script=${1:-irs}
gap=${2:-3}
lines="$here/../../../tools/mock-calls/mom/$script.txt"
P=dev.playground.pip
n=2025550143
call="live-$(date +%s)"

adb logcat -c
adb emu gsm call "$n"; sleep 2; adb emu gsm accept "$n"; sleep 2
grep -v '^\s*$' "$lines" | grep -viE '^(voice\s*:|#)' | while IFS= read -r line; do
  echo "mom: $line"
  adb shell am broadcast -n $P/.sources.SimTranscriptReceiver --es speaker user --es call "$call" --es text "$(printf %q "$line")" --es direction inbound --es number 2025550143 --ez in_contacts false --es verification not_verified </dev/null >/dev/null
  sleep "$gap"
done
sleep 3
mkdir -p "$here/../../../.proof"
adb exec-out screencap -p > "$here/../../../.proof/pip-live-$script.png"
adb emu gsm cancel "$n" || true
echo
adb logcat -d -s PipCallVerdicts | grep -E 'verdict|not scored|blocked' || echo "no verdicts: is pip.appSecret set in apps/pip-app/local.properties?"
echo "screenshot: .proof/pip-live-$script.png"
