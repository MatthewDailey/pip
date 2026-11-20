"""Today scenery: the torch-lit corridor and the arched plank door (HODOR), the falling dust, the playground yard and the doll."""
import math
from kit import *

FULL_VB = (-600, -1272, 1200, 2544)
SCREEN_C = (540, 1212)
DOOR_C = (540, 970)

THUDS = [(0.5, 0.55), (1.5, 0.6), (2.0, 0.7), (2.5, 0.8), (2.75, 0.75), (3.0, 1.0), (3.25, 0.8), (3.5, 1.0), (3.75, 0.85), (4.0, 1.15)]


def impulse(lt):
  return sum(s * math.exp(-(lt - t) * 9) for t, s in THUDS if lt >= t)


def light_of(lt):
  return min(1.3, max(0.0, 0.12 + 0.8 * smooth(0.8, 3.6, lt) - 0.85 * smooth(4.1, 4.5, lt) + 0.45 * impulse(lt)))


def input_curves():
  rate = 60
  n = int(4.85 * rate) + 1
  return {"note": "the scene clock drives these two inputs (dToday seconds 0 - 4.85); impact spikes on each thud and decays with exp(-9 age), light builds as the door gives, flares on hits and dies in the silence",
          "rate_hz": rate,
          "impact": [round(impulse(i / rate), 3) for i in range(n)],
          "light": [round(light_of(i / rate), 3) for i in range(n)],
          "thuds": [[t, s] for t, s in THUDS]}


INPUTS = [{"name": "light", "type": "number", "range": [0, 1.3], "drives": "light blazing through the cracks (0 dark, 1 full, up to 1.3 on a big hit)"},
          {"name": "impact", "type": "number", "range": [0, 1.6], "drives": "how hard the door is ringing right now; weights the rattle clip and swells the glow"}]

TORCH_P = 2 * PI * 3 / 7.3  # 7.3 and 17 rad/s both fit whole cycles


# ------------------------------------------------------------------------------------------------ corridor

def corridor(state=None):
  st = state or {}
  lt = st.get("lt", 0.3)
  light, imp = light_of(lt), impulse(lt)
  s = Svg(FULL_VB, "Torch-lit corridor")
  hw, hh, w, h = 600, 1272, W, H
  floor_y = 970 + 380 - h / 2
  out = [rect(-hw, -hh, hw, hh, fill(0xFF141012))]
  bricks = []
  r, y0 = 0, -hh
  while y0 < floor_y:
    x = -hw - mhash(r * 5) * 140
    k = 0
    while x < hw:
      bw = 150 + 110 * mhash(r * 13 + k)
      col = mix(0xFF2B2421, 0xFF3E342F, mhash(r * 31 + k * 3))
      bricks.append(rect(x + 4, y0 + 4, x + bw - 4, y0 + 92, fill(col), rx=10))
      bricks.append(line(x + 12, y0 + 8, x + bw - 14, y0 + 8, stroke(0xFF5C4D45, 3, 0.45)))
      bricks.append(line(x + 12, y0 + 90, x + bw - 14, y0 + 90, stroke(0xFF0A0708, 3, 0.5)))
      x += bw
      k += 1
    y0 += 96
    r += 1
  out.append(g("bricks", bricks))
  fl = [rect(-hw, floor_y, hw, hh, s.vgrad(floor_y, h / 2, [0xFF2E2420, 0xFF0F0C0B]))]
  fy, gap = floor_y + 36, 36
  while fy < hh:
    fl.append(line(-hw, fy, hw, fy, stroke(0xFF0A0708, 3, 0.55)))
    gap *= 1.4
    fy += gap
  for k in range(-6, 7):
    fl.append(line(k * 90, floor_y, k * 320, hh, stroke(0xFF0A0708, 3, 0.45)))
  out.append(g("floor", fl))
  pool = path(poly([(-250, floor_y), (250, floor_y), (460, floor_y + 420), (-460, floor_y + 420)]),
              s.lin(0, floor_y, 0, floor_y + 420, [(0, 0xCCFFD68A), (1, 0x00FFD68A)]))
  out.append(g("light-pool", pool, opacity=0.55 * m01(light)))
  flk = lambda t: 0.82 + 0.12 * math.sin(2 * PI * 3 * t / TORCH_P) + 0.06 * math.sin(2 * PI * 7 * t / TORCH_P)
  torches, ttracks = [], []
  for side in (-1, 1):
    nm = "torch-%s" % ("l" if side < 0 else "r")
    tx, ty = side * 400, floor_y - 700
    torches.append(g(nm + "-glow", s.glow(tx, ty - 30, 560, 0xFFFF9A3C, 0.32), opacity=flk(0)))
    ttracks.append(track(nm + "-glow", "opacity", sample(flk, TORCH_P, 36)))
    bracket = [line(tx, ty + 80, tx, ty + 12, stroke(0xFF2A2523, 14)), line(tx - side * 30, ty + 84, tx, ty + 60, stroke(0xFF2A2523, 10)),
               path(poly([(tx - 26, ty - 6), (tx + 26, ty - 6), (tx + 14, ty + 20), (tx - 14, ty + 20)]), fill(0xFF3A3330))]
    torches.append(g(nm + "-bracket", bracket))
    node, tr = flame_node(s, nm + "-flame", tx, ty - 4, 100, 50, 5 + side, TORCH_P, glow=False)
    torches.append(node)
    ttracks += tr
  out.append(g("torches", torches))
  out.append(g("vignette", rect(-hw, -hh, hw, hh, s.rad(0, floor_y - 380, h * 0.62, [(0, BLACK, 0), (0.5, BLACK, 0), (1, 0xD8000000)]))))
  out.append(g("impact-flash", rect(-hw, -hh, hw, hh, fill(0xFFFFB060)), opacity=0.1 * m01(imp)))
  clips = [clip("torches", TORCH_P, ttracks, note="torch light 0.82 + 0.12 sin 7.3t + 0.06 sin 17t (fits 2.58 s exactly); flames flicker as in the fire pieces"),
           clip("light", 1.3, [track("light-pool", "opacity", [[0, 0], [1, 0.55], [1.3, 0.55]])], loop=False, driver="input:light"),
           clip("impact", 1.6, [track("impact-flash", "opacity", [[0, 0], [1, 0.1], [1.6, 0.1]])], loop=False, driver="input:impact")]
  j = piece_json("set-dtoday-corridor", "dToday", "paintCorridor", SCREEN_C, FULL_VB, "dToday 0.3 s (light 0.12, quiet)", clips, inputs=INPUTS,
                 extra={"input_curves": "see set-dtoday-door.json (shared light / impact curves)",
                        "layers": ["bricks", "floor", "light-pool", "torches/torch-l-*, torch-r-* (glow, bracket, flame/-flicker)", "vignette", "impact-flash"],
                        "scene_motion": "fades in over 0.35 s; camera shakes on each thud (scene level)"})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ door

