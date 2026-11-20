"""2010s scenery: the burning room (room, back/front fire, heart mug, hat-tip flame), the club backdrop and lasso, the glint."""
import math
from kit import *

FULL_VB = (-600, -1272, 1200, 2544)
SCREEN_C = (540, 1212)
FLICKER = 2 * PI * 2 / 11


def _pkeys(fn, n=20):
  return sample(fn, 1.0, n)


# ------------------------------------------------------------------------------------------------ room

def room(state=None):
  p = (state or {}).get("progress", 0.05)
  s = Svg(FULL_VB, "2010s burning room")
  hw, hh, w, h, deck = 600, 1272, W, H, DECK
  out = [rect(-hw, -hh, hw, deck - 110, s.vgrad(-h / 2, deck, [0xFFF3DDAE, 0xFFE2BC80]))]
  paper = []
  sx = -hw
  while sx < hw:
    paper.append(rect(sx, -hh, sx + 30, deck - 110, fill(0xFFC99A5C, 0.13)))
    dy = -hh + 60
    while dy < deck - 140:
      paper.append(circle(sx + 51, dy, 5, fill(0xFFC99A5C, 0.18)))
      dy += 120
    sx += 72
  out.append(g("wallpaper", paper))
  pic = [rect(-118, -92, 118, 92, fill(BLACK, 0.12)), rect(-114, -96, 114, 86, fill(0xFF6B4226)),
         rect(-96, -78, 96, 68, fill(0xFFF6EBD5)), rect(-80, -62, 80, 52, fill(0xFF9BD3E8)), circle(36, -26, 18, fill(0xFFFFD166)),
         path("M-80 52Q-30 -10 20 30Q50 0 80 20L80 52Z", fill(0xFF7FB069))]
  RP = 2 * PI / 3
  wob = lambda t: 2 * math.sin(3 * t)
  out.append(g("picture", g("picture-wobble", pic, rot=wob(0) * p), x=-290, y=-430, rot=-4))
  wains = [rect(-hw, deck - 110, hw, deck, s.vgrad(deck - 110, deck, [0xFFB0703F, 0xFF8E5530])), rect(-hw, deck - 116, hw, deck - 98, fill(0xFF7E4A2A))]
  px = -hw + 30
  while px < hw:
    wains.append(rect(px, deck - 84, px + 150, deck - 18, stroke(0xFF7A4526, 3, 0.5)))
    px += 180
  out.append(g("wainscot", wains))
  floor = [rect(-hw, deck, hw, hh, s.vgrad(deck, h / 2, [0xFF8A5A38, 0xFF5E3B23]))]
  fy, gap = deck + 40, 40
  while fy < hh:
    floor.append(line(-hw, fy, hw, fy, stroke(0xFF4E301C, 3, 0.45)))
    gap *= 1.35
    fy += gap
  out.append(g("floor", floor))
  out.append(g("side-table", [rect(230, deck - 160, 410, deck - 144, fill(0xFF6E4426)),
                              line(250, deck - 144, 250, deck + 10, stroke(0xFF5A361E, 10)), line(390, deck - 144, 390, deck + 10, stroke(0xFF5A361E, 10)),
                              path(poly([(290, deck - 160), (282, deck - 224), (358, deck - 224), (350, deck - 160)]), fill(0xFFC8643C))]))
  leaves, tracks = [], []
  for l in range(5):
    gid = "leaf-%d" % l
    rot = lambda p, l=l: -60 + l * 30 + p * (-40 if l < 2 else 40)
    col = mix(0xFF5FA85A, 0xFF8A8A40, p)
    leaves.append(g(gid, '<ellipse id="%s-shape" cx="0" cy="-43" rx="12" ry="43" %s/>' % (gid, fill(col)), x=320, y=deck - 226, rot=rot(p)))
    tracks += [track(gid, "rotation", [[0, rot(0)], [1, rot(1)]]), track(gid + "-shape", "fillColor", [[0, "#5FA85A"], [1, "#8A8A40"]])]
  out.append(g("plant", leaves))
  HS = 80 + h * 0.55
  smoke_s = lambda p: (80 + h * 0.55 * p) / HS
  out.append(g("smoke", rect(-hw, 0, hw, HS, s.vgrad(0, HS, [0xF0221A18, 0x00221A18])), y=-hh, sy=smoke_s(p), opacity=1 if p > 0 else 0))
  out.append(g("heat-tint", rect(-hw, -hh, hw, hh, fill(0xFFFF7A2A)), opacity=0.14 * p))
  tracks += [track("smoke", "scaleY", _pkeys(smoke_s, 4)), track("smoke", "opacity", [[0, 0], [0.01, 1]]),
             track("heat-tint", "opacity", [[0, 0], [1, 0.14]])]
  clips = [clip("heat", 1.0, tracks, loop=False, driver="input:progress",
                note="smoke banks down from the ceiling, the room warms orange and the plant wilts (leaves droop 40 deg, green -> olive)"),
           clip("picture-rattle", RP, [track("picture-wobble", "rotation", sample(wob, RP, 16))], mix_by="progress",
                note="the crooked picture rattles +/-2 deg (sin 3t), weighted by progress")]
  j = piece_json("set-d10s-room", "d10s", "paintRoom", SCREEN_C, FULL_VB, "progress 0.05 (scene start)", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1],
                          "drives": "how far the fire has taken hold; shared with the two fire pieces. Scene: 0.05 -> 0.5 (58 %) -> 0.8 (64 %) -> 0.72 -> 1 over 3.1 s"}],
                 extra={"layers": ["wallpaper", "picture/picture-wobble", "wainscot", "floor", "side-table", "plant/leaf-0..4", "smoke", "heat-tint"]})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ fire

