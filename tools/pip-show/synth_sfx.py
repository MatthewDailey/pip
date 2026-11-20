"""Synthesised sound effects, one function per cue name. Each returns mono or (2, n) audio."""
from __future__ import annotations

import math

import numpy as np

import synth_dsp
from synth_dsp import (
    SR, adsr, bandpass, bp_resp, fade_end, highpass, hz, lowpass, lp_resp, noise, norm_rms, ns,
    peak_resp, perc, pulse, saw, smoothstep, spec_filter, tri, tv_filter, tvec,
)
from synth_instruments import bass808, bell, crash, fm_bell, kick, supersaw, tom


def _r(a, b):
    return float(synth_dsp.RNG.uniform(a, b))


def _sweep_tone(fr, n):
    return np.sin(2 * np.pi * np.cumsum(fr) / SR)


def hop():
    n = ns(0.4)
    t = tvec(n)
    base = 190 * _r(0.9, 1.12)
    fr = base * (1 + 2.6 * (1 - np.exp(-t / 0.11))) * (1 + 0.07 * np.sin(2 * np.pi * 26 * t) * np.exp(-t / 0.2))
    ph = np.cumsum(fr) / SR
    y = np.sin(2 * np.pi * ph) + 0.25 * np.sin(4 * np.pi * ph) + 0.1 * tri(fr, n)
    return y * np.minimum(1, t / 0.004) * np.exp(-t / 0.13)


def land():
    n = ns(0.35)
    t = tvec(n)
    body = _sweep_tone(45 + 80 * np.exp(-t / 0.025), n) * np.exp(-t / 0.09)
    thud = norm_rms(lowpass(noise(n), 350), 0.5) * np.exp(-t / 0.03)
    pat = norm_rms(bandpass(noise(n), 1500, 0.6), 0.5) * np.exp(-t / 0.008) * 0.3
    return body + thud + pat


def poof():
    n = ns(0.7)
    t = tvec(n)
    chans = []
    for _ in range(2):
        y = tv_filter(noise(n), lambda F, T: lp_resp(F, 7000 * np.exp(-np.clip(T, 0, None) / 0.12) + 500))
        chans.append(norm_rms(y, 0.5) * np.minimum(1, t / 0.008) * np.exp(-t / 0.16))
    thump = _sweep_tone(60 + 60 * np.exp(-t / 0.03), n) * np.exp(-t / 0.08) * 0.4
    return np.vstack(chans) + thump


def slash():
    n = ns(0.75)
    t = tvec(n)
    sw = tv_filter(noise(n), lambda F, T: bp_resp(F, 700 * (9000 / 700) ** np.clip(T / 0.13, 0, 1), 0.45))
    sw = norm_rms(sw, 0.5) * smoothstep(t, 0, 0.09) * np.exp(-np.clip(t - 0.09, 0, None) / 0.035)
    t2 = np.clip(t - 0.085, 0, None)
    shing = sum(a * np.sin(2 * np.pi * f * t2 + _r(0, 6)) * np.exp(-t2 / dec)
                for f, a, dec in ((3150, 1, 0.45), (4630, 0.6, 0.3), (6170, 0.4, 0.22), (7930, 0.25, 0.15)))
    y = sw + 0.3 * shing * (t >= 0.085) * smoothstep(t, 0.085, 0.09)
    p = smoothstep(t, 0, 0.15)
    return np.vstack([y * (1 - 0.7 * p), y * (0.3 + 0.7 * p)])


def split():
    n = ns(0.6)
    t = tvec(n)
    crack = norm_rms(highpass(noise(n), 1800), 0.6) * np.exp(-t / 0.012)
    imp = np.zeros(n)
    idx = synth_dsp.RNG.integers(0, ns(0.07), 25)
    imp[idx] = synth_dsp.RNG.uniform(-1, 1, 25)
    crackle = norm_rms(bandpass(imp, 2500, 1.0), 0.3) * np.exp(-t / 0.05)
    t2 = np.clip(t - 0.02, 0, None)
    thud = _sweep_tone(40 + 60 * np.exp(-t2 / 0.03), n) * np.exp(-t2 / 0.18) * (t >= 0.02)
    wood = norm_rms(bandpass(noise(n), 900, 0.5), 0.4) * np.exp(-t / 0.04)
    return crack + crackle + thud + wood


