#!/usr/bin/env bash
# Drive Pip on the running emulator through the app's broadcasts (handled by GuardAccessibilityService, so run
# app:setup first).
#
#   scripts/pip.sh show [sampler|peeks]   # the Kotlin showreel: a sampler, the peeks, or (no argument) the full show
#   scripts/pip.sh rive [hodor|showreel]  # the Rive showreel (compiled by rivc), played full screen; default hodor
#   scripts/pip.sh mood [0-4|name]        # Rive overlay mood: 0 neutral, 1 happy, 2 sad, 3 angry, 4 surprised
#   scripts/pip.sh fire <trigger>         # fire a Rive trigger, e.g. blink
#   scripts/pip.sh push <file.riv>        # play a compiled .riv without reinstalling (reload from /sdcard/Download)
#   scripts/pip.sh bundled                # back to the .riv bundled in the APK
#   scripts/pip.sh story <file.riv>       # push a story to /sdcard/Download/<name>.riv and play it full screen, no reinstall
#   scripts/pip.sh roam [seed [seconds]]  # Pip roams the home screen (pushes dist/roam/roam.riv first); a seed replays a run
#   scripts/pip.sh roam-stop              # stop roaming (or any show) and bring the overlay Pip back
#   scripts/pip.sh exploratory on|off     # the developer flag for experiments (wandering, showreels, roam, warnings)
#   scripts/pip.sh detect-scam-call [--foreground] [reason [evidence]]  # tell Pip the live call is a scam; he mutes (rule on) or warns
#   scripts/pip.sh act-unmute             # as if the person tapped "Unmute me" on Pip's card
#   scripts/pip.sh mute-rule on|off|default  # the "mute me on scam calls" rule (debug builds default it on)
#   scripts/pip.sh greet [reset]          # replay the first-run greeting (pushes dist's hello-install.riv); reset clears
#                                         # "greeted" and setup's "done" instead: the next open shows setup, the one after greets
#   scripts/pip.sh voice list             # the engine's English voices and the one Pip picks for each purpose
#   scripts/pip.sh voice set <name>|reset # use another voice (e.g. en-us-x-iom-local) until reset
#   scripts/pip.sh voice render [line...] # render lines in Pip's voice to .proof/voices/pip-lines/<slug>.wav (default:
#                                         # every say: line in the stories), for demo soundtracks (PIP_VOICE_DIR)
# Prefer the turbo targets, which build first: turbo run pip:story -- <file.riv>, pip:roam [-- <seed>], pip:roam-stop,
# pip:greet [-- reset].
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../../../scripts/env.sh"
APP=dev.playground.pip
cmd="${1:-help}"
shift || true