def fire(front, state=None):
  p = (state or {}).get("progress", 1.0)
  name = "set-d10s-fire-front" if front else "set-d10s-fire-back"
  s = Svg(FULL_VB, "Fire " + ("front" if front else "back"))
  hw, hh, w, deck = W / 2, H / 2, W, DECK
  nodes, flick, grow_t = [], [], []

  def add(gid, x, base, fh, wfn, gfn, seed, ybase=None):
    full_w = wfn(1.0)
    gr = gfn(p)
    sxf = lambda q: wfn(gfn(q)) / full_w
    syf = lambda q: max(0.001, gfn(q))
    node, tr = flame_node(s, gid, x, base if ybase is None else ybase(p), fh, full_w, seed, FLICKER, sx=sxf(p), sy=syf(p))
    nodes.append(node)
    flick.extend(tr)
    grow_t.extend([track(gid, "scaleX", _pkeys(sxf)), track(gid, "scaleY", _pkeys(syf))])
    if ybase:
      grow_t.append(track(gid, "y", _pkeys(ybase, 4)))

  if not front:
    for i in range(12):
      fx = -hw + 40 + (w - 80) * i / 11 + (mhash(i + 3) - 0.5) * 40
      fh = 150 + 200 * mhash(i)
      h90 = mhash(i + 90)
      add("flame-floor-%d" % i, fx, deck + 6, fh, lambda gr, fh=fh: fh * gr * 0.55 + 24, lambda q, h90=h90: m01(q * 1.7 - h90 * 0.7), i)
    for side in (-1, 1):
      for jn in range(4):
        fh = 120 + 80 * mhash(jn + side * 7 + 30)
        add("flame-wall-%s-%d" % ("l" if side < 0 else "r", jn), side * (hw - 50 - 26 * jn), None, fh,
            lambda gr, fh=fh: fh * gr * 0.55 + 20, lambda q, jn=jn: m01(q * 1.6 - jn * 0.25), jn + 40 + side * 5,
            ybase=lambda q, jn=jn: deck - 110 - jn * 210 * q)
  else:
    for i in range(9):
      u = i / 8
      corner = abs(u - 0.5) * 2
      if corner < 0.45:
        continue
      fh = (240 + 260 * mhash(i + 40)) * corner
      add("flame-front-%d" % i, -hw + w * u, hh + 30, fh, lambda gr, fh=fh: fh * gr * 0.5 + 40,
          lambda q, corner=corner: m01(q * 1.5 - (1 - corner) * 0.5), i + 20)
  clips = [clip("flicker", FLICKER, flick, note="each flame's height flickers (0.86 + 0.1 sin 11t + 0.06 sin 23t) and its tip sways (sin 6.5t), as rotation about the base; frequencies rounded to fit one 1.14 s loop"),
           clip("spread", 1.0, grow_t, loop=False, driver="input:progress",
                note="flames grow from nothing as the fire spreads" + ("" if front else "; the wall flames also climb the walls"))]
  layers = ["flame-front-{0,1,2,6,7,8}"] if front else ["flame-floor-0..11", "flame-wall-l-0..3", "flame-wall-r-0..3"]
  j = piece_json(name, "d10s", "paintFire(front=%s)" % ("true" if front else "false"), SCREEN_C, FULL_VB, "progress 1 (fully ablaze), flicker at time 0",
                 clips, inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "fire spread, the same curve as set-d10s-room's progress"}],
                 extra={"layers": [l + " (each: placement/growth) / -flicker" for l in layers],
                        "draw_layer": "in front of Pip (actor layer 3)" if front else "behind Pip (actor layer -2), over the room"})
  return s.doc(g("fire", nodes)), j


