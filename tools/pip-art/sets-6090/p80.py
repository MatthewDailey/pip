"""1980s pieces (Scenes8090.kt)."""
from lib import *

HW, HH = 500, 430
SIL = 0xFF0A0E22
GHOST_C = 0xFFFF4FA3
DOT_Y = 94
MOON_Y = -60
MOON_R = 320
PANEL_NOTE = 'Panel: (0,0) is the centre of the 1000x860 panel at screen (540,1170); Pip stands on local y=180.'


def panel_shadow():
    return g(soft_rrect(-HW + 10, -HH + 26, HW - 10, HH + 26, 56, 0x553A2A26, 30), 'panel-shadow')


def panel_clip(doc):
    return doc.clip(f'<path d="{P().rrect(-HW, -HH, HW, HH, 56)}"/>', 'panel')


def panel_border():
    return g([path(str(P().rrect(-HW, -HH, HW, HH, 56)), S(INK, 6)),
              path(str(P().rrect(-HW + 7, -HH + 7, HW - 7, HH - 7, 50)), S(0x26FFFFFF, 3))], 'panel-border')


def whip_meta():
    return [clip('whip-in', 0.34, [track('root', 'x', [[0, 1410], [0.265, -16], [0.34, 0]], 'easeOut')], loop=False,
                 note='x relative to home (540): flies in from screen x 1950, overshoots 16 px, settles'),
            clip('whip-out', 0.26, [track('root', 'x', [[0, 0], [0.26, -1440]], 'easeIn')], loop=False)]


# ------------------------------------------------------------------ neon sign

def neon():
    d = Doc('set-d80s-neon', 760, 300, 'Neon "1980s" sign; (0,0) is the sign centre at screen (540,380).')
    plate = d.lin(0, -120, 0, 120, [0xFF2A1458, 0xFF0B0620])
    els = [g(soft_rrect(-340, -100, 340, 132, 40, 0x663A2A26, 16), 'shadow'),
           path(str(P().rrect(-340, -120, 340, 112, 40)), FG(plate), 'plate'),
           path(str(P().rrect(-340, -120, 340, 112, 40)), S(INK, 6), 'plate-outline'),
           g([circle(sx, sy, 6, F(0xFF4A3A70)) for sx in (-312, 312) for sy in (-92, 84)], 'bolts')]
    frame_d = str(P().rrect(-318, -98, 318, 90, 24))
    txt = text_d('1980s', FONT_BOLD_IT, 150, 0, 46)
    sw = str(P().M(-230, 82).C(-80, 62, 120, 98, 250, 70))
    els.append(g(soft_stroke(frame_d, 0x402EF3FF, 10, 6) + soft_stroke(txt, 0x59FF2FB4, 18, 12) + soft_stroke(sw, 0x592EF3FF, 14, 12), 'glow'))
    els.append(g([path(frame_d, S(0xFF8FF8FF, 3)), path(txt, S(0xFFFF4FC4, 10)), path(sw, S(0xFF2EF3FF, 7))], 'tube'))
    els.append(g([path(txt, S(0xFFFFE6F8, 3.5)), path(sw, S(0xFFE0FFFF, 2.5))], 'core'))
    d.add(*els)
    k = lambda pts: [[r3(1.35 * p / 100), v] for p, v in pts]
    hum = TAU / 57
    meta = {'origin': [540, 380],
            'notes': 'Blurred glows are stacked strokes of growing width at low alpha. "power" (0..1) maps to: glow and '
                     'core opacity = power, tube opacity = 0.25 + 0.75*power. Lettering outlined from Helvetica Neue '
                     'Bold Italic (Roboto Bold Italic in Kotlin).',
            'clips': [clip('flicker-on', 1.35, [
                track('root', 'opacity', k([(0, 0), (5, 1), (8, 0.15), (12, 1), (15, 0.45), (18, 1), (84, 1), (100, 0)]), 'hold'),
                track('glow', 'opacity', k([(0, 0), (5, 0.5), (8, 0.1), (12, 1)]), 'hold'),
                track('core', 'opacity', k([(0, 0), (5, 0.5), (8, 0.1), (12, 1)]), 'hold'),
                track('root', 'scale', k([(0, 0.95), (18, 0.95), (45, 1), (84, 1), (100, 1.05)]), 'easeInOut'),
                track('root', 'y', k([(0, 0), (84, 0), (100, -18)]), 'easeIn')], loop=False),
                clip('hum', r3(hum * 4), [track('glow', 'opacity', sine_keys(0.9, 0.1, 57, 0, hum * 4), 'easeInOut')])],
            'inputs': [{'name': 'power', 'type': 'number', 'drives': 'the tube brightness (see notes)'}]}
    return 'set-d80s-neon', d, meta


