"""1960s pieces (Scenes6070.kt)."""
from lib import *

PHW, PHH, PR = 470, 380, 64
PCX, PCY = 540, 1150
POP_RED, POP_BLUE, POP_YELLOW = 0xFFE8322B, 0xFF2A55C7, 0xFFFFE14D
PANEL_NOTE = 'Panel set: (0,0) is the centre of the 940x760 framed set at screen (540,1150); Pip stands on local y=200.'


def panel_clip(doc):
    return doc.clip(str(P().rrect(-PHW, -PHH, PHW, PHH, PR)).join(['<path d="', '"/>']), 'panel')


def glint_d(x, y, r):
    n = 0.12
    return str(P().M(x, y - r).Q(x + r * n, y - r * n, x + r, y).Q(x + r * n, y + r * n, x, y + r)
               .Q(x - r * n, y + r * n, x - r, y).Q(x - r * n, y - r * n, x, y - r).Z())


def panel_inputs():
    return [{'name': 'progress', 'type': 'number',
             'drives': 'Scene-level: n.f slides set n out and set n+1 in (a whip pan). The set itself is static at '
                       'its own origin; the host translates it by -f*940 (horizontal) or +f*760 (vertical).'}]


# ------------------------------------------------------------------ frame

def frame():
    d = Doc('set-d60s-frame', 1060, 900, 'Shared by d60s and d70s: drop shadow, cream border, flash, whip streaks.')
    d.add(g(soft_rrect(-PHW + 6, -PHH + 22, PHW - 6, PHH + 26, PR, 0x50000000, 30), 'shadow'))
    d.add(path(str(P().rrect(-PHW, -PHH, PHW, PHH, PR)), S(0xFFFFF8EE, 12), 'border'))
    d.add(path(str(P().rrect(-PHW - 7, -PHH - 7, PHW + 7, PHH + 7, PR + 7)), S(0x333A2A26, 2), 'border-outer'))
    # Whip-pan streaks, laid out at mid-pan (f = 0.5); the clip slides them through the panel.
    cid = panel_clip(d)
    st = []
    tracks = []
    for j in range(24):
        across = -PHH + hash01(j * 5 + 3) * 2 * PHH
        ln_ = 140 + hash01(j * 11 + 1) * 420
        a = 0.25 + 0.45 * hash01(j * 3)
        w = 3 + 7 * hash01(j * 13)
        span = 2 * PHW + 2 * ln_
        along0 = -PHW - ln_ + fract(hash01(j * 7 + 2)) * span
        st.append(g([line(0, 0, ln_, 0, with_alpha(WHITE, a), w)], f'streak-{j}', tr(along0, across)))
        tracks.append(track(f'streak-{j}', 'x', [[0, r3(along0)], [1, r3(along0 + 1.7 * span)]]))
    d.add(g([g(st, 'streaks', opacity=None)], clip=cid))
    d.add(path(str(P().rrect(-PHW, -PHH, PHW, PHH, PR)), F(WHITE), 'flash').replace('/>', ' opacity="0"/>'))
    meta = {
        'origin': [PCX, PCY], 'layer': 'frame shadow/border behind Pip (layer -1), flash in front (layer 2)',
        'notes': 'Streak x positions wrap in Kotlin (fract); here they slide linearly, which is what is visible '
                 'during the 0.28 s pan. Streaks opacity = sin(pi*f) of the pan; the streaks group is horizontal, '
                 'rotate it 90 degrees for vertical pans (d70s sea->disco).',
        'clips': [
            clip('pop-in', 0.5, [track('root', 'scale', [[0, 0.86], [0.5, 1]], 'outBack'),
                                 track('root', 'opacity', [[0, 0], [0.175, 1], [0.5, 1]])], loop=False),
            clip('pop-out', 0.42, [track('root', 'scale', [[0, 1], [0.42, 0.86]], 'inBack'),
                                   track('root', 'opacity', [[0, 1], [0.42, 0]], 'inBack')], loop=False),
            clip('whip', 1.0, tracks + [track('streaks', 'opacity', sampled(lambda f: math.sin(PI * f), 1, 8))],
                 loop=False, note='time = pan fraction f (scrub with progress)'),
            clip('flash', 0.4, [track('flash', 'opacity', [[0, 0.9], [0.4, 0]], 'easeOut')], loop=False),
        ],
        'inputs': panel_inputs(),
    }
    return 'set-d60s-frame', d, meta


