#!/usr/bin/env bash
# Fake signals on a running emulator. The emulator call has no real audio; it exercises call screening and call state only.
#   scripts/simulate.sh sms   [number] [text]
#   scripts/simulate.sh call  [number] [seconds]
#   scripts/simulate.sh scam-sms
#   scripts/simulate.sh transcript [speaker] [text] [callId]   # push a line to SimTranscriptReceiver (and the server)
#   scripts/simulate.sh gift-card [callId]                     # user mentions a Target gift card, then reads digits
#   scripts/simulate.sh setup        # same as scripts/setup.sh: install the debug APK and grant everything (emulator)
#   scripts/simulate.sh log          # follow the app's event log
set -euo pipefail
P=dev.playground.pip
cmd=${1:-help}

case "$cmd" in
  sms) adb emu sms send "${2:-5551234567}" "${3:-Hi, are we still on for lunch?}" ;;
  scam-sms) adb emu sms send 5554443333 "Grandma its me, Im in jail and need bail money right now. Dont tell mom. Buy Google Play gift cards and read me the code." ;;
  call)
    n=${2:-8005551212}
    adb emu gsm call "$n"; adb shell sleep 3
    adb emu gsm accept "$n"; adb shell sleep "${3:-10}"
    adb emu gsm cancel "$n" ;;
  transcript)
    speaker=${2:-user}
    text=${3:-hello}
    call=${4:-sim-1}
    adb shell "am broadcast -n $P/.sources.SimTranscriptReceiver --es speaker '$speaker' --es text '$text' --es call '$call'" ;;
  gift-card)
    call=${2:-gift-demo}
    # Unknown inbound caller + "Target gift card" should alert; reading digits should take over.
    adb shell "am broadcast -n $P/.sources.SimTranscriptReceiver --es speaker user --es text 'They want me to buy a Target gift card' --es call '$call' --es direction inbound --es number 5551112222 --ez in_contacts false"
    adb shell sleep 1
    adb shell "am broadcast -n $P/.sources.SimTranscriptReceiver --es speaker user --es text 'The numbers are four one one one two two two two three three three three four four four four' --es call '$call'" ;;
  setup) shift; exec bash "$(dirname "$0")/setup.sh" "$@" ;;
  log) adb logcat -s PipEvents:I | sed -u 's/^.*PipEvents: //' ;;
  *) sed -n '2,10p' "$0" ;;
esac
