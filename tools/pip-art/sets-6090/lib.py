"""Shared helpers for redrawing the Kotlin Canvas scenery as Rive-friendly SVG plus motion JSON."""
import json
import math
import os

import cairocffi as cairo
import numpy as np

OUT = 'packages/pip-rive/sets'
f32 = np.float32
PI = math.pi
TAU = 2 * PI
DEG = PI / 180

INK = 0xFF3A2A26
HAT_RED = 0xFFF05248
WHITE = 0xFFFFFFFF


# ------------------------------------------------------------------ Kotlin maths, bit-faithful where it matters

def hash01(i):
    x = f32(f32(i) * f32(12.9898)) + f32(78.233)
    s = f32(math.sin(float(x)))
    v = f32(s * f32(43758.547))
    return float(v - f32(math.floor(float(v))))


def fract(v):
    return v - math.floor(v)


def clamp01(v):
    return max(0.0, min(1.0, v))


def lerp(a, b, t):
    return a + (b - a) * t


def ease_out_cubic(t):
    u = 1 - clamp01(t)
    return 1 - u * u * u


def ease_in_out(t):
    x = clamp01(t)
    return x * x * (3 - 2 * x)


def smoothstep(e0, e1, x):
    return ease_in_out((x - e0) / (e1 - e0))


def with_alpha(c, a):
    base = (c >> 24) & 0xFF
    return (c & 0xFFFFFF) | (int(base * clamp01(a)) << 24)


def mix(a, b, t):
    u = clamp01(t)
    out = 0
    for s in (24, 16, 8, 0):
        ca = (a >> s) & 0xFF
        cb = (b >> s) & 0xFF
        out |= (int(ca + (cb - ca) * u) & 0xFF) << s
    return out


def darken(c, f):
    r = int(((c >> 16) & 0xFF) * f)
    g = int(((c >> 8) & 0xFF) * f)
    b = int((c & 0xFF) * f)
    return (c & 0xFF000000) | (min(255, r) << 16) | (min(255, g) << 8) | min(255, b)


def lighten(c, t):
    def ch(v):
        return min(255, max(0, int(v + (255 - v) * t)))
    return (c & 0xFF000000) | (ch((c >> 16) & 0xFF) << 16) | (ch((c >> 8) & 0xFF) << 8) | ch(c & 0xFF)


def hexc(c):
    return '#%06X' % (c & 0xFFFFFF)


def alpha(c):
    return ((c >> 24) & 0xFF) / 255


# ------------------------------------------------------------------ SVG writing

def fmt(v):
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def F(c, op=None):
    """Solid fill attributes for an ARGB colour."""
    a = alpha(c) * (1 if op is None else op)
    s = f'fill="{hexc(c)}"'
    if a < 0.999:
        s += f' fill-opacity="{a:.3f}"'
    return s


def FG(gid):
    return f'fill="url(#{gid})"'


# Appended to a stroke's attributes: the stroke keeps its width when its element scales (Kotlin draws
# these rings at a fixed pixel width); rivc emits it as a Rive stroke that the transform doesn't affect.
NON_SCALING = ' vector-effect="non-scaling-stroke"'


def S(c, w, cap='round', join='round', op=None):
    a = alpha(c) * (1 if op is None else op)
    s = f'fill="none" stroke="{hexc(c)}" stroke-width="{fmt(w)}" stroke-linecap="{cap}" stroke-linejoin="{join}"'
    if a < 0.999:
        s += f' stroke-opacity="{a:.3f}"'
    return s


def SG(gid, w, cap='round', join='round'):
    return f'fill="none" stroke="url(#{gid})" stroke-width="{fmt(w)}" stroke-linecap="{cap}" stroke-linejoin="{join}"'


def idattr(i):
    return f' id="{i}"' if i else ''


def rect(x0, y0, x1, y1, attrs, r=0, id=None):
    rr = f' rx="{fmt(r)}" ry="{fmt(r)}"' if r else ''
    return f'<rect{idattr(id)} x="{fmt(x0)}" y="{fmt(y0)}" width="{fmt(x1 - x0)}" height="{fmt(y1 - y0)}"{rr} {attrs}/>'


