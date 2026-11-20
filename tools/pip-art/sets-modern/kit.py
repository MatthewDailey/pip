"""Shared helpers for redrawing the Kotlin Canvas scenery as Rive-friendly SVG plus motion JSON."""
import math
import json
import numpy as np

PI = math.pi
W, H = 1080.0, 2424.0
HX, HY = 540.0, 1350.0
DECK = HY - H / 2  # 138: the floor line in a full-screen actor's local coordinates
INK = 0xFF3A2A26
SKIN = 0xFFFDE3D2
SKIN_LINE = 0xFFE7B9A3
HAT = 0xFFF05248
WHITE = 0xFFFFFFFF
BLACK = 0xFF000000

_F = np.float32


def mhash(i):
  """Bit-exact port of the Kotlin mHash (float32 maths, double sin)."""
  x = _F(i) * _F(12.9898) + _F(78.233)
  s = _F(math.sin(float(x)))
  v = s * _F(43758.547)
  return float(v - np.floor(v))


def m01(v):
  return min(1.0, max(0.0, v))


def lerp(a, b, t):
  return a + (b - a) * t


def fract(v):
  return v - math.floor(v)


def smooth(e0, e1, x):
  t = m01((x - e0) / (e1 - e0))
  return t * t * (3 - 2 * t)


def mback(t):
  x = m01(t) - 1
  return 1 + x * x * (2.70158 * x + 1.70158)


def cubic_bezier(x1, y1, x2, y2):
  """CSS cubic-bezier easing as a function of progress."""
  def bez(t, a, b):
    return 3 * (1 - t) ** 2 * t * a + 3 * (1 - t) * t * t * b + t ** 3

  def ease(u):
    if u <= 0:
      return 0.0
    if u >= 1:
      return 1.0
    lo, hi = 0.0, 1.0
    for _ in range(50):
      mid = (lo + hi) / 2
      if bez(mid, x1, x2) < u:
        lo = mid
      else:
        hi = mid
    return bez((lo + hi) / 2, y1, y2)
  return ease


def argb(c):
  a = ((c >> 24) & 0xFF) / 255.0
  return "#%06X" % (c & 0xFFFFFF), a


def mix(a, b, t):
  u = m01(t)
  out = 0
  for s in (24, 16, 8, 0):
    ca = (a >> s) & 0xFF
    cb = (b >> s) & 0xFF
    out |= int(ca + (cb - ca) * u) << s
  return out


def f(v):
  s = "%.2f" % v
  s = s.rstrip("0").rstrip(".")
  return "0" if s in ("-0", "") else s


def fill(c, a=1.0):
  hx, ca = argb(c)
  al = ca * a
  return 'fill="%s"' % hx + ('' if al >= 0.999 else ' fill-opacity="%s"' % f3(al))


def stroke(c, width, a=1.0, cap="round"):
  hx, ca = argb(c)
  al = ca * a
  s = 'fill="none" stroke="%s" stroke-width="%s" stroke-linecap="%s" stroke-linejoin="round"' % (hx, f(width), cap)
  return s + ('' if al >= 0.999 else ' stroke-opacity="%s"' % f3(al))


def f3(v):
  s = "%.3f" % v
  s = s.rstrip("0").rstrip(".")
  return s or "0"


