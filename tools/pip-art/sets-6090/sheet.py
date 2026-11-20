import glob
import sys

from PIL import Image

pat, out = sys.argv[1], sys.argv[2]
W = int(sys.argv[3]) if len(sys.argv) > 3 else 1800
fs = sorted(glob.glob(f'packages/pip-rive/sets/reference/{pat}'))
pos, x, y, rh = [], 0, 0, 0
for f in fs:
    im = Image.open(f)
    s = min(1, (W / int(sys.argv[4] if len(sys.argv) > 4 else 2) - 10) / im.width, 700 / im.height)
    im = im.resize((int(im.width * s), int(im.height * s)))
    if x + im.width > W:
        x = 0; y += rh + 10; rh = 0
    pos.append((im, x, y)); x += im.width + 10; rh = max(rh, im.height)
sheet = Image.new('RGB', (W, y + rh), 'white')
for im, x, y in pos:
    sheet.paste(im, (x, y))
sheet.save(out)
print(sheet.size)
