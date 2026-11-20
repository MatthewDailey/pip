"""2000s scenery: cavern + bridge, wizard hat, staff, burst, dusk field + cave, LEEROY lettering, dust trail, brawl, signal card."""
import math
from kit import *

FULL_VB = (-600, -1272, 1200, 2544)
SCREEN_C = (540, 1212)
charge = cubic_bezier(0.5, 0, 0.8, 0.6)


# ------------------------------------------------------------------------------------------------ cavern

def cavern(state=None):
  s = Svg(FULL_VB, "2000s cavern")
  hw, hh, w, h, deck = 600, 1272, W, H, DECK
  out = []
  out.append(rect(-hw, -hh, hw, hh, s.vgrad(-h / 2, h / 2, [0xFF0B0910, 0xFF191320, 0xFF2B1411, 0xFF6A250C], [0, 0.42, 0.66, 1])))
  pillars = []
  for i in range(5):
    px = -w / 2 + w * (0.1 + 0.2 * i) + (mhash(i) - 0.5) * 60
    pw = 56 + 40 * mhash(i + 7)
    pillars.append(rect(px - pw / 2, -hh, px + pw / 2, deck + 500, fill(0xFF231A26, 0.5 + 0.3 * mhash(i + 3))))
    pillars.append(rect(px - pw / 2 - 10, deck - 760 - 200 * mhash(i + 2), px + pw / 2 + 10, deck - 730 - 200 * mhash(i + 2), fill(0xFF2A202D, 0.6)))
  out.append(g("pillars", pillars))
  # Abyss glow: one flickering group.
  period = 2 * PI * 2 / 5.3
  c2 = round(11.7 * period / (2 * PI))
  fl = lambda t: 0.85 + 0.1 * math.sin(5.3 * t) + 0.05 * math.sin(2 * PI * c2 * t / period)
  abyss = [s.glow(0, deck + 560, w * 0.95, 0xFFFF6A1A, 0.6), s.glow(-w * 0.22, deck + 800, w * 0.55, 0xFFFFB347, 0.4),
           s.glow(w * 0.3, deck + 720, w * 0.45, 0xFFFF3D12, 0.35)]
  out.append(g("abyss-glow", abyss, opacity=fl(0)))
  # Cliffs.
  cliffs = []
  for side in (-1, 1):
    edge = side * (hw + 10)
    pts = [(edge, -h * 0.18)]
    for k in range(13):
      y = lerp(-h * 0.18, hh, k / 12)
      ledge = 70 if deck - 30 < y < deck + 120 else 0
      pts.append((side * (w / 2 - 50 - 70 * mhash(k * 3 + side + 20) - ledge), y))
    pts.append((edge, hh))
    cliffs.append(path(poly(pts), fill(0xFF120D12)))
  out.append(g("cliffs", cliffs))
  # Embers: 28 sparks rising on a 20 s loop.
  embers, tracks = [], []
  L = 20.0
  for i in range(28):
    sp = 0.07 + 0.08 * mhash(i + 50)
    k = max(1, round(L * sp))
    ph = mhash(i + 9)
    x0 = -w / 2 + w * mhash(i)
    ampl = 0.5 + 0.5 * mhash(i + 77)
    r = 2.5 + 3.5 * mhash(i + 31)
    life = lambda t, k=k, ph=ph: fract(t * k / L + ph)
    ey0 = h / 2 - life(0) * h * 0.95
    xw = lambda t, i=i, x0=x0: x0 + math.sin(2 * PI * 5 * t / L + i) * 18
    op = lambda t, life=life, ampl=ampl: math.sin(life(t) * PI) * ampl
    gid = "ember-%d" % i
    embers.append(g(gid, [s.glow(0, 0, r * 5, 0xFFFF8A2A, 0.55), circle(0, 0, r, fill(0xFFFFD27A))], x=xw(0), y=ey0, opacity=op(0)))
    tracks.append(track(gid, "y", saw_keys(h / 2, h / 2 - h * 0.95, L / k, ph, k)))
    tracks.append(track(gid, "x", sample(xw, L, 40)))
    tracks.append(track(gid, "opacity", sample(op, L, 24 * k)))
  out.append(g("embers", embers))
  # Bridge.
  th = 44
  bridge = [path("M%s %sL%s %sL%s %sQ0 %s %s %sZ" % (f(-hw), f(deck), f(hw), f(deck), f(hw), f(deck + th + 80), f(deck + th - 80), f(-hw), f(deck + th + 80)),
                 s.vgrad(deck, deck + th + 80, [0xFF8E8996, 0xFF5A5564, 0xFF2E2A33], [0, 0.35, 1]))]
  bx, k = -hw + 20, 0
  while bx < hw:
    u = bx / hw
    under = deck + th + 80 * u * u
    bridge.append(line(bx, deck + 4, bx + 3, under - 4, stroke(0xFF2F2B36, 3, 0.7)))
    if k % 2 == 0:
      bridge.append(line(bx + 6, deck + 22, bx + 76, deck + 22, stroke(0xFF2F2B36, 2, 0.4)))
    bx += 72 + 22 * mhash(k + 40)
    k += 1
  bridge.append(line(-hw, deck + 2, hw, deck + 2, stroke(0xFFC9C4D2, 4, 0.7)))
  out.append(g("bridge", bridge))
  under = path("M%s %sQ0 %s %s %s" % (f(hw), f(deck + th + 80), f(deck + th - 80), f(-hw), f(deck + th + 80)), stroke(0xFFFF8A3D, 7, 0.55))
  out.append(g("bridge-underglow", under, opacity=fl(0)))
  flick = clip("flicker", period, [track("abyss-glow", "opacity", sample(fl, period, 24)), track("bridge-underglow", "opacity", sample(fl, period, 24))],
               note="fire light from the abyss: 0.85 + 0.1 sin(5.3t) + 0.05 sin(~11.7t)")
  emb = clip("embers", L, tracks, note="28 embers rising out of the abyss; speeds rounded so each completes whole cycles in 20 s")
  j = piece_json("set-d00s-cavern", "d00s", "paintCavern", SCREEN_C, FULL_VB, "rest (time 0)", [flick, emb],
                 extra={"layers": ["pillars", "abyss-glow", "cliffs", "embers/ember-0..27", "bridge", "bridge-underglow"],
                        "scene_motion": "alpha 0 -> 1 over 0.4 s; whip pan slides it to x = -756 at 3.04-3.36 s; camera shake at the slam (1.8 s)"})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ wizard hat