R_ARCH = 260.0
N_PLANK = 7
PW = 520.0 / N_PLANK


def arch_top(x):
  x = max(-R_ARCH, min(R_ARCH, x))
  return -120 - math.sqrt(R_ARCH * R_ARCH - x * x)


def arch_ang(x):
  return -math.degrees(math.acos(max(-1, min(1, x / R_ARCH))))


def door_outline(g_, closed=True):
  """The Kotlin doorShape(): straight jambs, a half-circle head (radius 260 + g about (0, -120)), floor at 380."""
  r = R_ARCH + g_
  d = "M%s 380L%s -120%s L%s 380" % (f(-r), f(-r), arc_cubics(0, -120, r, 180, 360), f(r))
  return d + ("Z" if closed else "")


def strap_poly(sy, half=16):
  """A strap (y sy +/- 16, x +/- 262) clipped to the door opening."""
  pts_l, pts_r = [], []
  for k in range(9):
    y = sy - half + 2 * half * k / 8
    xr = min(262, R_ARCH if y >= -120 else math.sqrt(max(0, R_ARCH ** 2 - (y + 120) ** 2)))
    pts_r.append((xr, y))
    pts_l.append((-xr, y))
  return pts_r + pts_l[::-1]


def door(state=None):
  st = state or {}
  lt = st.get("lt", None)
  light = light_of(lt) if lt is not None else st.get("light", 0.55)
  imp = impulse(lt) if lt is not None else st.get("impact", 0.0)
  ml = m01(light)
  vb = (-800, -820, 1600, 1600)
  s = Svg(vb, "Arched plank door")
  inner = []
  GR = 720.0
  gscale = lambda i: (560 + 160 * i) / GR
  inner.append(g("door-glow", s.glow(0, 0, GR, 0xFFFFD08A, 1.0), y=-380, sx=gscale(imp), sy=gscale(imp), opacity=0.28 * light))
  # Stone frame with voussoirs.
  frame = [path(door_outline(50), s.vgrad(-430, 380, [0xFF5A4F48, 0xFF3A322D]))]
  for k in range(9):
    a = math.radians(180 + k * 22.5)
    frame.append(line(math.cos(a) * 262, -120 + math.sin(a) * 262, math.cos(a) * 310, -120 + math.sin(a) * 310, stroke(0xFF221C19, 4, 0.8)))
  for yy in (-40, 90, 220, 330):
    frame.append(line(-310, yy, -262, yy, stroke(0xFF221C19, 4, 0.8)))
    frame.append(line(262, yy, 310, yy, stroke(0xFF221C19, 4, 0.8)))
  frame.append(path(door_outline(50), stroke(0xFF7A6C62, 3, 0.5)))
  inner.append(g("door-frame", frame))
  # The light behind the door: dark when quiet, a hot glow as it gives.
  inner.append(g("backlight", [path(door_outline(0), fill(0xFF3A2414)), g("backlight-hot", path(door_outline(0), fill(0xFFFFE6B0)), opacity=ml)]))
  planks, light_t, rattle = [], [], []
  gap = 4 + 4 * ml
  RP = 0.5
  for i in range(N_PLANK):
    x0, x1 = -260 + i * PW + 2, -260 + (i + 1) * PW - 2  # drawn at the 4 px rest gap; scaleX narrows it as light grows
    pcx = (x0 + x1) / 2
    lx0, lx1 = x0 - pcx, x1 - pcx
    body = "M%s 380L%s %s%s L%s 380Z" % (f(lx0), f(lx0), f(arch_top(x0)), arc_cubics(-pcx, -120, R_ARCH, arch_ang(x0), arch_ang(x1)), f(lx1))
    paint = s.vgrad(-380, 380, [0xFF8E5D36, 0xFF6A4224, 0xFF4A2D17] if i % 2 == 0 else [0xFF835532, 0xFF623C20, 0xFF432814])
    kids = [path(body, paint)]
    kids.append(line(lx0 + 2, arch_top(x0 + 2) + 2, lx0 + 2, 380, stroke(0xFFB98A5E, 3, 0.3, cap="butt")))
    kids.append(line(lx1 - 2, arch_top(x1 - 2) + 2, lx1 - 2, 380, stroke(0xFF2A190C, 3, 0.4, cap="butt")))
    for gn in range(3):
      gx = x0 + 14 + gn * 18 + mhash(i * 3 + gn) * 6
      gy = max(-330 + mhash(i * 7 + gn) * 300, arch_top(gx) + 10)
      kids.append(path("M%s %sC%s %s %s %s %s %s" % (f(gx - pcx), f(gy), f(gx + 6 - pcx), f(gy + 90), f(gx - 6 - pcx), f(gy + 180), f(gx + 2 - pcx), f(min(376, gy + 300))),
                       stroke(0xFF3A2312, 2.5, 0.35)))
    if mhash(i + 11) > 0.45:
      ky = max(-250 + 480 * mhash(i + 5), arch_top(pcx) + 22)
      kids.append(oval(-9, ky - 14, 9, ky + 14, fill(0xFF3A2312, 0.6)))
    gid = "plank-%d" % i
    k_gap = lambda L: (PW - 4 - 4 * m01(L)) / (PW - 4)
    planks.append(g(gid, kids, x=pcx, sx=(PW - gap) / (PW - 4)))
    light_t.append(track(gid, "scaleX", [[0, 1], [1, r4(k_gap(1))], [1.3, r4(k_gap(1))]]))
    rx = lambda t, i=i, c=pcx: c + 7 * math.sin(2 * PI * 6 * t / RP + i * 1.9)
    ry = lambda t, i=i: 6 * math.cos(2 * PI * 4 * t / RP + i * 2.7)
    rattle += [track(gid, "x", sample(rx, RP, 48)), track(gid, "y", sample(ry, RP, 48))]
  inner.append(g("planks", planks))
  cracks = []
  for i in range(1, N_PLANK):
    gx = -260 + i * PW
    top = arch_top(gx)
    gid = "crack-%d" % i
    cracks.append(g(gid, [line(0, top, 0, 380, stroke(0xFFFFE6A6, 2 + 7 * ml, 0.2 + 0.6 * ml, cap="butt")).replace("<path ", '<path id="%s-glow" ' % gid, 1),
                          line(0, top, 0, 380, stroke(WHITE, 1.5, 0.6 * ml, cap="butt")).replace("<path ", '<path id="%s-core" ' % gid, 1)], x=gx))
    light_t += [track(gid + "-glow", "strokeWidth", [[0, 2], [1, 9], [1.3, 9]]), track(gid + "-glow", "opacity", [[0, 0.2], [1, 0.8], [1.3, 0.8]]),
                track(gid + "-core", "opacity", [[0, 0], [1, 0.6], [1.3, 0.6]])]
    rattle.append(track(gid, "x", sample(lambda t, i=i, c=gx: c + 3.5 * math.sin(2 * PI * 6 * t / RP + i * 1.9), RP, 48)))
  inner.append(g("cracks", cracks))
  straps = []
  for sn, sy in enumerate((-170, 60, 290)):
    kids = [path(poly([(x, y - sy) for x, y in strap_poly(sy)]), fill(0xFF3B3634))]
    hx = min(262, math.sqrt(max(0, R_ARCH ** 2 - (sy - 13 + 120) ** 2)) if sy - 13 < -120 else R_ARCH)
    kids.append(line(-hx, -13, hx, -13, stroke(0xFF77706B, 3, 0.6, cap="butt")))
    for i in range(N_PLANK):
      rxp = -260 + PW * (i + 0.5)
      kids += [circle(rxp, 0, 7, fill(0xFF5A5450)), circle(rxp - 2, -2, 2.5, fill(0xFFB0A8A2))]
    gid = "strap-%d" % sn
    straps.append(g(gid, kids, y=sy))
    rattle.append(track(gid, "y", sample(lambda t, sn=sn, sy=sy: sy + 4 * math.sin(2 * PI * 4 * t / RP + sn * 2), RP, 32)))
  inner.append(g("straps", straps))
  # The stone reveal lapping the door's edge (also hides the planks' rattle at the arch).
  lip_paint = s.vgrad(-430, 380, [0xFF5A4F48, 0xFF3A322D]).replace("fill=", "stroke=")
  inner.append(g("frame-lip", '<path d="%s" fill="none" %s stroke-width="12"/>' % (door_outline(0, closed=False), lip_paint)))
  rim = line(-250, 378, 250, 378, stroke(0xFFFFE2A0, 3 + 6 * ml, 0.3 + 0.6 * ml)).replace("<path ", '<path id="hot-rim-line" ', 1)
  inner.append(g("hot-rim", rim))
  light_t += [track("hot-rim-line", "strokeWidth", [[0, 3], [1, 9], [1.3, 9]]), track("hot-rim-line", "opacity", [[0, 0.3], [1, 0.9], [1.3, 0.9]]),
              track("backlight-hot", "opacity", [[0, 0], [1, 1], [1.3, 1]]), track("door-glow", "opacity", [[0, 0], [1.3, 0.364]])]
  # Everything inside shakes and swells from the floor line.
  body = "".join(x if isinstance(x, str) else "" for x in inner)
  shifted = g(None, body, y=-380)
  shake = g("door-shake", shifted, y=380 - 3 * imp, sx=1 + 0.012 * imp, sy=1 + 0.012 * imp)
  rattle.append(track("door-shake", "x", sample(lambda t: 5 * math.sin(2 * PI * 5 * t / RP), RP, 40)))
  impact_t = [track("door-shake", "y", [[0, 380], [1.6, r4(380 - 3 * 1.6)]]),
              track("door-shake", "scaleX", [[0, 1], [1.6, r4(1 + 0.012 * 1.6)]]), track("door-shake", "scaleY", [[0, 1], [1.6, r4(1 + 0.012 * 1.6)]]),
              track("door-glow", "scaleX", [[0, r4(gscale(0))], [1.6, r4(gscale(1.6))]]), track("door-glow", "scaleY", [[0, r4(gscale(0))], [1.6, r4(gscale(1.6))]])]
  clips = [clip("light", 1.3, light_t, loop=False, driver="input:light",
                note="the backlight heats from dark brown to cream, the gaps between planks widen 4 -> 8 px, the cracks blaze and the bottom rim glows"),
           clip("impact", 1.6, impact_t, loop=False, driver="input:impact", note="the door bulges toward us (scale about the floor line) and lifts; its halo swells"),
           clip("rattle", RP, rattle, mix_by="impact",
                note="planks jitter (+/-7 x, +/-6 y), cracks follow, straps bounce +/-4, the whole door shivers +/-5; weight = impact (clamp to 1). Frequencies ~ the Kotlin 71 / 53 / 47 / 61 rad/s, rounded to fit 0.5 s")]
  j = piece_json("set-dtoday-door", "dToday", "paintDoor", DOOR_C, vb, "light %.2f, impact %.2f" % (light, imp), clips, inputs=INPUTS,
                 extra={"input_curves": input_curves(),
                        "layers": ["door-shake/ door-glow, door-frame, backlight/backlight-hot, planks/plank-0..6, cracks/crack-1..6 (-glow, -core), straps/strap-0..2, frame-lip, hot-rim"],
                        "approximation": "the Kotlin clips the planks to the arch; here each plank's top edge is the arch itself, and a 6 px stone lip (frame-lip) laps the opening so rattling planks never poke past it"})
  return s.doc(shake), j