def fire_back(state=None):
  return fire(False, state)


def fire_front(state=None):
  return fire(True, state)


# ------------------------------------------------------------------------------------------------ heart mug

def mug(state=None):
  vb = (-60, -150, 140, 170)
  s = Svg(vb, "Heart mug")
  out = [oval(-34, -50, 2, -4, stroke(0xFFE6DDD3, 10)),
         rect(-4, -64, 60, 10, s.hgrad(-4, 60, [WHITE, 0xFFF1E9E0, 0xFFD5C9BF]), rx=13),
         oval(-4, -72, 60, -56, fill(0xFFE8DED4)), oval(2, -69, 54, -59, fill(0xFF5A3524)),
         path("M28 -16C10 -28 14 -44 28 -36C42 -44 46 -28 28 -16Z", fill(HAT))]
  body = g("mug-body", out)
  SP = 1 / 0.55
  steam, tracks = [], []
  for k in range(3):
    ph = k / 3
    gid = "steam-%d" % k
    sx = 12 + 16 * k
    op = lambda t, ph=ph: math.sin(fract(t / SP + ph) * PI) * 0.55
    y0 = -80 - 50 * ph
    steam.append(g(gid, path("M0 0C-12 -18 12 -30 0 -50", stroke(WHITE, 5)), x=sx, y=y0, opacity=op(0)))
    tracks += [track(gid, "y", saw_keys(-80, -130, SP, ph)), track(gid, "opacity", sample(op, SP, 18))]
  c = clip("steam", SP, tracks, note="three wisps rise 50 px and fade in and out, a third of a cycle apart")
  j = piece_json("set-d10s-mug", "d10s", "paintMug", (701, 1272), vb, "time 0, origin at the fist that holds it", [c],
                 extra={"layers": ["mug-body", "steam/steam-0..2"], "scene_motion": "rises to the lips (470, 500 box units, -26 deg) for a sip at 0.55 s; trembles from 2.3 s"})
  return s.doc(body + g("steam", steam)), j


def tip_flame(state=None):
  vb = (-100, -140, 200, 180)
  s = Svg(vb, "Hat tip flame")
  node, tr = flame_node(s, "tip-flame", 0, 12, 82, 44, 77, FLICKER, glow=False)
  body = s.glow(0, -30, 90, 0xFFFF8A2A, 0.5) + node
  j = piece_json("set-d10s-tip-flame", "d10s", "paintTipFlame", None and (0, 0) or (None, None), vb, "time 0",
                 [clip("flicker", FLICKER, tr, note="same flame flicker as the room fire")],
                 extra={"anchor": "anchorToPip at (-138, 98): the tip of Pip's hat; pops in at 2.6 s, grows to 1.5x at 2.9 s",
                        "layers": ["tip-flame/tip-flame-flicker"]})
  j["origin"] = "pip-anchored (-138, 98)"
  return s.doc(body), j


# ------------------------------------------------------------------------------------------------ club

BOKEH = [0xFFFF4FA3, 0xFF3FE0FF, 0xFFFFE14F]