def circle(cx, cy, r, attrs, id=None):
    return f'<circle{idattr(id)} cx="{fmt(cx)}" cy="{fmt(cy)}" r="{fmt(r)}" {attrs}/>'


def ellipse(cx, cy, rx, ry, attrs, id=None):
    return f'<ellipse{idattr(id)} cx="{fmt(cx)}" cy="{fmt(cy)}" rx="{fmt(rx)}" ry="{fmt(ry)}" {attrs}/>'


def oval(x0, y0, x1, y1, attrs, id=None):
    return ellipse((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, attrs, id)


def path(d, attrs, id=None):
    return f'<path{idattr(id)} d="{d}" {attrs}/>'


def line(x0, y0, x1, y1, c, w, cap='round', id=None, op=None):
    return path(f'M{fmt(x0)} {fmt(y0)}L{fmt(x1)} {fmt(y1)}', S(c, w, cap, op=op), id)


def g(children, id=None, transform=None, opacity=None, clip=None, extra=''):
    a = idattr(id)
    if transform:
        a += f' transform="{transform}"'
    if opacity is not None and opacity < 0.999:
        a += f' opacity="{opacity:.3f}"'
    if clip:
        a += f' clip-path="url(#{clip})"'
    if extra:
        a += ' ' + extra
    body = '\n'.join(children) if isinstance(children, list) else children
    return f'<g{a}>\n{body}\n</g>'


def tr(x=0, y=0, rot=0, sx=1, sy=None):
    parts = []
    if x or y:
        parts.append(f'translate({fmt(x)} {fmt(y)})')
    if rot:
        parts.append(f'rotate({fmt(rot)})')
    if sy is None:
        sy = sx
    if sx != 1 or sy != 1:
        parts.append(f'scale({fmt(sx)} {fmt(sy)})' if sx != sy else f'scale({fmt(sx)})')
    return ' '.join(parts) or None


class P:
    """Absolute-coordinate path builder emitting only M/L/Q/C/Z."""

    def __init__(self):
        self.d = []

    def M(self, x, y):
        self.d.append(f'M{fmt(x)} {fmt(y)}'); return self

    def L(self, x, y):
        self.d.append(f'L{fmt(x)} {fmt(y)}'); return self

    def Q(self, x1, y1, x, y):
        self.d.append(f'Q{fmt(x1)} {fmt(y1)} {fmt(x)} {fmt(y)}'); return self

    def C(self, x1, y1, x2, y2, x, y):
        self.d.append(f'C{fmt(x1)} {fmt(y1)} {fmt(x2)} {fmt(y2)} {fmt(x)} {fmt(y)}'); return self

    def Z(self):
        self.d.append('Z'); return self

    def arc(self, cx, cy, rx, ry, a0, sweep, move=False):
        """Android-style arc on an ellipse: degrees, 0 = +x, positive = clockwise on screen."""
        n = max(1, int(math.ceil(abs(sweep) / 90 - 1e-9)))
        step = sweep / n * DEG
        a = a0 * DEG
        x, y = cx + rx * math.cos(a), cy + ry * math.sin(a)
        (self.M if move else self.L)(x, y)
        k = 4 / 3 * math.tan(step / 4)
        for _ in range(n):
            b = a + step
            x1 = cx + rx * (math.cos(a) - k * math.sin(a)); y1 = cy + ry * (math.sin(a) + k * math.cos(a))
            x2 = cx + rx * (math.cos(b) + k * math.sin(b)); y2 = cy + ry * (math.sin(b) - k * math.cos(b))
            self.C(x1, y1, x2, y2, cx + rx * math.cos(b), cy + ry * math.sin(b))
            a = b
        return self

    def circle(self, cx, cy, r):
        self.arc(cx, cy, r, r, 0, 360, move=True); return self.Z()

    def ellipse(self, cx, cy, rx, ry):
        self.arc(cx, cy, rx, ry, 0, 360, move=True); return self.Z()

    def rect(self, x0, y0, x1, y1):
        return self.M(x0, y0).L(x1, y0).L(x1, y1).L(x0, y1).Z()

    def rrect(self, x0, y0, x1, y1, r):
        r = min(r, (x1 - x0) / 2, (y1 - y0) / 2)
        if r <= 0:
            return self.rect(x0, y0, x1, y1)
        self.M(x0 + r, y0).L(x1 - r, y0)
        self.arc(x1 - r, y0 + r, r, r, 270, 90)
        self.L(x1, y1 - r)
        self.arc(x1 - r, y1 - r, r, r, 0, 90)
        self.L(x0 + r, y1)
        self.arc(x0 + r, y1 - r, r, r, 90, 90)
        self.L(x0, y0 + r)
        self.arc(x0 + r, y0 + r, r, r, 180, 90)
        return self.Z()

    def poly(self, pts, close=True):
        self.M(*pts[0])
        for p in pts[1:]:
            self.L(*p)
        return self.Z() if close else self

    def __str__(self):
        return ''.join(self.d)


class Doc:
    def __init__(self, name, w, h, note=''):
        self.name, self.w, self.h, self.note = name, w, h, note
        self.defs = []
        self.body = []
        self.n = 0

    def _id(self, kind):
        self.n += 1
        return f'{kind}{self.n}'

    @staticmethod
    def _stops(cols, stops):
        if stops is None:
            stops = [i / (len(cols) - 1) for i in range(len(cols))]
        out = []
        for c, s in zip(cols, stops):
            a = alpha(c)
            op = f' stop-opacity="{a:.3f}"' if a < 0.999 else ''
            out.append(f'<stop offset="{fmt(s)}" stop-color="{hexc(c)}"{op}/>')
        return ''.join(out)

    def lin(self, x0, y0, x1, y1, cols, stops=None):
        i = self._id('lg')
        self.defs.append(f'<linearGradient id="{i}" gradientUnits="userSpaceOnUse" x1="{fmt(x0)}" y1="{fmt(y0)}" '
                         f'x2="{fmt(x1)}" y2="{fmt(y1)}">{self._stops(cols, stops)}</linearGradient>')
        return i

    def rad(self, cx, cy, r, cols, stops=None):
        i = self._id('rg')
        self.defs.append(f'<radialGradient id="{i}" gradientUnits="userSpaceOnUse" cx="{fmt(cx)}" cy="{fmt(cy)}" '
                         f'r="{fmt(r)}">{self._stops(cols, stops)}</radialGradient>')
        return i

    def clip(self, inner, id=None):
        i = id or self._id('clip')
        self.defs.append(f'<clipPath id="{i}">{inner}</clipPath>')
        return i

    def add(self, *els):
        self.body.extend(els)

    def svg(self):
        w, h = self.w, self.h
        head = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{fmt(-w / 2)} {fmt(-h / 2)} {fmt(w)} {fmt(h)}" '
                f'width="{fmt(w)}" height="{fmt(h)}">')
        note = f'<!-- {self.note} -->\n' if self.note else ''
        defs = '<defs>\n' + '\n'.join(self.defs) + '\n</defs>\n' if self.defs else ''
        return head + '\n' + note + defs + '\n'.join(self.body) + '\n</svg>\n'