# ------------------------------------------------------------------ moon landing

def lander(x, y, s):
    leg = 0xFFB9BDCB
    els = [line(-38, -6, -72, 34, leg, 5), line(38, -6, 72, 34, leg, 5), line(-14, 0, -24, 38, leg, 5), line(14, 0, 24, 38, leg, 5),
           oval(-84, 30, -60, 40, F(leg)), oval(60, 30, 84, 40, F(leg)),
           path(str(P().poly([(-34, -34), (34, -34), (46, -18), (46, 2), (34, 10), (-34, 10), (-46, 2), (-46, -18)])), F(0xFFD9A441)),
           rect(-40, -28, 40, -16, F(0xFFF2D27A)),
           rect(-28, -76, 28, -34, F(0xFFCDD0DA), 8), rect(-12, -66, 6, -50, F(0xFF2A2E48), 4),
           line(18, -76, 30, -100, leg, 3), circle(30, -102, 5, F(0xFFE8EAF0))]
    return g(els, 'lander', tr(x, y, 0, s))


def crater(x, y, r):
    ry = r * 0.34
    return [ellipse(x, y, r, ry, F(0xFFEDEEF4)),
            ellipse(x, y - 0.04 * ry, 0.86 * r, 0.74 * ry, F(0xFF8C90A6)),
            ellipse(x + 0.06 * r, y + 0.235 * ry, 0.66 * r, 0.385 * ry, F(0xFFB2B6C7))]