# ------------------------------------------------------------------------------------------------ dust

def dust(state=None):
  tnow = (state or {}).get("lt", None)
  cx, top = 540.0, 970 - 400.0
  ox, oy = 540.0, 900.0
  vb = (-420, -420, 840, 1960)
  s = Svg(vb, "Falling dust")
  groups, tracks = [], []
  for n, (ti, sv) in enumerate(THUDS):
    seed = int(ti * 100)
    age = None if tnow is None else tnow - ti
    kids = []
    hx = cx + (mhash(seed) - 0.5) * 300 - ox
    hid = "haze-%d" % n
    if age is None or 0 <= age <= 1.6:
      a = 0.0 if age is None else age
      kids.append(g(hid, s.glow(0, 0, 60, 0xFFB8A68E, 1.0), x=hx, y=top + 30 + a * 80 - oy, sx=(60 + 160 * a) / 60, sy=(60 + 160 * a) / 60,
                    opacity=0.35 * sv * m01(1 - a / 0.9)))
    tracks += [track(hid, "opacity", [[0, 0], [r4(ti - 0.001), 0], [r4(ti), r4(0.35 * sv)], [r4(ti + 0.9), 0]]),
               track(hid, "y", [[r4(ti), r4(top + 30 - oy)], [r4(ti + 0.9), r4(top + 30 + 72 - oy)]]),
               track(hid, "scaleX", [[r4(ti), 1], [r4(ti + 0.9), r4((60 + 144) / 60)]]), track(hid, "scaleY", [[r4(ti), 1], [r4(ti + 0.9), r4((60 + 144) / 60)]])]
    for k in range(int(10 + 16 * sv)):
      h1, h2, h3 = mhash(seed * 7 + k), mhash(seed * 11 + k * 3), mhash(seed * 13 + k * 5)
      chunk = k % 4 == 0 and sv > 0.7
      gr = 1500 + 600 * h3 if chunk else 220 + 380 * h3
      x0 = cx - 270 + 540 * h1 - ox
      y0 = top - 20 + 60 * h2 - oy
      gid = "grit-%d-%d" % (n, k)
      a = 0.0 if age is None else age
      vis = age is None or 0 <= age <= 1.6
      x = x0 + (h2 - 0.5) * 70 * a
      y = y0 + 0.5 * gr * a * a
      al = m01(1 - a / 1.6) if vis else 0
      if chunk:
        shape = rect(-5 - 4 * h3, -5 - 3 * h1, 5 + 4 * h3, 5 + 3 * h1, fill(0xFF6A5D55))
        node = g(gid, shape, x=x, y=y, rot=a * 400 * (h2 - 0.5), opacity=al)
        tracks.append(track(gid, "rotation", [[r4(ti), 0], [r4(ti + 1.6), r4(1.6 * 400 * (h2 - 0.5))]]))
        peak = 1.0
      else:
        node = g(gid, circle(0, 0, 2 + 4 * h3, fill(0xFFD8C8B0)), x=x, y=y, opacity=al * 0.85)
        peak = 0.85
      kids.append(node)
      tracks += [track(gid, "opacity", [[0, 0], [r4(ti - 0.001), 0], [r4(ti), peak], [r4(ti + 1.6), 0]]),
                 track(gid, "x", [[r4(ti), r4(x0)], [r4(ti + 1.6), r4(x0 + (h2 - 0.5) * 112)]]),
                 track(gid, "y", [[r4(ti), r4(y0)], [r4(ti + 1.6), r4(y0 + 0.5 * gr * 2.56)]], "quadIn")]
    groups.append(g("thud-%d" % n, kids))
  c = clip("falls", 5.6, tracks, loop=False,
           note="for each thud (see set-dtoday-door input_curves.thuds) a haze puff and 18-28 grains drop from above the frame: x drifts linearly, y falls under gravity (quadIn is exact), alpha fades over 1.6 s; big hits also shed spinning stone chips. Play from dToday 0 s.")
  j = piece_json("set-dtoday-dust", "dToday", "paintDust", (ox, oy), vb, "every grain at its spawn point (the clip hides each until its thud)", [c],
                 extra={"layers": ["thud-0..9 / haze-n, grit-n-k"]})
  return s.doc(g("dust", groups)), j