# ------------------------------------------------------------------ arcade maze

def maze():
    d = Doc('set-d80s-maze', 1060, 960, PANEL_NOTE)
    cid = panel_clip(d)
    bg = d.lin(0, -HH, 0, HH, [0xFF0A0D2E, 0xFF140B38, 0xFF07081E])
    vig = d.rad(0, 0, 720, [0x00000000, 0x00000000, 0x66000000])
    walls = [(-HW + 26, -HH + 66, HW - 26, HH - 26, 34)] + [(*w, 22) for w in [
        (-420, -330, -170, -262), (-110, -330, 110, -262), (170, -330, 420, -262),
        (-420, 236, -170, 300), (-110, 236, 110, 300), (170, 236, 420, 300), (-300, 334, 300, 384)]]
    bloom, crisp, inner = [], [], []
    for x0, y0, x1, y1, r in walls:
        dd = str(P().rrect(x0, y0, x1, y1, r))
        bloom += soft_stroke(dd, 0x662E6BFF, 16, 8, 3)
        crisp.append(path(dd, S(0xFF3D7BFF, 5)))
        inner.append(path(str(P().rrect(x0 + 11, y0 + 11, x1 - 11, y1 - 11, r - 10)), S(0xFF1C3BC8, 3)))
    els = [rect(-HW, -HH, HW, HH, FG(bg), id='bg'), g(bloom, 'walls-bloom'), g(crisp + inner, 'walls')]
    dots = []
    for k in range(13):
        dx = 147 + 56 * k - 540
        dots.append(path(str(P().rrect(dx - 9, DOT_Y - 9, dx + 9, DOT_Y + 9, 4)), F(0xFFFFD9B8), f'dot-{k}')
                    .replace('/>', ' opacity="0"/>' if dx <= 55 else '/>'))
    els.append(g(dots, 'dots'))
    els.append(g([glow_circle(d, 0, 0, 30, 0x59FFD9B8, 8)], 'pellet-glow', tr(448, DOT_Y), opacity=0.5))
    els.append(g([circle(0, 0, 17.5, F(0xFFFFE6CC))], 'pellet', tr(448, DOT_Y)))
    els.append(g([path(text_d('1UP', FONT_MONO, 34, -440, -384, 'left'), F(0xFFFF4A4A))], 'score-1up'))
    els.append(f'<g id="text-score" data-text="001000" data-font="monospace bold" data-size="34" data-color="#FFFFFF" '
               f'data-align="left" data-x="-360" data-y="-384"/>')
    els.append(g([path(text_d('HI 025000', FONT_MONO, 34, 440, -384, 'right'), F(0xFF8FB4FF))], 'score-hi'))
    scan = P()
    y = -HH
    while y < HH:
        scan.rect(-HW, y - 1, HW, y + 1); y += 6
    els += [path(str(scan), F(0x18000000), 'scanlines'), rect(-HW, -HH, HW, HH, FG(vig), id='vignette')]
    d.add(panel_shadow(), g(els, 'set', clip=cid), panel_border())
    Dp = TAU / 10
    meta = {'origin': [540, 1170],
            'clips': [clip('pellet-pulse', r3(Dp), [sine_track('pellet-glow', 'opacity', 0.5, 0.5, 10, 0, Dp),
                                                    sine_track('pellet', 'scale', 17.5 / 17.5, 1.5 / 17.5, 10, 0, Dp)])] + whip_meta()
                     + [clip('pop-in', 0.45, [track('root', 'scale', [[0, 0.88], [0.45, 1]], 'outBack'),
                                              track('root', 'opacity', [[0, 0], [0.25, 1]], 'easeOut')], loop=False)],
            'inputs': [{'name': 'progress', 'type': 'number',
                        'drives': "screen x of Pip's mouth (starts 595): dot-k is hidden once 147 + 56k <= progress; "
                                  'the score text reads 1000 + 10 x (dots eaten), zero-padded to 6 digits'}],
            'notes': 'Wall bloom is stacked strokes; CRT scanlines are one compound path of 2 px bars; the score is a '
                     'Rive text run (text-score placeholder, glyphs omitted). 1UP and HI are outlined (Menlo Bold).'}
    return 'set-d80s-maze', d, meta


