"""Bubble, banner, smoke, slash, scam card, sweat, heart, sparkle and music notes."""
import math
from lib import *

DENS = 2.625  # the reference device; kit pixels == screen pixels there


def preview_text(x, y, s, size, color="#FFFFFF", anchor="middle", weight="bold", extra=""):
    lines = s.split("\n")
    out = []
    for i, ln in enumerate(lines):
        yy = y + (i - (len(lines) - 1) / 2) * size * 1.0 + size * 0.35
        out.append(f'<text x="{n(x)}" y="{n(yy)}" font-family="Roboto, Helvetica, Arial, sans-serif" font-weight="{weight}" '
                   f'font-size="{n(size)}" text-anchor="{anchor}" fill="{color}" {extra}>{ln}</text>')
    return "".join(out)


# ---- BUBBLE --------------------------------------------------------------------------------------

def bubble(preview=False, text="No no no!", text_w=283.0):
    d = DENS
    pad, gap, r, sw = 14 * d, 14 * d, 18 * d, 3 * d
    line_h = 19 * d * 1.17
    bw, bh = text_w + pad * 2, line_h + pad * 2
    left, top = -bw / 2, -gap - bh
    W, H = bw + 40, (bh + gap) * 2 + 40  # (0,0) = tail tip at the centre
    tail = f"M{n(-10 * d)},{n(-gap - 1)} L0,0 L{n(10 * d)},{n(-gap - 1)} Z"
    body = f'''<g id="bubble">
  <g id="bubble-shadow"><rect x="{n(left + 2 * d)}" y="{n(top + 4 * d)}" width="{n(bw)}" height="{n(bh)}" rx="{n(r)}" {paint(SHADOW)}/></g>
  <g id="bubble-box"><rect x="{n(left)}" y="{n(top)}" width="{n(bw)}" height="{n(bh)}" rx="{n(r)}" fill="#FFFFFF" stroke="{rgb(HAT_RED)}" stroke-width="{n(sw)}"/></g>
  <g id="bubble-tail"><path d="{tail}" fill="#FFFFFF"/><path d="M{n(-10 * d)},{n(-gap)} L0,0 L{n(10 * d)},{n(-gap)}" fill="none" stroke="{rgb(HAT_RED)}" stroke-width="{n(sw)}" stroke-linecap="round" stroke-linejoin="round"/></g>
  <g id="text-bubble" data-text="{text}" data-font-size="{n(19 * d)}" data-font-weight="bold" data-color="{rgb(INK)}" data-align="center" data-max-width="{n(260 * d)}" data-box="{n(left + pad)} {n(top + pad)} {n(text_w)} {n(line_h)}">{preview_text(0, top + bh / 2, text, 19 * d, rgb(INK)) if preview else ""}</g>
</g>'''
    motion = {
        "piece": "bubble", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "(0,0) is the tail tip. The box sits 14dp (36.75px) above it; tail half-width 10dp.",
            "Box size follows the text: width = textWidth + 2*36.75 (text wraps at 260dp = 682.5px, min 40dp), height = textHeight + 73.5. Corner radius 47.25, stroke 7.875 #F05248.",
            "The shadow is the box offset (+5.25, +10.5) at #3A2A26 18%.",
            "The Kotlin bubble also clamps the box horizontally to stay 12dp inside the screen; drive that with the boxShift input (the tail stays put).",
        ],
        "clips": [],
        "inputs": [
            {"name": "textWidth", "type": "number", "drives": "bubble-box/bubble-shadow width and x (centred) and text-bubble wrap width"},
            {"name": "textHeight", "type": "number", "drives": "bubble-box/bubble-shadow height and y (grows upward from the tail)"},
            {"name": "boxShift", "type": "number", "drives": "x offset of bubble-box, bubble-shadow and text-bubble that keeps the box on screen; tail does not move"},
        ],
    }
    return svg_doc(W, H, body, title="bubble"), motion


# ---- BANNER --------------------------------------------------------------------------------------