def boo():
    d = 0.62
    n = ns(d + 0.25)
    t = tvec(n)
    fr = 210 * _r(0.92, 1.05) * (1 - 0.12 * smoothstep(t, 0.15, d)) * (1 + 0.012 * np.sin(2 * np.pi * 6 * t))
    x = saw(fr, n) + saw(fr * 1.006, n) + 0.6 * pulse(fr * 0.5, n, 0.5)
    cut = lambda T: 300 + 1900 * smoothstep(T, 0.02, 0.12) * (1 - 0.6 * smoothstep(T, 0.25, 0.6))
    y = tv_filter(x, lambda F, T: lp_resp(F, cut(T), 3) * peak_resp(F, 900, 1.8, 0.5))
    return np.tanh(1.5 * y * adsr(n, 0.02, 0.3, 0.8, 0.08, d))


def peek():
    n = ns(0.15)
    t = tvec(n)
    fr = 1300 * _r(0.95, 1.1) * (1 + 0.5 * smoothstep(t, 0, 0.05))
    ph = np.cumsum(fr) / SR
    return (np.sin(2 * np.pi * ph) + 0.2 * np.sin(4 * np.pi * ph)) * np.minimum(1, t / 0.002) * np.exp(-t / 0.035)


def whoosh():
    d = 0.7
    n = ns(d + 0.05)
    t = tvec(n)
    y = tv_filter(noise(n), lambda F, T: bp_resp(F, 250 + 2600 * np.sin(np.pi * np.clip(T / d, 0, 1)) ** 2, 0.7))
    y = norm_rms(y, 0.5) * np.sin(np.pi * np.clip(t / d, 0, 1)) ** 1.5
    p = (np.pi / 2) * smoothstep(t, 0, d)
    if synth_dsp.RNG.random() < 0.5:
        p = np.pi / 2 - p
    return np.vstack([y * np.cos(p), y * np.sin(p)]) * math.sqrt(2)


def sigh():
    d = 1.1
    n = ns(d + 0.3)
    t = tvec(n)
    fr = (300 + 330 * np.exp(-t / 0.45)) * (1 + 0.006 * np.sin(2 * np.pi * 5 * t))
    ph = np.cumsum(fr) / SR
    y = lowpass(np.sin(2 * np.pi * ph) + 0.3 * np.sin(4 * np.pi * ph) + 0.1 * np.sin(6 * np.pi * ph), 1500)
    breath = norm_rms(bandpass(noise(n), 1200, 1.0), 0.25)
    return (y * 0.8 + breath * (0.5 + 0.5 * np.exp(-t / 0.3))) * adsr(n, 0.12, 0.4, 0.7, 0.25, d)


def no():
    d = 0.26
    n = ns(d + 0.08)
    t = tvec(n)
    fr = 150 * (1 - 0.08 * smoothstep(t, 0.15, d))
    x = lowpass(pulse(fr, n, 0.5) + 0.7 * saw(fr * 1.05, n), 2200)
    return np.tanh(2 * x) * adsr(n, 0.004, 0.1, 0.85, 0.025, d)


def ding():
    a, b = bell(hz("E6"), dur=1.4), bell(hz("B6"), dur=0.9)
    a[: len(b)] += 0.4 * b
    return a


def door_thud():
    n = ns(1.0)
    t = tvec(n)
    body = _sweep_tone(38 + 30 * np.exp(-t / 0.05), n) * np.exp(-t / 0.45)
    impact = norm_rms(lowpass(noise(n), 250), 0.6) * np.exp(-t / 0.18)
    knock = norm_rms(bandpass(noise(n), 450, 0.6), 0.5) * np.exp(-t / 0.06)
    return np.tanh(1.6 * (body + impact + knock))


def pop():
    n = ns(0.15)
    t = tvec(n)
    y = _sweep_tone(300 + 900 * _r(0.8, 1.2) * np.exp(-t / 0.012), n) * np.minimum(1, t / 0.0008) * np.exp(-t / 0.04)
    return y + highpass(noise(n), 3000) * np.exp(-t / 0.001) * 0.3


