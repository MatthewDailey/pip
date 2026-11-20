"""1990s pieces (Scenes8090.kt, plus Pip's pixel sunglasses from PipRenderer.kt)."""
from lib import *
from p80 import HW, HH, PANEL_NOTE, panel_shadow, panel_clip, panel_border, whip_meta
from p70 import gull, flap_tracks

HORIZON = 60


def banner90():
    d = Doc('set-d90s-banner', 760, 320, 'Memphis-style "1990s" title; (0,0) is the title anchor at screen (540,400).')
    fill = d.lin(0, -80, 0, 50, [0xFFFFF27A, 0xFFFFC21F])
    zig = P().M(-340, 84)
    for i in range(1, 8):
        zig.L(-340 + i * 26, 84 + (0 if i % 2 == 0 else -22))
    sq = P().M(-150, 106)
    for i in range(1, 7):
        sq.Q(-150 + i * 52 - 26, 106 + (16 if i % 2 == 0 else -16), -150 + i * 52, 106)
    tri = str(P().poly([(0, -46), (40, 24), (-40, 24)]))
    t = text_d('1990s', FONT_BOLD, 160, 0, 50, 'center', -0.22)
    d.add(g([circle(-215, -20, 96, F(0xFF19C3B4)), circle(-215, -20, 96, S(INK, 5))], 'circle'),
          g([path(tri, F(0xFF8E5CF7)), path(tri, S(INK, 5))], 'triangle', tr(262, -78)),
          path(str(zig), S(0xFFFF3E8E, 11), 'zigzag'),
          g([circle(196 + gx * 22, 50 + gy * 20, 4.5, F(INK)) for gx in range(4) for gy in range(3)], 'dots'),
          path(text_d('1990s', FONT_BOLD, 160, 12, 62, 'center', -0.22), F(0xFFFF3E8E), 'title-block'),
          g([path(t, S(INK, 16)), path(t, FG(fill))], 'title'),
          path(str(sq), S(0xFF19C3B4, 9), 'squiggle'))
    k = lambda pts: [[r3(1.4 * p / 100), v] for p, v in pts]
    meta = {'origin': [540, 400],
            'clips': [clip('triangle-spin', 3.0, [track('triangle', 'rotation', [[0, 0], [3, 120]])], note='40 deg/s; 3-fold symmetric'),
                      clip('pop', 1.4, [track('root', 'scale', k([(0, 0.3), (22, 1), (80, 1), (100, 0.4)]), 'easeInOut'),
                                        track('root', 'rotation', k([(0, -12), (22, 3), (40, -2), (60, 1), (80, 0), (100, 8)]), 'easeInOut'),
                                        track('root', 'opacity', k([(0, 1), (80, 1), (100, 0)]), 'inBack')], loop=False)],
            'notes': 'Lettering outlined from Helvetica Neue Bold (Roboto Bold in Kotlin), skewed -0.22.'}
    return 'set-d90s-banner', d, meta


def mirror_back():
    d = Doc('set-d90s-mirror-back', 700, 800, 'Vanity mirror glass behind Pip; (0,0) at screen (540,1150).')
    glass = d.lin(0, -330, 0, 212, [0xFFEAF3F6, 0xFFC9DCE3, 0xFFB2C8D1])
    wash = d.rad(0, -360, 300, [0x66FFF1C4, 0x00FFF1C4])
    cl = d.clip(f'<path d="{P().rrect(-262, -332, 262, 212, 30)}"/>', 'glass-clip')
    tiles = []
    gx = -262
    while gx < 262:
        tiles.append(line(gx, -332, gx, 212, 0x1F6E8A96, 3)); gx += 88
    gy = -332
    while gy < 212:
        tiles.append(line(-262, gy, 262, gy, 0x1F6E8A96, 3)); gy += 88
    d.add(g(soft_rrect(-290, -360, 290, 250, 40, 0x4D3A2A26, 30), 'shadow'),
          path(str(P().rrect(-262, -332, 262, 212, 30)), FG(glass), 'glass'),
          g([g(tiles, 'tiles'), circle(0, -360, 300, FG(wash), 'bulb-wash')], clip=cl))
    return 'set-d90s-mirror-back', d, mirror_meta()