def ghost():
    d = Doc('set-d80s-ghost', 400, 420, 'The wobbly pink ghost; (0,0) is its centre.')
    body_g = d.rad(-40, -80, 240, [0xFFFF9BCB, GHOST_C, 0xFFB8226E])
    els = [glow_circle(d, 0, 10, 140, with_alpha(GHOST_C, 0.3), 30)]
    body = P().M(-100, 110).L(-100, -20).arc(0, -20, 100, 100, 180, 180).L(100, 110).L(50, 110).Q(25, 102, 0, 110).L(-50, 110) \
        .Q(-75, 102, -100, 110).Z()
    els.append(path(str(body), FG(body_g), 'body'))
    lobes, tracks = [], []
    D = TAU / 9
    for i in (0, 2):
        x0 = 100 - 50 * i; x1 = x0 - 50; xm = (x0 + x1) / 2
        gid = d.rad(-40 - xm, -80 - 110, 240, [0xFFFF9BCB, GHOST_C, 0xFFB8226E])
        fill = P().M(x0 - xm, -7).L(x0 - xm, 0).Q(0, 36, x1 - xm, 0).L(x1 - xm, -7).Z()
        edge = P().M(x0 - xm, 0).Q(0, 36, x1 - xm, 0)
        wob = 8 * math.sin(i * 1.7)
        lobes.append(g([path(str(fill), FG(gid)), path(str(edge), S(INK, 5))], f'skirt-{i}', tr(xm, 110, 0, 1, (36 + wob) / 36)))
        tracks.append(sine_track(f'skirt-{i}', 'scaleY', 1, 8 / 36, 9, i * 1.7, D))
    els.append(g(lobes, 'skirt'))
    outline = P().M(-50, 110).Q(-75, 102, -100, 110).L(-100, -20).arc(0, -20, 100, 100, 180, 180).L(100, 110)
    els.append(path(str(outline), S(INK, 5), 'outline'))
    els.append(path(str(P().M(50, 110).Q(25, 102, 0, 110)), S(INK, 5), 'outline-notch'))
    els.append(oval(-70, -100, -20, -66, F(0x59FFFFFF), 'shine'))
    eyes, pup = [], []
    for s in (-1, 1):
        eyes += [oval(s * 38 - 26, -67, s * 38 + 26, -1, F(WHITE)), oval(s * 38 - 26, -67, s * 38 + 26, -1, S(INK, 3))]
        pup += [circle(s * 38, -28, 13, F(0xFF1D2263)), circle(s * 38 - 4, -32, 4, F(WHITE))]
    els += [g(eyes, 'eyes'), g(pup, 'pupils', 'translate(11 0)')]
    gr = P().M(-30, 40)
    for i in range(1, 7):
        gr.L(-30 + i * 10, 40 + (0 if i % 2 == 0 else 7))
    els.append(path(str(gr), S(0xFF7A1446, 4), 'grin'))
    d.add(*els)
    meta = {'origin': 'scene: slides from x -190 to 250 at y 1215, bobs 9 px, then chases off to x 1420',
            'clips': [clip('skirt-wobble', r3(D), tracks,
                           note='the two hanging lobes stretch on their own phases; the notches stay put')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'pupils x = 11 * progress (look direction)'}]}
    return 'set-d80s-ghost', d, meta


