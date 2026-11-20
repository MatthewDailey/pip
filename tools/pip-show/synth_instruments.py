"""Synthesised instruments: mallets, plucks, drums, synths, pads. Mono unless noted (2, n)."""
from __future__ import annotations

import math

import numpy as np

from synth_dsp import (
    SR, adsr, bandpass, bp_resp, fade_end, fftconv, gate, highpass, hp_resp, lowpass,
    lp_resp, noise, norm_rms, ns, peak_resp, perc, pulse, saw, sine, smoothstep, spec_filter,
    tri, tv_filter, tvec,
)
import synth_dsp


def _rng():
    return synth_dsp.RNG


# ---------------------------------------------------------------- mallets and plucks
def marimba(f, vel=1.0):
    d = float(np.clip(0.75 * (440.0 / f) ** 0.4, 0.18, 1.4))
    n = ns(d * 5)
    t = tvec(n)
    y = (np.sin(2 * np.pi * f * t) * np.exp(-t / d)
         + 0.32 * np.sin(2 * np.pi * 3.98 * f * t) * np.exp(-t / (d * 0.16))
         + 0.10 * np.sin(2 * np.pi * 9.1 * f * t) * np.exp(-t / (d * 0.05)))
    click = lowpass(noise(n) * np.exp(-t / 0.0015), 5000) * 0.25
    return fade_end((y + click) * np.minimum(1, t / 0.0012)) * vel


def kalimba(f, vel=1.0):
    d = float(np.clip(1.2 * (440.0 / f) ** 0.3, 0.3, 1.6))
    n = ns(d * 4)
    t = tvec(n)
    y = (np.sin(2 * np.pi * f * t) * np.exp(-t / d)
         + 0.22 * np.sin(2 * np.pi * 5.95 * f * t) * np.exp(-t / 0.06)
         + 0.08 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / (0.3 * d)))
    click = highpass(noise(n) * np.exp(-t / 0.002), 2000) * 0.08
    return fade_end((y + click) * np.minimum(1, t / 0.001)) * vel


def bell(f, dur=1.2, vel=1.0):
    n = ns(dur * 3)
    t = tvec(n)
    y = np.zeros(n)
    for ratio, amp, dec in ((1, 1, 1.0), (2.0, 0.35, 0.6), (2.76, 0.45, 0.4), (4.07, 0.22, 0.22), (5.4, 0.12, 0.12)):
        if f * ratio < 18000:
            y += amp * np.sin(2 * np.pi * f * ratio * t + _rng().random() * 6) * np.exp(-t / (dec * dur))
    return fade_end(y * np.minimum(1, t / 0.001)) * vel


def fm_bell(f, dur=1.4, idx=3.0, ratio=3.5, vel=1.0):
    n = ns(dur * 2)
    t = tvec(n)
    i_t = idx * np.exp(-t / 0.25)
    y = np.sin(2 * np.pi * f * t + i_t * np.sin(2 * np.pi * f * ratio * t))
    return fade_end(y * perc(n, 0.001, dur * 0.6)) * vel


