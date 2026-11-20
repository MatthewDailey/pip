"""1970s pieces (Scenes6070.kt)."""
from lib import *
from p60 import PHW, PHH, PR, PCX, PCY, PANEL_NOTE, panel_clip, panel_inputs, glint_d, banner_meta

WATER_Y = 120
DISCO = [0xFFFF4FA3, 0xFF36D6E7, 0xFFFFD84D, 0xFFFF8A3D, 0xFFA66BFF]


def gull(name, x, y, half=16, w=3, c=0xB33A2A26, ctrl=8):
    """A gull as two wings hinged at the body, so a flap is a pair of rotations."""
    lw = path(str(P().M(-half, 0).Q(-half / 2, -ctrl, 0, 0)), S(c, w))
    rw = path(str(P().M(0, 0).Q(half / 2, -ctrl, half, 0)), S(c, w))
    return g([g([lw], f'{name}-wing-l'), g([rw], f'{name}-wing-r')], name, tr(x, y))


def flap_tracks(name, amp_deg, w, phase, D):
    return [sine_track(f'{name}-wing-l', 'rotation', 0, amp_deg, w, phase, D),
            sine_track(f'{name}-wing-r', 'rotation', 0, -amp_deg, w, phase, D)]


def sun_slices(cx, cy, r, cuts, y_top, y_bot):
    """Horizontal slices of a disc between y_top and y_bot, leaving gaps at the cut bands."""
    bands = []
    y = y_top
    for a, b in sorted(cuts):
        if a > y:
            bands.append((y, a))
        y = max(y, b)
    if y < y_bot:
        bands.append((y, y_bot))
    p = P()
    for a, b in bands:
        a = max(a, cy - r)
        if b <= a:
            continue
        steps = max(2, int((b - a) / 6))
        left, right = [], []
        for i in range(steps + 1):
            yy = a + (b - a) * i / steps
            hw = math.sqrt(max(0, r * r - (yy - cy) ** 2))
            left.append((cx - hw, yy)); right.append((cx + hw, yy))
        p.poly(right + left[::-1])
    return str(p)