def mirror_meta(extra=None):
    m = {'origin': [540, 1150],
         'clips': [clip('pop-in', 0.45, [track('root', 'scale', [[0, 0.86], [0.45, 1]], 'outBack'), track('root', 'opacity', [[0, 0], [0.25, 1]], 'easeOut')], loop=False),
                   clip('rattle', 0.08, [track('root', 'rotation', [[0, 0], [0.02, -1.4], [0.06, 1.4], [0.08, 0]])],
                        note='the scream shakes the mirror (scene loops it 9 times)'),
                   clip('pop-out', 0.28, [track('root', 'scale', [[0, 1], [0.28, 0.72]], 'inBack'), track('root', 'opacity', [[0, 1], [0.28, 0]], 'easeIn')], loop=False)]}
    if extra:
        m.update(extra)
    return m


def mirror_front():
    d = Doc('set-d90s-mirror-front', 760, 860, 'Vanity mirror frame, bulbs, sheen and counter in front of Pip; (0,0) at screen (540,1150).')
    chrome = d.lin(-262, -332, 262, 212, [0xFFF4F6F8, 0xFF9AA6AE, 0xFFE6ECF0, 0xFF8C98A0])
    marble = d.lin(0, 196, 0, 262, [0xFFFBF8F4, 0xFFE6DDD4])
    cl = d.clip(f'<path d="{P().rrect(-262, -332, 262, 212, 30)}"/>', 'glass-clip')
    sheen = [path(str(P().poly([(-40, 240), (40, 240), (220, -360), (140, -360)])), F(0x47FFFFFF)),
             path(str(P().poly([(70, 240), (96, 240), (276, -360), (250, -360)])), F(0x2EFFFFFF))]
    rr = str(P().rrect(-262, -332, 262, 212, 30))
    bulbs = []
    for i in range(5):
        bx = -180 + i * 90
        bulbs.append(g([glow_circle(d, bx, -362, 36, 0x66FFE9A8, 16), circle(bx, -362, 19, F(0xFFFFF6D6)), circle(bx, -362, 19, S(0xFFE2B85C, 3)),
                        circle(bx - 6, -368, 5, F(WHITE))], f'bulb-{i}'))
    faucet = str(P().M(-200, 196).L(-200, 152).Q(-200, 136, -182, 136).L(-150, 136).L(-150, 156))
    counter = str(P().rrect(-340, 196, 340, 262, 14))
    d.add(g([g(sheen, 'sheen')], clip=cl),
          path(rr, SG(chrome, 26), 'chrome'),
          path(str(P().rrect(-275, -345, 275, 225, 42)), S(INK, 5), 'frame-outline'),
          path(str(P().rrect(-249, -319, 249, 199, 18)), S(0x803A2A26, 3), 'frame-inner'),
          g([path(str(P().rrect(-240, -378, 240, -340, 19)), FG(chrome)), path(str(P().rrect(-240, -378, 240, -340, 19)), S(INK, 4))], 'bulb-bar'),
          g(bulbs, 'bulbs'),
          g(soft_rrect(-340, 204, 340, 276, 16, 0x403A2A26, 16), 'counter-shadow'),
          g([path(faucet, S(0xFF9AA6AE, 14)), path(faucet, S(0xFFE6ECF0, 5))], 'faucet'),
          g([path(counter, FG(marble)), line(-326, 200, 326, 200, 0xCCFFFFFF, 3), line(-260, 228, -150, 246, 0x26A0968C, 2),
             line(80, 214, 220, 240, 0x26A0968C, 2), path(counter, S(INK, 5))], 'counter'))
    extra = {'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'sheen sweep: sheen x = -760 + 1520*progress (drawn here at 0.5)'}],
             'notes': 'Chrome frame is a stroke with a diagonal linear gradient; bulb glows are radial gradients.'}
    m = mirror_meta(extra)
    m['clips'].append(clip('sheen', 1.0, [track('sheen', 'x', [[0, -760], [1, 760]])], loop=False, note='time = progress; the scene sweeps it twice'))
    return 'set-d90s-mirror-front', d, m


