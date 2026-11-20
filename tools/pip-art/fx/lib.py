"""Helpers for porting the Kotlin actor paint functions to layered SVG + motion JSON."""
import json
import math
import numpy as np

OUT = "packages/pip-rive/sets"

INK = 0xFF3A2A26
HAT_RED = 0xFFF05248
SKIN = 0xFFFDE3D2
SHADOW = 0x2E3A2A26
F32 = np.float32


# ---- Kotlin float maths ----------------------------------------------------------------------

def hash01(i):
    """Bit-faithful port of the Kotlin hash01 (Float maths, sin via double)."""
    a = F32(F32(i) * F32(12.9898)) + F32(78.233)
    s = F32(math.sin(float(F32(a))))
    v = F32(s * F32(43758.547))
    return float(F32(v - F32(math.floor(float(v)))))


def clamp01(v):
    return max(0.0, min(1.0, v))


def ease_out_cubic(t):
    u = 1 - clamp01(t)
    return 1 - u * u * u


def ease_in_out(t):
    x = clamp01(t)
    return x * x * (3 - 2 * x)


def smoothstep(e0, e1, x):
    return ease_in_out((x - e0) / (e1 - e0))


def ease_out_back(t):
    x = clamp01(t) - 1
    s = 1.70158
    return 1 + x * x * ((s + 1) * x + s)


def fract(v):
    return v - math.floor(v)


# ---- colours ---------------------------------------------------------------------------------

def rgb(c):
    return "#%06X" % (c & 0xFFFFFF)


def alpha(c):
    return ((c >> 24) & 0xFF) / 255.0


def with_alpha(c, a):
    base = (c >> 24) & 0xFF
    return (c & 0xFFFFFF) | (int(base * clamp01(a)) << 24)


def darken(c, f):
    r = int(((c >> 16) & 0xFF) * f); g = int(((c >> 8) & 0xFF) * f); b = int((c & 0xFF) * f)
    return (c & 0xFF000000) | (r << 16) | (g << 8) | b


def lighten(c, t):
    def ch(v):
        return min(255, int(v + (255 - v) * t))
    return (c & 0xFF000000) | (ch((c >> 16) & 0xFF) << 16) | (ch((c >> 8) & 0xFF) << 8) | ch(c & 0xFF)


def lerp_color(a, b, t):
    u = clamp01(t)
    def ch(s):
        return int(((a >> s) & 0xFF) + (((b >> s) & 0xFF) - ((a >> s) & 0xFF)) * u)
    return 0xFF000000 | (ch(16) << 16) | (ch(8) << 8) | ch(0)


def hexa(c):
    """#RRGGBBAA string for JSON colour keys."""
    return "#%06X%02X" % (c & 0xFFFFFF, (c >> 24) & 0xFF)


def paint(c, kind="fill"):
    """SVG fill/stroke attributes for a Kotlin ARGB int."""
    a = alpha(c)
    s = f'{kind}="{rgb(c)}"'
    if a < 0.999:
        s += f' {kind}-opacity="{a:.3f}"'
    return s


# ---- numbers and paths -------------------------------------------------------------------------

def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def poly_d(pts, closed=True):
    d = "M" + " L".join(f"{n(x)},{n(y)}" for x, y in pts)
    return d + (" Z" if closed else "")


K = 0.5522847498


def arc_cubics(cx, cy, rx, ry, a0, a1, rot=0.0, move=True):
    """Elliptical arc (degrees, Android direction: +y down, angles clockwise) as cubic segments."""
    cr, sr = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    def pt(a):
        x, y = rx * math.cos(a), ry * math.sin(a)
        return cx + x * cr - y * sr, cy + x * sr + y * cr
    def dv(a):
        x, y = -rx * math.sin(a), ry * math.cos(a)
        return x * cr - y * sr, x * sr + y * cr
    segs = max(1, int(math.ceil(abs(a1 - a0) / 90.0 - 1e-9)))
    out = []
    t0 = math.radians(a0)
    step = math.radians((a1 - a0) / segs)
    k = 4 / 3 * math.tan(step / 4)
    p = pt(t0)
    if move:
        out.append(f"M{n(p[0])},{n(p[1])}")
    for i in range(segs):
        ta, tb = t0 + i * step, t0 + (i + 1) * step
        pa, pb = pt(ta), pt(tb)
        da, db = dv(ta), dv(tb)
        out.append("C%s,%s %s,%s %s,%s" % (n(pa[0] + k * da[0]), n(pa[1] + k * da[1]), n(pb[0] - k * db[0]), n(pb[1] - k * db[1]), n(pb[0]), n(pb[1])))
    return " ".join(out)