def moon():
    d = Doc('set-d60s-moon', 940, 760, PANEL_NOTE)
    cid = panel_clip(d)
    sky = d.lin(0, -PHH, 0, 160, [0xFF05081D, 0xFF141846, 0xFF2A2E62], [0, 0.6, 1])
    els = [rect(-PHW - 12, -PHH - 12, PHW + 12, PHH + 12, FG(sky), id='sky')]
    stars, tracks = [], []
    D = 12.0
    for i in range(50):
        x = -PHW + hash01(i * 7 + 1) * 2 * PHW
        y = -PHH + hash01(i * 13 + 5) * 500
        w = 1.3 + 3 * hash01(i * 3)
        tw0 = 0.4 + 0.6 * (0.5 + 0.5 * math.sin(i))
        stars.append(circle(x, y, 1.4 + 2.4 * hash01(i * 3 + 2), F(WHITE), f'star-{i}').replace('/>', f' opacity="{tw0:.3f}"/>'))
        tracks.append(sine_track(f'star-{i}', 'opacity', 0.7, 0.3, w, i, D))
        if i % 8 == 0:
            stars.append(g([path(glint_d(0, 0, 13), F(with_alpha(0xFFEAF4FF, 0.8)))], f'glint-{i}', tr(x, y, 0, tw0)))
            tracks.append(sine_track(f'glint-{i}', 'scale', 0.7, 0.3, w, i, D))
    els.append(g(stars, 'stars'))
    # Earth.
    glow = d.rad(300, -235, 112, [0x667FC4FF, 0x227FC4FF, 0x007FC4FF], [0, 0.6, 1])
    sea = d.lin(250, -290, 350, -180, [0xFF62B2F5, 0xFF2A5FB5])
    ecl = d.clip(f'<circle cx="300" cy="-235" r="58"/>', 'earth-disc')
    land, clouds = [], []
    for j in range(5):
        bx = 225 + fract(hash01(j * 3)) * 170
        by = -272 + hash01(j * 5 + 1) * 70
        for off in (0, -170):
            land.append(oval(bx + off - 24, by - 12 - 6 * hash01(j), bx + off + 20, by + 14, F(0xFF69C489)))
    for j in range(3):
        bx = 230 + fract(hash01(j * 9 + 4)) * 160
        by = -262 + j * 22
        for off in (0, -160):
            clouds.append(line(bx + off - 22, by, bx + off + 18, by - 4, 0xBBFFFFFF, 5))
    els.append(circle(300, -235, 112, FG(glow), 'earth-glow'))
    els.append(g([circle(300, -235, 58, FG(sea)), g(land, 'earth-land'), g(clouds, 'earth-clouds'),
                  circle(327, -226, 60, F(0x99050822), 'earth-night')], 'earth', clip=ecl))
    els.append(circle(300, -235, 59, S(0x66BFE3FF, 3), 'earth-rim'))
    tracks.append(track('earth-land', 'x', [[0, 0], [r3(1 / 0.035), 170]]))
    tracks.append(track('earth-clouds', 'x', [[0, 0], [20, 160]]))
    # Far ridge with the lander.
    rp = P().M(-PHW - 12, 118).L(-400, 96).Q(-300, 66, -220, 100).Q(-120, 120, -40, 98).Q(80, 68, 170, 100) \
        .Q(300, 124, 380, 88).L(PHW + 12, 104).L(PHW + 12, 220).L(-PHW - 12, 220).Z()
    els.append(path(str(rp), F(0xFF5A5E78), 'ridge'))
    els.append(lander(-318, 110, 0.62))
    dirt = d.lin(0, 100, 0, PHH, [0xFFE2E4EC, 0xFFB9BCCB, 0xFF8A8EA3], [0, 0.45, 1])
    els.append(path(str(P().M(-PHW - 12, 150).Q(0, 106, PHW + 12, 142).L(PHW + 12, PHH + 12).L(-PHW - 12, PHH + 12).Z()), FG(dirt), 'ground'))
    els.append(path(str(P().M(-PHW - 12, 150).Q(0, 106, PHW + 12, 142)), S(0x88FFFFFF, 3), 'ground-rim'))
    cr = [-330, 268, 66, -150, 345, 46, 250, 312, 86, 400, 196, 38, -250, 182, 30, 92, 172, 22, -20, 300, 16]
    craters = []
    for i in range(0, len(cr), 3):
        craters += crater(*cr[i:i + 3])
    els.append(g(craters, 'craters'))
    fps = []
    for k in range(9):
        u = k / 8
        x = lerp(-440, -90, u); y = lerp(338, 214, u) + (-9 if k % 2 == 0 else 9); s = lerp(1.1, 0.6, u)
        fps.append(path(str(P().rrect(-15, -6, 15, 6, 6)), F(0x668A8EA3)).replace('<path', f'<path transform="{tr(x, y, -18, s)}"'))
    els.append(g(fps, 'footprints'))
    d.add(g(els, 'set', clip=cid))
    meta = {'origin': [PCX, PCY], 'clips': [clip('ambient', D, tracks,
            note='Star twinkle rates rounded so the 12 s loop closes; Earth land/cloud bands scroll and repeat '
                 '(each band is drawn twice, one width apart, inside the Earth clip).')],
            'inputs': panel_inputs()}
    return 'set-d60s-moon', d, meta