def banner(preview=False, text="♪ Pip's Jam ♪"):
    d = DENS
    size = 44 * d; sw = 7 * d
    W, H = 1032, 2 * size
    pt = lambda col, extra="": preview_text(0, -size * 0.35, text, size, col, extra=extra) if preview else ""
    body = f'''<g id="banner">
  <g id="text-banner-shadow" data-text="{text}" data-font-size="{n(size)}" data-font-weight="bold" data-color="#3A2A2640" data-offset-y="{n(sw * 0.6)}" data-align="center" data-baseline="0">{preview_text(0, -size * 0.35 + sw * 0.6, text, size, rgb(INK), extra='fill-opacity="0.25"') if preview else ""}</g>
  <g id="text-banner-stroke" data-text="{text}" data-font-size="{n(size)}" data-font-weight="bold" data-stroke="{rgb(INK)}" data-stroke-width="{n(sw)}" data-stroke-join="round" data-align="center" data-baseline="0">{pt("none", f'stroke="{rgb(INK)}" stroke-width="{n(sw)}" stroke-linejoin="round"')}</g>
  <g id="text-banner-fill" data-text="{text}" data-font-size="{n(size)}" data-font-weight="bold" data-color="#FFFFFF" data-align="center" data-baseline="0">{pt("#FFFFFF")}</g>
</g>'''
    motion = {
        "piece": "banner", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Text only: three stacked copies of the same string, baseline at y=0, centred on x=0.",
            "Style: bold 44sp (115.5px); a 25% ink copy offset down by 0.6*stroke, a #3A2A26 stroke 7dp (18.4px, round joins), then the white fill.",
            "When the string is wider than the screen minus 24dp margins (after the actor's scale), font size and stroke shrink together: size = 115.5 * avail / (textWidth + 18.4).",
        ],
        "clips": [],
        "inputs": [{"name": "fontScale", "type": "number", "drives": "uniform scale of all three text layers when the string must shrink to fit"}],
    }
    return svg_doc(W, H, body, title="banner"), motion


# ---- SMOKE ---------------------------------------------------------------------------------------

def smoke_state(p0):
    p = clamp01(p0)
    fade = 1 - smoothstep(0.3, 1, p)
    e = ease_out_cubic(p)
    pop = ease_out_back(p / 0.14) if p < 0.14 else 1.0
    puffs = []
    for i in range(9):
        ang = math.radians(i * 40 + p * 50 + hash01(i) * 16)
        dist = (24 + 150 * e) * (0.85 + 0.3 * hash01(i + 11))
        r0 = 56 + 20 * hash01(i + 5)
        s = pop * (1 - 0.5 * e)
        puffs.append((math.cos(ang) * dist, math.sin(ang) * dist * 0.82 - 40 * e, r0, s))
    core = 78 * pop * (1 - smoothstep(0, 0.55, p))
    wisps = []
    for i in range(7):
        ang = math.radians(i * 51 + 20 + hash01(i + 40) * 20)
        dist = 60 + 250 * e * (0.8 + 0.4 * hash01(i + 50))
        r = 16 * pop * (1 - e) + 4
        wisps.append((math.cos(ang) * dist, math.sin(ang) * dist * 0.8 - 50 * e, r))
    return fade if p > 0 else 0.0, e, puffs, core, wisps


SMOKE_P = [0, 0.02, 0.04, 0.06, 0.08, 0.1, 0.12, 0.14, 0.2, 0.26, 0.32, 0.4, 0.48, 0.56, 0.64, 0.72, 0.8, 0.9, 1.0]


