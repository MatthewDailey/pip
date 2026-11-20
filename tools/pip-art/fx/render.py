"""Render SVG strings to PIL images and build an overview sheet of every piece."""
import io
import sys
import cairosvg
from PIL import Image, ImageDraw


def to_img(svg, scale=1.0, bg=None):
    png = cairosvg.svg2png(bytestring=svg.encode(), scale=scale)
    im = Image.open(io.BytesIO(png)).convert("RGBA")
    if bg:
        base = Image.new("RGBA", im.size, bg)
        base.alpha_composite(im)
        im = base
    return im


if __name__ == "__main__":
    from gen_a import BUILDERS_A
    from gen_b import BUILDERS_B
    allb = {**BUILDERS_A, **BUILDERS_B}
    names = sys.argv[1:] or list(allb)
    cell = 420
    cols = 5
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGBA", (cell * cols, (cell + 24) * rows), (40, 40, 52, 255))
    d = ImageDraw.Draw(sheet)
    for k, name in enumerate(names):
        svg, _ = allb[name](preview=True)
        im = to_img(svg)
        im.thumbnail((cell - 10, cell - 10))
        x, y = (k % cols) * cell, (k // cols) * (cell + 24)
        tile = Image.new("RGBA", (cell, cell), (20, 22, 32, 255))
        tile.alpha_composite(im, ((cell - im.width) // 2, (cell - im.height) // 2))
        sheet.paste(tile, (x, y + 24))
        d.text((x + 6, y + 6), name, fill="white")
    sheet.convert("RGB").save("overview.png")