def tree_line(base, k, seed):
    p = P().M(-HW - 20, HH + 20).L(-HW - 20, base)
    x, i = -HW - 20, 0
    while x < HW + 40:
        b = base + 22 * math.sin(x / 170 + seed)
        h = (70 + 110 * hash01(i * 7 + seed)) * k
        w = (22 + 14 * hash01(i * 3 + seed)) * k
        p.L(x - w, b).L(x - w * 0.55, b - h * 0.45).L(x - w * 0.8, b - h * 0.45).L(x, b - h) \
            .L(x + w * 0.8, b - h * 0.45).L(x + w * 0.55, b - h * 0.45).L(x + w, b)
        x += w * 2 + 6 * hash01(i + seed); i += 1
    return str(p.L(HW + 40, HH + 20).Z())


def moon80():
    d = Doc('set-d80s-moon', 1060, 960, PANEL_NOTE + ' Full moon centre local (0,-60), r 320.')
    cid = panel_clip(d)
    iris = d.clip('<circle id="iris-shape" cx="0" cy="-60" r="1000"/>', 'iris')
    sky = d.lin(0, -HH, 0, HH, [0xFF0B1433, 0xFF1A2756, 0xFF2B3A6B])
    glow = d.rad(0, MOON_Y, 560, [0x88FFF1CC, 0x22FFE7B0, 0x00FFE7B0])
    face = d.rad(-90, MOON_Y - 110, 440, [0xFFFFFDF2, 0xFFF8EAC6, 0xFFE4D09C])
    els = [rect(-HW, -HH, HW, HH, FG(sky), id='sky')]
    stars, tracks = [], []
    D = 20.0
    for i in range(46):
        w = 1.3 + hash01(i + 9)
        n = max(1, round(w * D / TAU)); w2 = TAU * n / D
        fn = lambda t, w2=w2, i=i: 0.35 + 0.65 * max(0, math.sin(t * w2 + i * 2.1))
        stars.append(circle(-HW + 1000 * hash01(i + 100), -HH + 700 * hash01(i + 200), 1.5 + 2.5 * hash01(i + 300), F(WHITE), f'star-{i}')
                     .replace('/>', f' opacity="{fn(0):.3f}"/>'))
        tracks.append(track(f'star-{i}', 'opacity', sampled(fn, D, 16 * n), 'linear'))
    els.append(g(stars, 'stars'))
    els += [circle(0, MOON_Y, 560, FG(glow), 'moon-glow'), circle(0, MOON_Y, MOON_R, FG(face), 'moon')]
    cr = [-120, -120, 48, 90, -160, 30, 140, 40, 62, -60, 90, 38, -180, 40, 24, 40, -40, 20, 210, -70, 22]
    cc = []
    for j in range(0, len(cr), 3):
        cx, cy, r = cr[j], MOON_Y + cr[j + 1], cr[j + 2]
        cc += [circle(cx, cy, r, F(0x40C2A672)), circle(cx + r * 0.12, cy + r * 0.12, r * 0.82, F(0x30FFFFFF))]
    els += [g(cc, 'craters'), path(tree_line(300, 0.75, 11), F(0xFF141C3E), 'trees-far'), path(tree_line(345, 1, 3), F(0xFF070B1C), 'trees-near')]
    d.add(g([panel_shadow(), g(els, 'set', clip=cid), panel_border()], 'iris-content', clip=iris),
          circle(0, MOON_Y, 1000, S(INK, 8) + NON_SCALING, 'iris-ring').replace('/>', ' opacity="0"/>'))
    meta = {'origin': [540, 1170],
            'clips': [clip('twinkle', D, tracks),
                      clip('iris-out', 1.0, [track('iris-shape', 'scale', [[0, 1], [1, 0]]), track('iris-ring', 'scale', [[0, 1], [1, 0]]),
                                             track('iris-ring', 'opacity', [[0, 0], [0.001, 1], [0.999, 1], [1, 0]], 'hold')],
                           loop=False, note='time = progress; a circular clip about the moon centre closes to nothing, ringed in ink')] + whip_meta(),
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'iris: scrubs iris-out (clip radius = (1-progress)*1000)'}],
            'notes': 'The iris is a Rive clipping shape (clip-path here). Ring stroke should not scale with the transform.'}
    return 'set-d80s-moon', d, meta