def flag():
    d = Doc('set-d60s-flag', 300, 680, 'Moon flag. (0,0) is the foot of the pole; planted at screen (773,1360).')
    shield = P().M(0, -22).C(10, -16, 16, -16, 20, -17).L(20, 0).C(20, 12, 10, 20, 0, 25).C(-10, 20, -20, 12, -20, 0) \
        .L(-20, -17).C(-16, -16, -10, -16, 0, -22).Z()
    heart = P().M(0, 9).C(-15, -2, -10, -15, 0, -7).C(10, -15, 15, -2, 0, 9).Z()
    cloth = P().M(4, -310).L(130, -310)
    x = 130
    while x >= 4:
        cloth.L(x, -226 + 3 * math.sin(x * 0.09))
        x -= 9
    cloth.Z()
    d.add(line(0, 0, 0, -320, 0xFFC3C8D3, 8, id='pole'), line(-2, -10, -2, -316, 0x99FFFFFF, 2, id='pole-shine'),
          circle(0, -324, 7, F(0xFFD9A441), 'finial'), line(0, -312, 132, -312, 0xFFC3C8D3, 5, id='rod'),
          g([path(str(cloth), F(HAT_RED)), rect(44, -308, 54, -228, F(0x22000000)), rect(92, -308, 100, -228, F(0x18FFFFFF)),
             g([path(str(shield), F(WHITE)), path(str(heart), F(HAT_RED))], 'emblem', tr(68, -268, 0, 0.9))],
            'cloth', 'translate(0 -310)'.replace('translate(0 -310)', '')))
    meta = {'origin': [773, 1360],
            'notes': 'The cloth bottom edge ripples (3 px sine, x*0.09 + t*1.1) in Kotlin; Rive approximates it with a '
                     'slow scaleY breathe on the cloth (pivot: the rod at y=-310). Clipped to the set panel in the scene.',
            'clips': [clip('ripple', 5.712, [track('cloth', 'scaleY', [[0, 1], [1.428, 1.025], [2.856, 1], [4.284, 0.975], [5.712, 1]], 'easeInOut')]),
                      clip('plant-wobble', 0.7, [track('root', 'rotation', [[0, 0], [0.105, 5], [0.28, -3], [0.462, 1.2], [0.7, 0]], 'easeInOut')], loop=False)],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': '< 0.5: the host attaches the flag to Pip\'s right fist '
                        '(pole foot 95 px below the fist); >= 0.5: planted at the actor position.'}],
            'pivots': {'cloth': [0, -310]}}
    return 'set-d60s-flag', d, meta


def helmet():
    d = Doc('set-d60s-helmet', 720, 720, "Fishbowl helmet in Pip's 540x660 box; (0,0) = box point (285,380), the pop-in pivot.")
    glass = d.rad(285, 360, 340, [0x00FFFFFF, 0x0CBFE6FF, 0x50D6F0FF], [0, 0.72, 1])
    collar = d.lin(0, 640, 0, 708, [0xFFF4F6FA, 0xFFC5CAD6])
    els = [circle(285, 355, 340, FG(glass), 'glass'), circle(285, 355, 340, S(0xAAFFFFFF, 7), 'rim'),
           path(str(P().arc(285, 355, 296, 296, 196, 62, move=True)), S(0xCCFFFFFF, 18), 'shine-long'),
           path(str(P().arc(285, 355, 296, 296, 266, 10, move=True)), S(0xAAFFFFFF, 14), 'shine-short'),
           circle(498, 470, 10, F(0x88FFFFFF), 'shine-dot'),
           g([path(str(P().rrect(64, 640, 506, 708, 34)), FG(collar)), path(str(P().rrect(64, 640, 506, 708, 34)), S(0xFF969DAD, 6)),
              line(92, 676, 478, 676, 0x55969DAD, 4), circle(430, 662, 10, F(HAT_RED))], 'collar')]
    d.add(g(els, 'helmet', 'translate(-285 -380)'))
    meta = {'origin': 'pip-box (285,380); rides on Pip with his transform (size/540 scale, squash, facing, rotation)',
            'space': 'pip-box',
            'clips': [clip('pop-in', 0.45, [track('root', 'scale', [[0, 0.5], [0.45, 1]], 'outBack')], loop=False),
                      clip('pop-out', 0.12, [track('root', 'scale', [[0, 1], [0.12, 0]], 'easeIn')], loop=False)],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'uniform scale of the root about (0,0)'}]}
    return 'set-d60s-helmet', d, meta


# ------------------------------------------------------------------ zebra crossing

def tree(x, y, r, i):
    return g([rect(x - r * 0.08, y, x + r * 0.08, y + r * 1.2, F(0xFF7A5A42)), circle(x, y - r * 0.2, r, F(0xFF4F9A5A)),
              circle(x - r * 0.28, y - r * 0.42, r * 0.62, F(0xFF67B36E))], f'tree-{i}')