def smoke(preview=False, p=0.3):
    fade, e, puffs, core, wisps = smoke_state(p)
    W = H = 840
    g_sh, g_body, g_hi, g_w = [], [], [], []
    for i, (x, y, r0, s) in enumerate(puffs):
        g_sh.append(f'<g id="puff-{i}-shadow" transform="translate({n(x + 5)},{n(y + 10)}) scale({n(s)})"><circle r="{n(r0)}" fill="#CFC5BD"/></g>')
        g_body.append(f'<g id="puff-{i}" transform="translate({n(x)},{n(y)}) scale({n(s)})"><circle r="{n(r0)}" fill="#F6F2EE"/></g>')
        g_hi.append(f'<g id="puff-{i}-highlight" transform="translate({n(x)},{n(y)}) scale({n(s)})"><circle cx="{n(-0.28 * r0)}" cy="{n(-0.3 * r0)}" r="{n(0.38 * r0)}" fill="#FFFFFF"/></g>')
    cs = core / 78
    core_sh = f'<g id="core-shadow" transform="translate(4,{n(8 - 30 * e)}) scale({n(cs)})"><circle r="78" fill="#CFC5BD"/></g>'
    core_b = f'<g id="core" transform="translate(0,{n(-30 * e)}) scale({n(cs)})"><circle r="78" fill="#F6F2EE"/></g>'
    for i, (x, y, r) in enumerate(wisps):
        g_w.append(f'<g id="wisp-{i}" transform="translate({n(x)},{n(y)}) scale({n(r / 20)})"><circle r="20" fill="#EDE7E2"/></g>')
    body = (f'<g id="smoke" opacity="{n(fade)}">\n<g id="smoke-shadows">{"".join(g_sh)}{core_sh}</g>\n<g id="smoke-bodies">{"".join(g_body)}{core_b}</g>\n'
            f'<g id="smoke-highlights">{"".join(g_hi)}</g>\n<g id="smoke-wisps">{"".join(g_w)}</g>\n</g>')
    tracks = []
    states = [smoke_state(q) for q in SMOKE_P]
    tracks.append({"target": "smoke", "property": "opacity", "keys": [[q, st[0]] for q, st in zip(SMOKE_P, states)]})
    for i in range(9):
        for suffix, dx, dy in (("-shadow", 5, 10), ("", 0, 0), ("-highlight", 0, 0)):
            tid = f"puff-{i}{suffix}"
            tracks.append({"target": tid, "property": "x", "keys": [[q, st[2][i][0] + dx] for q, st in zip(SMOKE_P, states)]})
            tracks.append({"target": tid, "property": "y", "keys": [[q, st[2][i][1] + dy] for q, st in zip(SMOKE_P, states)]})
            tracks.append({"target": tid, "property": "scale", "keys": [[q, st[2][i][3]] for q, st in zip(SMOKE_P, states)]})
    for tid, dx, dy in (("core-shadow", 4, 8), ("core", 0, 0)):
        tracks.append({"target": tid, "property": "y", "keys": [[q, dy - 30 * st[1]] for q, st in zip(SMOKE_P, states)]})
        tracks.append({"target": tid, "property": "scale", "keys": [[q, st[3] / 78] for q, st in zip(SMOKE_P, states)]})
    for i in range(7):
        tracks.append({"target": f"wisp-{i}", "property": "x", "keys": [[q, st[4][i][0]] for q, st in zip(SMOKE_P, states)]})
        tracks.append({"target": f"wisp-{i}", "property": "y", "keys": [[q, st[4][i][1]] for q, st in zip(SMOKE_P, states)]})
        tracks.append({"target": f"wisp-{i}", "property": "scale", "keys": [[q, st[4][i][2] / 20] for q, st in zip(SMOKE_P, states)]})
    motion = {
        "piece": "smoke", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": ["Kotlin draws all shadows, then all bodies, then all highlights, then the wisps; the four layer groups keep that order.",
                  "Each puff group is a circle of its rest radius at (0,0); x/y/scale keys place it. The SVG shows progress 0.3.",
                  "The Kotlin fade is a layer alpha over the whole poof (one group opacity, no per-puff overlap darkening)."],
        "clips": [{"name": "poof", "timeline": "progress", "duration": 1.0, "loop": False, "ease": "linear", "tracks": tracks}],
        "inputs": [{"name": "progress", "type": "number", "drives": "scrubs 'poof': 0 = nothing, 0..0.14 puffs pop out (easeOutBack), then drift apart, shrink and fade out by 1.0"}],
    }
    return svg_doc(W, H, body, title="smoke"), motion


# ---- SLASH ---------------------------------------------------------------------------------------

SR, STR = 210.0, 150.0


def slash_state(p0):
    p = clamp01(p0)
    fade = 1 - smoothstep(0.72, 1, p)
    head = 160 + 220 * ease_out_cubic(p / 0.5)
    reach = min(STR, head - 160)
    trail = reach * (1 - smoothstep(0.45, 1, p))
    trail = max(trail, 0.5)
    thick = 54 * (0.35 + 0.65 * min(1, trail / STR))
    nn = 32
    outer = [(math.cos(math.radians(-trail + trail * i / nn)) * SR, math.sin(math.radians(-trail + trail * i / nn)) * SR) for i in range(nn + 1)]
    inner = []
    for i in range(nn, -1, -1):
        t = i / nn
        w = thick * math.sin(math.pi * t ** 1.8)
        a = math.radians(-trail + trail * t)
        inner.append((math.cos(a) * (SR - w), math.sin(a) * (SR - w)))
    glint = (1 - smoothstep(0.4, 0.7, p)) * fade
    return fade if p > 0 else 0.0, head, outer, inner, glint


SLASH_P = [0, 0.02, 0.05, 0.08, 0.12, 0.16, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0]