def knee(hx, hy, fx, fy, ln):
    dx, dy = fx - hx, fy - hy
    dd = max(0.01, min(math.hypot(dx, dy), ln * 2 - 0.01))
    h = math.sqrt(max(0, ln * ln - dd * dd / 4))
    mx, my = hx + dx / 2, hy + dy / 2
    px, py = -dy / dd, dx / dd
    s = 1 if px >= 0 else -1
    return mx + px * h * s, my + py * h * s


def leg_angles(side, crank):
    ang = crank + side * PI
    fx, fy = 5 + math.cos(ang) * 24, 40 + math.sin(ang) * 24
    kx, ky = knee(-38, -74, fx, fy, 80)
    a1 = math.degrees(math.atan2(ky + 74, kx + 38))
    a2 = math.degrees(math.atan2(fy - ky, fx - kx))
    return a1, a2 - a1, -a2


def bike():
    d = Doc('set-d80s-bike', 420, 480, 'Silhouette kid on a bike; (0,0) is the actor point (bike drawn tilted -14 deg by the scene).')
    els = []
    for wx, nm in ((-115, 'wheel-back'), (125, 'wheel-front')):
        spokes = [line(0, 0, math.cos(s * TAU / 8) * 62, math.sin(s * TAU / 8) * 62, SIL, 2.5) for s in range(8)]
        els.append(g([circle(0, 0, 66, S(SIL, 11)), g(spokes, f'{nm}-spokes'), circle(0, 0, 8, F(SIL))], nm, tr(wx, 40)))
    fr = [(-115, 40, -40, -58), (-40, -58, 5, 40), (5, 40, -115, 40), (-40, -58, 92, -50), (5, 40, 92, -50), (92, -50, 125, 40), (92, -50, 100, -86)]
    els.append(g([line(*f, SIL, 9) for f in fr] + [line(100, -86, 74, -94, SIL, 8), rect(-64, -74, -16, -60, F(SIL), 7)], 'frame'))
    legs, tracks = [], []
    D = TAU / (11 * 0.9)
    for side in (0, 1):
        a1, a2, a3 = leg_angles(side, 0)
        col = 0xFF141A36 if side == 0 else SIL
        foot = g([line(-10, 0, 16, 0, SIL, 12)], f'leg-{side}-foot', tr(80, 0, a3))
        shin = g([line(0, 0, 80, 0, col, 20), foot], f'leg-{side}-shin', tr(80, 0, a2))
        legs.append(g([line(0, 0, 80, 0, col, 20), shin], f'leg-{side}-thigh', tr(-38, -74, a1)))
        ks = [[], [], []]
        prev = [a1, a2, a3]
        for i in range(17):
            t = D * i / 16
            vals = leg_angles(side, 11 * 0.9 * t)
            for j in range(3):
                v = vals[j]
                while v - prev[j] > 180: v -= 360
                while v - prev[j] < -180: v += 360
                prev[j] = v
                ks[j].append([r3(t), r3(v)])
        tracks += [track(f'leg-{side}-thigh', 'rotation', ks[0]), track(f'leg-{side}-shin', 'rotation', ks[1]), track(f'leg-{side}-foot', 'rotation', ks[2])]
    els.append(g(legs, 'legs'))
    hood = P().M(-8, -170).Q(-6, -230, 34, -222).Q(14, -200, 10, -168).Z()
    els.append(g([line(-40, -80, 4, -150, SIL, 48), line(8, -142, 76, -94, SIL, 16), circle(26, -188, 31, F(SIL)), path(str(hood), F(SIL))], 'rider'))
    d.add(*els)
    spin = 405
    meta = {'origin': 'scene: glides from (-260,1380) to (1360,900) over 2.7 s at rotation -14, bobbing 7 px',
            'clips': [clip('pedal', r3(D), tracks + [track('wheel-back-spokes', 'rotation', [[0, 0], [r3(D), spin]]),
                                                     track('wheel-front-spokes', 'rotation', [[0, 0], [r3(D), spin]])],
                           note='one crank turn; legs are a two-bone rig (thigh > shin > foot) solved from the Kotlin IK; '
                                'wheels turn 405 deg per crank turn (8-fold spokes, 1% faster than Kotlin so it loops)')]}
    return 'set-d80s-bike', d, meta


