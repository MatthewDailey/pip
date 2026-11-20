#!/usr/bin/env bash
# Contact sheet: the Kotlin v1 video frame (left, video time = show time + 1.69 s) next to the Rive
# frame composited over the home-screen screenshot (right), for every shot showreel-proof.mjs saved.
#   packages/showreel-to-rivc/proof/contact-sheet.sh [.proof/showreel-rive]
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
dir="${1:-$repo/.proof/showreel-rive}"
video="$repo/.proof/pip_show.mp4"
backdrop="$dir/backdrop.png"
# A plain dark stage: the device screenshots carry the app's resident Pip, which would read as Rive output.
[[ -f "$backdrop" ]] || ffmpeg -v error -y -f lavfi -i color=c=0x0B0B16:s=1080x2424 -frames:v 1 "$backdrop"
font="$repo/packages/rivc/examples/project/fonts/Inter-Bold.ttf"
mkdir -p "$dir/kotlin" "$dir/pairs"
times=$(python3 -c "import json;print(' '.join('%.2f'%s['t'] for s in json.load(open('$dir/rive-proof.json'))['shots']))")
scene() { python3 -c "
import json;d=json.load(open('$repo/apps/pip-app/app/build/showreel/showreel.json'))
print([s['name'] for s in d['scenes'] if s['start']<=$1<s['end']][0])"; }
pairs=(); : > "$dir/contact-sheet.txt"
for t in $times; do
  tag=$(printf %06.2f "$t")
  v=$(python3 -c "print($t + 1.69)")
  ffmpeg -v error -y -ss "$v" -i "$video" -frames:v 1 -vf scale=270:606 "$dir/kotlin/kotlin-t$tag.png"
  echo "$t $(scene "$t")" >> "$dir/contact-sheet.txt"
  ffmpeg -v error -y -i "$dir/kotlin/kotlin-t$tag.png" -i "$backdrop" -i "$dir/rive/rive-t$tag.png" -filter_complex \
    "[1]scale=270:606[b];[2]scale=270:606[r];[b][r]overlay[o];[0][o]hstack,pad=544:606:0:0:white" \
    "$dir/pairs/pair-t$tag.png"
  pairs+=("$dir/pairs/pair-t$tag.png")
done
n=${#pairs[@]}
cols=8
rows=$(( (n + cols - 1) / cols ))
ffmpeg -v error -y -pattern_type glob -i "$dir/pairs/pair-t*.png" -vf "tile=${cols}x${rows}:padding=6:color=white" -frames:v 1 "$dir/contact-sheet.png"
echo "$dir/contact-sheet.png ($n pairs)"