def party(state=None):
  s = Svg(FULL_VB, "Club backdrop")
  hw, hh, w, h, deck = 600, 1272, W, H, DECK
  pulse = lambda t: (1 - fract(t * 2)) ** 3
  out = [rect(-hw, -hh, hw, hh, s.rad(0, -200, h * 0.75, [(0, 0xFF8A33B0), (0.5, 0xFF3A1260), (1, 0xFF14061F)]))]
  wedges = []
  for i in range(0, 18, 2):
    a1, a2 = math.radians(i * 20), math.radians((i + 1) * 20)
    wedges.append(path(poly([(0, 0), (math.cos(a1) * h, math.sin(a1) * h), (math.cos(a2) * h, math.sin(a2) * h)]), fill(WHITE, 0.09)))
  sun_op = lambda t: (0.05 + 0.04 * pulse(t)) / 0.09
  out.append(g("sunburst-beat", g("sunburst", wedges), y=-200, opacity=sun_op(0)))
  dots, tw = [], []
  TP = PI
  for i in range(22):
    bx = -w / 2 + w * mhash(i + 300)
    by = -h / 2 + (deck + h / 2 - 100) * mhash(i + 330)
    r = 10 + 26 * mhash(i + 360)
    op = lambda t, i=i: (0.12 + 0.18 * (0.5 + 0.5 * math.sin(2 * t + i))) / 0.30
    gid = "bokeh-%d" % i
    dots.append(g(gid, circle(0, 0, r, fill(BOKEH[i % 3], 0.30)), x=bx, y=by, opacity=op(0)))
    tw.append(track(gid, "opacity", sample(op, TP, 12)))
  bk_op = lambda t: 0.7 + 0.3 * pulse(t)
  out.append(g("bokeh", dots, opacity=bk_op(0)))
  spots, sw = [], []
  SPP = 2 * PI / 1.4
  for i in range(3):
    ox = (-0.36 + 0.36 * i) * w
    rot = lambda t, i=i: math.sin(1.4 * t + i * 2.1) * 26
    paint = s.lin(0, 0, 0, h * 0.85, [(0, BOKEH[i], 0.42), (1, BOKEH[i], 0)])
    gid = "spotlight-%d" % i
    spots.append(g(gid, path(poly([(-18, 0), (18, 0), (250, h * 0.85), (-250, h * 0.85)]), paint), x=ox, y=-h / 2, rot=rot(0)))
    sw.append(track(gid, "rotation", sample(rot, SPP, 16)))
  sp_op = lambda t: 0.8 + 0.2 * pulse(t)
  out.append(g("spotlights", spots, opacity=sp_op(0)))
  out.append(g("dance-floor", rect(-hw, deck, hw, hh, s.vgrad(deck, h / 2, [0xFF2A0B3D, 0xFF12051C]))))
  fl_op = lambda t: 0.5 + 0.4 * pulse(t)
  out.append(g("floor-edge", line(-hw, deck, hw, deck, stroke(0xFFFF7BC4, 4)), opacity=fl_op(0)))
  gl_op = lambda t: 0.35 + 0.4 * pulse(t)
  out.append(g("floor-glow", s.glow(0, 0, 430, 0xFFFF4FA3, 1.0), y=deck + 8, sy=0.22, opacity=gl_op(0)))
  beat = [track("sunburst-beat", "opacity", sample(sun_op, 0.5, 10)), track("bokeh", "opacity", sample(bk_op, 0.5, 10)),
          track("spotlights", "opacity", sample(sp_op, 0.5, 10)), track("floor-edge", "opacity", sample(fl_op, 0.5, 10)),
          track("floor-glow", "opacity", sample(gl_op, 0.5, 10))]
  clips = [clip("beat", 0.5, beat, note="120 BPM thump: pulse = (1 - beat)^3, starting on the scene's first beat"),
           clip("sunburst-spin", 360 / 14.0, [track("sunburst", "rotation", [[0, 0], [r4(360 / 14.0), 360]])], note="14 deg/s"),
           clip("bokeh-twinkle", TP, tw, note="each dot breathes on sin(2t + i)"),
           clip("spotlight-swing", SPP, sw, note="three beams swing +/-26 deg on sin(1.4t + 2.1 i)")]
  j = piece_json("set-d10s-party", "d10s", "paintParty", SCREEN_C, FULL_VB, "time 0 (on the beat)", clips,
                 extra={"layers": ["sunburst-beat/sunburst", "bokeh/bokeh-0..21", "spotlights/spotlight-0..2", "dance-floor", "floor-edge", "floor-glow"],
                        "scene_motion": "whip-pans in from the right at 3.06 s; fades out at 6.0 s"})
  return s.doc("".join(out)), j