class Svg:
  """Collects gradient defs and body markup; every gradient is userSpaceOnUse so it survives grouping."""

  def __init__(self, vb, title=""):
    self.vb = vb
    self.defs = []
    self.n = 0
    self.title = title

  def _stops(self, stops):
    out = []
    for off, c, *rest in stops:
      a = rest[0] if rest else 1.0
      hx, ca = argb(c)
      out.append('<stop offset="%s" stop-color="%s"%s/>' % (f3(off), hx, '' if ca * a >= 0.999 else ' stop-opacity="%s"' % f3(ca * a)))
    return "".join(out)

  def lin(self, x1, y1, x2, y2, stops):
    self.n += 1
    gid = "g%d" % self.n
    self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>' % (
      gid, f(x1), f(y1), f(x2), f(y2), self._stops(stops)))
    return 'fill="url(#%s)"' % gid

  def vgrad(self, y0, y1, colors, offsets=None):
    offsets = offsets or [i / (len(colors) - 1) for i in range(len(colors))]
    return self.lin(0, y0, 0, y1, [(o, c) for o, c in zip(offsets, colors)])

  def hgrad(self, x0, x1, colors, offsets=None):
    offsets = offsets or [i / (len(colors) - 1) for i in range(len(colors))]
    return self.lin(x0, 0, x1, 0, [(o, c) for o, c in zip(offsets, colors)])

  def rad(self, cx, cy, r, stops):
    self.n += 1
    gid = "g%d" % self.n
    self.defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>' % (
      gid, f(cx), f(cy), f(r), self._stops(stops)))
    return 'fill="url(#%s)"' % gid

  def glow(self, x, y, r, color, alpha, gid=None):
    """The Kotlin glow(): colour at the centre, 40 % at 0.4 r, clear at r; overall alpha as element opacity."""
    if alpha <= 0.004 or r <= 0.5:
      return ""
    paint = self.rad(x, y, r, [(0, color), (0.4, color, 0.4), (1, color, 0)])
    idattr = ' id="%s"' % gid if gid else ''
    return '<circle%s cx="%s" cy="%s" r="%s" %s opacity="%s"/>' % (idattr, f(x), f(y), f(r), paint, f3(min(1, alpha)))

  def doc(self, body):
    x, y, w, h = self.vb
    t = '<title>%s</title>' % self.title if self.title else ''
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="%s %s %s %s" width="%s" height="%s">%s<defs>%s</defs>%s</svg>\n' % (
      f(x), f(y), f(w), f(h), f(w), f(h), t, "".join(self.defs), body))


def rect(x0, y0, x1, y1, paint, rx=0, extra=""):
  r = ' rx="%s"' % f(rx) if rx else ''
  return '<rect x="%s" y="%s" width="%s" height="%s"%s %s%s/>' % (f(x0), f(y0), f(x1 - x0), f(y1 - y0), r, paint, extra)


def oval(x0, y0, x1, y1, paint, idattr=""):
  return '<ellipse%s cx="%s" cy="%s" rx="%s" ry="%s" %s/>' % (idattr, f((x0 + x1) / 2), f((y0 + y1) / 2), f(abs(x1 - x0) / 2), f(abs(y1 - y0) / 2), paint)


def circle(x, y, r, paint):
  return '<circle cx="%s" cy="%s" r="%s" %s/>' % (f(x), f(y), f(r), paint)


def line(x0, y0, x1, y1, paint):
  return '<path d="M%s %sL%s %s" %s/>' % (f(x0), f(y0), f(x1), f(y1), paint)


def path(d, paint, idattr=""):
  return '<path%s d="%s" %s/>' % (idattr, d, paint)


def poly(points, close=True):
  d = "M" + "L".join("%s %s" % (f(x), f(y)) for x, y in points)
  return d + ("Z" if close else "")


def g(gid, children, x=0.0, y=0.0, rot=0.0, sx=1.0, sy=1.0, opacity=1.0, attrs=""):
  """A node in Rive terms: translate, then rotate, then scale; its pivot is its translate origin."""
  tf = []
  if abs(x) > 1e-6 or abs(y) > 1e-6:
    tf.append("translate(%s %s)" % (f(x), f(y)))
  if abs(rot) > 1e-6:
    tf.append("rotate(%s)" % f3(rot))
  if abs(sx - 1) > 1e-6 or abs(sy - 1) > 1e-6:
    tf.append("scale(%s %s)" % (f3(sx), f3(sy)))
  t = ' transform="%s"' % " ".join(tf) if tf else ''
  o = ' opacity="%s"' % f3(opacity) if opacity < 0.999 else ''
  i = ' id="%s"' % gid if gid else ''
  body = "".join(children) if isinstance(children, (list, tuple)) else children
  return '<g%s%s%s%s>%s</g>' % (i, t, o, (" " + attrs) if attrs else "", body)


