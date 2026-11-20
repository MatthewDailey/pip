#!/usr/bin/env bash
# Install the debug app and, on an emulator, grant everything Pip's setup would ask for, so the app opens on a
# setup checklist that is already ticked off (Continue goes to the conversation home and the first-run greeting).
#
#   scripts/setup.sh              # build, install, grant (emulator), fetch the speech model, open the app
#   scripts/setup.sh --manual     # build, install, take every grant back: Pip's setup asks for each one, as on a phone
#   scripts/setup.sh --no-build   # install the APK already built
#   scripts/setup.sh --grant-only # no build or install: grant and fetch the speech model for the installed app
#   scripts/setup.sh --no-open    # leave the app closed at the end
#
# On a physical phone it installs and opens the app without granting anything unless you pass --grant; a grant the
# phone refuses is listed at the end instead of stopping the script (Pip's setup then asks for it).
#
# What it grants: the runtime permissions (phone, call log, contacts, SMS, microphone, notifications, hanging up),
# full-screen warnings, restricted
# settings, file access for pushed .riv files, the accessibility service (added to the existing list, not replacing
# it), the notification listener, the call-screening role and a battery-optimisation exemption. MODIFY_AUDIO_SETTINGS,
# VIBRATE and INTERNET are normal permissions Android grants at install. Pip draws through the accessibility service
# (TYPE_ACCESSIBILITY_OVERLAY), so there is no "display over other apps" grant. It also downloads the on-device
# speech model, so the system's download prompt never appears during a call.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. ../../scripts/env.sh
P=dev.playground.pip
A11Y="$P/$P.sources.GuardAccessibilityService"
LISTENER="$P/$P.sources.GuardNotificationListener"
RUNTIME=(READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS RECEIVE_SMS READ_SMS RECORD_AUDIO POST_NOTIFICATIONS ANSWER_PHONE_CALLS)

build=1 install=1 open=1 mode=auto
for arg in "$@"; do
  case "$arg" in
    --manual) mode=manual ;;
    --grant) mode=grant ;;
    --no-build) build=0 ;;
    --grant-only) build=0 install=0 mode=grant ;;
    --no-open) open=0 ;;
    *) echo "setup: unknown option $arg (see the top of scripts/setup.sh)" >&2; exit 2 ;;
  esac
done

adb get-state >/dev/null 2>&1 || { echo "setup: no emulator or device; start one (turbo droid)" >&2; exit 1; }
emulator=0
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] && emulator=1
[[ "$mode" == auto ]] && { if ((emulator)); then mode=grant; else mode=none; fi; }

if ((build)); then echo "setup: building"; ./gradlew assembleDebug -q; fi
if ((install)); then echo "setup: installing"; adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null; fi

a11y_list() { adb shell settings get secure enabled_accessibility_services | tr -d '\r'; }

refused=()
# Runs one grant step; a device that refuses it (a phone's OEM policy, an older Android) is reported, not fatal.
try() {
  local out
  if ! out="$(adb shell "$@" 2>&1)" || grep -qiE 'exception|error|not allowed|denied|unknown' <<<"$out"; then
    refused+=("$*: $(head -1 <<<"$out" | tr -d '\r')")
  fi
}

grant() {
  echo "setup: granting permissions, roles and services"
  for p in "${RUNTIME[@]}"; do try pm grant $P android.permission.$p; done
  try appops set $P ACCESS_RESTRICTED_SETTINGS allow
  # The full-screen scam warning during a call; an app-op since Android 14.
  try appops set $P USE_FULL_SCREEN_INTENT allow
  # Lets the players read .riv files pushed to /sdcard/Download (pip:push, pip:story, pip:roam) without a reinstall.
  try appops set --uid $P MANAGE_EXTERNAL_STORAGE allow
  local current next
  current="$(a11y_list)"
  case ":$current:" in
    *":$A11Y:"*) ;;
    *)
      if [[ -z "$current" || "$current" == null ]]; then next="$A11Y"; else next="$current:$A11Y"; fi
      try settings put secure enabled_accessibility_services "$next" ;;
  esac
  try settings put secure accessibility_enabled 1
  try cmd notification allow_listener "$LISTENER"
  try cmd role add-role-holder android.app.role.CALL_SCREENING $P 0
  try dumpsys deviceidle whitelist +$P
  if ((${#refused[@]})); then
    echo "setup: the device refused ${#refused[@]} grant(s); Pip's setup will ask for these:" >&2
    printf '  - %s\n' "${refused[@]}" >&2
  fi
}

revoke() {
  echo "setup: taking every grant back, so Pip's setup asks for each one"
  for p in "${RUNTIME[@]}"; do adb shell pm revoke $P android.permission.$p 2>/dev/null || true; done
  local current next
  current="$(a11y_list)"
  next="$(tr ':' '\n' <<<"$current" | grep -vx "$A11Y" | paste -sd: - || true)"
  [[ "$next" != "$current" ]] && adb shell settings put secure enabled_accessibility_services "${next:-\"\"}"
  adb shell appops set $P USE_FULL_SCREEN_INTENT default
  adb shell cmd notification disallow_listener "$LISTENER"
  adb shell cmd role remove-role-holder android.app.role.CALL_SCREENING $P 0
  adb shell dumpsys deviceidle whitelist -$P >/dev/null
  adb shell am broadcast -n $P/.sources.FlagsReceiver --ez setup_done false >/dev/null
}

speech() { adb shell am broadcast -n $P/.sources.SpeechModelReceiver --es do "$1" | sed -n 's/.*data="\([A-Z]*\)".*/\1/p'; }

# The recognizer may ask before downloading ("Download English?"). On an emulator we answer it: the dialog's
# positive button is found by its text in a UI dump and tapped.
confirm_download_prompt() {
  local xml bounds
  xml="$(adb exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
  bounds="$(grep -oE 'text="(Download|DOWNLOAD|OK|Install|Continue)"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' <<<"$xml" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)"
  [[ -z "$bounds" ]] && return 1
  read -r x1 y1 x2 y2 < <(tr '[],' '   ' <<<"$bounds")
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

fetch_speech_model() {
  local state
  state="$(speech check)"
  case "$state" in
    INSTALLED) echo "setup: speech model installed"; return ;;
    UNSUPPORTED|"") echo "setup: no on-device speech recognizer; calls are recorded without transcripts"; return ;;
  esac
  echo "setup: downloading the on-device speech model ($state)"
  ((emulator)) || echo "setup: if the phone asks to download a speech model (\"Download English?\"), accept it there"
  speech download >/dev/null
  for _ in $(seq 1 45); do
    state="$(speech check)"
    [[ "$state" == INSTALLED ]] && { echo "setup: speech model installed"; return; }
    if [[ "$state" == DOWNLOADABLE ]] && ((emulator)) && confirm_download_prompt; then echo "setup: accepted the download prompt"; fi
    sleep 2
  done
  echo "setup: speech model still $state; calls skip transcription until it arrives (rerun with --grant-only)"
}

case "$mode" in
  grant) grant; fetch_speech_model ;;
  manual) revoke ;;
  none) echo "setup: a physical device: nothing granted (pass --grant to grant over adb)" ;;
esac

if ((open)); then adb shell am start -n $P/.MainActivity >/dev/null; fi
echo "setup: done"