# ------------------------------------------------------------------------------------------------ yard

def yard(state=None):
  p = (state or {}).get("progress", 0.0)
  s = Svg(FULL_VB, "Playground yard")
  hw, hh, w, h, deck = 600, 1272, W, H, DECK
  wall_y = deck - 560
  out = [rect(-hw, -hh, hw, wall_y, s.vgrad(-h / 2, wall_y, [0xFFB8EADF, 0xFF7CCBBB]))]
  clouds, ct = [], []
  CP = 2 * PI / 0.2
  for i in range(5):
    cx0 = -w / 2 + w * (0.1 + 0.2 * i) + (mhash(i + 500) - 0.5) * 80
    cy = -h / 2 + 260 + 420 * mhash(i + 510)
    puffs = [circle((b - 1.5) * 46, -(24 if b in (1, 2) else 0), 44 + 10 * mhash(i * 4 + b), fill(WHITE, 0.92)) for b in range(4)]
    xf = lambda t, i=i, c=cx0: c + math.sin(0.2 * t + i) * 6
    clouds.append(g("cloud-%d" % i, puffs, x=xf(0), y=cy))
    ct.append(track("cloud-%d" % i, "x", sample(xf, CP, 8)))
  out.append(g("clouds", clouds))
  out.append(rect(-hw, wall_y - 10, hw, wall_y + 8, fill(0xFF5FA898)))
  sand = [rect(-hw, wall_y + 8, hw, hh, s.vgrad(wall_y, h / 2, [0xFFF1DDB4, 0xFFE2C48F, 0xFFD1AE76], [0, 0.5, 1]))]
  for i in range(60):
    sand.append(circle(-w / 2 + w * mhash(i + 600), wall_y + 40 + (h / 2 - wall_y) * mhash(i + 660), 2 + 3 * mhash(i + 720), fill(0xFFB8955E, 0.35)))
  out.append(g("sand", sand))
  tx, ty = -330, wall_y + 190
  tree = [rect(tx - 16, ty - 260, tx + 16, ty, fill(0xFF6E4A30), rx=10), oval(tx - 90, ty + 6, tx + 90, ty + 26, fill(BLACK, 0.12))]
  for b in range(5):
    a = math.radians(b * 72)
    tree.append(circle(tx + math.cos(a) * 56, ty - 300 + math.sin(a) * 40, 66, fill(0xFF4E8A4A if b % 2 == 0 else 0xFF5FA05A)))
  tree.append(circle(tx - 20, ty - 330, 40, fill(0xFF79B872, 0.7)))
  out.append(g("tree", tree))
  ly = deck - 330
  out.append(g("finish-line", rect(-hw, ly - 7, hw, ly + 7, fill(0xFFE0453A, 0.85))))
  out.append(g("red-tint", rect(-hw, -hh, hw, hh, fill(0xFFFF3B30)), opacity=0.13 * m01(p)))
  clips = [clip("cloud-drift", CP, ct, note="painted clouds drift +/-6 px on sin(0.2t)"),
           clip("red-light", 1.0, [track("red-tint", "opacity", [[0, 0], [1, 0.13]])], loop=False, driver="input:progress")]
  j = piece_json("set-dtoday-yard", "dToday", "paintYard", SCREEN_C, FULL_VB, "progress 0 (green light)", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "red-light tint: 0 -> 1 over 0.16 s as the doll turns (5.95 s), back to 0 at 7.3 s"}],
                 extra={"layers": ["clouds/cloud-0..4", "sand", "tree", "finish-line", "red-tint"]})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ doll

