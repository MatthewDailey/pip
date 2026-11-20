"""Side-by-side compares: reference video crop | SVG render placed the same way on a dark backdrop."""
import re
import sys
from PIL import Image, ImageDraw
from render import to_img
from gen_a import BUILDERS_A
from gen_b import BUILDERS_B
from lib import OUT

ALL = {**BUILDERS_A, **BUILDERS_B}
FR = "frames"
BG = (11, 12, 18, 255)
SW, SH = 1080, 2424


def placed(svg, x, y, scale=1.0, rot=0.0, alpha=1.0):
    vb = re.search(r'viewBox="([^"]+)"', svg).group(1).split()
    l, t, w, h = map(float, vb)
    inner = re.sub(r"^<svg[^>]*>", f'<svg x="{l}" y="{t}" width="{w}" height="{h}" viewBox="{" ".join(vb)}">', svg.strip())
    outer = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{SW}" height="{SH}" viewBox="0 0 {SW} {SH}">'
             f'<g transform="translate({x},{y}) rotate({rot}) scale({scale})" opacity="{alpha}">{inner}</g></svg>')
    return to_img(outer)


# name, video time, crop box, builder kwargs, list of placements (x, y, scale, rot, alpha)
CASES = [
    ("fx-smoke", 30.3, (140, 700, 940, 1500), {"p": 0.7}, [(540, 1180, 1.75, 0, 1)]),
    ("fx-scam-card", 30.88, (0, 1000, 500, 1500), {"p": 0.0}, [(-15, 1235, 1, 5, 1)]),
    ("fx-rain-cloud", 40.7, (290, 600, 790, 1000), {"p": 0.6}, [(550, 895, 1, 0, 1)]),
    ("fx-sweat", 41.3, (560, 1100, 760, 1300), {}, [(658, 1200, 1, 0, 1)]),
    ("fx-bubble", 47.2, (290, 760, 790, 1060), {"text": "No no no!", "text_w": 210}, [(540, 1000, 1.18, 0, 1)]),
    ("fx-banner", 50.8, (160, 200, 920, 460), {}, [(540, 330, 1, 0, 1)]),
    ("fx-music-note", 50.8, (200, 700, 400, 900), {"color": 0xFFFF4FD8}, [(300, 817, 1, 0, 1)]),
    ("fx-spotlight", 52.2, (0, 0, 540, 1500), {"color": 0xFFFF4FD8}, [(170, 40, 1, 0, 0.6)]),
    ("fx-sparkle", 56.9, (300, 450, 800, 850), {}, [(390, 730, 1.1, 0, 1), (700, 690, 0.9, 0, 1), (560, 600, 1.2, 0, 1)]),
    ("fx-confetti", 58.0, (0, 300, 1080, 1500), {"p": 0.45}, [(540, 850, 1.1, 0, 1)]),
]


def compare(case):
    name, t, box, kw, places = case
    ref = Image.open(f"{FR}/f{t}.png").convert("RGBA").crop(box)
    svg, _ = ALL[name](preview=True, **kw)
    canvas = Image.new("RGBA", (SW, SH), BG)
    for x, y, s, r, a in places:
        canvas.alpha_composite(placed(svg, x, y, s, r, a))
    mine = canvas.crop(box)
    W, H = ref.size
    scale = min(1.0, 700 / max(W, H))
    ref = ref.resize((int(W * scale), int(H * scale))); mine = mine.resize(ref.size)
    out = Image.new("RGBA", (ref.width * 2 + 12, ref.height + 30), (255, 255, 255, 255))
    out.paste(ref, (0, 30)); out.paste(mine, (ref.width + 12, 30))
    d = ImageDraw.Draw(out)
    d.text((6, 8), f"reference video {t}s", fill="black"); d.text((ref.width + 18, 8), f"{name}.svg", fill="black")
    piece = name
    out.convert("RGB").save(f"{OUT}/reference/{piece}-compare.png")
    return out


def svg_only(name, kw=None, note="not used in the showreel: no reference frame"):
    svg, _ = ALL[name](preview=True, **(kw or {}))
    im = to_img(svg)
    im.thumbnail((700, 700))
    out = Image.new("RGBA", (im.width + 12, im.height + 30), (255, 255, 255, 255))
    tile = Image.new("RGBA", im.size, BG); tile.alpha_composite(im)
    out.paste(tile, (6, 30))
    ImageDraw.Draw(out).text((6, 8), f"{name}.svg ({note})", fill="black")
    out.convert("RGB").save(f"{OUT}/reference/{name}-compare.png")
    return out


if __name__ == "__main__":
    only = sys.argv[1:]
    for c in CASES:
        if not only or c[0] in only:
            compare(c)