def wizard_hat(state=None):
  vb = (-200, -320, 400, 380)
  s = Svg(vb, "Wizard hat")
  out = [oval(-168, -4, 168, 40, fill(BLACK, 0.16)), oval(-182, -22, 182, 30, fill(0xFF5C6169)),
         oval(-182, -30, 182, 18, s.hgrad(-182, 182, [0xFFB6BAC1, 0xFF969BA3, 0xFF6F747C], [0, 0.5, 1]))]
  cone_paint = s.hgrad(-110, 110, [0xFFC2C6CD, 0xFF9DA2AA, 0xFF6C717A], [0, 0.45, 1])
  base = "M-108 -6C-86 -90 -40 -190 8 -262L44 -204C58 -124 88 -62 110 -6Q0 12 -108 -6Z"
  out.append(g("hat-cone", [path(base, cone_paint), path("M-24 -34Q-12 -150 16 -236", stroke(0xFF5E636B, 4, 0.35))]))
  # The bent tip is its own node so it can trail (pivot where the cone bends).
  px, py = 26, -233
  tip = "M%s %sQ%s %s %s %sQ%s %s %s %sL%s %sL%s %sZ" % (
    f(8 - px), f(-262 - py), f(40 - px), f(-306 - py), f(88 - px), f(-292 - py), f(52 - px), f(-266 - py), f(44 - px), f(-204 - py),
    f(28 - px), f(-200 - py), f(6 - px), f(-250 - py))
  cone_tip = s.hgrad(-110 - px, 110 - px, [0xFFC2C6CD, 0xFF9DA2AA, 0xFF6C717A], [0, 0.45, 1])
  wob = lambda t: math.sin(3.3 * t) * 3.2
  out.append(g("hat-tip", path(tip, cone_tip), x=px, y=py, rot=wob(0)))
  out.append(g("hat-band", [path("M-104 -12Q0 8 106 -12L99 -44Q0 -26 -97 -44Z", fill(0xFF4B4656)),
                            path("M-97 -44Q0 -26 99 -44", stroke(0xFF8A8597, 3, 0.6))]))
  period = 2 * PI / 3.3
  c = clip("tip-sway", period, [track("hat-tip", "rotation", sample(wob, period, 16))],
           note="tip wobble sin(3.3t) * 5 px at the tip ~ +/-3.2 deg about the bend; the Kotlin also adds 1.5 x the hat's own rotation")
  j = piece_json("set-d00s-wizard-hat", "d00s", "paintWizardHat", (None, None), vb, "rest, origin at the brim centre", [c],
                 inputs=[{"name": "lean", "type": "number", "drives": "optional: hat-tip rotation += lean (deg) * 0.9 to echo the actor's rotation (Kotlin: wob += rotation * 1.5 px)"}],
                 extra={"anchor": "anchorToPip at (-14, 60); drops in at 0.4 s and sways with Pip", "layers": ["hat-cone", "hat-tip", "hat-band"]})
  j["origin"] = "pip-anchored (-14, 60)"
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ staff