def sea():
    d = Doc('set-d70s-sea', 940, 760, PANEL_NOTE + ' The sea set: water line local y=120 (Pip waist deep).')
    cid = panel_clip(d)
    sky = d.lin(0, -PHH, 0, -10, [0xFFE0705A, 0xFFF39F5E, 0xFFFFE1A6], [0, 0.5, 1])
    sun = d.lin(0, -140, 0, -10, [0xFFFFE27C, 0xFFFF5E3A])
    water = d.lin(0, -10, 0, PHH, [0xFF6CC3D6, 0xFF2B88AE, 0xFF0D466E], [0, 0.35, 1])
    cuts = [(-66 + gg * 15, -66 + gg * 15 + 3 + gg * 2.4) for gg in range(4)]
    els = [rect(-PHW - 12, -PHH - 12, PHW + 12, PHH + 12, FG(sky), id='sky'),
           path(sun_slices(0, -10, 130, cuts, -140, -10), FG(sun), 'sun')]
    D = 20.0
    tracks = []
    for k in range(2):
        gx = -260 + k * 90 + math.sin(k) * 20
        gy = -230 + k * 34 + math.sin(k) * 6
        els.append(gull(f'gull-{k}', gx, gy))
        tracks += flap_tracks(f'gull-{k}', 25, 7, 2 * k + PI, D)
    els.append(rect(-PHW - 12, -10, PHW + 12, PHH + 12, FG(water), id='water'))
    refl = []
    for i in range(7):
        y = -2 + i * 9
        wb = 120 - i * 13
        refl.append(g([path(str(P().rrect(-wb, 0, wb, 3.5, 2)), F(0x99FFD27A))], f'glow-{i}', tr(0, y, 0, (wb + 10 * math.sin(i)) / wb, 1)))
        tracks.append(sine_track(f'glow-{i}', 'scaleX', 1, 10 / wb, 3, i, D))
    els.append(g(refl, 'sun-path'))
    spk = []
    for i in range(30):
        dd = hash01(i * 3 + 7)
        y = 2 + dd * dd * 380
        w = 8 + dd * 56
        x = -PHW + fract(hash01(i * 5)) * 2 * PHW
        a0 = 0.25 + 0.2 * math.sin(i)
        el = [line(-w + o, 0, w + o, 0, WHITE, 2 + dd * 3) for o in (-2 * PHW, 0, 2 * PHW)]
        spk.append(g(el, f'sparkle-{i}', tr(x, y), opacity=a0))
        tracks.append(sine_track(f'sparkle-{i}', 'opacity', 0.25, 0.2, 2, i, D))
        speed = 0.02 * (1 + dd) * 2 * PHW
        tracks.append(track(f'sparkle-{i}', 'x', wrap_keys(x, speed, -PHW, PHW, D)))
    els.append(g(spk, 'sparkles'))
    buoy = [path(str(P().poly([(-16, 0), (-8, -40), (8, -40), (16, 0)])), F(0xFFE8452E)), rect(-12, -24, 12, -16, F(WHITE)),
            circle(0, -46, 5, F(0xFFFFE27C))]
    els.append(g(buoy, 'buoy', tr(360, 12)))
    tracks.append(sine_track('buoy', 'y', 12, 4, 2.2, 0, D))
    tracks.append(sine_track('buoy', 'rotation', 0, 6, 1.7, 0, D))
    d.add(g(els, 'set', clip=cid))
    meta = {'origin': [PCX, PCY],
            'notes': 'Sparkle dashes scroll right and wrap: each is drawn twice one panel-width apart and its x is '
                     'keyed over whole panel widths (rate quantised so the 20 s loop closes; the host or a modulo '
                     'wraps x back after each 940 px). Gull flaps are wing rotations; their slow drift (16 px/s) is '
                     'the scene\'s job. The striped retro sun is pre-cut into slices.',
            'clips': [clip('ambient', D, tracks)], 'inputs': panel_inputs()}
    return 'set-d70s-sea', d, meta


def sea_front():
    d = Doc('set-d70s-sea-front', 940, 760, PANEL_NOTE + ' Water in front of Pip; scrolls left to make the swell travel.')
    cid = panel_clip(d)
    water = d.lin(0, -10, 0, PHH, [0xFF6CC3D6, 0xFF2B88AE, 0xFF0D466E], [0, 0.35, 1])
    lam = TAU / 0.018

    def wy(x):
        return WATER_Y + 5 * math.sin(x * 0.018) + 3 * math.sin(x * 0.036 + 0.6)
    xs = []
    x = -PHW - 12
    while x < PHW + 12 + lam + 16:
        xs.append(x); x += 16
    body = P().M(xs[0], PHH + 12)
    for x in xs:
        body.L(x, wy(x))
    body.L(xs[-1], PHH + 12).Z()
    crest = P().M(xs[0], wy(xs[0]))
    for x in xs[1:]:
        crest.L(x, wy(x))
    d.add(g([g([path(str(body), FG(water)), path(str(crest), S(0x26FFFFFF, 18)).replace('<path', '<path transform="translate(0 12)"'),
                path(str(crest), S(0xDDFFFFFF, 6))], 'waves')], 'set', clip=cid))
    D = lam / (2.6 / 0.018)
    meta = {'origin': [PCX, PCY],
            'notes': 'Kotlin sums two travelling sines; here the swell is one periodic profile (second harmonic '
                     'snapped to twice the first) that scrolls left one wavelength per loop.',
            'clips': [clip('swell', D, [track('waves', 'x', [[0, 0], [r3(D), r3(-lam)]])])],
            'inputs': panel_inputs()}
    return 'set-d70s-sea-front', d, meta