def _ellipse_pts(cx, cy, rx, ry, n=96):
  return [(cx + rx * math.cos(2 * PI * k / n), cy + ry * math.sin(2 * PI * k / n)) for k in range(n)]


def _clip_convex(subject, clipper):
  """Sutherland-Hodgman: subject (any simple polygon) clipped by a convex, counter-clockwise-in-y-down clipper."""
  def inside(p, a, b):
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0

  def inter(p, q, a, b):
    x1, y1, x2, y2 = p[0], p[1], q[0], q[1]
    x3, y3, x4, y4 = a[0], a[1], b[0], b[1]
    den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if abs(den) < 1e-12:
      return q
    t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
    return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))

  out = subject
  for i in range(len(clipper)):
    a, b = clipper[i], clipper[(i + 1) % len(clipper)]
    inp, out = out, []
    if not inp:
      break
    s_ = inp[-1]
    for e in inp:
      if inside(e, a, b):
        if not inside(s_, a, b):
          out.append(inter(s_, e, a, b))
        out.append(e)
      elif inside(s_, a, b):
        out.append(inter(s_, e, a, b))
      s_ = e
  return out


def _quad_pts(p0, c, p1, n=10):
  return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1])
          for t in (k / n for k in range(1, n + 1))]


R_HEAD = 120.0
HAIR = 0xFF3A2418
HEAD_CLIP = _ellipse_pts(0, 0, R_HEAD, R_HEAD, 160)