def sparkle():
    n = ns(1.6)
    out = np.zeros((2, n))
    notes = ["D6", "E6", "F#6", "A6", "B6", "D7", "E7", "F#7"]
    times = np.sort(synth_dsp.RNG.uniform(0, 0.45, 7))
    for i, t0 in enumerate(times):
        b = bell(hz(notes[min(i + int(_r(0, 2)), len(notes) - 1)]), dur=0.5, vel=_r(0.5, 1.0))
        p = _r(-0.8, 0.8)
        a = (p + 1) * math.pi / 4
        i0 = ns(t0)
        m = min(len(b), n - i0)
        out[0, i0:i0 + m] += b[:m] * math.cos(a)
        out[1, i0:i0 + m] += b[:m] * math.sin(a)
    shimmer = highpass(noise(n), 8000) * perc(n, 0.05, 0.25) * 0.15
    return out + shimmer


def scratch():
    src_n = ns(1.2)
    src = saw(220, src_n) + saw(331, src_n) + 0.5 * noise(src_n)
    src = norm_rms(bandpass(src, 1000, 1.0), 0.5)
    d = 0.55
    n = ns(d)
    t = tvec(n)
    pos = 0.3 + 0.08 * (1 - np.cos(2 * np.pi * 2 * t / d))
    y = np.interp(pos * SR, np.arange(src_n), src)
    speed = np.abs(np.gradient(pos) * SR)
    y *= np.clip(speed / 1.2, 0, 1) ** 0.7
    return highpass(y, 150) * smoothstep(t, 0, 0.01)


# ---------------------------------------------------------------- pop-culture-moment cues
def pow_():
    n = ns(0.6)
    t = tvec(n)
    body = _sweep_tone(50 + 110 * np.exp(-t / 0.02), n) * np.exp(-t / 0.15)
    crack = norm_rms(bandpass(noise(n), 1600, 0.8), 0.7) * np.exp(-t / 0.02)
    whap = tv_filter(noise(n), lambda F, T: bp_resp(F, 3000 * np.exp(-np.clip(T, 0, None) / 0.05) + 400, 0.6))
    whap = norm_rms(whap, 0.4) * np.exp(-t / 0.06)
    return np.tanh(2.2 * (body + crack + whap))


def chomp():
    seg = 0.13
    n = ns(seg * 2 + 0.05)
    t = tvec(n)
    local = t % seg
    first = t < seg
    fr = np.where(first, 520 - 280 * local / seg, 240 + 280 * local / seg)
    fr = np.round(fr / 20) * 20  # stepped pitch, like an old sound chip
    y = pulse(fr, n, 0.5) * 0.6 + 0.4 * tri(fr, n)
    env = np.where(t < 2 * seg, 1.0, np.exp(-(t - 2 * seg) / 0.01)) * np.minimum(1, local / 0.004)
    return y * env


def scream():
    d = 1.1
    n = ns(d + 0.2)
    t = tvec(n)
    contour = 620 + 260 * smoothstep(t, 0, 0.15) - 380 * smoothstep(t, 0.4, d)
    fr = contour * (1 + 0.035 * np.sin(2 * np.pi * 7.5 * t))
    x = saw(fr, n) + 0.5 * saw(fr * 1.01, n) + 0.15 * noise(n)
    form = lambda F: bp_resp(F, 850, 0.3) + 0.8 * bp_resp(F, 1250, 0.3) + 0.4 * bp_resp(F, 2900, 0.25)
    y = norm_rms(spec_filter(x, form), 0.4)
    return np.tanh(1.5 * y) * adsr(n, 0.03, 0.3, 0.85, 0.15, d)


def splash():
    n = ns(1.0)
    t = tvec(n)
    chans = []
    for _ in range(2):
        y = tv_filter(noise(n), lambda F, T: bp_resp(F, 2600 * np.exp(-np.clip(T, 0, None) / 0.25) + 700, 0.9))
        chans.append(norm_rms(y, 0.5) * np.minimum(1, t / 0.005) * np.exp(-t / 0.22))
    out = np.vstack(chans)
    for _ in range(9):
        t0 = _r(0.05, 0.7)
        bn = ns(0.06)
        bt = tvec(bn)
        b = _sweep_tone(_r(500, 900) * (1 + 1.5 * bt / 0.06), bn) * np.exp(-bt / 0.02) * _r(0.1, 0.3)
        i0 = ns(t0)
        p = _r(0, 1)
        out[0, i0:i0 + bn] += b * (1 - p)
        out[1, i0:i0 + bn] += b * p
    return out