def flame_d(x, base, h, w, sway):
  return ("M%s %sC%s %s %s %s %s %sC%s %s %s %s %s %sQ%s %s %s %sZ" % (
    f(x - w / 2), f(base),
    f(x - w * 0.62), f(base - h * 0.45), f(x - w * 0.1 + sway * 0.6), f(base - h * 0.7), f(x + sway), f(base - h),
    f(x + w * 0.12 + sway * 0.5), f(base - h * 0.62), f(x + w * 0.62), f(base - h * 0.42), f(x + w / 2), f(base),
    f(x), f(base + w * 0.3), f(x - w / 2), f(base)))


def flame_shapes(h, w):
  """The three nested flame layers (red, orange, yellow core) standing on (0, 0), at rest (no sway)."""
  return [path(flame_d(0, 0, h, w, 0), fill(0xFFE8432A)),
          path(flame_d(0, 0, h * 0.72, w * 0.68, 0), fill(0xFFFF8F2B)),
          path(flame_d(0, 0, h * 0.44, w * 0.4, 0), fill(0xFFFFE066))]


def star_d(x, y, r, n=0.2):
  n = r * n
  return "M%s %sQ%s %s %s %sQ%s %s %s %sQ%s %s %s %sQ%s %s %s %sZ" % (
    f(x), f(y - r), f(x + n), f(y - n), f(x + r), f(y), f(x + n), f(y + n), f(x), f(y + r),
    f(x - n), f(y + n), f(x - r), f(y), f(x - n), f(y - n), f(x), f(y - r))


def arc_cubics(cx, cy, r, a0, a1, segs=None):
  """Circular arc from angle a0 to a1 (degrees, y down) as cubic segments; returns the 'C...' string (no M)."""
  sweep = a1 - a0
  segs = segs or max(1, int(math.ceil(abs(sweep) / 90.0 - 1e-9)))
  out = []
  step = math.radians(sweep / segs)
  k = 4.0 / 3.0 * math.tan(step / 4)
  for s in range(segs):
    t0 = math.radians(a0) + step * s
    t1 = t0 + step
    p0 = (cx + r * math.cos(t0), cy + r * math.sin(t0))
    p3 = (cx + r * math.cos(t1), cy + r * math.sin(t1))
    c1 = (p0[0] - k * r * math.sin(t0), p0[1] + k * r * math.cos(t0))
    c2 = (p3[0] + k * r * math.sin(t1), p3[1] - k * r * math.cos(t1))
    out.append("C%s %s %s %s %s %s" % (f(c1[0]), f(c1[1]), f(c2[0]), f(c2[1]), f(p3[0]), f(p3[1])))
  return "".join(out)


# ------------------------------------------------------------------------------------------------ motion JSON helpers

def r4(v):
  return round(float(v), 4)


def sample(fn, duration, n, ease="linear"):
  """n + 1 evenly spaced keys of fn over [0, duration]."""
  return [[r4(duration * i / n), r4(fn(duration * i / n))] for i in range(n + 1)]


def saw_keys(v0, v1, period, phase, cycles=1):
  """A value that runs linearly from v0 to v1 once per period and snaps back, starting at fraction `phase`."""
  keys = []
  dur = period * cycles
  t = 0.0
  val = lambda ph: v0 + (v1 - v0) * ph
  keys.append([0.0, r4(val(phase))])
  first_wrap = (1 - phase) * period
  tw = first_wrap
  while tw < dur - 1e-6:
    keys.append([r4(tw - 1e-3), r4(v1)])
    keys.append([r4(tw), r4(v0)])
    tw += period
  keys.append([r4(dur), r4(val(phase))])
  return keys


def track(target, prop, keys, ease="linear"):
  return {"target": target, "property": prop, "ease": ease, "keys": keys}