def _head_pose(s, q, idx, t=0.0, scan_tracks=None):
  r = R_HEAD
  kids = []
  if q > 0.02:
    fx = r * 0.98 * (1 - q)
    frx = r * 0.86 * (0.2 + 0.8 * math.sin(q * PI / 2))
    face = _clip_convex(_ellipse_pts(fx, (-r * 0.62 + r * 0.98) / 2, frx, (r * 0.98 + r * 0.62) / 2, 120), HEAD_CLIP)
    if face:
      kids.append(path(poly(face), fill(0xFFFCE1CC)))
    feat = smooth(0.35, 0.8, q)
    if feat > 0:
      sc = frx / (r * 0.86)
      scan = smooth(0.85, 1, q)
      for side in (-1, 1):
        ex, ey = fx + side * 0.38 * frx, 16
        if scan > 0:
          kids.append(s.glow(ex, ey, 46, 0xFFFF3B30, 0.55 * scan * feat))
        kids.append(oval(ex - 11 * sc, ey - 14, ex + 11 * sc, ey + 14, fill(0xFF231510, feat)))
        pup = circle(0, 0, 4.5 * sc, fill(mix(WHITE, 0xFFFF6A5E, scan), feat))
        pid = "pupil-%d-%s" % (idx, "l" if side < 0 else "r")
        look = math.sin(2.6 * t) * 5 * scan
        kids.append(g(pid, pup, x=ex + look * sc, y=ey - 2))
        if scan_tracks is not None and scan > 0:
          scan_tracks.append(track(pid, "x", sample(lambda tt, ex=ex, sc=sc, scan=scan: ex + math.sin(2.6 * tt) * 5 * scan * sc, 2 * PI / 2.6, 16)))
        kids.append(oval(ex - 18 * sc, ey + 22, ex + 18 * sc, ey + 36, fill(0xFFF59A9A, 0.6 * feat)))
      kids.append(path("M%s 62Q%s 70 %s 62" % (f(fx - 12 * sc), f(fx), f(fx + 12 * sc)), stroke(0xFFB2504A, 4, feat)))
  edge = lerp(r * 0.2, -r * 0.3, q)
  pts = [(-r, -r * 1.1), (r, -r * 1.1), (r, edge)]
  prev = (r, edge)
  for k in range(5):
    x1 = r - (k + 1) * (2 * r / 5)
    nxt = (x1, edge)
    pts += _quad_pts(prev, (x1 + r / 5, edge + 22), nxt)
    prev = nxt
  bangs = _clip_convex(pts, HEAD_CLIP)
  kids.append(path(poly(bangs), fill(HAIR)))
  if q < 0.6:
    al = 1 - q / 0.6
    for k in range(4):
      kids.append(path("M%s -92Q%s 0 %s 92" % (f(-60 + k * 40), f(-50 + k * 40), f(-70 + k * 44)), stroke(0xFF6A4630, 3, 0.5 * al)))
  return kids