def boom():
    n = ns(2.2)
    t = tvec(n)
    sub = _sweep_tone(28 + 45 * np.exp(-t / 0.08), n) * np.exp(-t / 1.0)
    rumble = norm_rms(lowpass(noise(n), 300), 0.5) * np.exp(-t / 0.4)
    crack = norm_rms(highpass(noise(n), 2000), 0.5) * np.exp(-t / 0.02)
    mono = np.tanh(1.8 * (sub + rumble + crack))
    return np.vstack([mono, np.roll(mono, 23) * 0.95])


def dundun():
    out = None
    for t0, note, d in ((0.0, "E2", 0.32), (0.48, "F2", 0.5)):
        st = supersaw([hz(note)], d, voices=3, det=6, cutoff=700, fenv=1.5, fdecay=0.08, a=0.01, d=0.3, s=0.6, r=0.18, drive=1.4)
        sub = bass808(hz(note) / 2, d, vel=0.5)
        st[:, : len(sub)] += sub[: st.shape[1]]
        if out is None:
            out = np.zeros((2, ns(1.5)))
        i0 = ns(t0)
        m = min(st.shape[1], out.shape[1] - i0)
        out[:, i0:i0 + m] += st[:, :m]
    return out


def zap():
    n = ns(0.32)
    t = tvec(n)
    fr = 200 + 2400 * np.exp(-t / 0.06)
    fr = fr * (1 + 0.3 * np.sin(2 * np.pi * 45 * t))
    y = 0.6 * pulse(fr, n, 0.3) + np.sin(2 * np.pi * np.cumsum(fr) / SR)
    return lowpass(y, 8000) * np.minimum(1, t / 0.002) * np.exp(-t / 0.12)


def crowd():
    d = 1.9
    n = ns(d + 0.5)
    t = tvec(n)
    left = np.zeros(n)
    right = np.zeros(n)
    vowels = ((750, 1200), (550, 1700), (400, 2100), (650, 1050))
    for _ in range(26):
        f0 = _r(170, 420)
        fr = f0 * (1 + _r(0.05, 0.25) * smoothstep(t, 0, _r(0.3, 0.9))) * (1 + 0.02 * np.sin(2 * np.pi * _r(4, 7) * t))
        f1, f2 = vowels[int(_r(0, 4))]
        v = spec_filter(saw(fr, n), lambda F: bp_resp(F, f1, 0.4) + 0.6 * bp_resp(F, f2, 0.35))
        v *= smoothstep(t, _r(0, 0.4), _r(0.5, 1.0)) * np.where(t < d - _r(0, 0.5), 1.0, np.exp(-(t - d + 0.5) / 0.3))
        p = _r(0, 1)
        left += v * (1 - p)
        right += v * p
    hiss = norm_rms(bandpass(noise(n), 2500, 1.2), 0.2) * smoothstep(t, 0, 0.8) * np.exp(-np.clip(t - d, 0, None) / 0.3)
    out = np.vstack([left, right])
    return norm_rms(out, 0.3) + np.vstack([hiss, np.roll(hiss, 101)])


def sting():
    out = np.zeros((2, ns(1.6)))
    pick = supersaw([hz(x) for x in ("G3", "B3", "D4")], 0.08, voices=4, det=12, cutoff=1500, fenv=3, fdecay=0.05, a=0.003, d=0.05, s=0.6, r=0.04)
    hit = supersaw([hz(x) for x in ("C3", "G3", "C4", "E4", "G4", "D5")], 0.55, voices=5, det=14, cutoff=1400, fenv=4, fdecay=0.2,
                   a=0.004, d=0.4, s=0.5, r=0.3, drive=1.5)
    for sig, t0 in ((pick, 0.0), (hit, 0.12)):
        i0 = ns(t0)
        m = min(sig.shape[1], out.shape[1] - i0)
        out[:, i0:i0 + m] += sig[:, :m]
    i0 = ns(0.12)
    for drum in (kick(1.0) * 0.8, tom(98, 0.7), crash(0.5)):
        m = min(len(drum), out.shape[1] - i0)
        out[:, i0:i0 + m] += drum[:m]
    return out


