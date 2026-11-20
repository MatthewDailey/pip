# Picks the connected Android device for turbo demo and demo:reset. Source it after env.sh, then call
#
#   pick_device "<serial or empty>"   # exports ANDROID_SERIAL and sets device_kind (emulator | physical device)
#
# Only devices adb lists as "device" count; unauthorized or offline ones are reported, not used. With several
# connected, a physical phone wins over an emulator. It never boots an emulator; with nothing connected it explains
# what to check and exits 1.

pick_device() {
  local serial="$1" listing not_ready phones=() ready=()
  listing="$(adb devices 2>/dev/null | tail -n +2 | awk 'NF >= 2 { print $1, $2 }')"
  while read -r s state; do
    [[ -n "$s" && "$state" == device ]] && ready+=("$s")
  done <<<"$listing"
  not_ready="$(awk '$2 != "device" { printf "  %s (%s)\n", $1, $2 }' <<<"$listing")"

  if [[ -n "$serial" ]]; then
    printf '%s\n' "${ready[@]:-}" | grep -qx -- "$serial" || _no_device "device $serial is not connected" "$not_ready"
  elif ((${#ready[@]} == 0)); then
    _no_device "no Android device connected" "$not_ready"
  elif ((${#ready[@]} == 1)); then
    serial="${ready[0]}"
  else
    for s in "${ready[@]}"; do [[ "$s" == emulator-* ]] || phones+=("$s"); done
    serial="${phones[0]:-${ready[0]}}"
    echo "device: ${#ready[@]} connected (${ready[*]}); using $serial. Pick another with -- --device <serial>"
  fi
  export ANDROID_SERIAL="$serial"

  if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]; then device_kind=emulator; else device_kind="physical device"; fi
  echo "device: $serial, $(adb shell getprop ro.product.model | tr -d '\r') ($device_kind)"
}

_no_device() {
  {
    echo "device: $1"
    [[ -n "$2" ]] && printf 'device: seen but not usable:\n%s\n' "$2"
    echo "device: check that"
    echo "  - the phone is plugged in with a USB cable that carries data"
    echo "  - Developer options > USB debugging is on"
    echo "  - you accepted the \"Allow USB debugging?\" (RSA key) prompt on the phone"
    echo "  - adb devices lists it as \"device\", not unauthorized or offline"
    echo "device: to use the emulator instead, start it with turbo droid, then run this again"
  } >&2
  exit 1
}