# ------------------------------------------------------------------ fake blur (Rive has no blur filters)

def soft_rrect(x0, y0, x1, y1, r, c, blur, n=6):
    """A blurred rounded rect as n stacked rounded rects whose overlap builds a soft edge."""
    a = alpha(c)
    la = 1 - (1 - a) ** (1 / n)
    out = []
    for i in range(n):
        e = blur * (2 * i / (n - 1) - 1) * 0.9
        out.append(path(str(P().rrect(x0 - e, y0 - e, x1 + e, y1 + e, max(0, r + e))), F(c & 0xFFFFFF | 0xFF000000, la)))
    return out


def soft_stroke(d, c, w, blur, n=4):
    """A blurred stroke as stacked strokes of growing width and low alpha."""
    a = alpha(c)
    la = 1 - (1 - a) ** (1 / n)
    out = []
    for i in range(n):
        ww = w + 2 * blur * (1 - i / n)
        out.append(path(d, S(c | 0xFF000000, ww, op=la)))
    return out


def glow_circle(doc, cx, cy, r, c, blur):
    """A blurred disc as a radial gradient that holds the colour to r - blur and fades out by r + blur."""
    R = r + blur
    gid = doc.rad(cx, cy, R, [c, c, c & 0xFFFFFF], [0, max(0, (r - blur)) / R, 1])
    return circle(cx, cy, R, FG(gid))