def scream():
    d = Doc('set-d90s-scream', 760, 560, 'Scream shock lines around Pip; (0,0) at screen (548,1185).')
    rays, tracks = [], []
    D = TAU / 50 * 5
    for i in range(13):
        a0 = -200 + 220 * i / 12
        r0 = 236
        ln_ = 54 + 26 * hash01(i)
        els = [g([line(0, 0, ln_, 0, INK, 9)] + ([circle(ln_ + 22, 0, 6, F(0xFFFFC21F))] if i % 3 == 1 else []), f'ray-{i}-len', tr(r0 + 10 * math.sin(i * 1.3), 0))]
        rays.append(g(els, f'ray-{i}', tr(0, 0, a0 + 2 * math.sin(i))))
        tracks.append(sine_track(f'ray-{i}', 'rotation', a0, 2, 50, i, D))
        tracks.append(sine_track(f'ray-{i}-len', 'x', r0, 10, 40, i * 1.3, D))
    d.add(g(rays, 'rays'))
    meta = {'origin': [548, 1185],
            'clips': [clip('jitter', r3(D), tracks),
                      clip('show', 0.85, [track('root', 'opacity', [[0, 0], [0.1275, 1], [0.68, 1], [0.85, 0]]),
                                          track('root', 'scale', [[0, 0.7], [0.1275, 1], [0.68, 1], [0.85, 1.1]])], loop=False)]}
    return 'set-d90s-scream', d, meta


def sun_disc_d(cx, cy, r, cut):
    dy = cut - cy
    hw = math.sqrt(r * r - dy * dy)
    a0 = math.degrees(math.atan2(dy, -hw)) % 360
    a1 = math.degrees(math.atan2(dy, hw)) % 360
    sweep = (a1 - a0) % 360
    return str(P().arc(cx, cy, r, r, a0, sweep, move=True).Z())


def sunset():
    d = Doc('set-d90s-sunset', 1060, 960, PANEL_NOTE + ' Horizon at local y=60.')
    cid = panel_clip(d)
    sky = d.lin(0, -HH, 0, HORIZON, [0xFF3A2B6E, 0xFF8E4C8A, 0xFFF2677A, 0xFFFFB060])
    sea = d.lin(0, HORIZON, 0, HH, [0xFFF08A5D, 0xFF7A3F7A, 0xFF2B1F4E])
    glow = d.rad(-170, HORIZON, 340, [0xAAFFE29A, 0x33FFB060, 0x00FFB060])
    disc = d.lin(0, HORIZON - 150, 0, HORIZON, [0xFFFFF3B0, 0xFFFF8A4C])
    els = [rect(-HW, -HH, HW, HORIZON, FG(sky), id='sky'), circle(-170, HORIZON, 340, FG(glow), 'sun-glow'),
           path(sun_disc_d(-170, HORIZON + 10, 150, HORIZON), FG(disc), 'sun')]
    tracks = []
    D = 20.0
    cl = [-430, -250, -110, -230, 40, -312, 420, -292, -70, -150, 250, -136]
    for i in range(0, len(cl), 4):
        x0, y0, x1, y1 = cl[i:i + 4]
        dr = 10 * math.sin(i)
        els.append(g([path(str(P().rrect(x0, y0, x1, y1, 12)), F(0xB3F2A9CF)),
                      path(str(P().rrect(x0 + 18, y0 + 8, x1 + 18, y0 + 14, 4)), F(0x668E4C8A))], f'cloud-{i // 4}', tr(dr, 0)))
        tracks.append(sine_track(f'cloud-{i // 4}', 'x', 0, 10, 0.4, i, D))
    els.append(rect(-HW, HORIZON, HW, HH, FG(sea), id='sea'))
    refl = []
    for k in range(26):
        y = HORIZON + 8 + k * k * 0.55
        l0 = 150 - k * 3.5
        c = with_alpha(0xFFFFE6A8, 0.85 * (1 - k / 28))
        refl.append(g([line(-l0 / 2, 0, l0 / 2, 0, c, 4 + k * 0.2)], f'glint-{k}',
                      tr(-170 + 30 * math.sin(k), y, 0, 0.75 + 0.25 * math.sin(k * 1.7), 1)))
        tracks.append(sine_track(f'glint-{k}', 'scaleX', 0.75, 0.25, 3, k * 1.7, D))
        tracks.append(sine_track(f'glint-{k}', 'x', -170, 30, 1.3, k, D))
    els.append(g(refl, 'sun-path'))
    els.append(line(-HW, HORIZON, HW, HORIZON, 0x66FFE0B0, 6, id='horizon'))
    for gg in range(2):
        els.append(gull(f'gull-{gg}', 150 + gg * 60, -190 - gg * 22, 18, 4, 0xB33A2A26))
        tracks += flap_tracks(f'gull-{gg}', 30, 6, 2 * gg, D)
    d.add(panel_shadow(), g(els, 'set', clip=cid), panel_border())
    meta = {'origin': [540, 1170], 'clips': [clip('ambient', D, tracks, note='cloud drift loops over 40 s worth of phase squeezed into 20 s')] + whip_meta()
            + [clip('pop-in', 0.42, [track('root', 'scale', [[0, 0.82], [0.42, 1]], 'outBack'), track('root', 'opacity', [[0, 0], [0.25, 1]], 'easeOut')], loop=False)],
            'notes': 'Sun reflection dashes breathe (scaleX) and sway (x); round caps scale with them in Rive (Kotlin keeps 4-9 px caps).'}
    return 'set-d90s-sunset', d, meta