POSES = [i / 10 for i in range(11)]


def doll(state=None):
  st = state or {}
  p = st.get("progress", 0.0)
  t = st.get("time", 0.0)
  q = m01(p)
  vb = (-170, -190, 340, 650)
  s = Svg(vb, "Red light doll")
  body = [oval(-120, 405, 120, 440, fill(BLACK, 0.14)),
          rect(-44, 320, -14, 425, fill(SKIN), rx=12), rect(14, 320, 44, 425, fill(SKIN), rx=12),
          rect(-46, 380, -12, 396, fill(WHITE)), rect(12, 380, 46, 396, fill(WHITE)),
          rect(-50, 410, -10, 432, fill(0xFF2A1A14), rx=10), rect(10, 410, 50, 432, fill(0xFF2A1A14), rx=10),
          rect(-112, 130, -82, 270, fill(SKIN), rx=14), rect(82, 130, 112, 270, fill(SKIN), rx=14),
          path("M-72 104L72 104L132 336Q0 356 -132 336Z", s.vgrad(104, 350, [0xFFFFA24A, 0xFFE07A22])),
          rect(-58, 96, 58, 128, fill(0xFFFFE07A), rx=12)]
  out = [g("doll-body", body)]
  collar = lambda v: smooth(0.4, 0.8, m01(v))
  out.append(g("collar", [oval(-46, 100, 0, 136, fill(WHITE)), oval(0, 100, 46, 136, fill(WHITE))], opacity=collar(p)))
  head = [oval(-R_HEAD * 0.9, R_HEAD * 0.7, R_HEAD * 0.9, R_HEAD, fill(BLACK, 0.12))]
  for side in (-1, 1):
    head.append(circle(side * 74, -R_HEAD + 8, 42, fill(HAIR)))
    head.append(circle(side * 74 - 10, -R_HEAD - 4, 14, fill(0xFF5A3A28, 0.6)))
    bx, by = side * 42, -R_HEAD + 30
    head.append(path(poly([(bx, by), (bx + side * 26, by - 18), (bx + side * 26, by + 18)]), fill(HAT)))
  head.append(circle(0, 0, R_HEAD, s.rad(-30, -40, R_HEAD * 1.3, [(0, 0xFF5A3A28), (1, HAIR)])))
  scan_tracks = []
  pose_t = []
  near = min(range(len(POSES)), key=lambda k: abs(POSES[k] - q))
  for k, pq in enumerate(POSES):
    gid = "head-pose-%d" % k
    head.append(g(gid, _head_pose(s, pq, k, t, scan_tracks if k >= 9 else None), opacity=1 if k == near else 0))
    lo, hi = pq - 0.05, pq + 0.05
    keys = [[0, 1 if k == 0 else 0]]
    if k > 0:
      keys += [[r4(lo), 1]]
    if k < len(POSES) - 1:
      keys += [[r4(hi), 0]]
    keys.append([1.2, 1 if k == len(POSES) - 1 else 0])
    pose_t.append(track(gid, "opacity", keys, "hold"))
  sway = lambda tt: 3 * math.sin(1.3 * tt)
  out.append(g("doll-head", g(None, "".join(head), y=-100), y=100, rot=(1 - q) * sway(t) + (p - q) * 30))
  pose_t += [track("collar", "opacity", sample(collar, 1.0, 8)), track("doll-head", "rotation", [[0, 0], [1, 0], [1.2, 6]])]
  SW = 2 * PI / 1.3
  clips = [clip("turn", 1.2, pose_t, loop=False, driver="input:progress",
                note="eleven head poses (progress 0, 0.1 ... 1) swap on hold keys: the face slides round from her right and widens, features fade in from 0.35, eyes start to glow red from 0.85. Past 1 (overshoot ease) the head tips up to 6 deg."),
           clip("idle-sway", SW, [track("doll-head", "rotation", sample(sway, SW, 16))], mix_by="1 - progress",
                note="back turned, her head rocks +/-3 deg (sin 1.3t); fades out as she turns"),
           clip("eye-scan", 2 * PI / 2.6, scan_tracks, note="glowing pupils scan side to side (+/-5 px) once she is facing")]
  j = piece_json("set-dtoday-doll", "dToday", "paintDoll", (540, 640), vb, "progress %.2f" % p, clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1.2],
                          "drives": "0 back turned, 1 facing us. Scene: 0 -> 1 over 0.3 s at 5.95 s (overshoot), back to 0 over 0.3 s at 7.3 s"}],
                 extra={"layers": ["doll-body", "collar", "doll-head / buns, hair, head-pose-0..10 (face, eyes, pupil-k-l/r, cheeks, mouth, bangs, strands)"],
                        "approximation": "the Kotlin clips the sliding face and the bangs to the head circle every frame; here the clip is precomputed for 11 poses (hold-swapped, 0.03 s apart during the 0.3 s turn), so no masks are needed"})
  return s.doc("".join(out)), j