def lasso(state=None):
  p0 = (state or {}).get("progress", 0.0)
  vb = (-140, -150, 380, 180)
  s = Svg(vb, "Lasso")
  cx, cy, rx, ry = 60, -78, 160, 34
  out = [oval(cx - rx, cy - ry, cx + rx, cy + ry, stroke(0xFF8C6236, 10)), oval(cx - rx, cy - ry, cx + rx, cy + ry, stroke(0xFFD8AA6A, 5))]
  loop = g("lasso-loop", out)
  tracks = []
  smear = []
  for k in range(1, 4):
    pos = lambda p, k=k: (cx + rx * math.cos(2 * PI * p + 0.5 - k * 0.35), cy + ry * math.sin(2 * PI * p + 0.5 - k * 0.35))
    gid = "smear-%d" % k
    x, y = pos(p0)
    smear.append(g(gid, circle(0, 0, 5, fill(0xFFD8AA6A, 0.4 / k)), x=x, y=y))
    tracks += [track(gid, "x", sample(lambda p, f=pos: f(p)[0], 1, 24)), track(gid, "y", sample(lambda p, f=pos: f(p)[1], 1, 24))]
  RL = 200.0

  def rope(p):
    th = 2 * PI * p
    hx, hy = 72 + 33 * math.cos(th), -33 + 10 * math.sin(th)
    lx, ly = cx + rx * math.cos(th + 0.5), cy + ry * math.sin(th + 0.5)
    ang = math.degrees(math.atan2(ly - hy, lx - hx))
    return hx, hy, ang, math.hypot(lx - hx, ly - hy) / RL

  hx, hy, ang, sc = rope(p0)
  rd = "M0 0Q100 18 200 0"
  rope_g = g("lasso-rope", [path(rd, stroke(0xFF8C6236, 9)), path(rd, stroke(0xFFD8AA6A, 4))], x=hx, y=hy, rot=ang, sx=sc)
  # Unwrap the angle so it never jumps 360 between keys.
  angs, prev = [], None
  for i in range(25):
    a = rope(i / 24)[2]
    if prev is not None:
      while a - prev > 180:
        a -= 360
      while a - prev < -180:
        a += 360
    angs.append([r4(i / 24), r4(a)])
    prev = a
  tracks += [track("lasso-rope", "x", sample(lambda p: rope(p)[0], 1, 24)), track("lasso-rope", "y", sample(lambda p: rope(p)[1], 1, 24)),
             track("lasso-rope", "rotation", angs), track("lasso-rope", "scaleX", sample(lambda p: rope(p)[3], 1, 24))]
  c = clip("spin", 1.0, tracks, driver="input:progress",
           note="progress = turns; the scene runs one turn per beat (0.5 s), so this can also loop on the clock at 0.5 s. The rope runs from Pip's circling fist to the loop: a node at the fist, aimed and stretched at the loop point.")
  j = piece_json("set-d10s-lasso", "d10s", "paintLasso", (None, None), vb, "progress 0", [c],
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "the loop's turn (and the fist's matching circle)"}],
                 extra={"anchor": "anchorToPip at (0, 0)", "layers": ["lasso-loop", "smear/smear-1..3", "lasso-rope"],
                        "approximation": "the rope's quad sag (18 px down in screen space) is drawn in the rope's own frame, so it tilts with the rope"})
  j["origin"] = "pip-anchored (0, 0)"
  return s.doc(loop + g("smear", smear) + rope_g), j


def _star_unit(r, color, a=1.0):
  return path(star_d(0, 0, r, 0.16), fill(color, a))