def bow():
    d = Doc('set-d90s-bow', 1060, 960, PANEL_NOTE + ' Ship bow over the sunset sea (in front of the sunset, behind Pip).')
    cid = panel_clip(d)
    hull = d.lin(0, 180, 0, 460, [0xFF3A2B4A, 0xFF1B1428])
    deck = d.lin(-520, 0, 330, 0, [0xFF8E5A33, 0xFFC98E58, 0xFFE2AE74])
    dk = str(P().poly([(-520, 150), (330, 186), (-520, 250)]))
    posts = []
    x = -500
    while x < 320:
        base = 150 + (x + 520) / 850 * 36
        posts.append(line(x, base, x, base - 64, 0xFF2A2036, 7)); x += 110
    els = [path(str(P().poly([(-520, 250), (330, 186), (250, 310), (110, 470), (-520, 470)])), FG(hull), 'hull'),
           line(-520, 288, 292, 214, 0xFFF4EEE8, 9, id='hull-stripe'), line(-520, 250, 330, 186, 0xFFFFB27A, 5, id='hull-edge'),
           path(dk, FG(deck), 'deck'), g([line(-520, 150 + k * 20, 330, 186, 0x40603818, 2.5) for k in range(1, 5)], 'planks'),
           path(dk, S(INK, 4), 'deck-outline'),
           g(posts + [line(-520, 86, 320, 140, 0xFF2A2036, 8), line(-520, 82, 320, 136, 0x99FFC08A, 3), line(320, 140, 330, 186, 0xFF2A2036, 7)], 'rail')]
    d.add(g(els, 'set', clip=cid))
    meta = {'origin': [540, 1170], 'clips': [clip('bob', 1.25, [track('root', 'y', [[0, 0], [0.3125, -9], [0.9375, 9], [1.25, 0]], 'easeInOut')])] + whip_meta()}
    return 'set-d90s-bow', d, meta


def wind():
    d = Doc('set-d90s-wind', 1000, 860, PANEL_NOTE + ' Wind lines streaming left past Pip at the bow.')
    cid = panel_clip(d)
    lines_, tracks = [], []
    D = 10.0
    for i in range(14):
        y = -380 + 600 * hash01(i + 40)
        sp = 1.1 + hash01(i + 50)
        ln_ = 90 + 160 * hash01(i + 60)
        x = 520 - fract(hash01(i + 70)) * 1100
        lines_.append(g([line(0, 0, ln_, 0, 0x73FFFFFF, 3.5)], f'gust-{i}', tr(x, y)))
        tracks.append(track(f'gust-{i}', 'x', wrap_keys(x, -sp * 0.9 * 1100, -580, 520, D)))
    d.add(g(lines_, 'gusts', clip=cid))
    return 'set-d90s-wind', d, {'origin': [540, 1170], 'clips': [clip('blow', D, tracks)]}