def pizz(f, vel=1.0):
    n = ns(0.7)
    t = tvec(n)
    y = np.zeros(n)
    for k in range(1, int(min(16, 11000 // f)) + 1):
        y += (0.85 ** k / k) * np.sin(2 * np.pi * k * f * t + _rng().random()) * np.exp(-t * (6 + 1.6 * k))
    y *= np.minimum(1, t / 0.002)
    y = spec_filter(y, lambda F: peak_resp(F, 320, 1.8, 0.6) * peak_resp(F, 2500, 1.3, 0.5))
    return fade_end(y) * vel


def bass_pluck(f, vel=1.0, dur=0.5):
    n = ns(dur + 0.4)
    t = tvec(n)
    y = (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(4 * np.pi * f * t) * np.exp(-t / 0.25)
         + 0.25 * np.sin(6 * np.pi * f * t) * np.exp(-t / 0.12))
    thump = lowpass(noise(n), 200) * np.exp(-t / 0.02) * 0.6
    y = (y * np.exp(-t / 0.45) + thump) * np.minimum(1, t / 0.006) * gate(n, dur, 0.08)
    return fade_end(y) * vel


def koto(f, vel=1.0, bend=0.0):
    n = ns(2.0)
    t = tvec(n)
    ff = f * (1 + 0.012 * np.exp(-t / 0.02)) * 2 ** (bend * smoothstep(t, 0.25, 0.45) / 12)
    ph = np.cumsum(ff) / SR
    y = np.zeros(n)
    for k in range(1, 11):
        if f * k < 14000:
            y += (1 / k ** 1.1) * np.sin(2 * np.pi * k * ph) * np.exp(-t * (2.2 + 2.8 * k))
    y += bandpass(noise(n), 3000, 0.7) * np.exp(-t / 0.004) * 0.3
    y = spec_filter(y, lambda F: peak_resp(F, 600, 1.5, 0.5))
    return fade_end(y * np.minimum(1, t / 0.001)) * vel


def flute(f, dur, vel=1.0):
    n = ns(dur + 0.5)
    t = tvec(n)
    ff = f * 2 ** (-np.exp(-t / 0.06) / 12) * (1 + 0.006 * smoothstep(t, 0.1, 0.5) * np.sin(2 * np.pi * 5 * t))
    ph = np.cumsum(ff) / SR
    tone = np.sin(2 * np.pi * ph) + 0.18 * np.sin(4 * np.pi * ph) + 0.06 * np.sin(6 * np.pi * ph)
    breath = norm_rms(bandpass(noise(n), f * 2, 0.8), 0.3)
    y = (tone + breath * (0.15 + 0.5 * np.exp(-t / 0.08))) * adsr(n, 0.12, 0.3, 0.85, 0.25, dur)
    return fade_end(y) * vel


def trombone(f0, f1, dur, wobble=0.0, vel=1.0):
    """Muted trombone with a plunger 'wah' and a droop from f0 to f1."""
    n = ns(dur + 0.4)
    t = tvec(n)
    glide = smoothstep(t, dur * 0.45, dur * 0.95)
    fr = f0 * (f1 / f0) ** glide
    vib_rate = 5.2 - 1.5 * wobble
    vib = 1 + (0.004 + 0.012 * wobble * smoothstep(t, 0.3, dur)) * np.sin(2 * np.pi * vib_rate * t)
    x = saw(fr * vib, n) + 0.5 * saw(fr * vib * 1.003, n)

    def cutoff(T):
        if wobble:
            return 320 + 1100 * (0.5 - 0.5 * np.cos(2 * np.pi * 2.6 * np.clip(T, 0, None))) * np.exp(-np.clip(T, 0, None) / 1.4)
        return 320 + 1500 * smoothstep(T, 0.02, 0.13) * (1 - 0.7 * smoothstep(T, 0.2, dur))

    y = tv_filter(x, lambda F, T: lp_resp(F, cutoff(T), 3) * peak_resp(F, 1100, 2.0, 0.4))
    return fade_end(np.tanh(1.3 * y * adsr(n, 0.03, 0.2, 0.85, 0.12, dur))) * vel


def surf_twang(f, dur, vel=1.0):
    n = ns(dur + 0.6)
    t = tvec(n)
    ff = f * (1 + 0.005 * np.sin(2 * np.pi * 5.5 * t) * smoothstep(t, 0.1, 0.3))
    x = saw(ff, n) + 0.4 * pulse(ff, n, 0.3)
    dark = lowpass(x, f * 2.5)
    bright = lowpass(x, 5000)
    y = np.tanh(2 * (dark + (bright - dark) * np.exp(-t / 0.06)))
    return fade_end(y * perc(n, 0.002, 0.5) * gate(n, dur, 0.08)) * vel


# ---------------------------------------------------------------- drums
def kick(vel=1.0, decay=0.32, f_hi=170, f_lo=48, click=0.35):
    n = ns(decay * 3)
    t = tvec(n)
    fr = f_lo + (f_hi - f_lo) * np.exp(-t / 0.032)
    y = np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.exp(-t / decay)
    c = lowpass(noise(n) * np.exp(-t / 0.002), 4000) * click
    return fade_end(np.tanh(1.6 * (y + c)) / np.tanh(1.6)) * vel


def snare(vel=1.0, tone=190, decay=0.15, snap=1.0):
    n = ns(decay * 4 + 0.05)
    t = tvec(n)
    body = (np.sin(2 * np.pi * tone * t) + 0.6 * np.sin(2 * np.pi * tone * 1.62 * t)) * np.exp(-t / 0.055) * 0.55
    nz = norm_rms(spec_filter(noise(n), lambda F: hp_resp(F, 1500) * lp_resp(F, 9000)), 0.5)
    nz *= np.exp(-t / decay) * np.minimum(1, t / 0.0007)
    return fade_end(body + nz * snap) * vel


def clap(vel=1.0, decay=0.13):
    n = ns(0.5)
    t = tvec(n)
    e = np.zeros(n)
    for d0 in (0.0, 0.011, 0.022):
        e += (t >= d0) * np.exp(-np.clip(t - d0, 0, None) / 0.005)
    e += (t >= 0.031) * np.exp(-np.clip(t - 0.031, 0, None) / decay) * 0.7
    nz = norm_rms(bandpass(noise(n), 1400, 0.7), 0.5)
    return fade_end(nz * e * 0.9) * vel


def hat(vel=1.0, open_=False):
    n = ns(0.7 if open_ else 0.12)
    m = sum(pulse(330 * r, n, 0.5) for r in (1.0, 1.4471, 1.6170, 1.9265, 2.5028, 2.6637)) / 6
    m = highpass(m + 0.4 * noise(n), 7000, 3)
    y = norm_rms(m, 0.4) * perc(n, 0.0005, 0.28 if open_ else 0.035)
    return fade_end(y) * vel


def shaker(vel=1.0):
    n = ns(0.15)
    y = norm_rms(spec_filter(noise(n), lambda F: hp_resp(F, 5000, 3) * lp_resp(F, 11000)), 0.4)
    return fade_end(y * perc(n, 0.012, 0.045)) * vel


def woodblock(f=1100, vel=1.0):
    n = ns(0.15)
    t = tvec(n)
    y = (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * 2.7 * f * t)) * np.exp(-t / 0.035)
    return fade_end(y * np.minimum(1, t / 0.0005)) * vel


def snap(vel=1.0):
    n = ns(0.12)
    t = tvec(n)
    y = norm_rms(bandpass(noise(n), 2400, 0.6), 0.6) * np.exp(-t / 0.018)
    return fade_end(y) * vel


def tom(f=110, vel=1.0, decay=0.35):
    n = ns(decay * 3)
    t = tvec(n)
    fr = f * (1 + 0.6 * np.exp(-t / 0.04))
    y = np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.exp(-t / decay)
    y += lowpass(noise(n), 1500) * np.exp(-t / 0.03) * 0.2
    return fade_end(np.tanh(1.4 * y)) * vel


def crash(vel=1.0, decay=1.4):
    n = ns(decay * 3)
    m = sum(pulse(410 * r, n, 0.5) for r in (1.0, 1.3, 1.73, 2.12, 2.61, 3.07)) / 6
    m = highpass(0.6 * m + noise(n), 3500, 2)
    return fade_end(norm_rms(m, 0.4) * perc(n, 0.002, decay)) * vel


def ride(vel=1.0):
    n = ns(0.9)
    m = sum(pulse(520 * r, n, 0.5) for r in (1.0, 1.41, 1.93, 2.47)) / 4
    m = highpass(m + 0.3 * noise(n), 4000, 2)
    return fade_end(norm_rms(m, 0.3) * perc(n, 0.001, 0.35)) * vel


def taiko(vel=1.0, big=False):
    n = ns(1.6 if big else 1.0)
    t = tvec(n)
    fr = 52 + 75 * np.exp(-t / 0.045)
    body = np.sin(2 * np.pi * np.cumsum(fr) / SR) * np.exp(-t / (0.6 if big else 0.4))
    thump = norm_rms(lowpass(noise(n), 220), 0.5) * np.exp(-t / 0.10)
    skin = norm_rms(bandpass(noise(n), 700, 0.8), 0.5) * np.exp(-t / 0.018) * 0.4
    return fade_end(np.tanh(1.8 * (body + 0.8 * thump + skin))) * vel


def rim(vel=1.0):
    n = ns(0.1)
    t = tvec(n)
    y = norm_rms(bandpass(noise(n), 2500, 0.5), 0.5) * np.exp(-t / 0.01) + np.sin(2 * np.pi * 1800 * t) * np.exp(-t / 0.02)
    return fade_end(y) * vel


def bass808(f, dur, glide_from=None, vel=1.0):
    n = ns(dur + 0.2)
    t = tvec(n)
    ft = f * (1 + 1.2 * np.exp(-t / 0.012))
    if glide_from:
        ft = ft * (glide_from / f) ** np.exp(-t / 0.07)
    y = np.sin(2 * np.pi * np.cumsum(ft) / SR)
    env = np.minimum(1, t / 0.002) * np.exp(-t / (dur * 1.2 + 0.3)) * gate(n, dur, 0.04)
    return fade_end(np.tanh(2.5 * y * env) / np.tanh(2.5)) * vel


def gated_snare(vel=1.0):
    s = snare(1.0, tone=180, decay=0.25)
    n = ns(0.45)
    s = np.concatenate([s, np.zeros(max(0, n - len(s)))])[:n]
    ti = tvec(ns(0.6))
    g = np.where(tvec(n) < 0.26, 1.0, np.cos(np.clip((tvec(n) - 0.26) / 0.02, 0, 1) * np.pi / 2))
    out = []
    for _ in range(2):
        ir = lowpass(noise(len(ti)) * np.exp(-ti / 0.5), 7000)
        ir /= math.sqrt(np.sum(ir ** 2))
        out.append((s * 0.8 + fftconv(s, ir)[:n] * 1.4) * g)
    return np.vstack(out) * vel


# ---------------------------------------------------------------- synths
def supersaw(freqs, dur, voices=5, det=12.0, cutoff=3000.0, fenv=0.0, fdecay=0.15,
             a=0.005, d=0.2, s=0.7, r=0.15, width=0.8, vib=0.0, drive=0.0):
    n = ns(dur + 6 * r)
    t = tvec(n)
    left = np.zeros(n)
    right = np.zeros(n)
    for fr in freqs:
        for v in range(voices):
            pos = (v / (voices - 1) * 2 - 1) if voices > 1 else 0.0
            ff = fr * 2 ** (det * pos / 1200)
            if vib:
                ff = ff * (1 + vib * np.sin(2 * np.pi * (5.0 + 0.4 * v) * t + _rng().random() * 6))
            x = saw(ff, n)
            ang = (width * pos + 1) * math.pi / 4
            left += x * math.cos(ang)
            right += x * math.sin(ang)
    st = np.vstack([left, right]) / (len(freqs) * math.sqrt(voices))
    dark = lowpass(st, cutoff)
    if fenv:
        bright = lowpass(st, min(cutoff * (1 + fenv), 16000))
        st = dark + (bright - dark) * np.exp(-t / fdecay)
    else:
        st = dark
    if drive:
        st = np.tanh(drive * st) / np.tanh(drive)
    return fade_end(st * adsr(n, a, d, s, r, dur))


def warm_pad(freqs, dur, vel=1.0):
    n = ns(dur + 1.2)
    t = tvec(n)
    left = np.zeros(n)
    right = np.zeros(n)
    for fr in freqs:
        for c, side in ((-6, 0), (6, 1)):
            ff = fr * 2 ** (c / 1200) * (1 + 0.002 * np.sin(2 * np.pi * 0.6 * t + side))
            x = 0.7 * tri(ff, n, _rng().random()) + 0.5 * sine(ff * 2, n)
            if side:
                right += x
            else:
                left += x
    st = lowpass(np.vstack([left, right]), 1600) / len(freqs)
    return fade_end(st * adsr(n, 0.7, 0.5, 0.9, 1.0, dur)) * vel


def organ(freqs, dur, vel=1.0):
    n = ns(dur + 0.1)
    t = tvec(n)
    y = np.zeros(n)
    for fr in freqs:
        for h, amp in ((0.5, 0.5), (1, 1), (1.5, 0.6), (2, 0.5), (3, 0.3), (4, 0.25), (6, 0.1)):
            if fr * h < 12000:
                y += amp * np.sin(2 * np.pi * fr * h * t + _rng().random() * 6)
    y /= len(freqs) * 2
    y += highpass(noise(n), 2000) * np.exp(-t / 0.003) * 0.15
    env = adsr(n, 0.006, 0.3, 0.9, 0.03, dur)
    st = np.vstack([y * (1 + 0.18 * np.sin(2 * np.pi * 6.3 * t)), y * (1 + 0.18 * np.sin(2 * np.pi * 6.3 * t + 1.6))])
    return fade_end(st * env) * vel


def pulse_lead(f, dur, duty=0.25, vel=1.0):
    n = ns(dur + 0.1)
    t = tvec(n)
    ff = f * (1 + 0.004 * smoothstep(t, 0.15, 0.3) * np.sin(2 * np.pi * 6 * t))
    y = pulse(ff, n, duty) * adsr(n, 0.004, 0.1, 0.7, 0.04, dur)
    return fade_end(lowpass(y, 9000)) * vel * 0.5


def chip_tri(f, dur, vel=1.0):
    n = ns(dur + 0.05)
    return fade_end(tri(f, n) * adsr(n, 0.003, 0.2, 0.8, 0.02, dur)) * vel


def chip_noise(dur=0.06, vel=1.0, hold=3):
    n = ns(dur * 3)
    x = np.repeat(noise(n // hold + 1), hold)[:n]
    return fade_end(highpass(x, 3000) * perc(n, 0.0005, dur)) * vel * 0.6


def synth_bass(f, dur, cutoff=500.0, fenv=4.0, fdecay=0.1, sub=0.6, vel=1.0, sq=False):
    n = ns(dur + 0.1)
    t = tvec(n)
    x = (pulse(f, n, 0.5) * 0.5 if sq else saw(f, n)) + sub * np.sin(2 * np.pi * f * t)
    dark = lowpass(x, cutoff)
    bright = lowpass(x, min(cutoff * (1 + fenv), 15000))
    y = (dark + (bright - dark) * np.exp(-t / fdecay)) * adsr(n, 0.003, 0.2, 0.7, 0.03, dur)
    return fade_end(y) * vel


def power_chord(root, dur, mute=False, vel=1.0):
    n = ns(dur + (0.06 if mute else 0.25))
    out = []
    for cents in (-5, 5):
        x = sum(pulse(root * r * 2 ** (cents / 1200), n, 0.5) for r in (1, 2 ** (7 / 12), 2))
        x = np.tanh(5 * x)
        x = spec_filter(x, lambda F: lp_resp(F, 1200 if mute else 3800, 3) * hp_resp(F, 90) * peak_resp(F, 1800, 1.6, 0.5))
        out.append(x)
    env = perc(n, 0.002, 0.07) if mute else adsr(n, 0.003, 0.3, 0.75, 0.08, dur)
    return fade_end(np.vstack(out) * env * 0.5) * vel


def choir(freqs, dur, vel=1.0):
    n = ns(dur + 1.2)
    t = tvec(n)
    left = np.zeros(n)
    right = np.zeros(n)
    for fr in freqs:
        for v in range(4):
            pos = v / 3 * 2 - 1
            ff = fr * 2 ** (9 * pos / 1200) * (1 + 0.004 * np.sin(2 * np.pi * (4.8 + 0.3 * v) * t + _rng().random() * 6))
            x = saw(ff, n)
            ang = (0.8 * pos + 1) * math.pi / 4
            left += x * math.cos(ang)
            right += x * math.sin(ang)
    formant = lambda F: bp_resp(F, 700, 0.35) + 0.6 * bp_resp(F, 1150, 0.3) + 0.25 * bp_resp(F, 2700, 0.25) + 0.05 * lp_resp(F, 500)
    st = spec_filter(np.vstack([left, right]), formant) / len(freqs)
    return fade_end(st * adsr(n, 0.45, 0.5, 0.9, 0.9, dur)) * vel


def braam(freqs, dur, vel=1.0):
    st = supersaw(freqs, dur, voices=5, det=10, cutoff=380, fenv=6, fdecay=0.6, a=0.04, d=1.0, s=0.8, r=0.4, drive=1.8)
    n = st.shape[1]
    sub = sine(min(freqs) / 2, n) * adsr(n, 0.02, 1.0, 0.8, 0.4, dur) * 0.6
    return st + sub * vel


def noise_riser(dur, vel=1.0):
    n = ns(dur)
    t = tvec(n)
    out = []
    for _ in range(2):
        y = tv_filter(noise(n), lambda F, T: bp_resp(F, 300 * (9000 / 300) ** np.clip(T / dur, 0, 1), 0.5))
        out.append(norm_rms(y, 0.3) * (t / dur) ** 2)
    tone = lowpass(saw(110 * 8 ** (t / dur), n), 3000) * (t / dur) ** 2 * 0.25
    return fade_end(np.vstack(out) + tone) * vel


def reverse_cymbal(dur, vel=1.0):
    c = crash(1.0, decay=dur * 0.45)[: ns(dur)]
    c = c[::-1] * smoothstep(tvec(len(c)), 0, dur * 0.3)
    return fade_end(np.vstack([c, np.roll(c, 37)]), 3) * vel
