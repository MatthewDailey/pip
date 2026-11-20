"""Generate every set piece (SVG + JSON) and its reference comparison.

usage: python run.py [piece-prefix ...]
"""
import io
import os
import re
import subprocess
import sys

import cairosvg
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
import kit  # noqa: E402
import d00s  # noqa: E402

MODULES = [d00s]
try:
  import d10s  # noqa: E402
  MODULES.append(d10s)
except ImportError:
  pass
try:
  import dtoday  # noqa: E402
  MODULES.append(dtoday)
except ImportError:
  pass

ROOT = "."
OUT = ROOT + "/packages/pip-rive/sets"
REF = OUT + "/reference"
VIDEO = ROOT + "/.proof/pip_show.mp4"
SCRATCH = os.path.join(os.path.dirname(__file__), "..", "frames")
FFMPEG = "ffmpeg"


def frame(t):
  p = os.path.join(SCRATCH, "ref_%.2f.png" % t)
  if not os.path.exists(p):
    subprocess.run([FFMPEG, "-loglevel", "error", "-y", "-ss", "%.2f" % t, "-i", VIDEO, "-frames:v", "1", p], check=True)
  return Image.open(p).convert("RGBA")


def render(svg_text, scale=1.0):
  m = re.search(r'viewBox="([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+)"', svg_text)
  vx, vy, vw, vh = map(float, m.groups())
  png = cairosvg.svg2png(bytestring=svg_text.encode(), output_width=int(round(vw * scale)), output_height=int(round(vh * scale)))
  return Image.open(io.BytesIO(png)).convert("RGBA"), (vx, vy, vw, vh)


def place(svg_text, origin, scale=1.0, bg=(0, 0, 0, 0), size=(1080, 2424)):
  """Render the SVG onto a stage-sized canvas with its (0, 0) at `origin` (screen px)."""
  img, (vx, vy, vw, vh) = render(svg_text, scale)
  canvas = Image.new("RGBA", size, bg)
  canvas.alpha_composite(img, (int(round(origin[0] + vx * scale)), int(round(origin[1] + vy * scale))))
  return canvas


def checker(size, a=(236, 232, 228, 255), b=(222, 216, 212, 255), n=24):
  im = Image.new("RGBA", size, a)
  d = ImageDraw.Draw(im)
  for y in range(0, size[1], n):
    for x in range(0, size[0], n):
      if (x // n + y // n) % 2:
        d.rectangle([x, y, x + n - 1, y + n - 1], fill=b)
  return im


def compare(name, spec, build):
  t, crop, state, origin = spec["time"], spec["crop"], spec.get("state"), spec["origin"]
  scale = spec.get("scale", 1.0)
  svg, _ = build(state)
  ref = frame(t).crop(crop)
  stage = Image.new("RGBA", (1080, 2424), (0, 0, 0, 0))
  for b, st, o in spec.get("under", []):
    stage.alpha_composite(place(b(st)[0], o))
  stage.alpha_composite(place(svg, origin, scale))
  for b, st, o in spec.get("over", []):
    stage.alpha_composite(place(b(st)[0], o))
  if "post" in spec:
    spec["post"](stage)
  mine = stage.crop(crop)
  bg = spec.get("bg")
  if bg == "checker":
    base = checker(mine.size)
  elif bg == "ref":
    base = ref.copy()
  else:
    base = Image.new("RGBA", mine.size, bg or (40, 36, 40, 255))
  base.alpha_composite(mine)
  w, h = ref.size
  target_h = min(900, h)
  if w * target_h / h < 360:
    target_h = int(360 * h / w)
  k = target_h / h
  ref_s = ref.resize((int(w * k), target_h), Image.LANCZOS)
  mine_s = base.resize((int(w * k), target_h), Image.LANCZOS)
  pad = 16
  label_h = 44
  sheet = Image.new("RGBA", (max(940, ref_s.width * 2 + pad * 3), target_h + pad * 2 + label_h), (250, 247, 244, 255))
  sheet.paste(ref_s, (pad, pad + label_h))
  sheet.paste(mine_s, (ref_s.width + pad * 2, pad + label_h))
  d = ImageDraw.Draw(sheet)
  try:
    font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 22)
  except Exception:
    font = None
  d.text((pad, pad), "%s   left: Kotlin, video %.2f s   right: SVG redraw%s" % (name, t, (" (" + spec["label"] + ")") if spec.get("label") else ""),
         fill=(58, 42, 38, 255), font=font)
  os.makedirs(REF, exist_ok=True)
  out = os.path.join(REF, name + "-compare.png")
  sheet.convert("RGB").save(out)
  return out


def main(args):
  os.makedirs(OUT, exist_ok=True)
  done = []
  for mod in MODULES:
    for name, build in mod.PIECES.items():
      if args and not any(name.startswith(a) for a in args):
        continue
      svg, j = build(None)
      with open(os.path.join(OUT, name + ".svg"), "w") as fh:
        fh.write(svg)
      with open(os.path.join(OUT, name + ".json"), "w") as fh:
        fh.write(kit.dump_json(j))
      size_kb = len(svg.encode()) / 1024
      spec = getattr(mod, "COMPARE", {}).get(name)
      cmp = compare(name, spec, build) if spec else "-"
      done.append((name, size_kb, cmp))
      print("%-28s %7.1f KB  %s" % (name, size_kb, cmp))
  return done


if __name__ == "__main__":
  main(sys.argv[1:])