def matrix():
    d = Doc('set-d90s-matrix', 1060, 960, PANEL_NOTE + ' Bullet-time room with falling code.')
    cid = panel_clip(d)
    bg = d.lin(0, -HH, 0, HH, [0xFF020D06, 0xFF062614])
    floor = d.rad(0, 185, 320, [0x5530FF7A, 0x0030FF7A])
    glyphs = ['0', '1', '<', '>', '+', '=', '*', '/', '[', ']', '#', '%']
    cols, tracks = [], []
    D = 26.0
    for col in range(19):
        x = -486 + col * 54
        speed = 160 + 140 * hash01(col)
        head = -HH - 100 + (hash01(col + 5) * 1100) % 1300
        gl = []
        for k in range(12):
            ch = glyphs[min(11, int(hash01(col * 31 + k + k) * 12))]
            c = 0xFFD8FFE4 if k == 0 else with_alpha(0xFF2BE36F, (1 - k / 12) * 0.7)
            gl.append(path(text_d(ch, FONT_MONO, 30, 0, -34 * k, 'center'), F(c)))
        cols.append(g(gl, f'rain-{col}', tr(x, head)))
        tracks.append(track(f'rain-{col}', 'y', wrap_keys(head, speed, -HH - 100, -HH - 100 + 1300, D)))
    d.add(panel_shadow(), g([rect(-HW, -HH, HW, HH, FG(bg), id='bg'), g(cols, 'code-rain'), ellipse(0, 185, 320, 35, FG(floor), 'floor-glow')], 'set', clip=cid), panel_border())
    meta = {'origin': [540, 1170],
            'clips': [clip('rain', D, tracks, note='each column falls at its own speed (quantised so the loop closes) and wraps every 1300 px')] + whip_meta(),
            'notes': 'Glyphs are outlined Menlo Bold; Kotlin also reshuffles glyphs 6x a second, which is dropped here (the fall carries the motion).'}
    return 'set-d90s-matrix', d, meta


def orbit():
    d = Doc('set-d90s-orbit', 960, 400, 'Bullet-time ripple arcs around Pip; (0,0) at screen (540,1200).')
    rings, tracks = [], []
    for j in range(3):
        rx, ry, cy = 330 + j * 60, 70 + j * 16, j * 40 - 40
        a0 = 120 * j
        # The full ellipse, trimmed to the 110 deg arc; the clip moves the trim window round it.
        dd = str(P().ellipse(0, cy, rx, ry))
        trim = f' data-trim-end="{110 / 360:.4f}" data-trim-offset="{round(a0 / 360, 4)}"/>'
        layers = soft_stroke(dd, 0x407CFFB0, 12, 8, 3) + [path(dd, S(0x807CFFB0, 4))]
        rings.append(g([el.replace('<path', f'<path id="ring-{j}-{k}"', 1).replace('/>', trim) for k, el in enumerate(layers)], f'ring-{j}'))
        D = 360 / 70
        o0 = round(a0 / 360, 4)
        tracks.append(track(f'ring-{j}', 'trimOffset', [[0, o0], [r3(D), round(o0 + (1 if j % 2 == 0 else -1), 4)]]))
    d.add(g(rings, 'rings'))
    meta = {'origin': [540, 1200],
            'clips': [clip('orbit', r3(360 / 70), tracks, note='rings 0 and 2 run clockwise, ring 1 counter-clockwise, 70 deg/s')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'overall opacity (1 = full)'}],
            'notes': 'Each arc is 110 deg of an ellipse. In Rive build each ring as the full ellipse stroke with a Trim Path '
                     '(start 0, end 0.3056, offset = 120j/360) and animate the trim offset; the SVG draws the full ellipse with '
                     'data-trim-end/-offset giving the t=0 trim.'}
    return 'set-d90s-orbit', d, meta


def tint():
    d = Doc('set-d90s-tint', 1000, 860, PANEL_NOTE + ' Green bullet-time tint laid over Pip.')
    d.add(path(str(P().rrect(-HW, -HH, HW, HH, 56)), F(0x1C1FD86A), 'tint'))
    return 'set-d90s-tint', d, {'origin': [540, 1170], 'clips': whip_meta()}


