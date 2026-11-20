#!/usr/bin/env bash
# Follow the app's logs: the PipEvents event log, the Kotlin showreel (PipShow) and the Rive overlay (PipRive).
# Waits for a device and reconnects when the emulator restarts. Extra tags: LOGCAT_TAGS="Foo Bar".
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/env.sh"

filters=()
for tag in PipEvents PipShow PipRive ${LOGCAT_TAGS:-}; do filters+=("$tag:V"); done
while true; do
  echo "logcat: waiting for a device"
  adb wait-for-device
  echo "logcat: following ${filters[*]}"
  adb logcat -T 1 -v time "${filters[@]}" '*:S'
  sleep 2
done
