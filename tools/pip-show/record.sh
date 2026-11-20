#!/usr/bin/env bash
# Record the Pip show on the running emulator and mux it with the synthesised soundtrack.
#
#   tools/pip-show/record.sh
#
# Env overrides: ADB (default adb), SYNC_NUDGE (seconds added to the computed audio offset,
# + delays the audio), SHOW_TIMEOUT (seconds to wait for `PIPSHOW end`, default 200),
# SHOW_ARGS (extra SHOW broadcast args, e.g. "--es engine rive --es riv hodor"), SHOW_ACTION (the broadcast that
# starts it, default dev.playground.pip.SHOW; dev.playground.pip.GREET records the first-run greeting),
# OUT_NAME (output basename, default pip_show), and a soundtrack window for a clip of the show:
# SOUNDTRACK_FROM (show time the clip starts at; its cues, logged from 0, are shifted by it) and
# SOUNDTRACK_TO (show time the clip ends at). Audio outside the window is faded out. Spoken `say:` lines use Pip's
# device voice from PIP_VOICE_DIR (default .proof/voices/pip-lines, filled by `turbo run pip:voice -- render`),
# falling back to macOS `say` for a line that isn't there.
#
#   SHOW_ARGS="--es engine rive --es riv hodor" SOUNDTRACK_FROM=112 SOUNDTRACK_TO=121 \
#     OUT_NAME=hodor-rive tools/pip-show/record.sh
#
# Output: .proof/<OUT_NAME>_raw.mp4 (video only) and .proof/<OUT_NAME>.mp4 (with soundtrack).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PROOF="$ROOT/.proof"
PY="$ROOT/tools/.venv/bin/python"
FFMPEG="$(command -v ffmpeg || echo /opt/homebrew/bin/ffmpeg)"
FFPROBE="$(command -v ffprobe || echo /opt/homebrew/bin/ffprobe)"
ADB="${ADB:-adb}"
SYNC_NUDGE="${SYNC_NUDGE:-0}"
SHOW_TIMEOUT="${SHOW_TIMEOUT:-200}"
SHOW_ARGS="${SHOW_ARGS:-}"
SHOW_ACTION="${SHOW_ACTION:-dev.playground.pip.SHOW}"
OUT_NAME="${OUT_NAME:-pip_show}"
export PIP_VOICE_DIR="${PIP_VOICE_DIR:-$PROOF/voices/pip-lines}"
SOUNDTRACK_FROM="${SOUNDTRACK_FROM:-0}"
SOUNDTRACK_TO="${SOUNDTRACK_TO:-}"
DEVICE_MP4=/sdcard/pip_show.mp4
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pip_show.XXXXXX")"
REC_PID=""