# ------------------------------------------------------------------ text to outlines

_surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, 4, 4)


def text_d(s, font, size, x, y, align='center', skew=0.0):
    ctx = cairo.Context(_surf)
    ctx.select_font_face(font)
    ctx.set_font_matrix(cairo.Matrix(size, 0, skew * size, size, 0, 0))
    adv = ctx.text_extents(s)[4]
    ox = x - adv / 2 if align == 'center' else (x - adv if align == 'right' else x)
    ctx.move_to(ox, y)
    ctx.text_path(s)
    p = P()
    for kind, pts in ctx.copy_path():
        if kind == cairo.PATH_MOVE_TO:
            p.M(*pts)
        elif kind == cairo.PATH_LINE_TO:
            p.L(*pts)
        elif kind == cairo.PATH_CURVE_TO:
            p.C(*pts)
        elif kind == cairo.PATH_CLOSE_PATH:
            p.Z()
    return str(p)


def advance(s, font, size):
    ctx = cairo.Context(_surf)
    ctx.select_font_face(font)
    ctx.set_font_size(size)
    return ctx.text_extents(s)[4]


FONT_BLACK = 'Avenir-Black'          # Android sans-serif-black
FONT_BLACK_IT = 'Avenir-BlackOblique'
FONT_BOLD = 'HelveticaNeue-Bold'     # Android DEFAULT bold / Roboto Bold
FONT_BOLD_IT = 'HelveticaNeue-BoldItalic'
FONT_MONO = 'Menlo-Bold'             # Android monospace bold


# ------------------------------------------------------------------ motion helpers

def r3(v):
    return round(v, 3)


def sine_keys(base, amp, w, phase, D):
    """base + amp*sin(w t + phase), with w rounded so the loop of length D closes; keys at the extremes (ease easeInOut)."""
    n = max(1, round(w * D / TAU))
    w2 = TAU * n / D
    ts = {0.0, D}
    t = ((PI / 2 - phase) % PI) / w2
    while t < D - 1e-6:
        ts.add(t)
        t += PI / w2
    return [[r3(t), r3(base + amp * math.sin(w2 * t + phase))] for t in sorted(ts)]


def sampled(fn, D, n):
    return [[r3(D * i / n), r3(fn(D * i / n))] for i in range(n + 1)]


def track(target, prop, keys, ease='linear'):
    return {'target': target, 'property': prop, 'keys': keys, 'ease': ease}


def sine_track(target, prop, base, amp, w, phase, D):
    return track(target, prop, sine_keys(base, amp, w, phase, D), 'easeInOut')


def wrap_keys(x0, speed, lo, hi, D):
    """Linear motion that wraps from hi back to lo (a jump), quantised so it loops over D."""
    span = hi - lo
    n = max(1, round(abs(speed) * D / span))
    v = math.copysign(n * span / D, speed)
    keys = []
    t, x = 0.0, x0
    keys.append([0, r3(x)])
    while t < D - 1e-6:
        edge = hi if v > 0 else lo
        dt = (edge - x) / v
        if t + dt >= D - 1e-6:
            keys.append([r3(D), r3(x + v * (D - t))])
            break
        t += dt
        keys.append([r3(t), r3(edge)])
        x = lo if v > 0 else hi
        keys.append([r3(t + 0.001), r3(x)])
    return keys


def clip(name, duration, tracks, loop=True, note=None):
    c = {'name': name, 'duration': r3(duration), 'loop': loop, 'tracks': tracks}
    if note:
        c['note'] = note
    return c


def write_piece(name, doc, meta):
    os.makedirs(OUT, exist_ok=True)
    svg = doc.svg()
    with open(os.path.join(OUT, name + '.svg'), 'w') as f:
        f.write(svg)
    meta = dict(meta)
    meta = {'piece': name, **meta}
    meta.setdefault('size', [doc.w, doc.h])
    with open(os.path.join(OUT, name + '.json'), 'w') as f:
        json.dump(meta, f, indent=1)
    return len(svg)