def beacon(x, y, glow, name):
    top = y - 196
    pole = [rect(x - 7, top + 24 + b * 21.5, x + 7, top + 24 + (b + 1) * 21.5, F(0xFF2A2523 if b % 2 == 0 else WHITE)) for b in range(8)]
    return g([g(pole, f'{name}-pole'),
              circle(x, top, 46, F(0xFFFFB13B, 0.35), f'{name}-glow').replace('/>', f' opacity="{glow:.3f}"/>'),
              circle(x, top, 24, F(mix(0xFFC77A1C, 0xFFFFC04D, glow)), f'{name}-globe'),
              circle(x - 7, top - 8, 7, F(WHITE), f'{name}-shine').replace('/>', f' opacity="{0.5 + 0.4 * glow:.3f}"/>')], name)


def zebra():
    d = Doc('set-d60s-zebra', 940, 760, PANEL_NOTE)
    cid = panel_clip(d)
    sky = d.lin(0, -PHH, 0, -10, [0xFF86C3EC, 0xFFC9E5F2, 0xFFF1F4EA], [0, 0.6, 1])
    H, VPX = -20, 40

    def half(y):
        return (y - H) / (PHH + 10 - H) * 820
    els = [rect(-PHW - 12, -PHH - 12, PHW + 12, PHH + 12, FG(sky), id='sky'), rect(-PHW - 12, -150, PHW + 12, H, F(0xFFEADCC2))]
    houses = []
    for h in range(9):
        hx = -PHW + h * 110
        houses.append(rect(hx, -168 + 10 * hash01(h), hx + 104, -150, F(0xFFD9C6A6)))
        win = P()
        for w in range(3):
            for r in range(2):
                win.rect(hx + 14 + w * 30, -132 + r * 46, hx + 32 + w * 30, -104 + r * 46)
        houses.append(path(str(win), F(0xFF7E90A3)))
        houses.append(line(hx + 104, -168, hx + 104, H, 0x22000000, 2))
    els.append(g(houses, 'townhouses'))
    els.append(rect(-PHW - 12, H, PHW + 12, PHH + 12, F(0xFFD3CFC6), id='pavement'))
    road = d.lin(0, -20, 0, PHH, [0xFF8A8D93, 0xFF5E626A])
    els.append(path(str(P().poly([(VPX - 30, H), (VPX + 30, H), (VPX + half(PHH + 10), PHH + 10), (VPX - half(PHH + 10), PHH + 10)])), FG(road), 'road'))
    for s in (-1, 1):
        els.append(line(VPX + s * 30, H, VPX + s * half(PHH + 10), PHH + 10, 0xFFF4F1EA, 6))
    for k in range(5):
        y0 = lerp(H + 6, 140, k / 5); y1 = y0 + lerp(6, 18, k / 5)
        els.append(line(VPX, y0, VPX, y1, 0xCCF4F1EA, lerp(2, 6, k / 5)))
    yf, yn = 152, 268
    bars = []
    for j in range(11):
        u0 = -0.94 + j * 0.176; u1 = u0 + 0.096
        pts = [(VPX + u0 * half(yf), yf), (VPX + u1 * half(yf), yf), (VPX + u1 * half(yn), yn), (VPX + u0 * half(yn), yn)]
        bars.append(path(str(P().poly([(x, y + 4) for x, y in pts])), F(0x33000000)))
        bars.append(path(str(P().poly(pts)), F(0xFFF8F6F0)))
    els.append(g(bars, 'crossing'))
    els.append(g([tree(-420, -36, 74, 0), tree(-290, -28, 46, 1), tree(330, -30, 54, 2), tree(446, -44, 84, 3)], 'trees'))
    els.append(beacon(-372, 176, 0.5, 'beacon-left'))
    els.append(beacon(446, 176, 0.5, 'beacon-right'))
    d.add(g(els, 'set', clip=cid))
    D = TAU / 6.4
    tracks = []
    for name, sgn in (('beacon-left', 1), ('beacon-right', -1)):
        tracks.append(sine_track(f'{name}-glow', 'opacity', 0.5, 0.5 * sgn, 6.4, 0, D))
        tracks.append(sine_track(f'{name}-shine', 'opacity', 0.7, 0.2 * sgn, 6.4, 0, D))
        tracks.append(track(f'{name}-globe', 'fillColor', [[r3(D * i / 8), hexc(mix(0xFFC77A1C, 0xFFFFC04D, 0.5 + 0.5 * sgn * math.sin(TAU * i / 8)))] for i in range(9)]))
    meta = {'origin': [PCX, PCY], 'clips': [clip('belisha-blink', D, tracks, note='the two amber globes blink in antiphase')],
            'inputs': panel_inputs()}
    return 'set-d60s-zebra', d, meta