def bullet():
    d = Doc('set-d90s-bullet', 1300, 160, 'A bullet flying left with its ripple rings; (0,0) is the bullet. Drawn at 420 px travelled.')
    brass = d.lin(0, -11, 0, 11, [0xFFFFE3A1, 0xFFC98B2E, 0xFF8A5A1A])
    copper = d.lin(0, -11, 0, 11, [0xFFFFC09A, 0xFFB8653A, 0xFF7A3A1E])
    dist0 = 420
    els = [g([line(0, 0, 420, 0, 0x33CFFFE0, 6)], 'trail')]
    tracks = []
    T = 1.5
    for k in range(17):
        age0 = dist0 - k * 80
        vis = 0 <= age0 < 600
        s0 = (14 + max(0, age0) * 0.09) / 14
        els.append(g([ellipse(0, 0, 14 * 0.32, 14, S(0xFFCFFFE0, 3, op=0.7) + NON_SCALING)], f'ripple-{k}', tr(max(0, age0), 0, 0, s0),
                     opacity=(1 - age0 / 600) if vis else 0))
        kx, ks, ko = [], [], []
        for i in range(31):
            t = T * i / 30
            dist = 1330 * t / T
            age = dist - k * 80
            on = 0 <= age < 600
            kx.append([r3(t), r3(max(0, age))]); ks.append([r3(t), r3((14 + max(0, age) * 0.09) / 14)])
            ko.append([r3(t), r3((1 - age / 600) if on else 0)])
        tracks += [track(f'ripple-{k}', 'x', kx), track(f'ripple-{k}', 'scale', ks), track(f'ripple-{k}', 'opacity', ko)]
    tracks.append(track('trail', 'scaleX', [[0, 0], [r3(T * 420 / 1330), 1], [T, 1]]))
    els += [g([path(str(P().rrect(-6, -11, 46, 11, 3)), FG(brass)),
               path(str(P().M(-6, -11).Q(-30, -9, -32, 0).Q(-30, 9, -6, 11).Z()), FG(copper)),
               line(-20, -5, 40, -5, 0xAAFFFFFF, 3), line(46, -11, 46, 11, INK, 3)], 'bullet')]
    d.add(*els)
    meta = {'origin': 'scene: flies from screen x 1180 to -150 at y 1000 / 950 / 1030 over 1.5 s',
            'clips': [clip('fly', T, tracks, loop=False, note='the bullet sheds a ring every 80 px; rings stay put in the world (so drift right in the bullet\'s frame), grow and fade over 600 px')],
            'inputs': [{'name': 'progress', 'type': 'number', 'drives': 'distance travelled 0..1330 px = clip time * 887'}],
            'notes': 'Ring strokes must not scale with their transform (Kotlin keeps them 3 px).'}
    return 'set-d90s-bullet', d, meta


def sunglasses():
    d = Doc('set-d90s-sunglasses', 460, 100, "Pixel 'deal with it' shades in Pip's 540x660 box; (0,0) = box point (330,396).")
    px = 18
    rows = ["XXXXXXXXXXXXXXXXXXXXXXXX",
            "X.WWXXXXXXXXXX.WWXXXXXX.",
            ".XWXXXXX..XXX.XWXXXXX...",
            "..XXXXX....X...XXXXX...."]
    ox = 330 - len(rows[0]) * px / 2
    dark, white = P(), P()
    for r, row in enumerate(rows):
        i = 0
        while i < len(row):
            ch = row[i]
            if ch == '.':
                i += 1; continue
            j = i
            while j < len(row) and row[j] == ch:
                j += 1
            (dark if ch == 'X' else white).rect(ox + i * px, 360 + r * px, ox + j * px, 360 + (r + 1) * px)
            i = j
    d.add(g([g([path(str(dark), F(0xFF3A2A26))], 'contact-shadow', 'translate(5 9)', opacity=45 / 255),
             g([path(str(dark), F(0xFF111111)), path(str(white), F(WHITE))], 'lenses')], 'glasses', 'translate(-330 -396)'))
    meta = {'origin': 'pip-box (330,396)', 'space': 'pip-box',
            'clips': [clip('drop-on', 0.5, [track('glasses', 'y', [[0, -900 - 396], [0.5, -396]], 'outBounce'),
                                            track('contact-shadow', 'opacity', [[0, 0], [0.4, 0.05], [0.5, 1]], 'easeIn')], loop=False),
                      clip('lift-off', 0.32, [track('glasses', 'y', [[0, -396], [0.32, -1296]], 'easeIn'),
                                              track('contact-shadow', 'opacity', [[0, 1], [0.1, 0]])], loop=False)],
            'inputs': [{'name': 'drop', 'type': 'number', 'drives': '0..1 how far they have dropped: y offset = (1-drop)*-900, shadow opacity = drop^4; hidden when < 0'}]}
    return 'set-d90s-sunglasses', d, meta


PIECES = [banner90, mirror_back, mirror_front, scream, sunset, bow, wind, matrix, orbit, tint, bullet, sunglasses]
