"""Generate every piece, then render compare images against the reference video."""
import io
import json
import os
import re
import subprocess
import sys

import cairosvg
from PIL import Image, ImageDraw

import lib
from lib import OUT, write_piece

VIDEO = '.proof/pip_show.mp4'
HERE = os.path.dirname(os.path.abspath(__file__))
FRAMES = os.path.join(HERE, 'full')
REF = os.path.join(OUT, 'reference')
K = 300 / 540

# piece -> (video time, screen x, screen y of the piece's (0,0), render scale)
COMPARE = {
    'set-d60s-frame': (60.4, 540, 1150, 1), 'set-d60s-moon': (60.4, 540, 1150, 1),
    'set-d60s-banner': (60.4, 540, 670, 1), 'set-d60s-flag': (62.4, 773, 1360, 1),
    'set-d60s-helmet': (60.4, 540 + 15 * K, 1350 - 280 * K, K), 'set-d60s-zebra': (63.2, 540, 1150, 1),
    'set-d60s-shoes': (63.9, 423, 1350 + 12 * K, K), 'set-d60s-comic': (66.2, 540, 1150, 1),
    'set-d60s-starburst': (65.35, 540, 1150, 1), 'set-d60s-burst-pow': (66.55, 860, 990, 1),
    'set-d70s-sea': (69.0, 540, 1150, 1), 'set-d70s-sea-front': (69.0, 540, 1150, 1), 'set-d70s-fin': (70.3, 817, 1270, 1),
    'set-d70s-splash': (69.55, 540, 1270, 1), 'set-d70s-disco': (73.0, 540, 1150, 1), 'set-d70s-steps': (75.5, 540, 1150, 1),
    'set-d70s-banner': (69.4, 540, 670, 1),
    'set-d80s-neon': (78.6, 540, 380, 1), 'set-d80s-maze': (79.0, 540, 1170, 1), 'set-d80s-ghost': (80.1, 250, 1215, 1),
    'set-d80s-moon': (81.0, 540, 1170, 1), 'set-d80s-bike': (82.0, 600, 1075, 1, -14, '#F4E8C4'), 'set-d80s-basket': (82.0, 600, 1075, 1, -14, '#F4E8C4'),
    'set-d80s-speedo': (85.0, 540, 760, 1), 'set-d80s-zap': (84.9, 190, 1180, 1), 'set-d80s-trails': (85.6, 665, 1357, 1),
    'set-d80s-streaks': (80.55, 540, 1170, 1),
    'set-d90s-banner': (87.3, 540, 400, 1), 'set-d90s-mirror-back': (87.6, 540, 1150, 1), 'set-d90s-mirror-front': (87.6, 540, 1150, 1),
    'set-d90s-scream': (88.2, 548, 1185, 1), 'set-d90s-sunset': (90.7, 540, 1170, 1), 'set-d90s-bow': (90.7, 540, 1170, 1),
    'set-d90s-wind': (90.7, 540, 1170, 1), 'set-d90s-matrix': (93.0, 540, 1170, 1), 'set-d90s-orbit': (93.0, 540, 1200, 1),
    'set-d90s-tint': (93.0, 540, 1170, 1), 'set-d90s-bullet': (93.7, 825, 1000, 1),
    'set-d90s-sunglasses': (95.2, 540 + 60 * K, 1350 - 264 * K, K),
    'set-d60s-burst-bam': (66.9, 720, 900, 1), 'set-d60s-burst-zap': (67.4, 880, 1080, 1),
}


def frame_at(t):
    os.makedirs(FRAMES, exist_ok=True)
    f = os.path.join(FRAMES, f'{t:.2f}.png')
    if not os.path.exists(f):
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-ss', str(t), '-i', VIDEO, '-frames:v', '1', f], check=True)
    return Image.open(f).convert('RGB')


def compact(obj):
    s = json.dumps(obj, indent=1)
    # Collapse short numeric/string arrays (keys) onto one line.
    return re.sub(r'\[\s+([^\[\]{}]*?)\s+\]', lambda m: '[' + re.sub(r'\s*\n\s*', ' ', m.group(1)) + ']', s)


def compare(name):
    t, x, y, sc = COMPARE[name][:4]
    rot = COMPARE[name][4] if len(COMPARE[name]) > 4 else 0
    bgc = COMPARE[name][5] if len(COMPARE[name]) > 5 else '#1b1d2a'
    svg = open(os.path.join(OUT, name + '.svg')).read()
    w0, h0 = re.search(r'width="([\d.]+)" height="([\d.]+)"', svg).groups()
    w, h = int(float(w0) * sc), int(float(h0) * sc)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=w, output_height=h, background_color=bgc)
    art = Image.open(io.BytesIO(png)).convert('RGB')
    if rot:
        art = art.rotate(-rot, resample=Image.BICUBIC, fillcolor=bgc)
    fr = frame_at(t)
    box = (int(x - w / 2), int(y - h / 2), int(x - w / 2) + w, int(y - h / 2) + h)
    crop = Image.new('RGB', (w, h), '#1b1d2a')
    crop.paste(fr.crop(box), (0, 0))
    out = Image.new('RGB', (w * 2 + 30, h + 40), 'white')
    out.paste(crop, (10, 30)); out.paste(art, (w + 20, 30))
    dr = ImageDraw.Draw(out)
    dr.text((10, 8), f'{name}  video {t}s (left) | SVG (right)', fill='black')
    os.makedirs(REF, exist_ok=True)
    out.save(os.path.join(REF, name + '-compare.png'))
    return out


def main(mods, only=None):
    sizes = {}
    for m in mods:
        mod = __import__(m)
        for fn in mod.PIECES:
            name, doc, meta = fn()
            if only and name not in only:
                continue
            sizes[name] = write_piece(name, doc, meta)
            with open(os.path.join(OUT, name + '.json'), 'w') as f:
                f.write(compact({'piece': name, **meta, 'size': meta.get('size', [doc.w, doc.h])}))
            if name in COMPARE:
                compare(name)
    for k, v in sizes.items():
        print(f'{k:28s} {v / 1024:7.1f} KB')


if __name__ == '__main__':
    sys.path.insert(0, HERE)
    args = sys.argv[1:]
    mods = [a for a in args if a.startswith('p')]
    only = [a for a in args if a.startswith('set-')]
    main(mods, only)