def fin():
    d = Doc('set-d70s-fin', 340, 280, "Pip's hat tip as a shark fin; (0,0) is the waterline at the fin's foot.")
    cl = d.clip('<rect x="-300" y="-300" width="600" height="302"/>', 'above-water')
    body = P().M(58, 6).C(44, -50, 4, -112, -54, -128).C(-32, -92, -32, -40, -50, 6).Z()
    shade = P().M(-50, 6).C(-32, -40, -32, -92, -54, -128).C(-20, -100, -6, -50, -14, 6).Z()
    shine = P().M(44, -18).C(32, -60, 6, -100, -36, -118)
    wake = []
    for i in range(4):
        back = -(46 + i * 40)
        w = 26 - i * 4
        wake.append(g([ellipse(0, 2, w, 6, F(with_alpha(WHITE, 0.8 - i * 0.18)))], f'wake-{i}', tr(back, 0, 0, (w + 3 * math.sin(i)) / w, 1)))
    d.add(ellipse(0, 27, 100, 19, F(0x40062A44), 'shadow'),
          g([g(wake, 'wake'), g([path(str(body), F(HAT_RED)), path(str(shade), F(darken(HAT_RED, 0.8))), path(str(shine), S(0x66FFFFFF, 6))], 'fin', clip=cl)], 'heading'),
          line(-64, 2, 64, 2, 0xDDFFFFFF, 5, id='waterline'))
    D = TAU / 9
    tracks = [sine_track(f'wake-{i}', 'scaleX', 1, 3 / (26 - 4 * i), 9, i, D) for i in range(4)]
    meta = {'origin': 'scene: starts (200, 1270), cruises to x 850, turns, charges to 545 while growing to 1.25x',
            'clips': [clip('wake', D, tracks)],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'heading: scaleX of "heading" (1 = swimming right, -1 = left); the wake trails behind'}]}
    return 'set-d70s-fin', d, meta


def splash():
    d = Doc('set-d70s-splash', 700, 560, 'Dive/surface splash; (0,0) is the water surface point. Drawn at p = 0.35.')
    p0 = 0.35
    e0 = ease_out_cubic(p0)
    els = [g([ellipse(0, 0, 150, 16, S(WHITE, 6) + NON_SCALING)], 'ring', tr(0, 0, 0, e0), opacity=1 - p0)]
    tracks = []
    N = 14
    ts = [0.7 * i / N for i in range(N + 1)]
    ring_s, ring_o = [], []
    for t in ts:
        p = t / 0.7
        ring_s.append([r3(t), r3(ease_out_cubic(p))]); ring_o.append([r3(t), r3(1 - p if 0 < p < 1 else 0)])
    tracks += [track('ring', 'scale', ring_s), track('ring', 'opacity', ring_o)]
    for i in range(16):
        ang = (lerp(-165, -15, i / 15) + (hash01(i) - 0.5) * 14) * DEG
        v = 300 + hash01(i * 3) * 260
        r0 = 8 + hash01(i * 7) * 8
        col = 0xFFBFEAF5 if i % 3 == 0 else WHITE

        def st(p):
            return math.cos(ang) * v * p * 0.9, math.sin(ang) * v * p + 820 * p * p
        x, y = st(p0)
        vis = y < 6
        els.append(g([circle(0, 0, r0, F(col))], f'drop-{i}', tr(x, y, 0, 1 - 0.5 * p0), opacity=(1 - p0 * p0) if vis else 0))
        kx, ky, ks, ko = [], [], [], []
        for t in ts:
            p = t / 0.7
            x, y = st(p)
            kx.append([r3(t), r3(x)]); ky.append([r3(t), r3(y)]); ks.append([r3(t), r3(1 - 0.5 * p)])
            ko.append([r3(t), r3((1 - p * p) if (y < 6 and 0 < p < 1) else 0)])
        tracks += [track(f'drop-{i}', 'x', kx), track(f'drop-{i}', 'y', ky), track(f'drop-{i}', 'scale', ks), track(f'drop-{i}', 'opacity', ko)]
    d.add(*els)
    meta = {'origin': 'scene: (540, 1270) for the dive, (545, 1270) for the surfacing',
            'clips': [clip('splash', 0.7, tracks, loop=False, note='time = progress * 0.7; droplets fly ballistically and vanish when they fall back below the surface')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'scrubs the splash clip once (0..1)'}],
            'notes': 'The ring stroke should not scale with the transform (Kotlin keeps it 6 px).'}
    return 'set-d70s-splash', d, meta


