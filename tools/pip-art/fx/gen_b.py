"""Rain cloud, spotlight, confetti, disco ball, moon, peace swirl, neon grid and door."""
import math
from lib import *


# ---- RAIN_CLOUD ----------------------------------------------------------------------------------

CLOUD = sd_union(sd_circle(-78, 8, 50), sd_circle(-28, -26, 62), sd_circle(36, -18, 56), sd_circle(86, 12, 44), sd_rrect(-126, 0, 128, 58, 29))


def _drop_state(i, p, t):
    strength = min(1, p * 4)
    lane = -100 + i * 20 + (hash01(i + 21) - 0.5) * 10
    ph = fract(t * (1.25 + 0.4 * hash01(i + 31)) + hash01(i + 41))
    on = hash01(i * 7 + 3) <= p * 1.15 + 0.05 and p > 0
    return lane - ph * 6, 48 + ph * 240, (strength * (1 - ph * ph)) if on else 0.0, ph


def rain_cloud(preview=False, p=0.6, t=0.0):
    W, H = 300, 640  # (0,0) at centre; rain falls to y=310
    bb = (-140, -100, 140, 80)
    base = shape_d(CLOUD, bb)
    lit = shape_d(sd_inter(CLOUD, sd_xform(CLOUD, -8, -11)), bb)
    hi = shape_d(sd_inter(CLOUD, sd_xform(CLOUD, -18, -25, 0.55, 0.5)), bb)
    eye = "#4A4F57"
    face = []
    for sx in (-1, 1):
        ex = 34 * sx
        face.append(f'<path id="cloud-eye-{"l" if sx < 0 else "r"}" d="{arc_cubics(ex, 15, 11, 11, 0, 180)} Z" fill="{eye}"/>')
        face.append(f'<path id="cloud-brow-{"l" if sx < 0 else "r"}" d="M{n(ex - 13 * sx)},2 L{n(ex + 13 * sx)},-5" fill="none" stroke="{eye}" stroke-width="4" stroke-linecap="round"/>')
    face.append(f'<path id="cloud-frown" d="{arc_cubics(0, 43, 14, 9, 200, 340)}" fill="none" stroke="{eye}" stroke-width="4.5" stroke-linecap="round"/>')
    drops = []
    for i in range(11):
        x, y, a, _ = _drop_state(i, p, t)
        drops.append(f'<g id="drop-{i}" transform="translate({n(x)},{n(y)})" opacity="{n(a)}"><path d="M0,0 L-3,22" fill="none" stroke="#5FA8E0" stroke-width="7" stroke-linecap="round"/></g>')
    bob = math.sin(t * 1.6) * 5; br = 1 + 0.015 * math.sin(t * 1.6 + 1)
    body = f'''<g id="rain">{"".join(drops)}</g>
<g id="cloud" transform="translate(0,{n(bob)}) scale({n(br)},{n(1 / br)})">
  <g id="cloud-shadow" transform="translate(0,10)"><path d="{base}" {paint(0x1F3A2A26)} fill-rule="evenodd"/></g>
  <g id="cloud-base"><path d="{base}" fill="#7F8893" fill-rule="evenodd"/></g>
  <g id="cloud-lit"><path d="{lit}" fill="#A2AAB3" fill-rule="evenodd"/></g>
  <g id="cloud-highlight"><path d="{hi}" {paint(0x33FFFFFF)} fill-rule="evenodd"/></g>
  <g id="cloud-face">{"".join(face)}</g>
</g>'''
    D = 2 * math.pi / 1.6
    Drain = 4.0
    tracks = [
        {"target": "cloud", "property": "y", "keys": keys(lambda x: math.sin(x * 1.6) * 5, 0, D, 32)},
        {"target": "cloud", "property": "scaleX", "keys": keys(lambda x: 1 + 0.015 * math.sin(x * 1.6 + 1), 0, D, 32)},
        {"target": "cloud", "property": "scaleY", "keys": keys(lambda x: 1 / (1 + 0.015 * math.sin(x * 1.6 + 1)), 0, D, 32)},
    ]
    rain_tracks = []
    appear = []
    for i in range(11):
        sp = 1.25 + 0.4 * hash01(i + 31)
        cyc = max(1, round(sp * Drain)); sp2 = cyc / Drain
        ph0 = hash01(i + 41)
        lane = -100 + i * 20 + (hash01(i + 21) - 0.5) * 10
        ks_x, ks_y, ks_a = [], [], []
        # one fall per cycle: linear in phase, so key the wrap points exactly
        for c in range(cyc + 1):
            for frac in (0.0, 0.25, 0.5, 0.75, 0.999):
                tt = (c - ph0 + frac) / sp2
                if 0 <= tt <= Drain:
                    ks_x.append([tt, lane - frac * 6]); ks_y.append([tt, 48 + frac * 240]); ks_a.append([tt, 1 - frac * frac])
        for arr, prop in ((ks_x, "x"), (ks_y, "y"), (ks_a, "opacity")):
            arr.sort()
            rain_tracks.append({"target": f"drop-{i}", "property": prop, "keys": arr, "ease": "linear", "wrap": "values jump back at each new fall (hold between 0.999 and the next 0)"})
        appear.append([f"drop-{i}", max(0.0, (hash01(i * 7 + 3) - 0.05) / 1.15)])
    motion = {
        "piece": "rain-cloud", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Cloud outline is the union of four circles and a rounded bar, traced as one path. cloud-lit is the cloud intersected with itself shifted (-8,-11); cloud-highlight is the cloud intersected with a 0.55x0.5 copy at (-18,-25). Both were boolean'd ahead of time so no clipping is needed.",
            "Rain is drawn before the cloud so the underside hides where drops are born.",
            "Each drop falls at its own rate; rates are rounded to whole falls per 4 s loop so 'rain' loops seamlessly.",
        ],
        "clips": [
            {"name": "bob", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": tracks},
            {"name": "rain", "timeline": "time", "duration": Drain, "loop": True, "ease": "linear", "tracks": rain_tracks},
        ],
        "inputs": [{"name": "progress", "type": "number",
                    "drives": "rain density: drop-i shows once progress >= appearsAt (below); all drops' opacity is multiplied by min(1, progress*4). 0 = no rain. (The disappointed scene loops progress 0..1 every 0.9 s, so the rain pulses.)",
                    "appearsAt": appear}],
    }
    return svg_doc(W, H, body, title="rain-cloud"), motion