def staff(state=None):
  st = state or {}
  gp = st.get("progress", 0.3)
  vb = (-200, -560, 400, 700)
  s = Svg(vb, "Wizard staff")
  out = []
  wood = "M-8 117C-13 20 -3 -150 -10 -300L8 -300C13 -150 4 20 8 117Z"
  out.append(g("staff-wood", [path(wood, s.hgrad(-12, 12, [0xFFA87648, 0xFF7A4E2C, 0xFF573519])),
                              oval(-9, -92, 3, -70, fill(0xFF4A2D16, 0.7)), oval(-2, -222, 8, -204, fill(0xFF4A2D16, 0.7))]))
  crook = "M0 -298C-40 -330 -36 -404 10 -404Q46 -400 40 -362"
  out.append(g("staff-crook", [path(crook, stroke(0xFF6B4324, 17)), path(crook, stroke(0xFFB4834F, 5, 0.6))]))
  RMAX = 180.0
  gl_scale = lambda p: (60 + 120 * p) / RMAX
  gl_op = lambda p: (0.35 + 0.6 * p) / 0.95
  pulse = lambda t: 0.85 + 0.15 * math.sin(9 * t)
  inner = g("crystal-glow", s.glow(0, 0, RMAX, 0xFFBFE8FF, 0.95), sx=gl_scale(gp), sy=gl_scale(gp), opacity=gl_op(gp))
  out.append(g("crystal-pulse", inner, x=6, y=-360, opacity=pulse(0)))
  out.append(g("crystal", path("M6 -392L22 -362L6 -334L-10 -362Z", s.vgrad(-392, -334, [WHITE, 0xFFA8DCFF, 0xFF5AA7E6]))))
  out.append(g("fist", [circle(0, 0, 21, fill(SKIN)), circle(0, 0, 21, stroke(SKIN_LINE, 3.5)),
                        line(-9, -8, -9, 6, stroke(SKIN_LINE, 2.5)), line(1, -10, 1, 6, stroke(SKIN_LINE, 2.5))]))
  pp = 2 * PI / 9
  clips = [clip("crystal-pulse", pp, [track("crystal-pulse", "opacity", sample(pulse, pp, 12))], note="0.85 + 0.15 sin(9t)"),
           clip("charge", 1.0, [track("crystal-glow", "scaleX", sample(gl_scale, 1, 4)), track("crystal-glow", "scaleY", sample(gl_scale, 1, 4)),
                                track("crystal-glow", "opacity", sample(gl_op, 1, 4))], loop=False, driver="input:progress")]
  j = piece_json("set-d00s-staff", "d00s", "paintStaff", (729, 1233), vb, "progress 0.3 (in hand)", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1],
                          "drives": "crystal glow radius 60 + 120 p and alpha 0.35 + 0.6 p (0.2 spinning in, 0.3 held, 0.7 raised, 1 at the slam, 0.45 after)"}],
                 extra={"layers": ["staff-wood", "staff-crook", "crystal-pulse/crystal-glow", "crystal", "fist"],
                        "scene_motion": "spins in from 0.1x / -620 deg at 0.86 s; rises 150 px at 1.36 s; slams at 1.8 s"})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ burst

def burst(state=None):
  p = (state or {}).get("progress", 0.12)
  vb = (-1400, -1400, 2800, 2800)
  s = Svg(vb, "Staff burst")
  e = lambda p: 1 - (1 - p) ** 3
  fade = lambda p: 1 - p
  out = []
  flash_op = lambda p: 0.5 * fade(p) ** 3
  out.append(g("flash", rect(-1400, -1400, 1400, 1400, fill(0xFFD8ECFF)), opacity=flash_op(p)))
  GR = 1060.0
  gscale = lambda p: (160 + 900 * e(p)) / GR
  gop = lambda p: 0.95 * fade(p) ** 2
  out.append(g("flash-glow", s.glow(0, 0, GR, 0xFFE6F3FF, 1.0), y=-30, sx=gscale(p), sy=gscale(p), opacity=gop(p)))
  rays, tracks = [], []
  LMAX = 860.0
  rlen = lambda p: (140 + 720 * e(p)) / LMAX
  for i in range(15):
    ang = -170 + i * (160 / 14)
    wd = 9 + 8 * mhash(i)
    lm = LMAX * (0.55 + 0.45 * mhash(i + 3))
    gid = "ray-%d" % i
    rays.append(g(gid, path(poly([(0, wd), (lm, 0), (0, -wd)]), fill(WHITE)), rot=ang, sx=rlen(p)))
    tracks.append(track(gid, "scaleX", sample(rlen, 1, 10)))
  rop = lambda p: 0.6 * fade(p)
  out.append(g("rays", rays, rot=p * 10, opacity=rop(p)))
  ring_rx = lambda p: 60 + 820 * e(p)
  ring_w = lambda p: 3 + 14 * fade(p)
  rx = ring_rx(p)
  out.append(g("shock-ring", oval(-rx, -rx * 0.14, rx, rx * 0.14, stroke(0xFFCFE6FF, ring_w(p)), ' id="shock-ring-ellipse"'), opacity=fade(p)))
  core = lambda p: max(0.001, fade(p))
  out.append(g("core", circle(0, 0, 54, fill(WHITE)), y=-10, sx=core(p), sy=core(p), opacity=fade(p)))
  tracks += [track("flash", "opacity", sample(flash_op, 1, 10)),
             track("flash-glow", "scaleX", sample(gscale, 1, 10)), track("flash-glow", "scaleY", sample(gscale, 1, 10)),
             track("flash-glow", "opacity", sample(gop, 1, 10)),
             track("rays", "rotation", [[0, 0], [1, 10]]), track("rays", "opacity", sample(rop, 1, 4)),
             track("shock-ring-ellipse", "width", sample(lambda p: 2 * ring_rx(p), 1, 10)),
             track("shock-ring-ellipse", "height", sample(lambda p: 0.28 * ring_rx(p), 1, 10)),
             track("shock-ring-ellipse", "strokeWidth", sample(ring_w, 1, 4)),
             track("shock-ring", "opacity", [[0, 1], [1, 0]]),
             track("core", "scaleX", sample(core, 1, 4)), track("core", "scaleY", sample(core, 1, 4)), track("core", "opacity", [[0, 1], [1, 0]])]
  c = clip("burst", 1.0, tracks, loop=False, driver="input:progress",
           note="e = 1 - (1 - p)^3, fade = 1 - p. The ring animates its ellipse size and stroke so the line stays crisp.")
  j = piece_json("set-d00s-burst", "d00s", "paintBurst", (729, 1344), vb, "progress 0.12", [c],
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "the whole impact; the scene runs it 0 -> 1 linearly over 0.75 s from the slam; hidden at 0 and 1"}],
                 extra={"layers": ["flash", "flash-glow", "rays/ray-0..14", "shock-ring", "core"]})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ dusk field + cave