def star_d(r, deg=0.0, x=0.0, y=0.0):
    """The kit's four-point star (pinched quad sides) with tip radius r."""
    nn = 0.16
    pts = [(0, -1), (nn, -nn), (1, 0), (nn, nn), (0, 1), (-nn, nn), (-1, 0), (-nn, -nn)]
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    tp = [(x + r * (px * c - py * s), y + r * (px * s + py * c)) for px, py in pts]
    d = f"M{n(tp[0][0])},{n(tp[0][1])}"
    for i in range(1, 8, 2):
        q, e = tp[i], tp[(i + 1) % 8]
        d += f" Q{n(q[0])},{n(q[1])} {n(e[0])},{n(e[1])}"
    return d + " Z"


# ---- SDF boolean shapes traced to smooth paths --------------------------------------------------

def sd_circle(cx, cy, r):
    return lambda X, Y: np.hypot(X - cx, Y - cy) - r


def sd_rrect(l, t, r_, b, rad):
    cx, cy, hx, hy = (l + r_) / 2, (t + b) / 2, (r_ - l) / 2, (b - t) / 2
    def f(X, Y):
        qx = np.abs(X - cx) - hx + rad
        qy = np.abs(Y - cy) - hy + rad
        return np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - rad
    return f


def sd_union(*fs):
    return lambda X, Y: np.minimum.reduce([f(X, Y) for f in fs])


def sd_inter(*fs):
    return lambda X, Y: np.maximum.reduce([f(X, Y) for f in fs])


def sd_sub(a, b):
    return lambda X, Y: np.maximum(a(X, Y), -b(X, Y))


def sd_xform(f, tx=0.0, ty=0.0, sx=1.0, sy=1.0):
    """Shape f mapped by p -> (tx, ty) + (sx, sy) * p."""
    return lambda X, Y: f((X - tx) / sx, (Y - ty) / sy) * min(sx, sy)


def sd_halfplane_below(y0):
    """Inside where Y > y0."""
    return lambda X, Y: y0 - Y


def trace(f, bbox, step=0.25):
    """Marching-squares loops of f == 0 inside bbox (l, t, r, b)."""
    l, t, r_, b = bbox
    xs = np.arange(l, r_ + step, step); ys = np.arange(t, b + step, step)
    X, Y = np.meshgrid(xs, ys)
    V = f(X, Y)
    inside = V < 0
    nrow, ncol = V.shape
    c = (inside[:-1, :-1].astype(int) | (inside[:-1, 1:] << 1) | (inside[1:, 1:] << 2) | (inside[1:, :-1] << 3))
    cand = np.argwhere((c != 0) & (c != 15))
    def edge_pt(e):
        kind, i, j = e
        if kind == "h":  # between (i,j) and (i,j+1)
            v0, v1 = V[i, j], V[i, j + 1]; tt = v0 / (v0 - v1)
            return xs[j] + tt * step, ys[i]
        v0, v1 = V[i, j], V[i + 1, j]; tt = v0 / (v0 - v1)
        return xs[j], ys[i] + tt * step
    adj = {}
    def link(a, bb):
        adj.setdefault(a, []).append(bb); adj.setdefault(bb, []).append(a)
    for i, j in cand:
        code = c[i, j]
        top, right, bottom, left = ("h", i, j), ("v", i, j + 1), ("h", i + 1, j), ("v", i, j)
        tbl = {1: [(left, top)], 2: [(top, right)], 3: [(left, right)], 4: [(right, bottom)], 6: [(top, bottom)],
               7: [(left, bottom)], 8: [(bottom, left)], 9: [(bottom, top)], 11: [(bottom, right)], 12: [(right, left)],
               13: [(right, top)], 14: [(top, left)]}
        if code in (5, 10):
            centre = (V[i, j] + V[i, j + 1] + V[i + 1, j] + V[i + 1, j + 1]) / 4 < 0
            cut_tr_bl = [(top, right), (bottom, left)]
            cut_tl_br = [(left, top), (right, bottom)]
            if code == 5:
                segs = cut_tr_bl if centre else cut_tl_br
            else:
                segs = cut_tl_br if centre else cut_tr_bl
        else:
            segs = tbl[code]
        for a, bb in segs:
            link(a, bb)
    loops = []
    seen = set()
    for start in list(adj):
        if start in seen:
            continue
        loop = [start]; seen.add(start); prev = None; cur = start
        while True:
            nxt = [x for x in adj[cur] if x != prev and x not in seen]
            if not nxt:
                break
            prev, cur = cur, nxt[0]
            seen.add(cur); loop.append(cur)
        if len(loop) > 3:
            loops.append([edge_pt(e) for e in loop])
    return loops