# ---- SPOTLIGHT -----------------------------------------------------------------------------------

def spotlight(preview=False, color=0xFFFF4FD8, flicker=1.0):
    col = color | 0xFF000000
    L = 1400
    W, H = 2 * 470, 2 * 1500  # (0,0) centred: fixture at top centre region
    defs = lin_grad("cone-grad", 0, 0, 0, L, [(0, with_alpha(col, 0.75)), (0.55, with_alpha(col, 0.35)), (1, with_alpha(col, 0.12))])
    defs += rad_grad("pool-grad", 0, 0, 1, [(0, with_alpha(col, 0.45)), (1, with_alpha(col, 0))])
    cones = []
    for i in range(4):
        w = 1 + i * 0.13
        pts = [(-40 * w, 20), (40 * w, 20), (330 * w, L), (-330 * w, L)]
        cones.append(f'<g id="cone-{i}" opacity="{0.42 if i == 0 else 0.2}"><path d="{poly_d(pts)}" fill="url(#cone-grad)"/></g>')
    dark = "#3A363D"
    body = f'''<g id="spotlight">
  <g id="cones" opacity="{n(flicker)}">{"".join(cones)}</g>
  <g id="pool" transform="translate(0,{L}) scale(430,80)"><circle r="1" fill="url(#pool-grad)"/></g>
  <g id="fixture">
    <path d="M-62,-40 L-62,-120 L62,-120 L62,-40" fill="none" stroke="{dark}" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M0,-120 L0,-170" fill="none" stroke="{dark}" stroke-width="12" stroke-linecap="round"/>
    <rect x="-52" y="-100" width="104" height="106" rx="22" fill="#2E2B31"/>
    <rect x="-40" y="-96" width="16" height="90" rx="8" {paint(0x26FFFFFF)}/>
    <circle cx="-62" cy="-40" r="9" fill="#56515A"/><circle cx="62" cy="-40" r="9" fill="#56515A"/>
    <rect x="-58" y="-6" width="116" height="28" rx="14" fill="#4A464D"/>
  </g>
  <g id="lens-glow" transform="translate(0,18) scale({n(flicker)})"><circle r="70" {paint(with_alpha(col, 0.3))}/></g>
  <g id="lens"><ellipse cx="0" cy="17" rx="44" ry="13" fill="{rgb(lighten(col, 0.55))}"/><ellipse cx="0" cy="16.5" rx="24" ry="7.5" fill="#FFFFFF"/></g>
</g>'''
    D = 2.0
    w1, w2 = periodic(13, D), periodic(7.3, D)
    fl = lambda x: 0.96 + 0.04 * math.sin(x * w1) * math.sin(x * w2)
    motion = {
        "piece": "spotlight", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "(0,0) is the lens; the cone throws 1400px straight down to an elliptical floor pool. The actor's rotation swings it about (0,0).",
            "Four stacked cones, each 13% wider and fainter, fake the soft edge (no blur). Cone opacities 0.42/0.2/0.2/0.2.",
            "Tint: cone-grad stops are the tint at 75/35/12% alpha, pool-grad 45%->0, lens-glow 30%, the lens = tint mixed 55% toward white. The SVG shows the pink light (#FF4FD8); the dance also uses cyan #4FE3FF and gold #FFD84F.",
            "Flicker frequencies are rounded to whole cycles in a 2 s loop.",
        ],
        "clips": [{"name": "flicker", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": [
            {"target": "cones", "property": "opacity", "keys": keys(fl, 0, D, 80)},
            {"target": "lens-glow", "property": "scale", "keys": keys(fl, 0, D, 80)},
        ]}],
        "inputs": [{"name": "tint", "type": "color", "drives": "cone-grad, pool-grad, lens-glow and lens colours as above"}],
    }
    return svg_doc(W, H, body, defs, title="spotlight"), motion


# ---- CONFETTI ------------------------------------------------------------------------------------

CONF_COLORS = [HAT_RED, 0xFFFFC93C, 0xFF2EC4B6, 0xFF4D96FF, 0xFFFF7AB6, 0xFF7BD389, 0xFFA06CD5]


def _conf_pieces():
    out = []
    for i in range(40):
        ang = math.radians(-90 + (hash01(i * 3 + 1) - 0.5) * 150)
        sp = 900 + 1300 * hash01(i * 7 + 2)
        out.append(dict(vx=math.cos(ang) * sp, vy=math.sin(ang) * sp, w=16 + 12 * hash01(i * 5 + 3), h=9 + 5 * hash01(i * 11 + 4),
                        spin=(hash01(i * 13 + 5) - 0.5) * 900, ph=hash01(i * 17 + 6) * 2 * math.pi, col=CONF_COLORS[i % 7],
                        shape=int(hash01(i * 19 + 7) * 3), fw=6 + 4 * hash01(i + 90)))
    return out


PIECES = _conf_pieces()


def _conf_state(c, p):
    t = p * 2.6
    drag = 3.2
    spread = (1 - math.exp(-drag * t)) / drag
    fall = 360 * (t - spread)
    pop = p / 0.05 if p < 0.05 else 1.0
    sway = math.sin(t * 3 + c["ph"]) * 26 * min(1, t)
    return c["vx"] * spread + sway, c["vy"] * spread + fall, c["spin"] * t + c["ph"] * 57, pop


