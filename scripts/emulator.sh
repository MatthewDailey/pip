#!/usr/bin/env bash
# Boot the Pip emulator, or attach to one that is already running, then report its state until stopped.
#
#   scripts/emulator.sh            # AVD_NAME (default Pip_API_37)
#   EMULATOR_FOREGROUND=1 ...      # tie the emulator to this process (quitting the TUI shuts it down)
#
# By default a newly booted emulator is detached (log: .turbo/emulator.log), so quitting `turbo dev` leaves it
# running and the next run attaches in a second.
#
# Flags: -crash-report-mode never keeps the crash dialog away. Never add -no-audio: it disables the virtual mic
# that the mock calls inject audio into.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/env.sh"

running_emulator() { adb devices 2>/dev/null | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'; }

serial="$(running_emulator || true)"
if [[ -n "$serial" ]]; then
  echo "emulator: attached to $serial ($(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r'))"
else
  command -v emulator >/dev/null || { echo "emulator: Android emulator not found; run scripts/bootstrap.sh" >&2; exit 1; }
  emulator -list-avds | grep -qx "$AVD_NAME" || { echo "emulator: no AVD named $AVD_NAME; run scripts/bootstrap.sh or set AVD_NAME" >&2; exit 1; }
  args=(-avd "$AVD_NAME" -crash-report-mode never)
  if [[ -n "${EMULATOR_FOREGROUND:-}" ]]; then
    echo "emulator: booting $AVD_NAME (foreground)"
    exec emulator "${args[@]}"
  fi
  mkdir -p "$SHADY_PIP_ROOT/.turbo"
  log="$SHADY_PIP_ROOT/.turbo/emulator.log"
  echo "emulator: booting $AVD_NAME (detached; log $log)"
  nohup emulator "${args[@]}" >"$log" 2>&1 &
  disown || true
  adb wait-for-device
  serial="$(running_emulator || true)"
fi

printf 'emulator: waiting for boot'
until [[ "$(adb ${serial:+-s "$serial"} shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do printf .; sleep 2; done
echo
echo "emulator: ready. Try app:setup, then pip:sampler (pnpm menu, or the menu pane)."

last=""
while true; do
  state="$(adb ${serial:+-s "$serial"} get-state 2>/dev/null || echo offline)"
  [[ "$state" != "$last" ]] && echo "[$(date +%H:%M:%S)] ${serial:-emulator}: $state" && last="$state"
  sleep 5
done