def slash(preview=False, p=0.3):
    fade, head, outer, inner, glint = slash_state(p)
    W, H = 520, 760  # (0,0) at centre; the sweep circle is centred at (0, 100)
    a0 = math.radians(-STR)
    gx1, gy1 = math.cos(a0) * SR, math.sin(a0) * SR
    defs = (lin_grad("slash-trail-grad", gx1, gy1, SR, 0, [(0, 0x00FFFFFF), (0.45, 0x55FFFFFF), (0.92, 0xFFFFFFFF), (1, 0xFFFFFFFF)])
            + lin_grad("slash-edge-grad", gx1, gy1, SR, 0, [(0, 0x0036D6FF), (0.5, 0x6036D6FF), (1, 0xFF36D6FF)]))
    body = f'''<g id="slash" transform="translate(0,100)">
  <g id="slash-sweep" transform="rotate({n(head)})">
    <g id="slash-trail" opacity="{n(fade)}"><path id="slash-trail-path" d="{poly_d(outer + inner)}" fill="url(#slash-trail-grad)"/></g>
    <g id="slash-edge" opacity="{n(fade)}"><path id="slash-edge-path" d="{poly_d(outer, False)}" fill="none" stroke="url(#slash-edge-grad)" stroke-width="6" stroke-linecap="round"/></g>
    <g id="slash-glint" transform="translate({n(SR - 6)},0) scale({n(glint)})" opacity="{n(glint)}">
      <path d="{star_d(30)}" fill="#FFFFFF"/><path d="{star_d(14, 45)}" fill="#B8F2FF"/>
    </g>
  </g>
</g>'''
    states = [slash_state(q) for q in SLASH_P]
    tracks = [
        {"target": "slash-sweep", "property": "rotation", "keys": [[q, st[1]] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-trail-path", "property": "path", "keys": [[q, poly_d(st[2] + st[3])] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-edge-path", "property": "path", "keys": [[q, poly_d(st[2], False)] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-trail", "property": "opacity", "keys": [[q, st[0]] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-edge", "property": "opacity", "keys": [[q, st[0]] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-glint", "property": "scale", "keys": [[q, st[4]] for q, st in zip(SLASH_P, states)]},
        {"target": "slash-glint", "property": "opacity", "keys": [[q, st[4]] for q, st in zip(SLASH_P, states)]},
    ]
    motion = {
        "piece": "slash", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "The sweep is drawn in a frame that rotates with the blade's head: slash-sweep sits at the circle centre (0, 100) and its rotation is the head angle, so the head is always at local angle 0 and the glint never moves locally.",
            "The crescent changes length and thickness, so slash-trail-path and slash-edge-path are keyed as vertex morphs (66 and 33 vertices, constant counts).",
            "Kotlin uses sweep gradients anchored 150 degrees behind the head; Rive has no sweep gradient, so both use a linear gradient along the chord from local angle -150 to 0 with the same stops (close match for this 150-degree arc).",
        ],
        "clips": [{"name": "sweep", "timeline": "progress", "duration": 1.0, "loop": False, "ease": "linear", "tracks": tracks}],
        "inputs": [{"name": "progress", "type": "number", "drives": "scrubs 'sweep': the head sweeps 160->380 degrees by 0.5 (easeOutCubic), the tail catches up from 0.45, everything fades 0.72->1"}],
    }
    return svg_doc(W, H, body, defs, title="slash"), motion


# ---- SCAM_CARD -----------------------------------------------------------------------------------

def _zig():
    pts = []
    for i in range(9):
        t = i / 8
        jag = 0 if i in (0, 8) else (11 if i % 2 == 0 else -11)
        pts.append((-46 + 92 * t + jag, -120 + 240 * t))
    return pts


ZIG = _zig()


def _zig_x(y):
    for (x0, y0), (x1, y1) in zip(ZIG, ZIG[1:]):
        if y0 <= y <= y1:
            return x0 + (x1 - x0) * (y - y0) / (y1 - y0)
    raise ValueError(y)


def _zig_between(t, b):
    return [(x, y) for x, y in ZIG if t < y < b]


def half_rrect(l, t, r_, b, rad, side, closed=True):
    """Left (-1) or right (+1) part of a rounded rect cut along the zigzag, as an exact path."""
    xt, xb = _zig_x(t), _zig_x(b)
    if side < 0:
        d = f"M{n(xt)},{n(t)} L{n(l + rad)},{n(t)} " + arc_cubics(l + rad, t + rad, rad, rad, 270, 180, move=False)
        d += f" L{n(l)},{n(b - rad)} " + arc_cubics(l + rad, b - rad, rad, rad, 180, 90, move=False) + f" L{n(xb)},{n(b)}"
    else:
        d = f"M{n(xt)},{n(t)} L{n(r_ - rad)},{n(t)} " + arc_cubics(r_ - rad, t + rad, rad, rad, 270, 360, move=False)
        d += f" L{n(r_)},{n(b - rad)} " + arc_cubics(r_ - rad, b - rad, rad, rad, 0, 90, move=False) + f" L{n(xb)},{n(b)}"
    if closed:
        for x, y in reversed(_zig_between(t, b)):
            d += f" L{n(x)},{n(y)}"
        d += " Z"
    return d


def _warning():
    return f'''<g id="card-warning" transform="translate(-96,2)">
      <path d="M0,-30 L32,26 L-32,26 Z" fill="#FFFFFF" stroke="#FFFFFF" stroke-width="16" stroke-linejoin="round"/>
      <rect x="-5" y="-14" width="10" height="24" rx="5" fill="#D8352F"/><circle cx="0" cy="19" r="5.5" fill="#D8352F"/>
    </g>'''


def _card_text(tid, label, preview, clip=""):
    attrs = f'data-text="{label}" data-font-size="38" data-font-weight="bold" data-color="#FFFFFF" data-align="center" data-line-spacing="0.95" data-box="-50 -66 184 132" data-shadow="#00000040 0 3"'
    if clip:
        attrs += f' data-clip="{clip}"'
    pv = ""
    if preview:
        pv = preview_text(42, 3, label, 38, "#000000", extra='fill-opacity="0.25"') + preview_text(42, 0, label, 38, "#FFFFFF")
    return f'<g id="{tid}" {attrs}>{pv}</g>'


def scam_card(preview=False, p=0.0, label="SCAM\nCALL"):
    crack = clamp01(p / 0.12)
    u = clamp01((p - 0.12) / 0.88)
    W, H = 760, 1500
    defs = lin_grad("card-grad", 0, -85, 0, 85, [(0, 0xFFF4605A), (1, 0xFFD8352F)])
    cut_in = [(_zig_x(-85), -85)] + _zig_between(-85, 85) + [(_zig_x(85), 85)]
    whole_vis = 1 if u <= 0 else 0
    body = f'''<g id="card" opacity="{whole_vis}">
  <g id="card-shadow"><rect x="-146" y="-76" width="300" height="176" rx="26" {paint(0x333A2A26)}/></g>
  <g id="card-body"><rect x="-150" y="-85" width="300" height="170" rx="24" fill="url(#card-grad)"/></g>
  <g id="card-border"><rect x="-140" y="-75" width="280" height="150" rx="16" fill="none" {paint(0x66FFFFFF, "stroke")} stroke-width="3"/></g>
  {_warning()}
  {_card_text("text-card", label, preview)}
  <g id="crack" opacity="{1 if crack > 0 else 0}">
    <path id="crack-glow" d="{poly_d(cut_in, False)}" fill="none" stroke="#FFFFFF" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" data-trim-end="{n(crack)}"/>
    <path id="crack-line" d="{poly_d(cut_in, False)}" fill="none" stroke="#7A1714" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" data-trim-end="{n(crack)}"/>
  </g>
</g>'''
    halves = []
    for side, name in ((-1, "left"), (1, "right")):
        dx = side * (16 + (130 if side < 0 else 105) * ease_out_cubic(u))
        dy = 620 * u * u - 70 * u
        rot = side * (30 if side < 0 else 24) * (u ** 0.8)
        fade = 1 - smoothstep(0.55, 1, u)
        vis = fade if u > 0 else 0
        px = side * 70
        halves.append(f'''<g id="half-{name}" opacity="{n(vis)}" transform="translate({n(dx)},{n(dy)}) rotate({n(rot)},{px},0)" data-pivot="{px} 0">
  <path d="{half_rrect(-146, -76, 154, 100, 26, side)}" {paint(0x333A2A26)}/>
  <path d="{half_rrect(-150, -85, 150, 85, 24, side)}" fill="url(#card-grad)"/>
  <path d="{half_rrect(-140, -75, 140, 75, 16, side, closed=False)}" fill="none" {paint(0x66FFFFFF, "stroke")} stroke-width="3"/>
  {_warning().replace('id="card-warning"', 'id="half-left-warning"') if side < 0 else ""}
  {_card_text(f"text-card-{name}", label, preview, clip=f"half-{name} outline (body path)")}
  <path d="{poly_d(cut_in, False)}" fill="none" {paint(0xCCFFFFFF, "stroke")} stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
</g>''')
    body += "\n" + "\n".join(halves)
    if preview and 0 < u:
        # The preview text must be clipped by each half; cairosvg can do clipPath so the preview uses it.
        pass
    # Motion
    PU = [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]
    tracks = [
        {"target": "crack-glow", "property": "trimEnd", "keys": [[0, 0], [0.12, 1]]},
        {"target": "crack-line", "property": "trimEnd", "keys": [[0, 0], [0.12, 1]]},
        {"target": "crack", "property": "opacity", "keys": [[0, 0], [0.001, 1]], "ease": "hold"},
        {"target": "card", "property": "opacity", "keys": [[0, 1], [0.1201, 0]], "ease": "hold"},
    ]
    for side, name in ((-1, "left"), (1, "right")):
        def st(uu, side=side):
            return (side * (16 + (130 if side < 0 else 105) * ease_out_cubic(uu)), 620 * uu * uu - 70 * uu,
                    side * (30 if side < 0 else 24) * (uu ** 0.8), 1 - smoothstep(0.55, 1, uu))
        pk = [0.12 + 0.88 * uu for uu in PU]
        tracks += [
            {"target": f"half-{name}", "property": "x", "keys": [[q, st(uu)[0]] for q, uu in zip(pk, PU)]},
            {"target": f"half-{name}", "property": "y", "keys": [[q, st(uu)[1]] for q, uu in zip(pk, PU)]},
            {"target": f"half-{name}", "property": "rotation", "pivot": [side * 70, 0], "keys": [[q, st(uu)[2]] for q, uu in zip(pk, PU)]},
            {"target": f"half-{name}", "property": "opacity", "keys": [[0, 0], [0.1199, 0]] + [[q, st(uu)[3]] for q, uu in zip(pk, PU) if uu > 0]},
        ]
    D = 2 * math.pi / periodic(61, 0.5) * round(61 * 0.5 / (2 * math.pi))
    w1, w2 = periodic(61, 1.0), periodic(47, 1.0)
    shake = [
        {"target": "card", "property": "x", "keys": keys(lambda t: math.sin(t * w1) * 5, 0, 1.0, 48)},
        {"target": "card", "property": "rotation", "keys": keys(lambda t: math.sin(t * w2) * 1.5, 0, 1.0, 48)},
    ]
    motion = {
        "piece": "scam-card", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Card 300x170 centred on (0,0). The label is a text run (text-card, 'SCAM CALL' wraps to two lines at 38px bold; it shrinks by 2px steps until it fits 184x132, min 18px).",
            "The split halves are separate pre-cut geometry (no masks): body, shadow and border are cut exactly along the zigzag. Their label copies (text-card-left/right) must be clipped by the half's body outline; that is the one clip this piece needs (Rive clipping shape = the half's body path).",
            "Each half rotates about (±70, 0) in its own frame (pivot given on the rotation track).",
            "Kotlin draws the halves' white cut line along the clip boundary from y=-120 to 120, so 35px of half-width line pokes out above and below the card; here it is trimmed to the card.",
        ],
        "clips": [
            {"name": "split", "timeline": "progress", "duration": 1.0, "loop": False, "ease": "linear", "tracks": tracks},
            {"name": "shake", "timeline": "time", "duration": 1.0, "loop": True, "ease": "linear", "tracks": shake,
             "blend": "weight = crack amount = clamp(progress/0.12); only while progress < 0.12 (the whole card rattles as it cracks)"},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "0..0.12 the crack draws top to bottom while the card rattles; 0.12..1 the card is swapped for two halves that fly apart (left 146px, right 121px out), drop 550px and fade from 0.6"}],
    }
    return svg_doc(W, H, body, defs, title="scam-card"), motion


# ---- SWEAT ---------------------------------------------------------------------------------------

DROP = "M0,-50 C8,-30 32,-4 32,16 C32,36 18,48 0,48 C-18,48 -32,36 -32,16 C-32,-4 -8,-30 0,-50 Z"


def sweat(preview=False):
    W, H = 90, 120
    body = f'''<g id="sweat">
  <g id="sweat-shadow" transform="translate(3,5)"><path d="{DROP}" {paint(0x223A2A26)}/></g>
  <g id="sweat-drop"><path d="{DROP}" fill="#4BA3DE"/></g>
  <g id="sweat-inner" transform="translate(-2,-3) scale(0.88,0.9)"><path d="{DROP}" fill="#79C6F2"/></g>
  <g id="sweat-shine"><ellipse cx="0" cy="0" rx="6" ry="13" transform="translate(-12,14) rotate(-20)" {paint(0xCCFFFFFF)}/><circle cx="-6" cy="-14" r="4" {paint(0xAAFFFFFF)}/></g>
</g>'''
    motion = {"piece": "sweat", "origin": [W / 2, H / 2], "size": [W, H], "notes": ["Static drop (about 64x98). All its motion (pop, slide down, fade) is at actor level in the scene."], "clips": [], "inputs": []}
    return svg_doc(W, H, body, title="sweat"), motion


# ---- HEART ---------------------------------------------------------------------------------------

HEART = "M0,72 C-118,2 -80,-92 0,-40 C80,-92 118,2 0,72 Z"


def heart(preview=False, color=HAT_RED):
    W, H = 260, 200
    body = f'''<g id="heart">
  <g id="heart-shadow" transform="translate(6,9)"><path d="{HEART}" {paint(SHADOW)}/></g>
  <g id="heart-rim"><path d="{HEART}" fill="{rgb(darken(color, 0.82))}"/></g>
  <g id="heart-body" transform="translate(-3,-5) scale(0.92)"><path d="{HEART}" fill="{rgb(color)}"/></g>
  <g id="heart-gloss"><ellipse cx="0" cy="0" rx="15" ry="9" transform="translate(-38,-34) rotate(-35)" {paint(0x99FFFFFF)}/><circle cx="-14" cy="-46" r="5" {paint(0x88FFFFFF)}/></g>
</g>'''
    w = 7.0
    D = 2 * math.pi / w
    motion = {
        "piece": "heart", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": ["Tint: heart-body takes the actor colour (white means hat red #F05248); heart-rim is that colour * 0.82."],
        "clips": [{"name": "beat", "timeline": "time", "duration": D, "loop": True, "ease": "linear",
                   "tracks": [{"target": "heart", "property": "scale", "keys": keys(lambda t: 1 + 0.07 * max(0, math.sin(t * w)) ** 2, 0, D, 24)}]}],
        "inputs": [{"name": "tint", "type": "color", "drives": "heart-body fill; heart-rim = tint * 0.82"}],
    }
    return svg_doc(W, H, body, title="heart"), motion


# ---- SPARKLE -------------------------------------------------------------------------------------

def sparkle(preview=False, tint=0xFFFFD447, t=0.25):
    W = H = 220
    tw = 0.82 + 0.18 * math.sin(t * 6.3)
    rot = math.sin(t * 1.7) * 10
    s1 = max(0, math.sin(t * 5.1 + 2.1)); s2 = max(0, math.sin(t * 4.3 + 4.0))
    defs = rad_grad("sparkle-glow-grad", 0, 0, 100, [(0, 0x88FFFFFF), (1, 0x00FFFFFF)])
    body = f'''<g id="sparkle">
  <g id="sparkle-glow" transform="scale({n(tw)})"><circle r="100" fill="url(#sparkle-glow-grad)"/></g>
  <g id="sparkle-star" transform="rotate({n(rot)}) scale({n(tw)})"><path d="{star_d(64)}" fill="{rgb(tint)}"/></g>
  <g id="sparkle-core" transform="rotate({n(rot)}) scale({n(tw)})"><path d="{star_d(30)}" fill="#FFFFFF"/></g>
  <g id="sparkle-sat-1" transform="translate(62,-54) scale({n(s1)})"><path d="{star_d(20)}" fill="{rgb(tint)}"/></g>
  <g id="sparkle-sat-2" transform="translate(-56,48) scale({n(s2)})"><path d="{star_d(15)}" fill="{rgb(tint)}"/></g>
</g>'''
    D = 2 * math.pi / 1.7
    wt, w1, w2 = periodic(6.3, D), periodic(5.1, D), periodic(4.3, D)
    tracks = [
        {"target": "sparkle-glow", "property": "scale", "keys": keys(lambda x: 0.82 + 0.18 * math.sin(x * wt), 0, D, 48)},
        {"target": "sparkle-star", "property": "scale", "keys": keys(lambda x: 0.82 + 0.18 * math.sin(x * wt), 0, D, 48)},
        {"target": "sparkle-core", "property": "scale", "keys": keys(lambda x: 0.82 + 0.18 * math.sin(x * wt), 0, D, 48)},
        {"target": "sparkle-star", "property": "rotation", "keys": keys(lambda x: math.sin(x * 1.7) * 10, 0, D, 24)},
        {"target": "sparkle-core", "property": "rotation", "keys": keys(lambda x: math.sin(x * 1.7) * 10, 0, D, 24)},
        {"target": "sparkle-sat-1", "property": "scale", "keys": keys(lambda x: max(0, math.sin(x * w1 + 2.1)), 0, D, 72)},
        {"target": "sparkle-sat-2", "property": "scale", "keys": keys(lambda x: max(0, math.sin(x * w2 + 4.0)), 0, D, 72)},
    ]
    motion = {
        "piece": "sparkle", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": ["One 3.70 s loop (the rotation's period). Twinkle and satellite frequencies are rounded to whole cycles in that loop (6.3->6.80, 5.1->5.10, 4.3->5.10 rad/s) so it loops seamlessly.",
                  "Tint: actor colour on sparkle-star and both satellites; white means gold #FFD447."],
        "clips": [{"name": "twinkle", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": tracks}],
        "inputs": [{"name": "tint", "type": "color", "drives": "fill of sparkle-star, sparkle-sat-1, sparkle-sat-2"}],
    }
    return svg_doc(W, H, body, defs, title="sparkle"), motion


# ---- MUSIC_NOTE ----------------------------------------------------------------------------------

def _note_head(x, y, col, shine):
    s = f'<g transform="translate({n(x)},{n(y)}) rotate(-24)"><ellipse rx="28" ry="19" {col}/>'
    if shine:
        s += f'<ellipse cx="-10" cy="-7.5" rx="7" ry="3.5" {paint(0x88FFFFFF)}/>'
    return s + "</g>"


def music_note(preview=False, double=False, color=HAT_RED, t=0.0, seed=0.0):
    W, H = 200, 220
    if double:
        stem = "M-14,-52 L-5,-52 L-5,36 L-14,36 Z M50,-70 L59,-70 L59,22 L50,22 Z M-14,-56 L59,-76 L59,-54 L-14,-34 Z"
        heads = [(-32, 36), (32, 22)]
        off = (-20, 4)
    else:
        stem = "M14,-62 L23,-62 L23,30 L14,30 Z M14,-64 C30,-52 62,-40 54,2 C52,-22 38,-32 14,-36 Z"
        heads = [(-4, 30)]
        off = (-16, 4)
    def layer(col_attr, shine):
        return f'<path d="{stem}" {col_attr}/>' + "".join(_note_head(x, y, col_attr, False) for x, y in heads)
    shine = "".join(f'<g transform="translate({n(x)},{n(y)}) rotate(-24)"><ellipse cx="-10" cy="-7.5" rx="7" ry="3.5" {paint(0x88FFFFFF)}/></g>' for x, y in heads)
    dy = math.sin(t * 2.4 + seed) * 9; rot = math.sin(t * 3.2 + seed) * 14; sq = 1 + 0.05 * math.sin(t * 6.4 + seed)
    body = f'''<g id="note" transform="translate(0,{n(dy)}) rotate({n(rot)}) scale({n(1 / sq)},{n(sq)})">
  <g transform="translate({off[0]},{off[1]})">
    <g id="note-shadow" transform="translate(5,7)">{layer(paint(SHADOW), False)}</g>
    <g id="note-body">{layer(f'fill="{rgb(color)}"', True)}</g>
    <g id="note-shine">{shine}</g>
  </g>
</g>'''
    D = 2 * math.pi / 0.8
    motion = {
        "piece": "music-note-double" if double else "music-note", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": ["A beamed pair ('♫', '2' or 'double' in the actor text) uses fx-music-note-double; otherwise fx-music-note.",
                  "Wobble frequencies 2.4, 3.2 and 6.4 rad/s are 3:4:8 multiples of 0.8, so one 7.85 s loop holds them all exactly. Kotlin desynchronises notes with a per-actor phase; start each instance at a different clip time.",
                  "Tint: note-body fill takes the actor colour (white means hat red)."],
        "clips": [{"name": "wobble", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": [
            {"target": "note", "property": "y", "keys": keys(lambda x: math.sin(x * 2.4) * 9, 0, D, 72)},
            {"target": "note", "property": "rotation", "keys": keys(lambda x: math.sin(x * 3.2) * 14, 0, D, 96)},
            {"target": "note", "property": "scaleX", "keys": keys(lambda x: 1 / (1 + 0.05 * math.sin(x * 6.4)), 0, D, 192)},
            {"target": "note", "property": "scaleY", "keys": keys(lambda x: 1 + 0.05 * math.sin(x * 6.4), 0, D, 192)},
        ]}],
        "inputs": [{"name": "tint", "type": "color", "drives": "note-body fill (stems, beam and heads)"}],
    }
    return svg_doc(W, H, body, title=motion["piece"]), motion


BUILDERS_A = {
    "fx-bubble": bubble, "fx-banner": banner, "fx-smoke": smoke, "fx-slash": slash, "fx-scam-card": scam_card,
    "fx-sweat": sweat, "fx-heart": heart, "fx-sparkle": sparkle, "fx-music-note": music_note,
    "fx-music-note-double": lambda preview=False, **k: music_note(preview, double=True, **k),
}
