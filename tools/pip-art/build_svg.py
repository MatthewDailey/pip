"""Builds app/src/main/assets/pip/pip.svg from potrace output of Pip's original artwork.

Potrace writes relative path commands inside a flipped, 0.1-scaled group. This rewrites every path as absolute
M/L/C/Z commands in Pip's own 540 x 660 box (the region x 478..1018, y 195..855 of the 1536 x 1024 original), so the
app can parse and warp the points directly.

  python build_svg.py --hat hatfull.svg --face face.svg --eyes "<repr of [(cx, cy, rx, ry), ...]>" --out pip.svg
"""

import argparse
import ast
import re

BOX_X, BOX_Y, IMG_H = 478.0, 195.0, 1024.0


def to_box(x: float, y: float) -> tuple[float, float]:
    # potrace group transform: translate(0, 1024) scale(0.1, -0.1)
    return x * 0.1 - BOX_X, IMG_H - y * 0.1 - BOX_Y


def absolute(d: str) -> str:
    toks = re.findall(r"[MmLlCcZz]|-?\d+(?:\.\d+)?", d)
    out, i, cmd = [], 0, None
    cx = cy = sx = sy = 0.0
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]; i += 1
            if cmd in "Zz":
                out.append("Z"); cx, cy = sx, sy
                continue
        n = {"M": 2, "m": 2, "L": 2, "l": 2, "C": 6, "c": 6}[cmd]
        vals = [float(v) for v in toks[i:i + n]]; i += n
        rel = cmd.islower()
        pts = [(vals[k] + (cx if rel else 0), vals[k + 1] + (cy if rel else 0)) for k in range(0, n, 2)]
        letter = cmd.upper()
        out.append(letter + " " + " ".join("%.1f %.1f" % to_box(*p) for p in pts))
        cx, cy = pts[-1]
        if letter == "M":
            sx, sy = cx, cy
            cmd = "l" if rel else "L"  # implicit lineto after moveto
    return " ".join(out)


def paths(svg: str) -> list[str]:
    return [absolute(d) for d in re.findall(r'<path d="([^"]+)"', svg, re.S)]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hat"); ap.add_argument("--face"); ap.add_argument("--eyes"); ap.add_argument("--out")
    a = ap.parse_args()
    hat = max(paths(open(a.hat).read()), key=len)
    face = max(paths(open(a.face).read()), key=len)
    eyes = ast.literal_eval(a.eyes)
    eye_tags = "\n".join(
        f'  <ellipse id="eye{side}" cx="{cx:.1f}" cy="{cy:.1f}" rx="{rx:.1f}" ry="{ry:.1f}" fill="#8B3F37"/>'
        for side, (cx, cy, rx, ry) in zip("LR", eyes)
    )
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 540 660">
  <!-- Traced from Pip's original artwork. The hat path continues under the face so it can tilt and sway. -->
  <path id="hat" fill="#F05248" d="{hat}"/>
  <path id="face" fill="#FDE3D2" d="{face}"/>
{eye_tags}
</svg>
"""
    open(a.out, "w").write(svg)
    print(f"wrote {a.out}: hat {len(hat)} chars, face {len(face)} chars")


if __name__ == "__main__":
    main()