def field(state=None):
  alarm = (state or {}).get("progress", 0.0)
  s = Svg(FULL_VB, "Dusk field and cave")
  hw, hh, w, h, deck = 600, 1272, W, H, DECK
  cx = 870 - w / 2
  out = [rect(-hw, -hh, hw, deck, s.vgrad(-h / 2, deck, [0xFF2E2350, 0xFF7A4B78, 0xFFE58B63, 0xFFF8C77E], [0, 0.38, 0.78, 1]))]
  out.append(g("sun", [s.glow(-220, deck - 300, 560, 0xFFFFD9A0, 0.75), circle(-220, deck - 300, 105, fill(0xFFFFEBC6))]))
  hills = []
  for layer in (0, 1):
    pts = [(-hw, deck)]
    for k in range(10):
      x = -hw + 2 * hw * k / 9
      y = deck - (300 if layer == 0 else 140) - (230 if layer == 0 else 150) * mhash(k + 40 + layer * 17)
      pts.append((x, y))
    pts.append((hw, deck))
    hills.append(path(poly(pts), fill(0xFF8A5A86 if layer == 0 else 0xFF5B3A63, 0.8 if layer == 0 else 1)))
  out.append(g("mountains", hills))
  ground = [rect(-hw, deck, hw, hh, s.vgrad(deck, h / 2, [0xFF7C9A45, 0xFF3F5A26])), rect(-hw, deck - 6, hw, deck + 40, fill(0xFFC9A46E)),
            line(-hw, deck - 5, hw, deck - 5, stroke(0xFFE6C690, 4))]
  for i in range(26):
    gx = -w / 2 + w * mhash(i + 200)
    gy = deck + 70 + (h / 2 - deck - 90) * mhash(i + 230)
    ground.append(path("M%s %sL%s %sM%s %sL%s %s" % (f(gx - 6), f(gy - 18), f(gx), f(gy), f(gx), f(gy), f(gx + 7), f(gy - 15)), stroke(0xFF9DBB5C, 3, 0.7)))
  out.append(g("ground", ground))
  rock = "M%s %sC%s %s %s %s %s %sC%s %s %s %s %s %sL%s %sZ" % (
    f(cx - 280), f(deck + 10), f(cx - 270), f(deck - 260), f(cx - 130), f(deck - 440), f(cx + 20), f(deck - 450),
    f(cx + 170), f(deck - 460), f(hw + 40), f(deck - 380), f(hw + 40), f(deck - 200), f(hw + 40), f(deck + 10))
  hl = "M%s %sC%s %s %s %s %s %s" % (f(cx - 262), f(deck - 140), f(cx - 230), f(deck - 330), f(cx - 100), f(deck - 430), f(cx + 20), f(deck - 438))
  mouth = "M%s %sC%s %s %s %s %s %sZ" % (f(cx - 155), f(deck + 8), f(cx - 155), f(deck - 220), f(cx + 155), f(deck - 220), f(cx + 155), f(deck + 8))
  out.append(g("cave", [path(rock, s.vgrad(deck - 460, deck, [0xFF7A6E72, 0xFF4A4045])), path(hl, stroke(0xFF9C8F90, 6, 0.6)),
                        line(cx + 160, deck - 380, cx + 120, deck - 250, stroke(0xFF3A3236, 4, 0.6)),
                        line(cx - 200, deck - 160, cx - 160, deck - 60, stroke(0xFF3A3236, 4, 0.6)),
                        path(mouth, fill(0xFF0E090C)), s.glow(cx, deck - 40, 140, 0xFF4A2340, 0.5)]))
  YEL, RED = 0xFFFFD54A, 0xFFFF4A3A
  eyes, blink, alarm_t = [], [], []
  BP = 1 / 0.6
  sc = lambda a: (6 + 5 * a) / 6
  for j in range(7):
    ex = cx + (mhash(j + 60) - 0.5) * 190
    ey = deck - 30 - 100 * mhash(j + 70)
    pair = []
    for side, dx in (("l", -10), ("r", 10)):
      gid = "eye-%d-%s" % (j, side)
      red = g(gid + "-red", oval(-3.6, -6, 3.6, 6, fill(RED)), opacity=alarm)
      pair.append(g(gid, [oval(-3.6, -6, 3.6, 6, fill(YEL)), red], x=dx, sx=sc(alarm), sy=sc(alarm)))
      alarm_t += [track(gid, "scaleX", [[0, 1], [1, sc(1)]]), track(gid, "scaleY", [[0, 1], [1, sc(1)]]), track(gid + "-red", "opacity", [[0, 0], [1, 1]])]
    gl_red = g("eye-%d-glow-red" % j, s.glow(0, 0, 30, RED, 0.35), opacity=alarm)
    alarm_t.append(track("eye-%d-glow-red" % j, "opacity", [[0, 0], [1, 1]]))
    gid = "eye-pair-%d" % j
    eyes.append(g(gid, [s.glow(0, 0, 30, YEL, 0.35), gl_red] + pair, x=ex, y=ey))
    ph = mhash(j)
    # Shut while fract(0.6 t + ph) < 0.05.
    t_close = ((1 - ph) % 1.0) * BP
    t_open = t_close + 0.05 * BP
    keys = []
    if t_open > BP:
      keys = [[0, 0], [r4(t_open - BP), 1], [r4(t_close), 0], [r4(BP), 0]]
    else:
      keys = [[0, 1], [r4(t_close), 0], [r4(t_open), 1], [r4(BP), 1]]
    blink.append(track(gid, "opacity", keys, "hold"))
  out.append(g("cave-eyes", eyes))
  clips = [clip("eyes-blink", BP, blink, note="each pair blinks shut for 5 % of a 1.67 s cycle at its own phase"),
           clip("alarm", 1.0, alarm_t, loop=False, driver="input:progress", note="eyes grow 6 -> 11 px and turn from yellow to red")]
  j = piece_json("set-d00s-field", "d00s", "paintField", SCREEN_C, FULL_VB, "progress 0 (calm cave)", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "the cave's alarm: the scene snaps it 0 -> 1 (outBack) at 4.45 s as Pip charges in"}],
                 extra={"layers": ["sun", "mountains", "ground", "cave", "cave-eyes/eye-pair-0..6"]})
  return s.doc("".join(out)), j