def dp_simplify(pts, tol):
    if len(pts) < 3:
        return pts
    a = np.array(pts)
    def rec(i0, i1):
        if i1 <= i0 + 1:
            return [i0]
        p0, p1 = a[i0], a[i1]
        d = p1 - p0
        L = math.hypot(*d) or 1e-9
        seg = a[i0 + 1:i1]
        dist = np.abs(d[0] * (seg[:, 1] - p0[1]) - d[1] * (seg[:, 0] - p0[0])) / L
        k = int(np.argmax(dist))
        if dist[k] > tol:
            m = i0 + 1 + k
            return rec(i0, m) + rec(m, i1)
        return [i0]
    # split the closed loop at its farthest point pair
    far = int(np.argmax(np.hypot(a[:, 0] - a[0, 0], a[:, 1] - a[0, 1])))
    idx = rec(0, far) + rec(far, len(a) - 1) + [len(a) - 1]
    out = [tuple(a[i]) for i in idx]
    if math.hypot(out[0][0] - out[-1][0], out[0][1] - out[-1][1]) < 1e-6:
        out.pop()
    return out


def smooth_d(pts, corner_deg=38.0):
    """Closed Catmull-Rom spline through pts as cubics, keeping sharp corners as corners."""
    m = len(pts)
    P = [np.array(p) for p in pts]
    def turn(i):
        a, b, c = P[i - 1], P[i], P[(i + 1) % m]
        v1, v2 = b - a, c - b
        ang = math.degrees(math.atan2(v1[0] * v2[1] - v1[1] * v2[0], v1 @ v2))
        return abs(ang)
    corner = [turn(i) > corner_deg for i in range(m)]
    d = f"M{n(P[0][0])},{n(P[0][1])}"
    for i in range(m):
        a, b = P[i], P[(i + 1) % m]
        ta = np.zeros(2) if corner[i] else (P[(i + 1) % m] - P[i - 1]) / 6
        tb = np.zeros(2) if corner[(i + 1) % m] else (P[(i + 2) % m] - P[i]) / 6
        if corner[i] and corner[(i + 1) % m]:
            d += f" L{n(b[0])},{n(b[1])}"
            continue
        c1, c2 = a + ta, b - tb
        d += f" C{n(c1[0])},{n(c1[1])} {n(c2[0])},{n(c2[1])} {n(b[0])},{n(b[1])}"
    return d + " Z"


def shape_d(f, bbox, tol=0.35, step=0.25):
    loops = trace(f, bbox, step)
    return " ".join(smooth_d(dp_simplify(lp, tol)) for lp in loops)


# ---- documents ------------------------------------------------------------------------------

def svg_doc(w, h, body, defs="", title=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{n(-w / 2)} {n(-h / 2)} {n(w)} {n(h)}" width="{n(w)}" height="{n(h)}">\n'
            + (f"<title>{title}</title>\n" if title else "")
            + (f"<defs>\n{defs}\n</defs>\n" if defs else "")
            + body + "\n</svg>\n")


def lin_grad(gid, x1, y1, x2, y2, stops):
    s = "".join(f'<stop offset="{n(o)}" stop-color="{rgb(c)}" stop-opacity="{alpha(c):.3f}"/>' for o, c in stops)
    return f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{n(x1)}" y1="{n(y1)}" x2="{n(x2)}" y2="{n(y2)}">{s}</linearGradient>'


def rad_grad(gid, cx, cy, r, stops):
    s = "".join(f'<stop offset="{n(o)}" stop-color="{rgb(c)}" stop-opacity="{alpha(c):.3f}"/>' for o, c in stops)
    return f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{n(cx)}" cy="{n(cy)}" r="{n(r)}">{s}</radialGradient>'


def write(name, svg, motion):
    with open(f"{OUT}/{name}.svg", "w") as fh:
        fh.write(svg)
    with open(f"{OUT}/{name}.json", "w") as fh:
        fh.write(json_dump(motion))


def json_dump(obj):
    """Pretty JSON with key lists kept on one line each."""
    def rnd(o):
        if isinstance(o, float):
            return round(o, 3)
        if isinstance(o, list):
            return [rnd(x) for x in o]
        if isinstance(o, dict):
            return {k: rnd(v) for k, v in o.items()}
        return o
    txt = json.dumps(rnd(obj), indent=2, ensure_ascii=False)
    # collapse "keys" arrays
    import re
    def collapse(m):
        return re.sub(r"\s+", " ", m.group(0)).replace("[ ", "[").replace(" ]", "]")
    return re.sub(r'"(keys|origin|size|path)": \[[^\]]*?(\[[^\[\]]*\][^\[\]]*?)*\]', collapse, txt) + "\n"


def periodic(freq, dur):
    """Nearest angular frequency with a whole number of cycles in dur (so a loop is seamless)."""
    cyc = max(1, round(freq * dur / (2 * math.pi)))
    return cyc * 2 * math.pi / dur


def keys(fn, t0, t1, count):
    return [[t0 + (t1 - t0) * i / count, fn(t0 + (t1 - t0) * i / count)] for i in range(count + 1)]