def disco():
    d = Doc('set-d70s-disco', 940, 760, PANEL_NOTE + ' Mirror ball at local (0,-290).')
    cid = panel_clip(d)
    wall = d.lin(0, -PHH, 0, 110, [0xFF12061F, 0xFF2E0F4A, 0xFF5A217C], [0, 0.6, 1])
    els = [rect(-PHW - 12, -PHH - 12, PHW + 12, PHH + 12, FG(wall), id='wall')]
    tracks = []
    Dt = PI / 2
    stars = []
    for i in range(40):
        x = -PHW + hash01(i * 7 + 3) * 2 * PHW
        y = -PHH + hash01(i * 11 + 1) * 470
        tw = lambda t, i=i: 0.5 + 0.5 * math.sin(4 * t + i * 1.7)
        stars.append(g([circle(0, 0, 3.5, F(WHITE))], f'star-{i}', tr(x, y, 0, (1.5 + 2 * tw(0) ** 2) / 3.5), opacity=0.2 + 0.6 * tw(0) ** 3))
        tracks.append(track(f'star-{i}', 'opacity', sampled(lambda t, tw=tw: 0.2 + 0.6 * tw(t) ** 3, Dt, 12)))
        tracks.append(track(f'star-{i}', 'scale', sampled(lambda t, tw=tw: (1.5 + 2 * tw(t) ** 2) / 3.5, Dt, 12)))
    els.append(g(stars, 'stars'))
    Db = 360 / 38
    beams = []
    for b in range(5):
        a0 = 72 * b + math.degrees(0.4 * math.sin(b))
        beams.append(g([path(str(P().poly([(0, 0), (900, 70), (900, -70)])), F(with_alpha(DISCO[b], 0.16)))], f'beam-{b}', tr(0, -290, a0)))
        w2 = TAU / Db
        tracks.append(track(f'beam-{b}', 'rotation', sampled(lambda t, b=b: 38 * t + 72 * b + math.degrees(0.4 * math.sin(w2 * t + b)), Db, 38)))
    els.append(g(beams, 'beams'))
    rows = [110, 148, 196, 258, 336, 430]
    half = lambda y: 330 + (y - 110) / 320 * 560
    tiles = []
    Df = 5.0
    for r in range(5):
        for col in range(9):
            y0, y1 = rows[r], rows[r + 1]
            xa = (col - 4.5) / 4.5; xb = (col - 3.5) / 4.5
            gap = 0.06
            lx0 = lerp(xa, xb, gap) * half(y0); rx0 = lerp(xb, xa, gap) * half(y0)
            lx1 = lerp(xa, xb, gap) * half(y1); rx1 = lerp(xb, xa, gap) * half(y1)
            ins = (y1 - y0) * gap
            pp = str(P().poly([(lx0, y0 + ins), (rx0, y0 + ins), (rx1, y1 - ins), (lx1, y1 - ins)]))

            def state(beat, pulse):
                c = DISCO[(r * 3 + col + beat) % 5]
                lit = hash01(r * 31 + col * 7 + beat * 13) > 0.48
                return (mix(darken(c, 0.4), lighten(c, 0.25), pulse) if lit else darken(c, 0.2)), lit
            c0, lit0 = state(0, 1)
            tid = f'tile-{r}-{col}'
            tiles.append(path(pp, F(c0), tid))
            tiles.append(path(pp, S(WHITE, 2, op=0.35), f'{tid}-edge').replace('/>', f' opacity="{1 if lit0 else 0}"/>'))
            ck, ek = [], []
            for beat in range(10):
                ts, te = beat * 0.5, beat * 0.5 + 0.495
                cs, lit = state(beat, 1)
                ce, _ = state(beat, 0.45 + 0.55 * 0.01)
                ck += [[r3(ts), hexc(cs)], [r3(te), hexc(ce)]]
                ek += [[r3(ts), 1 if lit else 0], [r3(te), 0.46 if lit else 0]]
            ck.append([Df, ck[0][1]]); ek.append([Df, ek[0][1]])
            tracks.append(track(tid, 'fillColor', ck))
            tracks.append(track(f'{tid}-edge', 'opacity', ek))
    els.append(g(tiles, 'floor'))
    # Mirror ball.
    els.append(line(0, -PHH - 10, 0, -346, 0xFF9A9AB0, 3, id='ball-string'))
    bcl = d.clip('<circle cx="0" cy="-290" r="56"/>', 'ball-disc')
    tl = []
    for row in range(-5, 5):
        for j in range(-18, 6):
            pidx = ((j + 6) % 12) - 6
            x = j * 12; y = -290 + row * 12
            c = mix(0xFF5F6480, 0xFFF2F5FF, hash01((row + 9) * 19 + (pidx + 40) * 7))
            tl.append(rect(x + 1, y + 1, x + 11, y + 11, F(c)))
    shade = d.rad(-14, -304, 64, [0x00000000, 0x00000000, 0x99000000], [0, 0.55, 1])
    els.append(g([circle(0, -290, 56, F(0xFF8D93A8)), g(tl, 'ball-tiles'), circle(0, -290, 56, FG(shade))], 'ball', clip=bcl))
    Dball = 12 / 0.9
    gl = []
    for k in range(3):
        s0 = 0.5 + 0.5 * math.sin(k * 2.1)
        gl.append(g([path(glint_d(0, 0, 16), F(WHITE))], f'ball-glint-{k}', tr(-24 + k * 22, -312 + k * 14, 0, s0)))
    els.append(g(gl, 'ball-glints'))
    d.add(g(els, 'set', clip=cid))
    meta = {'origin': [PCX, PCY],
            'clips': [clip('twinkle', Dt, tracks[:80]),
                      clip('beams', Db, [t for t in tracks if t['target'].startswith('beam-')]),
                      clip('floor-beat', Df, [t for t in tracks if t['target'].startswith('tile-')],
                           note='2 beats/s: each tile jumps to a new colour on the beat and lit tiles decay toward dark until the next; '
                                'Kotlin\'s pattern never repeats, this one repeats every 10 beats'),
                      clip('ball-spin', Dball, [track('ball-tiles', 'x', [[0, 0], [r3(Dball), 144]])] +
                           [sine_track(f'ball-glint-{k}', 'scale', 0.5, 0.5, 5, k * 2.1, Dball) for k in range(3)],
                           note='tile shading repeats every 12 columns, so sliding 144 px loops seamlessly')],
            'inputs': panel_inputs()}
    return 'set-d70s-disco', d, meta