# ------------------------------------------------------------------------------------------------ LEEROY lettering

# Relative advances for a heavy italic sans (measured from Arial Black, the closest local match to Roboto Black Italic).
def _advances(text, size):
  try:
    from PIL import ImageFont
    font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Black.ttf", 100)
    adv = [font.getlength(ch) for ch in text]
  except Exception:
    adv = [60.0] * len(text)
  total = sum(adv)
  return [a / total for a in adv]


def leeroy(state=None):
  text = "LEEEEEROY!"
  size = 170 * 1000 / 1180.0  # paintShout shrinks the 170 px word to the 1000 px max width
  rel = _advances(text, size)
  vb = (-560, -170, 1120, 300)
  s = Svg(vb, "LEEROY lettering")
  letters, tracks = [], []
  x = -500.0
  jit = []
  for i, ch in enumerate(text):
    adv = rel[i] * 1000
    cxl = x + adv / 2
    gid = "text-leeroy-%d" % i
    rot = math.sin(i * 1.7) * 4
    attrs = ('data-text="%s" data-font="sans-serif-black bold italic" data-size="%s" data-fill="#FFC93C" data-highlight="#FFE4A0 above y=-0.05em" '
             'data-stroke="#3A2A26" data-stroke-width="%s" data-shadow="#00000066 dx=%s dy=%s" data-align="center" data-baseline="%s"') % (
      ch.replace('"', '&quot;'), f(size), f(size * 0.15), f(size * 0.04), f(size * 0.08), f(size * 0.36))
    letters.append(g(gid, g(gid + "-pop", "", attrs=attrs), x=cxl, rot=rot))
    # Reveal: letter i pops in as progress passes i / n, scaling 0.25 -> 1 with a back overshoot.
    n = len(text)
    keys = [[0, 0.001]]
    for k in range(9):
      vis = k / 8 * 1.4
      keys.append([r4(min(1.0, (i + vis) / n)), r4(0.25 + 0.75 * mback(vis / 1.4))])
    keys[1][1] = 0.25
    tracks.append(track(gid + "-pop", "scaleX", keys))
    tracks.append(track(gid + "-pop", "scaleY", keys))
    jp = 2 * PI / 43 * 4
    jit.append(track(gid, "x", sample(lambda t, i=i, c=cxl: c + 4 * math.sin(2 * PI * 4 * t / jp + i * 2.1), jp, 16)))
    jit.append(track(gid, "y", sample(lambda t, i=i: 4 * math.cos(2 * PI * 3 * t / jp + i * 1.3), jp, 16)))
    x += adv
  clips = [clip("reveal", 1.0, tracks, loop=False, driver="input:progress", note="letters pop in one by one; at progress 1 the whole word shows"),
           clip("jitter", 2 * PI / 43 * 4, jit, note="each letter shakes +/-4 px on its own clock (sin 43t / cos 37t)")]
  j = piece_json("set-d00s-leeroy", "d00s", "paintShout('LEEEEEROY!', SHOUT_GOLD, 170, 1000, jitter 4)", (540, 560), vb, "progress 1, word fully shown", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 1], "drives": "letter reveal; the scene runs 0 -> 1 linearly over 0.7 s from 4.0 s"}],
                 extra={"text": {"string": "LEEEEEROY!", "font": "sans-serif-black, bold italic (Roboto Black Italic)", "size_px": round(size, 1),
                                 "fill": "#FFC93C", "top_half_highlight": "#FFE4A0 (fill mixed 55 % to white, above 0.05 em over the baseline)",
                                 "outline": "#3A2A26, width 0.15 em", "shadow": "#000000 @ 40 %, offset (0.04 em, 0.08 em)",
                                 "letter_tilt_deg": "sin(i * 1.7) * 4", "baseline_offset_px": round(size * 0.36, 1)},
                        "text_note": "Rive text: each text-leeroy-i group is one glyph slot (centre x, tilt); glyphs are left to Rive's text run. Positions use Arial Black advances fitted to 1000 px.",
                        "layers": ["text-leeroy-0..9 (slot) / -pop (reveal scale)"]})
  return s.doc("".join(letters)), j