def shoes():
    d = Doc('set-d60s-shoes', 420, 220, "Walking shoes in Pip's 540x660 box; (0,0) = box point (270,672).")

    def shoe(name):
        return g([path(str(P().rrect(-50, -28, 60, 14, 22)), F(0xFF4A3530)), oval(12, -24, 54, -2, F(0x30FFFFFF)),
                  line(-46, 12, 56, 12, 0xFF2A1E1B, 9)], name, tr(0, 0))

    # Rest pose: progress 0 (feet spread wide, both planted).
    a = shoe('shoe-a').replace('<g id="shoe-a">', f'<g id="shoe-a" transform="{tr(120, 0)}">')
    b = shoe('shoe-b').replace('<g id="shoe-b">', f'<g id="shoe-b" transform="{tr(-120, 0)}">')
    d.add(g([b, a], 'shoes'))
    n = 16
    ta, tb = {'x': [], 'y': [], 'rotation': []}, {'x': [], 'y': [], 'rotation': []}
    order = []
    for i in range(n + 1):
        t = i / n
        ph = t * TAU
        ca, sa = math.cos(ph), math.sin(ph)
        la, lb = max(0, -sa), max(0, sa)
        ta['x'].append([r3(t), r3(120 * ca)]); ta['y'].append([r3(t), r3(-la * 50)]); ta['rotation'].append([r3(t), r3(-la * 16)])
        tb['x'].append([r3(t), r3(-120 * ca)]); tb['y'].append([r3(t), r3(-lb * 50)]); tb['rotation'].append([r3(t), r3(-lb * 16)])
    order = [[0, 'shoe-a above shoe-b'], [0.001, 'shoe-b above shoe-a'], [0.5, 'shoe-a above shoe-b']]
    tracks = [track(f'shoe-a', p, k) for p, k in ta.items()] + [track(f'shoe-b', p, k) for p, k in tb.items()]
    tracks.append({'target': 'shoes', 'property': 'drawOrder', 'keys': order, 'ease': 'hold',
                   'note': 'the lifted shoe draws on top: shoe-b during the first half-cycle, shoe-a during the second'})
    meta = {'origin': 'pip-box (270,672)', 'space': 'pip-box',
            'clips': [clip('stride', 1.0, tracks, note='one full stride cycle = progress 0..2 (the scene runs progress 0..3 over 1.5 s)')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'stride phase: clip time = progress / 2 (mod 1)'}]}
    return 'set-d60s-shoes', d, meta


# ------------------------------------------------------------------ pop-art punch-up

def dots_path(xs, ys, rfn):
    p = P()
    for y in ys:
        for x in xs:
            r = rfn(x, y)
            if r > 0.2:
                p.circle(x, y, r)
    return str(p)


def frange(a, b, s):
    out = []
    v = a
    while v < b:
        out.append(v); v += s
    return out