def confetti(preview=False, p=0.25, time=0.0):
    W, H = 2400, 2400
    pcs = []
    for i, c in enumerate(PIECES):
        x, y, rot, pop = _conf_state(c, p)
        flip = math.cos(time * c["fw"] + c["ph"])
        col = darken(c["col"], 0.8) if flip < 0 else c["col"]
        if c["shape"] == 0:
            sh = f'<rect x="{n(-c["w"] / 2)}" y="{n(-c["h"] / 2)}" width="{n(c["w"])}" height="{n(c["h"])}" fill="{rgb(col)}"/>'
        elif c["shape"] == 1:
            sh = f'<circle r="{n(c["h"] * 0.75)}" fill="{rgb(col)}"/>'
        else:
            sh = f'<rect x="{n(-c["w"] * 0.7)}" y="-4" width="{n(c["w"] * 1.4)}" height="8" rx="4" fill="{rgb(col)}"/>'
        pcs.append(f'<g id="confetti-{i}" transform="translate({n(x)},{n(y)}) rotate({n(rot)}) scale({n(pop)})"><g id="confetti-{i}-flip" transform="scale(1,{n(flip)})">{sh}</g></g>')
    fade = 1 - smoothstep(0.8, 1, p)
    body = f'<g id="confetti" opacity="{n(fade)}">\n' + "\n".join(pcs) + "\n</g>"
    P = [0, 0.01, 0.025, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.38, 0.46, 0.55, 0.64, 0.73, 0.82, 0.91, 1.0]
    tr = [{"target": "confetti", "property": "opacity", "keys": [[0, 0], [0.001, 1], [0.8, 1], [0.85, 0.84], [0.9, 0.5], [0.95, 0.16], [1, 0]]}]
    D = 4.0
    flips = []
    for i, c in enumerate(PIECES):
        st = [_conf_state(c, q) for q in P]
        tr += [
            {"target": f"confetti-{i}", "property": "x", "keys": [[q, s[0]] for q, s in zip(P, st)]},
            {"target": f"confetti-{i}", "property": "y", "keys": [[q, s[1]] for q, s in zip(P, st)]},
            {"target": f"confetti-{i}", "property": "rotation", "keys": [[q, s[2]] for q, s in zip(P, st)]},
            {"target": f"confetti-{i}", "property": "scale", "keys": [[0, 0], [0.05, 1]]},
        ]
        fw = periodic(c["fw"], D)
        cyc = round(fw * D / (2 * math.pi))
        flips.append({"target": f"confetti-{i}-flip", "property": "scaleY", "keys": keys(lambda x: math.cos(x * fw + c["ph"]), 0, D, cyc * 16)})
        # colour darkens on the back face: hold keys at the sign changes
        ck = []
        for k in range(cyc * 2 + 2):
            # zeros of cos(fw x + ph): x = ((k + 0.5) * pi - ph) / fw
            z = ((k + 0.5) * math.pi - c["ph"]) / fw
            if 0 <= z <= D:
                back = math.cos((z + 1e-4) * fw + c["ph"]) < 0
                ck.append([z, hexa(darken(c["col"], 0.8) if back else c["col"])])
        start_back = math.cos(c["ph"]) < 0
        ck.insert(0, [0, hexa(darken(c["col"], 0.8) if start_back else c["col"])])
        flips.append({"target": f"confetti-{i}-flip", "property": "fill", "ease": "hold", "keys": ck})
    motion = {
        "piece": "confetti", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": ["40 pieces burst from (0,0) (rect, dot or rounded strip; 7 colours). Positions follow drag + gravity, keyed against progress; the SVG shows progress 0.25.",
                  "The paper flip (scaleY = cos) and the darker back face are time-based; flip speeds are rounded to whole cycles in a 4 s loop."],
        "clips": [
            {"name": "burst", "timeline": "progress", "duration": 1.0, "loop": False, "ease": "linear", "tracks": tr},
            {"name": "flutter", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": flips},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "scrubs 'burst': pieces pop in by 0.05, fly out, sway and fall; all fade 0.8->1"}],
    }
    return svg_doc(W, H, body, title="confetti"), motion


# ---- DISCO_BALL ----------------------------------------------------------------------------------

TINTS = [0xFFFFFFFF, 0xFFFFC2E2, 0xFFB8F2FF, 0xFFFFF0A8, 0xFFD9C2FF]
BR = 110.0


def _tile(b, slot, spin):
    lat0 = math.radians(-90 + 180 * b / 10 + 1.6)
    lat1 = math.radians(-90 + 180 * (b + 1) / 10 - 1.6)
    latC = (lat0 + lat1) / 2
    lon0 = math.radians(slot * 18 + spin + (0 if b % 2 == 0 else 9))
    lonC = lon0 + math.radians(9)
    lon1 = lon0 + math.radians(18 - 3.2)
    vis = math.cos(lonC) > 0.05
    pts = [(BR * math.cos(lat0) * math.sin(lon0), BR * math.sin(lat0)), (BR * math.cos(lat0) * math.sin(lon1), BR * math.sin(lat0)),
           (BR * math.cos(lat1) * math.sin(lon1), BR * math.sin(lat1)), (BR * math.cos(lat1) * math.sin(lon0), BR * math.sin(lat1))]
    nx = math.cos(latC) * math.sin(lonC); ny = math.sin(latC); nz = math.cos(latC) * math.cos(lonC)
    lit = clamp01(-0.45 * nx - 0.55 * ny + 0.7 * nz)
    seed = b * 31 + (slot % 20)
    col = lerp_color(0xFF6F7985, 0xFFE9EEF3, lit * (0.7 + 0.3 * hash01(seed + 3)))
    return pts, col, vis


def disco_ball(preview=False, p=0.0, t=0.6):
    spin = p * 360
    W, H = 1100, 1240  # (0,0) at the ball centre; string runs 420px up, rays reach 520
    defs = rad_grad("ball-base", -38, -42, 170, [(0, 0xFFB9C2CC), (0.6, 0xFF6E7884), (1, 0xFF3F4650)])
    defs += rad_grad("ball-glow", -40, -44, 70, [(0, 0xAAFFFFFF), (1, 0x00FFFFFF)])
    rays = []
    for i in range(8):
        ang = math.radians(i * 45); w = 0.07
        a = 0.10 + 0.06 * math.sin(t * 2 + i)
        pts = [(0, 0), (math.cos(ang - w) * 520, math.sin(ang - w) * 520), (math.cos(ang + w) * 520, math.sin(ang + w) * 520)]
        rays.append(f'<g id="ray-{i}" opacity="{n(a)}"><path d="{poly_d(pts)}" fill="{rgb(TINTS[1 + i % 4])}"/></g>')
    tiles = []
    slots = list(range(-6, 6))
    for b in range(10):
        for s in slots:
            pts, col, vis = _tile(b, s, spin % 18)
            tiles.append(f'<path id="tile-{b}-{s + 6}" d="{poly_d(pts)}" fill="{rgb(col)}" opacity="{1 if vis else 0}"/>')
    spots = [(-150, -62), (146, -110), (162, 92), (-128, 120), (40, -160)]
    sparks = []
    for i, (sx, sy) in enumerate(spots):
        tw = max(0, math.sin(t * (2.6 + 0.5 * i) + i * 1.7))
        sparks.append(f'<g id="ball-sparkle-{i}" transform="translate({sx},{sy}) scale({n(tw)})"><path d="{star_d(30, t * 40)}" fill="#FFFFFF"/><path d="{star_d(13, 45)}" fill="{rgb(TINTS[1 + i % 4])}"/></g>')
    body = f'''<g id="disco">
  <g id="rays" transform="rotate({n(spin * 0.5)})">{"".join(rays)}</g>
  <g id="ball-string"><path d="M0,-110 L0,-530" fill="none" stroke="#5A5450" stroke-width="4" stroke-linecap="round"/><rect x="-16" y="-124" width="32" height="20" rx="5" fill="#8C8C8C"/></g>
  <g id="ball-shadow"><circle cx="6" cy="10" r="110" {paint(SHADOW)}/></g>
  <g id="ball-base"><circle r="110" fill="url(#ball-base)"/></g>
  <g id="disco-tiles">{"".join(tiles)}</g>
  <g id="ball-gloss"><circle cx="-40" cy="-44" r="70" fill="url(#ball-glow)"/><circle r="110" fill="none" {paint(0x553A2A26, "stroke")} stroke-width="3"/></g>
  <g id="ball-sparkles">{"".join(sparks)}</g>
</g>'''
    # Spin: the tile pattern repeats every 18 degrees = 0.05 progress.
    S = [k * 1.5 for k in range(13)]
    spin_tracks = []
    for b in range(10):
        for s in slots:
            st = [_tile(b, s, x) for x in S]
            tid = f"tile-{b}-{s + 6}"
            spin_tracks.append({"target": tid, "property": "path", "keys": [[x / 360, poly_d(q[0])] for x, q in zip(S, st)]})
            spin_tracks.append({"target": tid, "property": "fill", "keys": [[x / 360, hexa(q[1])] for x, q in zip(S, st)]})
            if any(not q[2] for q in st):
                spin_tracks.append({"target": tid, "property": "opacity", "ease": "hold", "keys": [[x / 360, 1 if q[2] else 0] for x, q in zip(S, st)]})
    Dt = 4 * math.pi
    tw_tracks = []
    for i in range(5):
        w = periodic(2.6 + 0.5 * i, Dt)
        tw_tracks.append({"target": f"ball-sparkle-{i}", "property": "scale", "keys": keys(lambda x, w=w, i=i: max(0, math.sin(x * w + i * 1.7)), 0, Dt, 160)})
        tw_tracks.append({"target": f"ball-sparkle-{i}", "property": "rotation", "keys": [[0, 0], [Dt, 540]]})
    ray_tracks = [{"target": f"ray-{i}", "property": "opacity", "keys": keys(lambda x, i=i: 0.10 + 0.06 * math.sin(x * 2 + i), 0, math.pi, 24)} for i in range(8)]
    motion = {
        "piece": "disco-ball", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Not used by the current showreel scenes (no reference frame); redrawn straight from PropsDrawer.discoBall.",
            "The mirror tiles are an orthographic sphere projection. Rive has no 3D, so 'spin' is an 18-degree loop (one tile column; the pattern repeats exactly) of per-tile vertex morphs and lit colours over 120 tile slots (10 bands x 12). Tiles behind the limb are hidden by opacity.",
            "Kotlin's time-based tint glints on individual tiles are dropped (they would fight the spin colour keys); the five twinkling sparkles carry the glitter. Each tile keeps its own speckle while it travels, so the speckle steps by one column when the 18-degree loop wraps.",
            "Sparkle twinkle rates are rounded to whole cycles in a 12.57 s loop; the sparkle star turns 540 degrees per loop (4-point symmetry keeps it seamless).",
        ],
        "clips": [
            {"name": "spin", "timeline": "progress", "duration": 0.05, "loop": True, "ease": "linear", "tracks": spin_tracks,
             "drives": "progress mod 0.05 scrubs this clip"},
            {"name": "rays-turn", "timeline": "progress", "duration": 1.0, "loop": True, "ease": "linear", "tracks": [{"target": "rays", "property": "rotation", "keys": [[0, 0], [1, 180]]}]},
            {"name": "rays-flicker", "timeline": "time", "duration": math.pi, "loop": True, "ease": "linear", "tracks": ray_tracks},
            {"name": "twinkle", "timeline": "time", "duration": Dt, "loop": True, "ease": "linear", "tracks": tw_tracks},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "spin: one full turn per 1.0 (tiles via 'spin' at progress mod 0.05, rays turn at half speed)"}],
    }
    return svg_doc(W, H, body, defs, title="disco-ball"), motion