def glint(state=None):
  vb = (-110, -110, 220, 220)
  s = Svg(vb, "Deal-with-it glint")
  L = 2 * PI / 1.7
  tw = lambda t: 0.82 + 0.18 * math.sin(2 * PI * 4 * t / L)
  rot = lambda t: math.sin(1.7 * t) * 10
  s1 = lambda t: max(0.001, math.sin(2 * PI * 3 * t / L + 2.1))
  s2 = lambda t: max(0.001, math.sin(2 * PI * 3 * t / L + 4.0))
  out = [g("glint-glow", circle(0, 0, 100, s.rad(0, 0, 100, [(0, WHITE, 0.533), (1, WHITE, 0)])), sx=tw(0), sy=tw(0)),
         g("glint-star", [_star_unit(64, 0xFFFFD447), _star_unit(30, WHITE)], rot=rot(0), sx=tw(0), sy=tw(0)),
         g("glint-sat-1", _star_unit(20, 0xFFFFD447), x=62, y=-54, sx=s1(0), sy=s1(0)),
         g("glint-sat-2", _star_unit(15, 0xFFFFD447), x=-56, y=48, sx=s2(0), sy=s2(0))]
  n = 48
  tracks = [track("glint-glow", "scaleX", sample(tw, L, n)), track("glint-glow", "scaleY", sample(tw, L, n)),
            track("glint-star", "scaleX", sample(tw, L, n)), track("glint-star", "scaleY", sample(tw, L, n)),
            track("glint-star", "rotation", sample(rot, L, n)),
            track("glint-sat-1", "scaleX", sample(s1, L, n)), track("glint-sat-1", "scaleY", sample(s1, L, n)),
            track("glint-sat-2", "scaleX", sample(s2, L, n)), track("glint-sat-2", "scaleY", sample(s2, L, n))]
  c = clip("twinkle", L, tracks, note="breathing (0.82 + 0.18 sin), a +/-10 deg rock, two satellites blinking; frequencies rounded to whole cycles of one 3.7 s loop")
  j = piece_json("set-d10s-glint", "d10s", "ActorFx.sparkle (gold)", (None, None), vb, "time 0", [c],
                 extra={"anchor": "SPARKLE actor anchored to Pip at (100, 200), popping 0 -> 0.55 -> 0 scale over 0.55 s from 7.52 s",
                        "layers": ["glint-glow", "glint-star", "glint-sat-1", "glint-sat-2"],
                        "note": "this is the shared SPARKLE effect (ActorDrawFx.sparkle) as used for the 'Deal with it' beat; if a shared sparkle piece exists, prefer one copy"})
  j["origin"] = "pip-anchored (100, 200)"
  return s.doc("".join(out)), j


PIECES = {
  "set-d10s-room": room,
  "set-d10s-fire-back": fire_back,
  "set-d10s-fire-front": fire_front,
  "set-d10s-mug": mug,
  "set-d10s-tip-flame": tip_flame,
  "set-d10s-party": party,
  "set-d10s-lasso": lasso,
  "set-d10s-glint": glint,
}

COMPARE = {
  "set-d10s-room": {"time": 105.2, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C, "state": {"progress": 0.1}},
  "set-d10s-fire-back": {"time": 107.5, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C, "state": {"progress": 0.85},
                         "under": [(room, {"progress": 0.85}, SCREEN_C)], "over": [(fire_front, {"progress": 0.85}, SCREEN_C)],
                         "label": "room + back + front at progress 0.85"},
  "set-d10s-fire-front": {"time": 107.5, "crop": (0, 1500, 1080, 2424), "origin": SCREEN_C, "state": {"progress": 0.85}, "bg": "checker"},
  "set-d10s-mug": {"time": 105.2, "crop": (600, 1120, 820, 1320), "origin": (701, 1272), "bg": "checker"},
  "set-d10s-tip-flame": {"time": 107.5, "crop": (280, 940, 520, 1180), "origin": (390, 1100), "bg": "checker"},
  "set-d10s-party": {"time": 108.8, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C},
  "set-d10s-lasso": {"time": 108.8, "crop": (320, 760, 840, 1000), "origin": (500, 930), "bg": (60, 20, 90, 255)},
  "set-d10s-glint": {"time": 112.45, "crop": (560, 1112, 720, 1272), "origin": (640, 1192), "scale": 0.36, "bg": (16, 16, 22, 255),
                     "label": "rendered at the 0.36 pop scale"},
}