# ------------------------------------------------------------------------------------------------ dust trail

def dust_trail(state=None):
  tnow = (state or {}).get("time", None)
  ox, oy = 540, 1290
  vb = (-520, -160, 1040, 260)
  s = Svg(vb, "Charge dust trail")
  puffs, tracks = [], []
  run_dur = 0.72
  RREF = 50.0
  for i in range(16):
    u = i / 15
    px = lerp(230, 870, charge(u)) - 40 - ox
    py = HY - 14 - oy
    r0 = 16 + 14 * mhash(i)
    r1 = 76 + 14 * mhash(i)
    t0 = run_dur * u
    t1 = t0 + 0.75
    gid = "puff-%d" % i
    age = 0.0 if tnow is None else tnow - t0
    vis = tnow is None or 0 <= age <= 0.75
    k = m01(age / 0.75)
    sc = (r0 + (r1 - r0) * k) / RREF
    op = (1 - k) * 0.8 if vis else 0.0
    kids = [circle(0, 4, RREF, fill(0xFFA8916E, 0.5)), circle(0, 0, RREF, fill(0xFFE3CFAE)),
            circle(-0.3 * RREF, -0.3 * RREF, 0.45 * RREF, fill(0xFFF6EAD4, 0.8))]
    puffs.append(g(gid, kids, x=px - age * 60 * (1 if vis else 0), y=py - age * 70 * (1 if vis else 0), sx=sc, sy=sc, opacity=op))
    tracks += [track(gid, "opacity", [[0, 0], [r4(t0), 0.8], [r4(t1), 0]], "linear"),
               track(gid, "x", [[0, r4(px)], [r4(t0), r4(px)], [r4(t1), r4(px - 45)]]),
               track(gid, "y", [[0, r4(py)], [r4(t0), r4(py)], [r4(t1), r4(py - 52.5)]]),
               track(gid, "scaleX", [[0, r4(r0 / RREF)], [r4(t0), r4(r0 / RREF)], [r4(t1), r4(r1 / RREF)]]),
               track(gid, "scaleY", [[0, r4(r0 / RREF)], [r4(t0), r4(r0 / RREF)], [r4(t1), r4(r1 / RREF)]])]
    tracks[-5]["keys"] = [[0, 0], [r4(max(0, t0 - 0.001)), 0], [r4(t0), 0.8], [r4(t1), 0]]
  c = clip("trail", run_dur + 0.75, tracks, loop=False,
           note="16 puffs spawn along the charge (230 -> 870 px, ease cubic-bezier(0.5, 0, 0.8, 0.6) over 0.72 s), each drifting up-left, growing and fading over 0.75 s")
  j = piece_json("set-d00s-dust-trail", "d00s", "paintDustTrail", (ox, oy), vb, "all puffs at spawn size, opacity 0 (play 'trail' at the charge, 4.0 s)", [c],
                 extra={"layers": ["puff-0..15"]})
  return s.doc(g("dust-trail", puffs)), j


# ------------------------------------------------------------------------------------------------ brawl