# ---- MOON ----------------------------------------------------------------------------------------

CRATERS = [(-48, -30, 24), (40, 30, 30), (-10, 62, 18), (62, -52, 14), (-70, 50, 12), (8, -82, 10)]
MR = 130.0


def _flag(time, fw=76.0, fh=46.0, top=-120.0, w5=5.0):
    wave = lambda x: math.sin(x * 0.08 - time * w5) * 5 * (x / 76)
    pts = [(fw * k / 10, top + wave(fw * k / 10)) for k in range(11)]
    pts += [(fw * k / 10, top + fh + wave(fw * k / 10) * 1.1) for k in range(10, -1, -1)]
    fold = fract(time * 0.8) * (fw + 30) - 15
    x0, x1 = max(0, fold - 8), min(fw, fold + 8)
    if x1 <= x0:
        x0 = x1 = min(max(fold, 0), fw)
    fq = [(x0, top + wave(x0)), (x1, top + wave(x1)), (x1, top + fh + wave(x1) * 1.1), (x0, top + fh + wave(x0) * 1.1)]
    return pts, fq


def moon(preview=False, crescent=False, time=0.0):
    W, H = 560, 800  # (0,0) at the moon centre
    erase = sd_circle(62, -40, MR * 0.96)
    def cut(f):
        return sd_sub(f, erase) if crescent else f
    bb = (-140, -140, 140, 140)
    moon_disc = sd_circle(0, 0, MR)
    defs = rad_grad("moon-glow", 0, 0, 240, [(0, 0x66FFF3C4), (0.5, 0x66FFF3C4), (0.75, 0x22FFF3C4), (1, 0x00FFF3C4)])
    parts = [f'<g id="moon-disc"><path d="{shape_d(cut(moon_disc), bb)}" fill="#E0CC92" fill-rule="evenodd"/></g>' if crescent else '<g id="moon-disc"><circle r="130" fill="#E0CC92"/></g>']
    parts.append(f'<g id="moon-face"><path d="{shape_d(cut(sd_inter(moon_disc, sd_circle(-14, -16, MR - 4))), bb)}" fill="#F6EBC4" fill-rule="evenodd"/></g>')
    cr = []
    for i, (cx, cy, r) in enumerate(CRATERS):
        if crescent:
            o = shape_d(cut(sd_circle(cx, cy, r)), bb); inner = shape_d(cut(sd_circle(cx + r * 0.18, cy + r * 0.22, r * 0.74)), bb)
            if o:
                cr.append(f'<g id="crater-{i}"><path d="{o}" fill="#DDC88D"/>' + (f'<path d="{inner}" fill="#E8D7A4"/>' if inner else "") + "</g>")
        else:
            cr.append(f'<g id="crater-{i}"><circle cx="{cx}" cy="{cy}" r="{r}" fill="#DDC88D"/><circle cx="{n(cx + r * 0.18)}" cy="{n(cy + r * 0.22)}" r="{n(r * 0.74)}" fill="#E8D7A4"/></g>')
    parts.append(f'<g id="moon-craters">{"".join(cr)}</g>')
    a = math.radians(-122 if crescent else -112)
    bx, by = math.cos(a) * (MR - 6), math.sin(a) * (MR - 6)
    w5 = 2 * math.pi / 1.25
    pts, fq = _flag(time, w5=w5)
    body = f'''<g id="moon-glow"><circle r="240" fill="url(#moon-glow)"/></g>
<g id="moon">{"".join(parts)}</g>
<g id="flag-mound"><ellipse cx="{n(bx)}" cy="{n(by + 1)}" rx="18" ry="7" fill="#D8C489"/></g>
<g id="flag" transform="translate({n(bx)},{n(by)}) rotate(-8)">
  <path d="M0,0 L0,-124" fill="none" stroke="#6B6460" stroke-width="6" stroke-linecap="round"/>
  <circle cx="0" cy="-126" r="5" fill="#D9A441"/>
  <g id="flag-cloth-shadow" transform="translate(3,4)"><path id="flag-cloth-shadow-path" d="{poly_d(pts)}" {paint(SHADOW)}/></g>
  <g id="flag-cloth"><path id="flag-cloth-path" d="{poly_d(pts)}" fill="{rgb(HAT_RED)}"/></g>
  <g id="flag-fold"><path id="flag-fold-path" d="{poly_d(fq)}" {paint(0x33FFFFFF)}/></g>
</g>'''
    D = 1.25
    frames = [_flag(x, w5=w5) for x in [D * k / 20 for k in range(21)]]
    tr = [
        {"target": "flag-cloth-path", "property": "path", "keys": [[D * k / 20, poly_d(f[0])] for k, f in enumerate(frames)]},
        {"target": "flag-cloth-shadow-path", "property": "path", "keys": [[D * k / 20, poly_d(f[0])] for k, f in enumerate(frames)]},
        {"target": "flag-fold-path", "property": "path", "keys": [[D * k / 20, poly_d(f[1])] for k, f in enumerate(frames)]},
    ]
    name = "moon-crescent" if crescent else "moon"
    motion = {
        "piece": name, "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Not used by the current showreel scenes (the 80s scene's moon is its own CUSTOM painter); redrawn from PropsDrawer.moon.",
            "Actor text 'crescent' selects fx-moon-crescent: the same art with a 124.8px circle at (62,-40) cut away, pre-computed as boolean paths (no erase blend mode). The flag sits at -122 degrees instead of -112.",
            "The flag cloth waves (22-vertex morph); the fold highlight (a 16px band clipped to the cloth in Kotlin) is a 4-vertex quad following the cloth edges. The wave speed is nudged from 5.0 to 5.03 rad/s so wave and fold share one 1.25 s loop.",
        ],
        "clips": [{"name": "wave", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": tr}],
        "inputs": [],
    }
    return svg_doc(W, H, body, defs, title=name), motion


# ---- PEACE_BG ------------------------------------------------------------------------------------

DYE = [0xFFFF6FA8, 0xFFFFB547, 0xFFFFE45C, 0xFF7BE0A0, 0xFF5CC8FF, 0xFFA07BFF, 0xFFFF6FA8]
SWR = 460.0


def _sweep_col(f):
    x = f * 6
    i = min(5, int(x))
    return lerp_color(DYE[i], DYE[i + 1], x - i)


def _bands():
    arms = []
    for arm in range(5):
        base = math.radians(arm * 72)
        pts = []
        for i in range(29):
            r = 20 + (SWR - 20) * i / 28
            a = base + r * 0.0105
            pts.append((math.cos(a) * r, math.sin(a) * r))
        for i in range(28, -1, -1):
            r = 20 + (SWR - 20) * i / 28
            a = base + r * 0.0105 + 0.08 + 0.32 * (r / SWR)
            pts.append((math.cos(a) * r, math.sin(a) * r))
        arms.append(smooth_d(pts, corner_deg=25))
    return " ".join(arms)


def peace_bg(preview=False, p=0.0):
    W = H = 980
    N = 60
    defs = []
    wedges = []
    for k in range(N):
        a0, a1 = 360 * (k - 0.5) / N, 360 * (k + 1.5) / N
        col = _sweep_col((k + 0.5) / N)
        gid = f"dye-{k}"
        defs.append(rad_grad(gid, 0, 0, SWR, [(0, col), (0.55, with_alpha(col, 1 - math.sqrt(0.2))), (1, with_alpha(col, 0))]))
        pts = [(0, 0), (math.cos(math.radians(a0)) * SWR, math.sin(math.radians(a0)) * SWR)]
        d = poly_d(pts, False) + " " + arc_cubics(0, 0, SWR, SWR, a0, a1, move=False) + " Z"
        wedges.append(f'<path id="dye-wedge-{k}" d="{d}" fill="url(#{gid})"/>')
    defs.append(rad_grad("band-grad", 0, 0, SWR, [(0, 0x00FFFFFF), (0.2, 0x66FFFFFF), (0.6, 0x55FFFFFF), (1, 0x00FFFFFF)]))
    defs.append(rad_grad("centre-grad", 0, 0, 150, [(0, 0x88FFFFFF), (1, 0x00FFFFFF)]))
    bands = _bands()
    body = f'''<g id="swirl" transform="rotate({n(p * 360)})">
  <g id="dye" opacity="{n(120 / 255)}">{"".join(wedges)}</g>
  <g id="bands-outer"><path d="{bands}" fill="url(#band-grad)"/></g>
  <g id="bands-inner" transform="rotate(36) scale(0.7)" opacity="{n(150 / 255)}"><path d="{bands}" fill="url(#band-grad)"/>
    <g id="centre-glow"><circle r="{n(150 / 0.7)}" fill="url(#centre-grad)"/></g></g>
</g>'''
    D = 2 * math.pi / 1.5
    motion = {
        "piece": "peace-bg", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Not used by the current showreel scenes; redrawn from BackdropDrawer.peaceBg.",
            "Kotlin's tie-dye is a sweep gradient masked by a radial alpha gradient (ComposeShader DST_IN). Rive has neither, so the dye is 60 overlapping wedges 12 degrees wide every 6 degrees, so every point is covered by exactly two (this hides anti-aliasing seams and blends neighbouring hues). Each is a radial gradient of its hue at 100% -> 55% at 0.55 -> 0% at the rim; two such layers composite to Kotlin's 100/80/0% profile. Group opacity 47% matches the Kotlin paint alpha 120.",
            "Five spiral arms widen as they curl, filled with a white radial band gradient; a second copy turned 36 degrees and scaled 0.7 at 59% opacity, then a soft centre glow (inside the scaled group, as in Kotlin).",
        ],
        "clips": [
            {"name": "turn", "timeline": "progress", "duration": 1.0, "loop": True, "ease": "linear", "tracks": [{"target": "swirl", "property": "rotation", "keys": [[0, 0], [1, 360]]}]},
            {"name": "breathe", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": [{"target": "swirl", "property": "scale", "keys": keys(lambda x: 1 + 0.02 * math.sin(x * 1.5), 0, D, 32)}]},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "swirl rotation, one turn per 1.0"}],
    }
    return svg_doc(W, H, body, "\n".join(defs), title="peace-bg"), motion


# ---- NEON_GRID -----------------------------------------------------------------------------------

def _hline(nn, s):
    z = 1 + nn - s
    if z < 0.35:
        return 760.0, 0.0
    y = 700 * 0.55 / z
    if y > 710:
        return 760.0, 0.0
    return y, clamp01(y / 700 * 2.2)


def neon_grid(preview=False, p=0.0, time=0.0):
    W, H = 1080, 2 * 700  # (0,0) at the horizon centre; sky up to -440, floor down to 700
    defs = [lin_grad("sky-grad", 0, -440, 0, 0, [(0, 0x002A0B52), (0.55, 0x882A0B52), (1, 0xCCFF3EA5)]),
            lin_grad("sun-grad", 0, -300, 0, 0, [(0, 0xFFFFE66B), (0.45, 0xFFFF9A4D), (1, 0xFFFF3E9A)]),
            lin_grad("floor-grad", 0, 0, 0, 700, [(0, 0xF0200840), (1, 0xF0381060)])]
    stars = []
    for i in range(14):
        tw = 0.4 + 0.6 * max(0, math.sin(time * (1.5 + hash01(i + 5)) + i * 2.3))
        stars.append(f'<g id="star-{i}" opacity="{n(tw)}"><circle cx="{n(-540 + 1080 * hash01(i + 100))}" cy="{n(-420 + 260 * hash01(i + 200))}" r="{n(2 + 2 * hash01(i + 300))}" fill="#FFFFFF"/></g>')
    sun = sd_intersect = sd_inter(sd_circle(0, -70, 230), lambda X, Y: Y)  # inside where y < 0
    for k in range(7):
        y = -6 - k * 24; th = 13 - k * 1.7
        if th > 1:
            band = sd_inter(lambda X, Y, y=y, th=th: np.abs(Y - y) - th / 2, lambda X, Y: np.abs(X) - 230)
            sun = sd_sub(sun, band)
    sun_d = shape_d(sun, (-240, -310, 240, 5), tol=0.25)
    s = fract(p * 6)
    hl = []
    for k in range(26):
        y, a = _hline(k, s)
        hl.append(f'<g id="hline-{k}" transform="translate(0,{n(y)})"><g opacity="{n(a)}"><path d="M-540,0 L540,0" fill="none" {paint(0xFFFF3EC8, "stroke")} stroke-opacity="0.3" stroke-width="10"/><path d="M-540,0 L540,0" fill="none" stroke="#FF6AD8" stroke-width="3.5"/></g></g>')
    vl = []
    for i in range(-12, 13):
        x0, x1, y1 = i * 6, i * 150, 700.0
        if abs(x1) > 540:
            tt = (540 - abs(x0)) / (abs(x1) - abs(x0))
            x1, y1 = x0 + (x1 - x0) * tt, 700 * tt
        d = f"M{n(x0)},0 L{n(x1)},{n(y1)}"
        vl.append(f'<path d="{d}" fill="none" {paint(0x4D3EF0FF, "stroke")} stroke-width="10" stroke-linecap="butt"/><path d="{d}" fill="none" stroke="#3EF0FF" stroke-width="3" stroke-linecap="butt"/>')
    body = f'''<g id="sky"><rect x="-540" y="-440" width="1080" height="440" fill="url(#sky-grad)"/></g>
<g id="stars">{"".join(stars)}</g>
<g id="sun-glow"><circle cx="0" cy="-70" r="280" {paint(0x30FF3EA5)}/></g>
<g id="sun"><path d="{sun_d}" fill="url(#sun-grad)" fill-rule="evenodd"/></g>
<g id="floor"><rect x="-540" y="0" width="1080" height="700" fill="url(#floor-grad)"/></g>
<g id="grid-h">{"".join(hl)}</g>
<g id="grid-v">{"".join(vl)}</g>
<g id="horizon"><path d="M-540,0 L540,0" fill="none" {paint(0x663EF0FF, "stroke")} stroke-width="16"/><path d="M-540,0 L540,0" fill="none" stroke="#B8FBFF" stroke-width="4"/></g>'''
    S = [k / 24 for k in range(25)]
    tr = []
    for k in range(26):
        st = [_hline(k, x) for x in S]
        tr.append({"target": f"hline-{k}", "property": "y", "keys": [[x / 6, q[0]] for x, q in zip(S, st)]})
        tr.append({"target": f"hline-{k}", "property": "opacity", "keys": [[x / 6, q[1]] for x, q in zip(S, st)]})
    Dt = 4 * math.pi
    star_tr = []
    for i in range(14):
        w = periodic(1.5 + hash01(i + 5), Dt)
        star_tr.append({"target": f"star-{i}", "property": "opacity", "keys": keys(lambda x, w=w, i=i: 0.4 + 0.6 * max(0, math.sin(x * w + i * 2.3)), 0, Dt, 96)})
    motion = {
        "piece": "neon-grid", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Not used by the current showreel scenes; redrawn from BackdropDrawer.neonGrid. 1080 wide, horizon at y=0, sky to -440, floor to 700.",
            "The sun's stripes (clipOutRect in Kotlin) are cut into the sun path ahead of time. Grid lines that Kotlin clips to the floor are trimmed geometrically.",
            "Horizontal lines sit at y = 385 / z and scroll toward the viewer; 'scroll' covers one row (1/6 progress) and loops seamlessly because each line ends where the next one started. Lines past y=710 or with z < 0.35 are hidden (y parked at 760, opacity 0).",
            "Glow under each line is the same stroke wider and fainter (no blur).",
        ],
        "clips": [
            {"name": "scroll", "timeline": "progress", "duration": 1 / 6, "loop": True, "ease": "linear", "tracks": tr, "drives": "progress mod 1/6"},
            {"name": "stars", "timeline": "time", "duration": Dt, "loop": True, "ease": "linear", "tracks": star_tr},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "grid scroll, six rows per 1.0"}],
    }
    return svg_doc(W, H, body, "\n".join(defs), title="neon-grid"), motion