# name -> (generator, gain, pan, reverb send)
def ring():
    # An old phone's bell: a hammer trilling between two gongs, about 20 strikes a second, for 0.9 s.
    n = ns(1.1)
    t = tvec(n)
    y = np.zeros(n)
    for i in range(18):
        at = ns(i * 0.05)
        f = hz("G5") if i % 2 else hz("A#5")
        b = bell(f, dur=0.25)[: n - at]
        y[at:at + len(b)] += b * (0.9 if i % 2 else 1.0)
    return lowpass(y, 6000) * np.minimum(1, (1.1 - t) / 0.15).clip(0, 1)


def gameover():
    # An 8-bit "you lose": four falling square notes and a buzz.
    notes = ["E5", "C5", "A4", "E4"]
    parts = []
    for i, name in enumerate(notes):
        dur = 0.13 if i < 3 else 0.42
        n = ns(dur)
        t = tvec(n)
        f = hz(name) * (1 - (0.06 * t / dur if i == 3 else 0))
        parts.append(0.5 * pulse(f, n, 0.25) * np.minimum(1, t / 0.004) * np.exp(-t / (dur * 0.9)))
    return lowpass(np.concatenate(parts), 7000)


def dialup():
    # A short modem handshake: dial tones, a carrier whistle, then the hiss and warble.
    n = ns(1.2)
    t = tvec(n)
    dial = (np.sin(2 * np.pi * 697 * t) + np.sin(2 * np.pi * 1209 * t)) * (t < 0.12)
    whistle = np.sin(2 * np.pi * 2100 * t) * ((t > 0.18) & (t < 0.5))
    fr = 1200 + 900 * np.sin(2 * np.pi * 9 * t)
    warble = 0.6 * pulse(fr, n, 0.5) * ((t > 0.5) & (t < 1.05))
    hiss = norm_rms(bandpass(noise(n), 2600, 1.2), 0.25) * ((t > 0.55) & (t < 1.1))
    return lowpass(0.3 * dial + 0.35 * whistle + 0.35 * warble + hiss, 7000) * np.minimum(1, (1.2 - t) / 0.08).clip(0, 1)


FX = {
    "hop": (hop, 0.55, 0.0, 0.10),
    "land": (land, 0.70, 0.0, 0.08),
    "poof": (poof, 0.60, 0.0, 0.20),
    "slash": (slash, 0.60, 0.0, 0.25),
    "split": (split, 0.75, 0.0, 0.20),
    "boo": (boo, 0.60, 0.1, 0.15),
    "peek": (peek, 0.40, 0.2, 0.10),
    "whoosh": (whoosh, 0.55, 0.0, 0.20),
    "sigh": (sigh, 0.55, 0.0, 0.25),
    "no": (no, 0.55, 0.0, 0.10),
    "ding": (ding, 0.45, 0.1, 0.35),
    "door_thud": (door_thud, 0.70, 0.0, 0.30),
    "pop": (pop, 0.50, 0.0, 0.10),
    "sparkle": (sparkle, 0.45, 0.0, 0.40),
    "scratch": (scratch, 0.55, 0.0, 0.10),
    "pow": (pow_, 0.80, 0.0, 0.15),
    "chomp": (chomp, 0.60, 0.0, 0.08),
    "scream": (scream, 0.50, 0.0, 0.25),
    "splash": (splash, 0.60, 0.0, 0.20),
    "boom": (boom, 0.75, 0.0, 0.35),
    "dundun": (dundun, 0.75, 0.0, 0.20),
    "zap": (zap, 0.60, 0.0, 0.15),
    "crowd": (crowd, 0.70, 0.0, 0.30),
    "sting": (sting, 0.60, 0.0, 0.25),
    "ring": (ring, 0.45, 0.25, 0.20),
    "gameover": (gameover, 0.55, 0.0, 0.20),
    "dialup": (dialup, 0.35, 0.0, 0.15),
}