def brawl(state=None):
  t0 = (state or {}).get("time", 0.0)
  vb = (-260, -220, 520, 420)
  s = Svg(vb, "Brawl cloud")
  L = 2 * PI / (126 * PI / 180)  # one full turn of the cloud
  n13 = round(13 * L / (2 * PI))
  n17 = round(17 * L / (2 * PI))
  out, tracks = [], []
  R = 60.0
  for layer, (col, d0, r0, off) in enumerate(((0xFFB59C7A, 70, 62, (0, 5)), (0xFFE8D6B8, 64, 54, (-6, -6)))):
    puffs = []
    for i in range(10):
      def pos(t, i=i, d0=d0, off=off):
        ang = math.radians(i * 36) + 2 * PI * t / L
        d = d0 + 22 * math.sin(2 * PI * n13 * t / L + i)
        return math.cos(ang) * d + off[0], math.sin(ang) * d * 0.75 + off[1]
      rad = lambda t, i=i, r0=r0: (r0 + 16 * (r0 / 62) * math.sin(2 * PI * n17 * t / L + i * 2)) / R
      gid = "cloud-%s-%d" % ("back" if layer == 0 else "front", i)
      x, y = pos(t0)
      puffs.append(g(gid, circle(0, 0, R, fill(col)), x=x, y=y, sx=rad(t0), sy=rad(t0)))
      tracks += [track(gid, "x", sample(lambda t, p=pos: p(t)[0], L, 36)), track(gid, "y", sample(lambda t, p=pos: p(t)[1], L, 36)),
                 track(gid, "scaleX", sample(rad, L, 36)), track(gid, "scaleY", sample(rad, L, 36))]
    out.append(g("cloud-" + ("back" if layer == 0 else "front"), puffs))
  # Stray limbs: re-thrown to a random spot 12 times a second.
  limbs, ltracks = [], []
  frame0 = math.floor(t0 * 12)
  for jn in range(3):
    def lp(fr, jn=jn):
      ang = mhash(fr * 3 + jn) * 2 * PI
      return math.cos(ang) * 130, math.sin(ang) * 95
    x, y = lp(frame0)
    if jn == 2:
      kid = path(poly([(-22, 10), (4, -40), (22, 10)]), fill(HAT))
    else:
      kid = g(None, [circle(0, 0, 20, fill(SKIN)), circle(0, 0, 20, stroke(SKIN_LINE, 3))])
    gid = "limb-%d" % jn
    limbs.append(g(gid, kid, x=x, y=y))
    ltracks += [track(gid, "x", [[r4(fr / 12), r4(lp(fr)[0])] for fr in range(12)] + [[1.0, r4(lp(0)[0])]], "hold"),
                track(gid, "y", [[r4(fr / 12), r4(lp(fr)[1])] for fr in range(12)] + [[1.0, r4(lp(0)[1])]], "hold")]
  out.append(g("limbs", limbs))
  stars, stracks = [], []
  SP = 1.2
  for jn in range(4):
    pos = lambda t, jn=jn: (math.cos(math.radians(jn * 90 + 300 * t)) * 150, math.sin(math.radians(jn * 90 + 300 * t)) * 110 - 40)
    sz = lambda t, jn=jn: (22 + 8 * math.sin(2 * PI * 4 * t / SP + jn)) / 26
    gid = "star-%d" % jn
    x, y = pos(t0)
    stars.append(g(gid, path(star_d(0, 0, 26), fill(0xFFFFD447)), x=x, y=y, sx=sz(t0), sy=sz(t0)))
    stracks += [track(gid, "x", sample(lambda t, p=pos: p(t)[0], SP, 24)), track(gid, "y", sample(lambda t, p=pos: p(t)[1], SP, 24)),
                track(gid, "scaleX", sample(sz, SP, 24)), track(gid, "scaleY", sample(sz, SP, 24))]
  out.append(g("stars", stars))
  clips = [clip("cloud-tumble", L, tracks, note="20 puffs orbit (126 deg/s, y squashed 0.75) with breathing distance and radius"),
           clip("limbs", 1.0, ltracks, note="two fists and a hat flash to random spots at 12 fps"),
           clip("stars", SP, stracks, note="four stars circle at 300 deg/s, pulsing")]
  j = piece_json("set-d00s-brawl", "d00s", "paintBrawl", (870, 1240), vb, "time 0", clips,
                 extra={"layers": ["cloud-back/cloud-back-0..9", "cloud-front/cloud-front-0..9", "limbs/limb-0..2", "stars/star-0..3"],
                        "scene_motion": "pops in (scale 0 -> 1, outBack) at 4.84 s, out at 5.32 s"})
  return s.doc(g("brawl", out)), j


# ------------------------------------------------------------------------------------------------ signal card