# ---- DOOR ----------------------------------------------------------------------------------------

def _stones():
    out = []
    for sideX in (-182, 136):
        y = -266.0; row = 0
        while y < 290:
            h = 62 + hash01(row + (0 if sideX < 0 else 40)) * 22
            b = min(290.0, y + h)
            col = 0xFFA29C95 if row % 2 == 0 else 0xFF99938C
            out.append(f'<rect x="{n(sideX + 3)}" y="{n(y + 3)}" width="40" height="{n(b - 3 - (y + 3))}" rx="6" fill="{rgb(col)}"/>')
            out.append(f'<path d="M{n(sideX + 3)},{n(b)} L{n(sideX + 43)},{n(b)}" fill="none" stroke="#6E6964" stroke-width="3" stroke-linecap="round"/>')
            y = b; row += 1
    x = -182.0; col_i = 0
    while x < 182:
        w = 70 + hash01(col_i + 20) * 30
        r_ = min(182.0, x + w)
        col = 0xFFA8A29B if col_i % 2 == 0 else 0xFF9D978F
        out.append(f'<rect x="{n(x + 3)}" y="-305" width="{n(r_ - 3 - (x + 3))}" height="36" rx="6" fill="{rgb(col)}"/>')
        x = r_; col_i += 1
    return "".join(out)