def clip(name, duration, tracks, loop=True, driver="time", note=None, mix_by=None):
  c = {"name": name, "duration": r4(duration), "loop": loop, "driver": driver}
  if mix_by:
    c["mixBy"] = mix_by
  if note:
    c["note"] = note
  c["tracks"] = tracks
  return c


FORMAT_NOTE = (
  "Draft motion format for rivc. Every track targets a <g id> in the SVG; a group's pivot is its translate() origin. "
  "Properties: x, y (absolute, in the parent's units), rotation (degrees, clockwise), scaleX, scaleY, opacity, "
  "strokeWidth, fillColor ('#RRGGBB'). Keys are [time s, value]; 'ease' applies between keys (linear, hold, sine, quadIn). "
  "driver 'time' clips play on the clock; driver 'input:<name>' clips are scrubbed, clip time = input value. "
  "mixBy names an input whose value (0..1) is the clip's blend weight. The SVG's own transforms are each group's setup pose.")


def piece_json(piece, scene, painter, origin, vb, pose, clips, inputs=(), extra=None):
  d = {
    "piece": piece,
    "scene": scene,
    "painter": painter,
    "origin": [r4(origin[0]), r4(origin[1])] if origin[0] is not None else None,
    "origin_note": "screen px (1080 x 2424 stage) where the SVG's (0, 0) sits when the actor is at rest",
    "viewBox": [r4(v) for v in vb],
    "size": [r4(vb[2]), r4(vb[3])],
    "pose": pose,
    "format": FORMAT_NOTE,
    "clips": clips,
    "inputs": list(inputs),
  }
  if extra:
    d.update(extra)
  return d


def _compact(o, ind=0):
  pad = "  " * ind
  if isinstance(o, dict):
    if not o:
      return "{}"
    items = ['%s  %s: %s' % (pad, json.dumps(k), _compact(v, ind + 1)) for k, v in o.items()]
    return "{\n" + ",\n".join(items) + "\n" + pad + "}"
  if isinstance(o, list):
    if all(not isinstance(v, (dict, list)) for v in o) or all(isinstance(v, list) and all(not isinstance(x, (dict, list)) for x in v) for v in o):
      return json.dumps(o, separators=(",", ":"))
    return "[\n" + ",\n".join(pad + "  " + _compact(v, ind + 1) for v in o) + "\n" + pad + "]"
  return json.dumps(o)


def dump_json(d):
  return _compact(d) + "\n"


def flicker_tracks(gid, height, width, seed, period):
  """The Kotlin flame() flicker (height x fl, tip sway) as a periodic loop: scaleY and a sway rotation about the base."""
  c1 = max(1, round(11 * period / (2 * PI)))
  c2 = max(1, round(23 * period / (2 * PI)))
  c3 = max(1, round(6.5 * period / (2 * PI)))
  w = lambda c: 2 * PI * c / period
  fl = lambda t: 0.86 + 0.1 * math.sin(w(c1) * t + seed * 2.3) + 0.06 * math.sin(w(c2) * t + seed * 0.7)
  rot = lambda t: math.degrees(math.atan2(math.sin(w(c3) * t + seed * 1.7) * width * 0.22, height * fl(t)))
  n = max(16, 3 * c2)
  return [track(gid, "scaleY", sample(fl, period, n)), track(gid, "rotation", sample(rot, period, n))], fl(0), rot(0)


def flame_node(svg, gid, x, base, height, width, seed, period, glow=True, sx=1.0, sy=1.0, opacity=1.0):
  """A flame standing at (x, base): outer group `gid` (placement / growth), inner `gid-flicker` (loop)."""
  tracks, fl0, rot0 = flicker_tracks(gid + "-flicker", height, width, seed, period)
  kids = []
  if glow:
    kids.append(svg.glow(0, -height * 0.4, height * 0.95, 0xFFFF7A1E, 0.3))
  kids += flame_shapes(height, width)
  inner = g(gid + "-flicker", kids, rot=rot0, sy=fl0)
  return g(gid, inner, x=x, y=base, sx=sx, sy=sy, opacity=opacity), tracks