PIECES = {
  "set-dtoday-corridor": corridor,
  "set-dtoday-door": door,
  "set-dtoday-dust": dust,
  "set-dtoday-yard": yard,
  "set-dtoday-doll": doll,
}

COMPARE = {
  "set-dtoday-corridor": {"time": 114.0, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C, "state": {"lt": 0.3},
                          "over": [(door, {"lt": 0.3}, DOOR_C)], "label": "corridor + door, dToday 0.3 s"},
  "set-dtoday-door": {"time": 117.75, "crop": (180, 420, 900, 1420), "origin": DOOR_C, "state": {"lt": 4.05},
                      "under": [(corridor, {"lt": 4.05}, SCREEN_C)], "label": "light/impact at dToday 4.05 s (HODOR)"},
  "set-dtoday-dust": {"time": 116.5, "crop": (180, 420, 900, 1500), "origin": (540, 900), "state": {"lt": 2.85},
                      "under": [(corridor, {"lt": 2.85}, SCREEN_C), (door, {"lt": 2.85}, DOOR_C)], "label": "over corridor + door, dToday 2.85 s"},
  "set-dtoday-yard": {"time": 119.0, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C,
                      "over": [(doll, {"progress": 0.0}, (540, 640))], "label": "yard + doll, back turned"},
  "set-dtoday-doll": {"time": 120.2, "crop": (340, 400, 740, 1120), "origin": (540, 640), "state": {"progress": 1.0, "time": 1.0},
                      "under": [(yard, {"progress": 1.0}, SCREEN_C)], "label": "facing, red light"},
}