def _rattle(p, t, ws):
    w47, w31, w59, w23, w41, w37 = ws
    sx = p * (math.sin(t * w47) * 6 + math.sin(t * w31 + 1.3) * 3.5)
    sy = p * math.sin(t * w59) * 2.5
    rot = p * (math.sin(t * w23) * 1.6 + math.sin(t * w41 + 0.7) * 0.8)
    ring = p * math.sin(t * w37) * 18
    return sx, sy, rot, ring


def door(preview=False, p=0.0, time=0.0):
    W, H = 400, 640  # (0,0) at the door centre
    ws = (47, 31, 59, 23, 41, 37)
    sx, sy, rot, ring = _rattle(p, time, ws)
    planks = []
    for i in range(4):
        l = -130 + i * 65
        planks.append(f'<rect x="{l}" y="-260" width="65" height="520" fill="{"#B27646" if i % 2 == 0 else "#A86B3C"}"/>')
        for j in range(3):
            gx = l + 14 + j * 17 + hash01(i * 3 + j) * 6
            y0 = -230 + hash01(i * 7 + j) * 120
            planks.append(f'<path d="M{n(gx)},{n(y0)} L{n(gx + 3)},{n(y0 + 140 + hash01(i + j * 5) * 120)}" fill="none" {paint(0x30FFE2C0, "stroke")} stroke-width="3" stroke-linecap="round"/>')
        if i > 0:
            planks.append(f'<path d="M{l},-260 L{l},260" fill="none" stroke="#7A4A28" stroke-width="4" stroke-linecap="butt"/>')
    bands = []
    for by in (-170, 150):
        bands.append(f'<rect x="-130" y="{by - 14}" width="260" height="28" fill="#4A4440"/><rect x="-130" y="{by - 14}" width="260" height="5" {paint(0x40FFFFFF)}/>')
        for rx in (-108, -43, 22, 87):
            bands.append(f'<circle cx="{rx + 8}" cy="{by}" r="5.5" fill="#8E8780"/><circle cx="{rx + 6.5}" cy="{by - 1.5}" r="2" {paint(0xCCFFFFFF)}/>')
    dust = []
    dsm = smoothstep(0.35, 0.8, p)
    for i in range(8):
        ph = fract(time * (0.8 + 0.5 * hash01(i + 60)) + hash01(i + 70))
        x = -120 + 240 * hash01(i + 80) + math.sin(ph * 6 + i) * 8
        dust.append(f'<g id="dust-{i}" transform="translate({n(x)},{n(-262 + ph * 220)})" opacity="{n(dsm * (1 - ph))}"><circle r="{n(3 + 3 * hash01(i + 90))}" fill="#CFC2B4"/></g>')
    body = f'''<g id="door-frame" transform="translate({n(sx * 0.18)},{n(sy * 0.18)})">
  <rect x="-178" y="-300" width="364" height="600" rx="18" {paint(SHADOW)}/>
  <rect x="-182" y="-308" width="364" height="598" rx="18" fill="#8B857F"/>
  <g id="door-stones">{_stones()}</g>
  <rect x="-136" y="-266" width="272" height="532" rx="8" fill="#2B211C"/>
</g>
<g id="door-leaf" transform="translate({n(sx)},{n(sy)}) rotate({n(rot)},0,260)" data-pivot="0 260">
  <g id="door-planks">{"".join(planks)}</g>
  <g id="door-bands">{"".join(bands)}</g>
  <rect x="-127.5" y="-257.5" width="255" height="515" fill="none" {paint(0x553A2A26, "stroke")} stroke-width="5"/>
  <rect x="76" y="-28" width="26" height="70" rx="10" fill="#3E3833"/>
  <g id="door-ring" transform="rotate({n(ring)},89,-4)" data-pivot="89 -4"><circle cx="89" cy="22" r="20" fill="none" stroke="#B8862E" stroke-width="8"/><circle cx="89" cy="22" r="20" fill="none" stroke="#E4B24E" stroke-width="4"/></g>
  <circle cx="89" cy="-4" r="9" fill="#D9A441"/><circle cx="86" cy="-7" r="3" {paint(0xCCFFFFFF)}/>
</g>
<g id="door-dust">{"".join(dust)}</g>'''
    D = 2.0
    wsp = tuple(periodic(w, D) for w in ws)
    ks = lambda f: keys(f, 0, D, 160)
    rattle = [
        {"target": "door-leaf", "property": "x", "keys": ks(lambda t: _rattle(1, t, wsp)[0])},
        {"target": "door-leaf", "property": "y", "keys": ks(lambda t: _rattle(1, t, wsp)[1])},
        {"target": "door-leaf", "property": "rotation", "pivot": [0, 260], "keys": ks(lambda t: _rattle(1, t, wsp)[2])},
        {"target": "door-frame", "property": "x", "keys": ks(lambda t: 0.18 * _rattle(1, t, wsp)[0])},
        {"target": "door-frame", "property": "y", "keys": ks(lambda t: 0.18 * _rattle(1, t, wsp)[1])},
        {"target": "door-ring", "property": "rotation", "pivot": [89, -4], "keys": ks(lambda t: _rattle(1, t, wsp)[3])},
    ]
    Dd = 4.0
    dtr = []
    for i in range(8):
        sp = periodic(2 * math.pi * (0.8 + 0.5 * hash01(i + 60)), Dd) / (2 * math.pi)
        ph0 = hash01(i + 70)
        f = lambda t, sp=sp, ph0=ph0: fract(t * sp + ph0)
        bx = -120 + 240 * hash01(i + 80)
        dtr.append({"target": f"dust-{i}", "property": "y", "keys": keys(lambda t, f=f: -262 + f(t) * 220, 0, Dd, 200)})
        dtr.append({"target": f"dust-{i}", "property": "x", "keys": keys(lambda t, f=f, i=i, bx=bx: bx + math.sin(f(t) * 6 + i) * 8, 0, Dd, 200)})
        dtr.append({"target": f"dust-{i}", "property": "opacity", "keys": keys(lambda t, f=f: 1 - f(t), 0, Dd, 200)})
    motion = {
        "piece": "door", "origin": [W / 2, H / 2], "size": [W, H],
        "notes": [
            "Not used by the current showreel scenes; redrawn from PropsDrawer.door. Plank door 260x520 in a stone frame, centred on (0,0).",
            "Stone sizes and wood grain use the same hash01 values as Kotlin (ported bit-for-bit in float32).",
            "Kotlin clips the plank layer and strokes a 10px shade along its edge; here the bands are trimmed to the door and the shade is a 5px stroke inset 2.5px (the visible half).",
            "Rattle mixes sines at 47/31/59/23/41/37 rad/s; they are rounded to whole cycles in a 2 s loop. Keys are at full push (progress 1); Kotlin scales the shake linearly with progress, so blend 'rattle' by progress. Dust keys are full strength; blend 'dust' by smoothstep(0.35, 0.8, progress). (The keys have a sawtooth wrap: sample density makes the jump within one frame.)",
        ],
        "clips": [
            {"name": "rattle", "timeline": "time", "duration": D, "loop": True, "ease": "linear", "tracks": rattle, "blend": "weight = progress"},
            {"name": "dust", "timeline": "time", "duration": Dd, "loop": True, "ease": "linear", "tracks": dtr, "blend": "weight = smoothstep(0.35, 0.8, progress)"},
        ],
        "inputs": [{"name": "progress", "type": "number", "drives": "how hard the door is pushed: rattle amplitude (linear) and dust (from 0.35)"}],
    }
    return svg_doc(W, H, body, title="door"), motion


BUILDERS_B = {
    "fx-rain-cloud": rain_cloud, "fx-spotlight": spotlight, "fx-confetti": confetti, "fx-disco-ball": disco_ball,
    "fx-moon": moon, "fx-moon-crescent": lambda preview=False, **k: moon(preview, crescent=True, **k),
    "fx-peace-bg": peace_bg, "fx-neon-grid": neon_grid, "fx-door": door,
}