def comic():
    d = Doc('set-d60s-comic', 940, 760, PANEL_NOTE + ' Dutch angle: the set is rotated -7 deg about (0,60).')
    cid = panel_clip(d)
    rays = []
    for r in range(0, 24, 2):
        p = P().M(0, 0).arc(0, 0, 900, 900, r * 15, 15).Z()
        rays.append(path(str(p), F(0xFFFFDC55)))
    tone = dots_path(frange(-PHW - 80, PHW + 80, 34), frange(-PHH - 80, PHH + 80, 34),
                     lambda x, y: 1.5 + 8 * clamp01((x + y + 700) / 1500))
    floor_dots = dots_path(frange(-PHW - 80, PHW + 80, 30), frange(214, PHH + 100, 30), lambda x, y: 5)
    inner = [rect(-PHW - 90, -PHH - 90, PHW + 90, PHH + 90, F(0xFFFFC531), id='bg'),
             g(rays, 'rays', 'translate(0 -40)'),
             path(tone, F(0x40E8322B), 'halftone'),
             rect(-PHW - 90, 200, PHW + 90, PHH + 120, F(0xFF5E2B8C), id='floor'),
             path(floor_dots, F(0xFF7C45B0), 'floor-dots'),
             line(-PHW - 90, 200, PHW + 90, 200, INK, 8, id='floor-edge')]
    d.add(g([g(inner, 'dutch', 'rotate(-7 0 60)')], 'set', clip=cid))
    meta = {'origin': [PCX, PCY],
            'notes': 'Halftone dots are merged into one compound path per colour (one Rive shape each).',
            'clips': [clip('rays-spin', 5.0, [track('rays', 'rotation', [[0, 0], [5, 30]])],
                           note='6 deg/s; the ray pattern repeats every 30 deg so the loop is seamless')],
            'inputs': panel_inputs()}
    return 'set-d60s-comic', d, meta


def starburst():
    d = Doc('set-d60s-starburst', 940, 760, PANEL_NOTE + ' Wipe centre is local (0,40).')
    cid = panel_clip(d)
    r = 760
    wedges = [path(str(P().M(0, 0).arc(0, 0, r, r, i * 22.5, 22.5).Z()), F(POP_RED if i % 2 == 0 else POP_BLUE)) for i in range(16)]
    hub = [circle(0, 0, r * 0.2, F(POP_YELLOW)), circle(0, 0, r * 0.2, S(INK, 8) + NON_SCALING)]
    d.add(g([g([g(wedges, 'wedges'), g(hub, 'hub')], 'burst', 'translate(0 40)')], clip=cid))
    keys = []
    for i in range(21):
        s = i / 20
        cover = s * 2 if s < 0.5 else (1 - s) * 2
        keys.append([r3(s), r3(ease_out_cubic(cover))])
    meta = {'origin': [PCX, PCY],
            'clips': [clip('spin', 1 / 12, [track('wedges', 'rotation', [[0, 0], [r3(1 / 12), 45]])],
                           note='540 deg/s; two wedges repeat every 45 deg'),
                      clip('cover', 1.0, [track('burst', 'scale', keys)], loop=False,
                           note='time = progress; 0..0.5 covers the set, 0.5..1 uncovers it (radius = 760*easeOutCubic)')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'scrubs the cover clip; hidden at 0 and 1'}],
            'notes': 'Kotlin keeps the hub ring 8 px at every radius; in Rive set the hub stroke to not scale with the transform.'}
    return 'set-d60s-starburst', d, meta


BURSTS = {'pow': ('POW!', POP_YELLOW, POP_RED, POP_RED, 1, (860, 990), -10),
          'bam': ('BAM!', 0xFF4FC3F7, POP_BLUE, WHITE, 2, (720, 900), 12),
          'zap': ('ZAP!', POP_RED, POP_YELLOW, POP_YELLOW, 3, (880, 1080), -6)}