def steps():
    d = Doc('set-d70s-steps', 940, 760, PANEL_NOTE + ' Dawn over the city from the top of the museum steps.')
    cid = panel_clip(d)
    sky = d.lin(0, -PHH, 0, 150, [0xFF4E63B0, 0xFFB98AB6, 0xFFFFB27E, 0xFFFFD9A3], [0, 0.45, 0.8, 1])
    glow = d.rad(0, 0, 380, [0xAAFFE6A8, 0x44FFC37A, 0x00FFB070], [0, 0.4, 1])
    rays = [path(str(P().M(0, 0).arc(0, 0, 950, 950, i * 25.7, 7).Z()), F(0xFFFFF0C8, 0.24)) for i in range(14)]
    els = [rect(-PHW - 12, -PHH - 12, PHW + 12, PHH + 12, FG(sky), id='sky'),
           g(rays, 'rays', 'translate(0 70)', opacity=0.1 / 0.24),
           g([circle(0, 0, 380, FG(glow))], 'sun-glow', 'translate(0 70)'),
           circle(0, 70, 98, F(0xFFFFE9B0), 'sun')]

    def towers(t, color, windows, name):
        out = []
        win = P()
        for i in range(0, len(t), 3):
            x, w, h = t[i:i + 3]
            out.append(rect(x, 160 - h, x + w, 162, F(color)))
            if windows:
                wy = 160 - h + 12
                while wy < 150:
                    wx = x + 8
                    while wx < x + w - 10:
                        if hash01(int(wx + wy * 3)) > 0.55:
                            win.rect(wx, wy, wx + 6, wy + 8)
                        wx += 14
                    wy += 18
        if windows:
            out.append(path(str(win), F(0xCCFFE3A0)))
        return g(out, name)
    els.append(towers([-470, 60, 90, -400, 44, 140, -340, 70, 70, -260, 50, 170, 200, 60, 120, 270, 46, 190, 330, 70, 100, 410, 60, 150], 0xFFC09AB8, False, 'towers-far'))
    els.append(towers([-460, 80, 60, -370, 60, 96, -300, 90, 50, 230, 80, 70, 320, 56, 120, 390, 90, 66], 0xFF8C6F9F, True, 'towers-near'))
    for x, nm in ((-405, 'column-left'), (405, 'column-right')):
        els.append(g([rect(x - 40, -70, x + 40, 160, F(0xFFE6D2BC))] + [line(x + f * 14, -60, x + f * 14, 150, 0x1F000000, 4) for f in range(-2, 3)] +
                     [rect(x - 54, -96, x + 54, -70, F(0xFFF1E1CE)), rect(x - 50, 146, x + 50, 162, F(0xFFD9C2A8))], nm))
    st = [rect(-PHW - 12, 160, PHW + 12, 224, F(0xFFEAD3BA)), line(-PHW - 12, 161, PHW + 12, 161, 0xFFF8E9D6, 4)]
    y = 224
    for h in (24, 30, 38, 48, 62):
        st += [rect(-PHW - 12, y, PHW + 12, y + h * 0.42, F(0xFFC6A487)), rect(-PHW - 12, y + h * 0.42, PHW + 12, y + h, F(0xFFE3C7AA)),
               line(-PHW - 12, y + h * 0.42, PHW + 12, y + h * 0.42, 0xFFF4E1CB, 3)]
        y += h
    els.append(g(st, 'steps'))
    d.add(g(els, 'set', clip=cid))
    D = 25.7 / 6
    meta = {'origin': [PCX, PCY],
            'clips': [clip('rays-turn', D, [track('rays', 'rotation', [[0, 0], [r3(D), 25.7]])], note='6 deg/s; rays repeat every 25.7 deg'),
                      clip('bloom', 0.8, [track('rays', 'opacity', [[0, r3(0.1 / 0.24)], [0.8, 1]], 'easeInOut'),
                                          track('sun-glow', 'scale', [[0, 1], [0.8, 1.35]], 'easeInOut')], loop=False,
                           note='plays at set time 6.4 s (the arms-up beat): rays brighten, the sun glow swells')],
            'inputs': panel_inputs()}
    return 'set-d70s-steps', d, meta


def banner70():
    d = Doc('set-d70s-banner', 560, 300, 'Decade title "1970s"; (0,0) is the title anchor (text baseline at y=52).')
    cols = [0xFF7A3B22, 0xFFD9661F, 0xFFF29B2E, 0xFFF6C744]
    layers = []
    for i in range(4):
        o = (4 - i) * 7
        layers.append(path(text_d('1970s', FONT_BLACK_IT, 150, o, 52 + o, 'center', -0.1), F(cols[i])))
    t = text_d('1970s', FONT_BLACK_IT, 150, 0, 52, 'center', -0.1)
    arcs = [path(str(P().arc(0, 128 + 13 * i, 230, 32, 200, 140, move=True)), S(cols[i + 1], 8)) for i in range(3)]
    d.add(g(layers, 'title-stack'), g([path(t, S(INK, 10)), path(t, F(0xFFFFF3DC))], 'title'), g(arcs, 'rainbow'))
    return 'set-d70s-banner', d, banner_meta()


PIECES = [sea, sea_front, fin, splash, disco, steps, banner70]