def basket():
    d = Doc('set-d80s-basket', 440, 160, 'Handlebar basket Pip rides in (drawn in front of Pip); same origin as set-d80s-bike.')
    weave = []
    for yy in (-56, -44, -32):
        ins = (yy + 68) / 46 * 10
        weave.append(line(104 + ins + 3, yy, 206 - ins - 3, yy, 0xFF1E2750, 2.5))
    for xx in (126, 146, 166, 186):
        weave.append(line(xx, -64, xx - (xx - 155) * 0.08, -26, 0xFF1E2750, 2.5))
    d.add(line(104, -60, 92, -52, SIL, 6, id='stem'), path(str(P().poly([(104, -68), (206, -68), (196, -22), (114, -22)])), F(SIL), 'basket'),
          g(weave, 'weave'), line(100, -68, 210, -68, SIL, 8, id='rim'))
    return 'set-d80s-basket', d, {'origin': 'follows set-d80s-bike exactly'}


# ------------------------------------------------------------------ 88 mph

def speedo():
    d = Doc('set-d80s-speedo', 540, 260, '88 mph readout; (0,0) is the panel centre at screen (540,760).')
    bg = d.lin(0, -100, 0, 100, [0xFF2A1512, 0xFF0E0605])
    els = [g(soft_rrect(-250, -86, 250, 116, 28, 0x663A2A26, 16), 'shadow'),
           path(str(P().rrect(-250, -100, 250, 100, 28)), FG(bg), 'plate'),
           path(str(P().rrect(-250, -100, 250, 100, 28)), S(INK, 6), 'plate-outline'),
           path(str(P().rrect(-238, -88, 238, 88, 18)), S(0xFF5A4A4A, 3), 'bezel'),
           path(text_d('88', FONT_MONO, 150, 92, 52, 'right', -0.08), F(0xFF2E0C0A), 'segments-off')]
    adv = advance('8', FONT_MONO, 150)
    digits = []
    for place, x in (('tens', 92 - 2 * adv), ('ones', 92 - adv)):
        for v in range(10):
            dd = text_d(str(v), FONT_MONO, 150, x, 52, 'left', -0.08)
            on = v == 8
            digits.append(g(soft_stroke(dd, 0x59FF3B2F, 6, 10, 3) + [path(dd, F(0xFFFF7058))], f'{place}-{v}', opacity=1 if on else 0))
    els.append(g(digits, 'readout'))
    els.append(path(text_d('MPH', FONT_MONO, 44, 112, 50, 'left', -0.08), F(0xFFFFB347), 'mph'))
    els.append(path(text_d('SPEED', FONT_MONO, 24, 114, -30, 'left', -0.08), F(0x99FFB347), 'speed'))
    d.add(*els)
    tracks = []
    for v in range(10):
        kt, ko = [], []
        for n in range(89):
            t = n / 88
            tens, ones = n // 10, n % 10
            kt.append([r3(t), 1 if (tens == v and n >= 10) else 0])
            ko.append([r3(t), 1 if ones == v else 0])
        # Thin the hold keys to changes only.
        for lst, name in ((kt, f'tens-{v}'), (ko, f'ones-{v}')):
            thin = [lst[0]] + [k for a, k in zip(lst, lst[1:]) if k[1] != a[1]]
            tracks.append(track(name, 'opacity', thin, 'hold'))
    Dr = TAU / 28
    meta = {'origin': [540, 760],
            'clips': [clip('count', 1.0, tracks, loop=False, note='time = progress: shows round(progress*88), blank tens below 10'),
                      clip('redline', r3(Dr), [track('readout', 'opacity', [[0, 1], [r3(Dr / 2), 0.6], [r3(Dr), 1]], 'hold')],
                           note='plays once progress reaches 1: the digits strobe'),
                      clip('pop-in', 0.35, [track('root', 'scale', [[0, 0.6], [0.35, 1]], 'outBack'), track('root', 'opacity', [[0, 0], [0.35, 1]], 'outBack')], loop=False)],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'scrubs count (0..1 -> 0..88 mph)'}],
            'notes': 'Digits are outlined Menlo Bold (Android monospace bold), one group per value per place, toggled by opacity, '
                     'so the red glow (stacked strokes) stays vector art rather than Rive text.'}
    return 'set-d80s-speedo', d, meta