die() { echo "record.sh: error: $*" >&2; exit 1; }
say() { echo "record.sh: $*" >&2; }
cleanup() {
  if [[ -n "$REC_PID" ]] && kill -0 "$REC_PID" 2>/dev/null; then
    "$ADB" shell pkill -INT screenrecord >/dev/null 2>&1 || true
    kill "$REC_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

for tool in "$PY" "$FFMPEG" "$FFPROBE"; do [[ -x "$tool" ]] || die "missing $tool"; done
command -v "$ADB" >/dev/null || die "adb not found (set ADB=/path/to/adb)"
[[ "$("$ADB" get-state 2>/dev/null)" == "device" ]] || die "no device/emulator connected"
mkdir -p "$(dirname "$PROOF/$OUT_NAME")"

say "going home and clearing logcat"
"$ADB" shell input keyevent KEYCODE_HOME
sleep 1
"$ADB" logcat -c
"$ADB" shell rm -f "$DEVICE_MP4"

# Take the timestamp on the device in the same shell that execs screenrecord, so adb
# latency does not leak into the sync offset.
say "starting screenrecord"
"$ADB" shell "echo REC_T0 \$(date +%s.%N); exec screenrecord --bit-rate 12000000 $DEVICE_MP4" >"$WORK/rec.out" 2>&1 &
REC_PID=$!
for _ in $(seq 1 50); do grep -q '^REC_T0' "$WORK/rec.out" 2>/dev/null && break; sleep 0.1; done
REC_T0="$(awk '/^REC_T0/ {print $2; exit}' "$WORK/rec.out" | tr -d '\r')"
if [[ -z "$REC_T0" || "$REC_T0" == *N* ]]; then
  die "could not read the device clock when screenrecord started (got '${REC_T0:-nothing}')"
fi
kill -0 "$REC_PID" 2>/dev/null || die "screenrecord exited early: $(cat "$WORK/rec.out")"
say "screenrecord started at device time $REC_T0"

sleep 1.5
say "starting the show"
# shellcheck disable=SC2086  # SHOW_ARGS is a list of broadcast arguments
"$ADB" shell am broadcast -a "$SHOW_ACTION" $SHOW_ARGS >/dev/null

say "waiting for PIPSHOW end (timeout ${SHOW_TIMEOUT}s)"
deadline=$((SECONDS + SHOW_TIMEOUT))
until "$ADB" logcat -d -s PipShow:I | grep -q "PIPSHOW end"; do
  (( SECONDS < deadline )) || die "timed out waiting for PIPSHOW end"
  kill -0 "$REC_PID" 2>/dev/null || die "screenrecord stopped before the show ended (3-minute limit?)"
  sleep 0.5
done
sleep 1.5

say "stopping screenrecord"
"$ADB" shell pkill -INT screenrecord || true
for _ in $(seq 1 100); do kill -0 "$REC_PID" 2>/dev/null || break; sleep 0.1; done
kill -0 "$REC_PID" 2>/dev/null && die "screenrecord did not stop"
wait "$REC_PID" 2>/dev/null || true
REC_PID=""
sleep 1  # let the muxer finish writing the moov atom

"$ADB" pull "$DEVICE_MP4" "$PROOF/${OUT_NAME}_raw.mp4" >/dev/null || die "adb pull failed"
VDUR="$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$PROOF/${OUT_NAME}_raw.mp4")"
[[ -n "$VDUR" ]] || die "pulled video is unreadable"
say "video: $PROOF/${OUT_NAME}_raw.mp4 (${VDUR}s)"

"$ADB" logcat -d -v epoch -s PipShow:I >"$WORK/logcat.txt"
OFFSET="$("$PY" "$HERE/parse_log.py" --log "$WORK/logcat.txt" --rec-start "$REC_T0" --cues-out "$PROOF/${OUT_NAME}_cues.json" --shift "$SOUNDTRACK_FROM")"
OFFSET="$(awk -v a="$OFFSET" -v b="$SYNC_NUDGE" 'BEGIN { printf "%.4f", a + b }')"
say "audio offset ${OFFSET}s; cues saved to $PROOF/${OUT_NAME}_cues.json"

"$PY" "$HERE/soundtrack.py" --cues "$PROOF/${OUT_NAME}_cues.json" --offset "$OFFSET" \
  --length "$VDUR" --out "$WORK/soundtrack.wav"

# With a window, the clip occupies video time [OFFSET + FROM, OFFSET + TO]; fade the rest of the score out.
AFILTER=anull
if [[ "$SOUNDTRACK_FROM" != 0 || -n "$SOUNDTRACK_TO" ]]; then
  WIN_IN="$(awk -v o="$OFFSET" -v f="$SOUNDTRACK_FROM" 'BEGIN { s = o + f - 0.05; printf "%.3f", (s < 0 ? 0 : s) }')"
  AFILTER="afade=t=in:st=${WIN_IN}:d=0.05"
  if [[ -n "$SOUNDTRACK_TO" ]]; then
    WIN_OUT="$(awk -v o="$OFFSET" -v t="$SOUNDTRACK_TO" 'BEGIN { printf "%.3f", o + t }')"
    AFILTER="$AFILTER,afade=t=out:st=${WIN_OUT}:d=0.6"
  fi
  say "soundtrack window: show ${SOUNDTRACK_FROM}-${SOUNDTRACK_TO:-end}s, video ${WIN_IN}-${WIN_OUT:-end}s"
fi
"$FFMPEG" -hide_banner -loglevel error -y -i "$WORK/soundtrack.wav" -af "$AFILTER" "$PROOF/${OUT_NAME}_soundtrack.wav" \
  || die "ffmpeg soundtrack window failed"

"$FFMPEG" -hide_banner -loglevel error -y -i "$PROOF/${OUT_NAME}_raw.mp4" -i "$PROOF/${OUT_NAME}_soundtrack.wav" \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -t "$VDUR" -movflags +faststart \
  "$PROOF/${OUT_NAME}.mp4" || die "ffmpeg mux failed"
say "done: $PROOF/${OUT_NAME}.mp4"