def burst(key):
    word, bg, ring, fg, seed, pos, rot = BURSTS[key]
    name = f'set-d60s-burst-{key}'
    d = Doc(name, 520, 420, f'Comic sound-effect burst "{word}"; (0,0) is the burst centre.')
    n = 13
    pts = []
    for i in range(n * 2):
        rr = 150 + hash01(seed * 31 + i) * 46 if i % 2 == 0 else 104 + hash01(seed * 17 + i) * 16
        a = i * PI / n - PI / 2
        pts.append((math.cos(a) * rr * 1.18, math.sin(a) * rr * 0.86))
    star = str(P().poly(pts))
    word_d = text_d(word, FONT_BLACK, 96, 0, 34, 'center', -0.16)
    d.add(path(star, F(0x553A2A26), 'shadow').replace('<path', '<path transform="translate(12 14)"'),
          path(star, F(ring), 'ring'),
          path(star, F(bg), 'face').replace('<path', '<path transform="scale(0.78)"'),
          path(star, S(INK, 9), 'outline'),
          g([path(word_d, S(INK, 16)), path(word_d, F(fg))], f'word-{key}'))
    ks = lambda vals: [[r3(0.6 * p / 100), v] for p, v in vals]
    meta = {'origin': list(pos), 'rotation': rot,
            'clips': [clip('hit', 0.6, [
                track('root', 'scale', ks([(0, 0.2), (22, 1.12), (40, 0.98), (72, 1), (100, 0.3)]), 'easeOut'),
                track('root', 'rotation', ks([(0, rot - 25), (22, rot + 4), (40, rot), (72, rot), (100, rot + 18)]), 'easeInOut'),
                track('root', 'opacity', ks([(0, 1), (72, 1), (100, 0)]), 'easeIn')], loop=False,
                note='slams in on the punch (overshoot ease 0.1,0.9,0.3,1.4 on the first segment), holds, shrinks away')],
            'notes': 'Word glyphs are outlined (Avenir Black standing in for Roboto Black, skewed -0.16).'}
    return name, d, meta


def banner60():
    d = Doc('set-d60s-banner', 620, 260, 'Decade title "1960s"; (0,0) is the banner centre, drawn tilted -4 deg.')
    cl = d.clip(f'<path d="{P().rrect(-270, -84, 270, 84, 26)}"/>', 'banner-card')
    dots = dots_path(frange(-270, 271, 22), frange(-84, 85, 22), lambda x, y: 1 + 6 * clamp01((x + 270) / 540))
    t1 = text_d('1960s', FONT_BLACK, 132, 8, 54)
    t2 = text_d('1960s', FONT_BLACK, 132, 0, 46)
    card = str(P().rrect(-270, -84, 270, 84, 26))
    d.add(g([path(card, F(INK), 'card-shadow').replace('<path', '<path transform="translate(12 12)"'),
             path(card, F(POP_YELLOW), 'card'),
             g([path(dots, F(0x40E8322B))], 'halftone', clip=cl),
             path(card, S(INK, 7), 'card-outline'),
             g([path(t1, F(INK)), path(t2, S(INK, 13)), path(t2, F(POP_RED))], 'title')], 'banner', 'rotate(-4)'))
    return 'set-d60s-banner', d, banner_meta()


def banner_meta():
    k = lambda D, t0, pts: [[r3(t0 + D * p / 100), v] for p, v in pts]
    return {'origin': [540, 670],
            'clips': [clip('drop-in', 0.55, [
                track('root', 'scale', k(0.55, 0, [(0, 0.4), (55, 1.08), (100, 1)]), 'outBack'),
                track('root', 'rotation', k(0.55, 0, [(0, -14), (55, 3), (100, 0)]), 'easeInOut'),
                track('root', 'y', k(0.55, 0, [(0, -40), (100, 0)]), 'outBack'),
                track('root', 'opacity', k(0.55, 0, [(0, 0), (25, 1), (100, 1)]))], loop=False),
                clip('hold', 0.55, [track('root', 'y', [[0, 0], [0.55, -6]], 'easeInOut'),
                                    track('root', 'rotation', [[0, 0], [0.55, -1.5]], 'easeInOut')], loop=False),
                clip('lift-out', 0.3, [track('root', 'y', [[0, -6], [0.3, -100]], 'inBack'),
                                       track('root', 'opacity', [[0, 1], [0.3, 0]], 'inBack'),
                                       track('root', 'scale', [[0, 1], [0.3, 0.85]], 'inBack')], loop=False)],
            'notes': 'Scene plays drop-in at 0.05 s, hold at 0.6 s, lift-out at 1.15 s. y is relative to the origin.'}


PIECES = [frame, moon, flag, helmet, zebra, shoes, comic, starburst, lambda: burst('pow'), lambda: burst('bam'),
          lambda: burst('zap'), banner60]