def signal(state=None):
  v = (state or {}).get("progress", 3.1)
  vb = (-110, -86, 220, 172)
  s = Svg(vb, "Phone signal card")
  out = [rect(-92, -64, 96, 70, fill(INK, 0.16), rx=26), rect(-96, -70, 92, 64, fill(WHITE), rx=26),
         rect(-96, -70, 92, 64, stroke(HAT, 4), rx=26)]
  col = lambda v: "#F05248" if v < 1.6 else ("#FFB020" if v < 3.6 else "#34C759")
  bars, tracks = [], []
  for i in range(5):
    bx, bh = -66 + i * 30, 22 + i * 17
    fillv = m01(v - i)
    bars.append(rect(bx, 44 - bh, bx + 20, 44, fill(0xFFEDE5E0), rx=6))
    gid = "bar-%d-fill" % i
    shape = '<rect id="%s-shape" x="-10" y="%s" width="20" height="%s" rx="6" fill="%s"/>' % (gid, f(-bh), f(bh), col(v))
    bars.append(g(gid, shape, x=bx + 10, y=44, sy=max(0.001, fillv)))
    tracks.append(track(gid, "scaleY", [[0, 0.001], [i, 0.001], [i + 1, 1], [5, 1]] if i > 0 else [[0, 0.001], [1, 1], [5, 1]]))
    tracks.append(track(gid + "-shape", "fillColor", [[0, "#F05248"], [1.6, "#FFB020"], [3.6, "#34C759"]], "hold"))
  out.append(g("bars", bars))
  xmark = g("no-signal-x", [line(68, -48, 80, -36, stroke(HAT, 5)), line(80, -48, 68, -36, stroke(HAT, 5))])
  out.append(g("no-signal", xmark, opacity=1 if v < 1.6 else 0))
  tracks.append(track("no-signal", "opacity", [[0, 1], [1.6, 0]], "hold"))
  BP = 2 * PI / 18
  on0 = math.asin(-0.2)
  # sin(18t) > -0.2 is on: off between (pi - asin(-0.2)) and (2 pi + asin(-0.2)).
  t_off = (PI - on0) / 18
  t_on = (2 * PI + on0) / 18
  clips = [clip("bars", 5.0, tracks, loop=False, driver="input:progress",
                note="bar i fills as progress passes i -> i + 1; colour red < 1.6, amber < 3.6, green above; the X shows below 1.6"),
           clip("x-blink", BP, [track("no-signal-x", "opacity", [[0, 1], [r4(t_off), 0], [r4(t_on), 1], [r4(BP), 1]], "hold")],
                note="the no-signal X blinks: on while sin(18t) > -0.2")]
  j = piece_json("set-d00s-signal", "d00s", "paintSignal", (None, None), vb, "progress 3.1 (three amber bars)", clips,
                 inputs=[{"name": "progress", "type": "number", "range": [0, 5],
                          "drives": "number of bars (fractional fills partway). Scene: 1, 2, 3.1, collapse to 1.1, then 5.2 -> 5 between 6.12 and 7.52 s"}],
                 extra={"anchor": "anchorToPip at (210, 70)", "layers": ["bars/bar-0..4-fill", "no-signal/no-signal-x"],
                        "approximation": "the Kotlin bar 'pop' (2 % squeeze while a bar is part-filled) is dropped"})
  j["origin"] = "pip-anchored (210, 70)"
  return s.doc("".join(out)), j


PIECES = {
  "set-d00s-cavern": cavern,
  "set-d00s-wizard-hat": wizard_hat,
  "set-d00s-staff": staff,
  "set-d00s-burst": burst,
  "set-d00s-field": field,
  "set-d00s-leeroy": leeroy,
  "set-d00s-dust-trail": dust_trail,
  "set-d00s-brawl": brawl,
  "set-d00s-signal": signal,
}


# Reference checks: video time, screen crop, state for the redraw, screen origin of the SVG's (0, 0).
COMPARE = {
  "set-d00s-cavern": {"time": 96.6, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C, "state": None},
  "set-d00s-wizard-hat": {"time": 98.3, "crop": (300, 680, 760, 1110), "origin": (525, 1040), "bg": "checker"},
  "set-d00s-staff": {"time": 98.3, "crop": (560, 760, 900, 1380), "origin": (729, 1233), "state": {"progress": 0.45}, "bg": "checker"},
  "set-d00s-burst": {"time": 97.6, "crop": (0, 700, 1080, 1700), "origin": (729, 1344), "state": {"progress": 0.13}},
  "set-d00s-field": {"time": 99.2, "crop": (0, 0, 1080, 2424), "origin": SCREEN_C},
  "set-d00s-dust-trail": {"time": 100.2, "crop": (100, 1100, 1000, 1420), "origin": (540, 1290), "state": {"time": 0.5}, "bg": "checker"},
  "set-d00s-brawl": {"time": 100.7, "crop": (600, 980, 1080, 1480), "origin": (870, 1240), "bg": "checker"},
  "set-d00s-signal": {"time": 102.8, "crop": (760, 940, 1010, 1150), "origin": (890, 1045), "state": {"progress": 1.7}, "bg": (16, 16, 22, 255)},
}


def _leeroy_preview(stage):
  """Rive draws the glyphs; for the reference check, set them with a local heavy font at each slot."""
  import re
  from PIL import Image, ImageDraw, ImageFont
  svg, _ = leeroy(None)
  font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Black.ttf", 132)
  for m in re.finditer(r'<g id="text-leeroy-\d+" transform="translate\(([-\d.]+) 0\)(?: rotate\(([-\d.]+)\))?"><g id="[^"]+" data-text="([^"]+)"', svg):
    x, rot, ch = float(m.group(1)), float(m.group(2) or 0), m.group(3)
    tile = Image.new("RGBA", (260, 260), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    d.text((136, 140), ch, font=font, anchor="ms", fill=(0, 0, 0, 102))
    d.text((130, 130), ch, font=font, anchor="ms", fill=(255, 201, 60, 255), stroke_width=12, stroke_fill=(58, 42, 38, 255))
    tile = tile.transform(tile.size, Image.AFFINE, (1, 0.2, -26, 0, 1, 0), Image.BICUBIC).rotate(-rot, Image.BICUBIC)
    stage.alpha_composite(tile, (int(540 + x - 130), int(560 - 130 + 60)))


COMPARE["set-d00s-leeroy"] = {"time": 100.45, "crop": (0, 380, 1080, 760), "origin": (540, 560), "post": _leeroy_preview,
                              "label": "glyph slots, set in Arial Black for preview"}
