"""Art for Pip's first-run greeting (packages/pip-rive/stories/hello-install), drawn as originals.

    tools/.venv/bin/python tools/pip-art/first-run/hello_install.py

Writes the story's (empty) stage SVG and one SVG per piece the story places as its own artboard: his callout cards and
the shield he holds up.

Each piece is drawn about the point the story pins it to, which is also where it scales from.
"""
from __future__ import annotations

import os


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
OUT = os.path.join(ROOT, 'packages', 'pip-rive', 'stories', 'hello-install')

INK = '#3A2A26'
W, H = 1080, 2424


def svg(vb, body, title, defs=''):
    x, y, w, h = vb
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x} {y} {w} {h}" width="{w}" height="{h}">\n'
            f'<title>{title}</title>\n' + (f'<defs>{defs}</defs>\n' if defs else '') + body + '\n</svg>\n')


def write(name, text):
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(text)
    print('hello-install art:', os.path.relpath(path, ROOT))


# ---------------------------------------------------------------- stage (under Pip)

def stage():
    # Nothing is drawn under Pip any more; the story's own artboard still takes its size from this.
    body = '<g id="stage"/>'
    write('stage.svg', svg((0, 0, W, H), body, 'hello-install stage: props drawn under Pip'))


# ---------------------------------------------------------------- pieces drawn over Pip (own artboards)

# His callouts: white cards with a coral rim, no tail, one per line, drawn about an anchor point under the card's
# middle (the story pins it above Pip's head, and the card pops from there). (n, width, height, dx): the card's
# centre is dx right of the anchor and its bottom edge CARD_GAP above it. Keep in step with CARDS in build.mjs, which
# sets the line inside.
CARD_GAP = 24
CARDS = [
    (1, 630, 164, 0),
    (2, 545, 164, 173),
    (3, 610, 218, 173),
    (4, 575, 218, -247),
]


def card(n, w, h, dx):
    x, y = dx - w / 2, -CARD_GAP - h
    body = (f'<g id="card">'
            f'<rect x="{x}" y="{y + 8}" width="{w}" height="{h}" rx="34" fill="{INK}" fill-opacity="0.12"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="34" fill="#FFFFFF" stroke="#D9574A" stroke-width="5"/>'
            '</g>')
    write(f'art/card-{n}.svg', svg((x - 4, y - 4, w + 8, h + 16), body, f'callout {n}'))


def shield():
    body = (
        '<g id="shield">'
        '<circle cx="0" cy="0" r="190" fill="url(#glow)"/>'
        f'<path d="M 0 -130 C 50 -104 92 -100 118 -104 C 120 10 90 96 0 140 C -90 96 -120 10 -118 -104 C -92 -100 -50 -104 0 -130 Z" '
        f'fill="url(#face)" stroke="{INK}" stroke-width="8" stroke-linejoin="round"/>'
        '<path d="M 0 -104 C 40 -84 74 -80 96 -82 C 96 8 72 76 0 112 C -72 76 -96 8 -96 -82 C -74 -80 -40 -84 0 -104 Z" '
        'fill="none" stroke="#FFE08A" stroke-width="8" stroke-linejoin="round"/>'
        f'<path d="M -46 2 L -10 38 L 52 -36" fill="none" stroke="#FFFFFF" stroke-width="22" stroke-linecap="round" stroke-linejoin="round"/>'
        '<path d="M -70 -70 C -40 -86 -10 -92 0 -94 L 0 -30 C -30 -26 -60 -12 -80 0 Z" fill="#FFFFFF" fill-opacity="0.25"/>'
        '</g>'
    )
    defs = ('<radialGradient id="glow" cx="0" cy="0" r="190" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#9FE8FF" stop-opacity="0.55"/><stop offset="1" stop-color="#9FE8FF" stop-opacity="0"/></radialGradient>'
            '<linearGradient id="face" x1="0" y1="-130" x2="0" y2="140" gradientUnits="userSpaceOnUse">'
            '<stop offset="0" stop-color="#5CC8F5"/><stop offset="1" stop-color="#2367D1"/></linearGradient>')
    write('art/shield.svg', svg((-200, -200, 400, 400), body, 'Pip shield', defs))


if __name__ == '__main__':
    stage()
    for spec in CARDS:
        card(*spec)
    shield()