def zap():
    d = Doc('set-d80s-zap', 680, 620, "Electric arcs crackling around Pip while he revs; (0,0) at screen (190,1180).")
    frames = 8
    groups, thresholds, tracks = [], {}, []
    Df = frames / 22
    for f in range(frames):
        for i in range(6):
            ang = TAU * hash01(f * 7 + i * 3)
            r = 150
            p = P().M(math.cos(ang) * r, math.sin(ang) * r * 0.9)
            for s in range(1, 7):
                r += 24
                j = (hash01(f * 31 + i * 11 + s) - 0.5) * 0.5
                p.L(math.cos(ang + j) * r, math.sin(ang + j) * r * 0.9)
            dd = str(p)
            nm = f'arc-{f}-{i}'
            thresholds[nm] = r3(hash01(f * 13 + i))
            groups.append(g(soft_stroke(dd, 0x596FD8FF, 10, 6, 3) + [path(dd, S(0xFFEFFBFF, 4))], nm, opacity=1 if f == 0 else 0))
            t0, t1 = f / 22, (f + 1) / 22
            keys = [[0, 1 if f == 0 else 0]]
            if f > 0:
                keys.append([r3(t0), 1])
            keys.append([r3(t1), 0])
            tracks.append(track(nm, 'opacity', keys, 'hold'))
    d.add(g(groups, 'arcs'))
    meta = {'origin': [190, 1180],
            'clips': [clip('crackle', r3(Df), tracks, note='22 fresh arc sets per second, 8 sets cycling')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'charge 0..1: an arc shows only when progress >= its threshold (more arcs as it builds)'}],
            'thresholds': thresholds}
    return 'set-d80s-zap', d, meta