case "$cmd" in
  show)
    if [[ -n "${1:-}" ]]; then adb shell am broadcast -a $APP.SHOW --es show "$1"
    else adb shell am broadcast -a $APP.SHOW; fi ;;
  rive) adb shell am broadcast -a $APP.SHOW --es engine rive --es riv "${1:-hodor}" ;;
  mood)
    # The rig's mood is a view-model enum, so numbers are sent as their names.
    names=(neutral happy sad angry surprised)
    value="${1:-2}"
    if [[ "$value" =~ ^[0-4]$ ]]; then value="${names[$value]}"; fi
    adb shell am broadcast -a $APP.PIP --es set mood --es value "$value" ;;
  fire) adb shell am broadcast -a $APP.PIP --es fire "${1:?trigger name, e.g. blink}" ;;
  push)
    file="${1:?path to a .riv}"
    adb push "$file" /sdcard/Download/pip.riv
    adb shell am broadcast -a $APP.PIP --es reload true ;;
  bundled) adb shell am broadcast -a $APP.PIP --es reload false ;;
  story)
    # The player prefers /sdcard/Download/<name>.riv over the bundled assets/showreel/<name>.riv.
    file="${1:?path to a story .riv, e.g. packages/pip-rive/dist/showreel/hello.riv}"
    # Turbo runs this from apps/pip-app; a path from the repo root works too.
    [[ -f "$file" || ! -f "$SHADY_PIP_ROOT/$file" ]] || file="$SHADY_PIP_ROOT/$file"
    name="$(basename "$file" .riv)"
    adb push "$file" "/sdcard/Download/$name.riv"
    adb shell am broadcast -a $APP.SHOW --es engine rive --es riv "$name" ;;
  roam)
    # The director prefers /sdcard/Download/roam.riv over the bundled assets/roam/roam.riv.
    riv="$SHADY_PIP_ROOT/packages/pip-rive/dist/roam/roam.riv"
    [[ -f "$riv" ]] && adb push "$riv" /sdcard/Download/roam.riv >/dev/null
    args=(--es show roam)
    [[ -n "${1:-}" ]] && args+=(--el seed "$1")
    [[ -n "${2:-}" ]] && args+=(--ef seconds "$2")
    adb shell am broadcast -a $APP.SHOW "${args[@]}"
    echo "roam: adb logcat -s PipShow:I | grep ROAM" ;;
  roam-stop) adb shell am broadcast -a $APP.SHOW --es show stop ;;
  exploratory)
    case "${1:-on}" in on|true) v=true ;; off|false) v=false ;; *) echo "exploratory on|off" >&2; exit 2 ;; esac
    adb shell am broadcast -a $APP.FLAGS -n $APP/.sources.FlagsReceiver --ez exploratory "$v" ;;
  detect-scam-call)
    # --foreground forces the mute ladder's step 3 (bring the in-call screen up and press its Mute), for testing.
    foreground=false
    if [[ "${1:-}" == --foreground ]]; then foreground=true; shift; fi
    # adb shell re-splits its arguments, so values with spaces are quoted again for the device shell.
    reason="${1:-asked for gift cards}"
    args=(--es detect scam-call --es reason "'$reason'")
    [[ -n "${2:-}" ]] && args+=(--es evidence "'$2'")
    [[ -n "${PIP_MUTE_PATH:-}" ]] && args+=(--es path "$PIP_MUTE_PATH")
    [[ "${PIP_SPEAK:-1}" == 0 ]] && args+=(--ez speak false)
    [[ "$foreground" == true ]] && args+=(--ez foreground true)
    adb shell am broadcast -a $APP.PIP "${args[@]}"
    echo "detect-scam-call: adb logcat -s PipEvents:I | grep scam-call" ;;
  act-unmute) adb shell am broadcast -a $APP.PIP --es respond undo ;;
  mute-rule) adb shell am broadcast -a $APP.PIP --es rule mute-scam-calls --es value "${1:?on, off or default}" ;;
  greet)
    # The greeting plays through the same player as stories, so a pushed build replaces the bundled one.
    riv="$SHADY_PIP_ROOT/packages/pip-rive/dist/showreel/hello-install.riv"
    [[ -f "$riv" ]] && adb push "$riv" /sdcard/Download/hello-install.riv >/dev/null
    if [[ "${1:-}" == reset ]]; then adb shell am broadcast -a $APP.GREET --es greet reset
    else adb shell input keyevent KEYCODE_HOME; adb shell am broadcast -a $APP.GREET; fi
    echo "greet: adb logcat -s PipShow:I | grep -E 'GREET|PIPCUE|PIPSHOW'" ;;
  voice)
    rcv=(-n $APP/.voice.PipVoiceReceiver)
    sub="${1:-list}"; shift || true
    case "$sub" in
      list) args=(--es voice list) ;;
      set) args=(--es voice set --es name "${1:?voice name, from pip:voice -- list}") ;;
      reset) args=(--es voice reset) ;;
      render)
        if (( $# )); then lines="$(printf '%s|' "$@")"
        else lines="$(grep -ho '"say:[^"]*"' "$SHADY_PIP_ROOT"/packages/pip-rive/stories/*/*.json | sed 's/^"say://; s/"$//' | sort -u | paste -sd'|' -)"; fi
        args=(--es voice render --es lines "\"${lines%|}\"") ;;
      *) echo "voice list|set <name>|reset|render [line...]" >&2; exit 2 ;;
    esac
    adb logcat -c
    adb shell am broadcast "${rcv[@]}" "${args[@]}" >/dev/null
    for _ in $(seq 1 120); do adb logcat -d -s PipVoice:I | grep -q ' END$' && break; sleep 0.5; done
    adb logcat -d -v raw -s PipVoice:I | grep -v '^--------- '
    if [[ "$sub" == render ]]; then
      out="$SHADY_PIP_ROOT/.proof/voices/pip-lines"; mkdir -p "$out"
      adb pull /sdcard/Android/data/$APP/files/voice/. "$out/" >/dev/null && echo "voice: rendered to $out"
    fi ;;
  *) sed -n '2,25p' "$0" ;;
esac