def trails():
    CX, CY = 665, 1357
    d = Doc('set-d80s-trails', 1120, 300, f'Twin fire tracks, painted in screen space; (0,0) = screen ({CX},{CY}).')
    x0, x1 = 150, 1180
    rv = d.clip(f'<rect id="reveal-shape" x="{x0}" y="1150" width="{x1 - x0}" height="300"/>', 'reveal')
    rows, tracks = [], []
    Dflk = 0.5
    De = 1 / 1.3
    for ri in (0, 1):
        y = 1338 if ri == 0 else 1376
        w = 0.8 if ri == 0 else 1.0
        grad = d.lin(0, -74, 0, 0, [0xFFFFF2B0, 0xFFFFB23F, 0xFFFF4A1C])
        base = [line(x0, y, x1, y, 0x553A2A26, 12 * w)]
        glow = soft_stroke(f'M{x0} {y}L{x1} {y}', 0x80FF6A1F, 28 * w, 16, 3)
        flames = []
        x, i = x0, 0
        while x < x1:
            h0 = (30 + 36 * hash01(i * 17 + ri * 101)) * w
            lean = h0 * 0.22
            fp = P().M(-11 * w, 0).Q(-12 * w, -h0 * 0.55, -lean, -h0).Q(10 * w, -h0 * 0.45, 11 * w, 0).Z()
            nm = f'flame-{ri}-{i}'
            flames.append(g([path(str(fp), FG(grad))], nm, tr(x, 0)))
            keys = []
            for f in range(8):
                h = (30 + 36 * hash01(i * 17 + f * 3 + ri * 101)) * w
                keys.append([r3(f / 16), r3(h / h0)])
            keys.append([Dflk, keys[0][1]])
            tracks.append(track(nm, 'scaleY', keys, 'hold'))
            x += 20; i += 1
        embers = []
        for e in range(10):
            ex = x0 + (x1 - x0) * hash01(e * 9 + ri * 3)
            l0 = fract(hash01(e * 5 + ri))
            nm = f'ember-{ri}-{e}'
            embers.append(g([circle(0, 0, 3.5, F(0xFFFFD27A))], nm, tr(ex + 10 * math.sin(e), y - l0 * 130), opacity=1 - l0))
            tw = (1 - l0) * De
            tracks.append(track(nm, 'y', [[0, r3(y - l0 * 130)], [r3(tw), y - 130], [r3(tw + 0.001), y], [r3(De), r3(y - l0 * 130)]]))
            tracks.append(track(nm, 'opacity', [[0, r3(1 - l0)], [r3(tw), 0], [r3(tw + 0.001), 1], [r3(De), r3(1 - l0)]]))
        rows.append(g(base + [g(glow, f'glow-{ri}'), g(flames, f'flames-{ri}', tr(0, y)),
                              line(x0, y, x1, y, 0xFFFFE08A, 5 * w, id=f'hot-{ri}'), g(embers, f'embers-{ri}')], f'track-{ri}'))
    d.add(g([g(rows, 'trails', clip=rv)], 'screen', f'translate({-CX} {-CY})'))
    meta = {'origin': [CX, CY], 'space': 'screen',
            'clips': [clip('flicker', Dflk, [t for t in tracks if t['target'].startswith('flame')], note='16 fps flame heights, 8 frames'),
                      clip('embers', r3(De), [t for t in tracks if t['target'].startswith('ember')]),
                      clip('lay-down', 1.0, [track('reveal-shape', 'scaleX', [[0, 0], [1, 1]], 'easeIn')], loop=False,
                           note='progress 0..1: the reveal clip grows from x=150 to 1180 (pivot at its left edge)'),
                      clip('burn-out', 1.0, [track('flames-0', 'scaleY', [[0, 1], [1, 0]]), track('flames-1', 'scaleY', [[0, 1], [1, 0]]),
                                             track('glow-0', 'opacity', [[0, 1], [1, 0]]), track('glow-1', 'opacity', [[0, 1], [1, 0]]),
                                             track('hot-0', 'opacity', [[0, 1], [1, 0]]), track('hot-1', 'opacity', [[0, 1], [1, 0]]),
                                             track('embers-0', 'opacity', [[0, 1], [1, 0]]), track('embers-1', 'opacity', [[0, 1], [1, 0]])],
                           loop=False, note='progress 1..2: flames shrink, glow and embers fade; the scorched base line stays')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': '0..1 lay-down, 1..2 burn-out'}],
            'notes': 'Flame gradient is in each flame\'s local space, so it stretches with the flame (Kotlin keeps it fixed in screen space).'}
    return 'set-d80s-trails', d, meta


def streaks():
    d = Doc('set-d80s-streaks', 1800, 860, 'Whip-pan speed streaks across the panel (shared by d80s and d90s); (0,0) = panel centre. Drawn at progress 0.')
    lines_, tracks = [], []
    for i in range(22):
        y = -420 + 840 * hash01(i * 3 + 1)
        ln_ = 220 + 420 * hash01(i * 3 + 2)
        sp = 0.8 + 0.6 * hash01(i * 3 + 3)
        th = 3 + 7 * hash01(i * 5)
        c = 0x553A2A26 if i % 3 == 0 else 0xD9FFFFFF
        f0 = hash01(i * 7)
        x = 900 - fract(f0) * 1800
        lines_.append(g([line(0, 0, ln_, 0, c, th)], f'streak-{i}', tr(x, y)))
        keys = [[0, r3(x)]]
        wrap_t = (1 - f0) / sp
        if wrap_t < 1:
            keys += [[r3(wrap_t), -900], [r3(wrap_t + 0.001), 900]]
        keys.append([1, r3(900 - fract(f0 + sp) * 1800)])
        tracks.append(track(f'streak-{i}', 'x', keys))
    d.add(g(lines_, 'streaks'))
    meta = {'origin': [540, 1170],
            'clips': [clip('pan', 1.0, tracks + [track('streaks', 'opacity', [[0, 0], [0.22, 1], [0.68, 1], [1, 0]])], loop=False,
                           note='time = progress; scene plays it over 0.5 s')]}
    return 'set-d80s-streaks', d, meta


PIECES = [neon, maze, ghost, moon80, bike, basket, speedo, zap, trails, streaks]
