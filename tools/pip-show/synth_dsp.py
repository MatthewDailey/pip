"""Small numpy-only DSP kit for the Pip show soundtrack.

Filters are zero-phase FFT magnitude responses: a static one for whole buffers,
and an STFT version whose response can change over time (sweeps, wah, risers).
"""
from __future__ import annotations

import math

import numpy as np

SR = 48000
RNG = np.random.default_rng(20261120)

_NOTE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def reseed(seed: int) -> None:
    global RNG
    RNG = np.random.default_rng(seed)


def ns(seconds: float) -> int:
    return max(1, int(round(seconds * SR)))


def tvec(n: int) -> np.ndarray:
    return np.arange(n) / SR


def midi(name) -> float:
    if isinstance(name, (int, float)):
        return float(name)
    i, acc = 1, 0
    while i < len(name) and name[i] in "#b":
        acc += 1 if name[i] == "#" else -1
        i += 1
    return 12 * (int(name[i:]) + 1) + _NOTE[name[0]] + acc


def hz(name) -> float:
    return 440.0 * 2 ** ((midi(name) - 69) / 12)


def chord(*names) -> list[float]:
    return [hz(n) for n in names]


def noise(n: int) -> np.ndarray:
    return RNG.standard_normal(n)


def smoothstep(t, a: float, b: float):
    x = np.clip((np.asarray(t, float) - a) / max(b - a, 1e-9), 0.0, 1.0)
    return x * x * (3 - 2 * x)


def rms(x) -> float:
    return float(np.sqrt(np.mean(np.square(x)) + 1e-20))


def norm_rms(x, level: float):
    return x * (level / rms(x))


def norm_peak(x, level: float = 1.0):
    return x * (level / (np.max(np.abs(x)) + 1e-12))


def fade_end(x, ms: float = 5.0):
    m = min(x.shape[-1], ns(ms / 1000))
    x = np.array(x, float)
    x[..., -m:] *= np.linspace(1, 0, m)
    return x


# ---------------------------------------------------------------- filters
def lp_resp(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (f / np.maximum(fc, 5.0)) ** (2 * order))


def hp_resp(f, fc, order=2):
    return 1.0 / np.sqrt(1.0 + (fc / np.maximum(f, 1e-3)) ** (2 * order))


def bp_resp(f, fc, width_oct=0.5):
    return np.exp(-0.5 * (np.log2(np.maximum(f, 1.0) / np.maximum(fc, 5.0)) / width_oct) ** 2)


def peak_resp(f, fc, gain, width_oct=0.3):
    return 1.0 + (gain - 1.0) * bp_resp(f, fc, width_oct)


def spec_filter(x, resp):
    """Static zero-phase filter along the last axis; resp(freqs) -> magnitude."""
    x = np.asarray(x, float)
    n = x.shape[-1]
    nfft = 1 << int(math.ceil(math.log2(n + 8192)))
    spec = np.fft.rfft(x, nfft, axis=-1)
    f = np.fft.rfftfreq(nfft, 1 / SR)
    return np.fft.irfft(spec * resp(f), nfft, axis=-1)[..., :n]


def lowpass(x, fc, order=2):
    return spec_filter(x, lambda f: lp_resp(f, fc, order))


def highpass(x, fc, order=2):
    return spec_filter(x, lambda f: hp_resp(f, fc, order))


def bandpass(x, fc, width_oct=0.6):
    return spec_filter(x, lambda f: bp_resp(f, fc, width_oct))


def tv_filter(x, resp_t, nwin: int = 2048):
    """Time-varying zero-phase filter (STFT, Hann, 75% overlap) for short mono buffers.

    resp_t(f[None, :], t[:, None]) returns gains per frame and bin, t in seconds from x[0].
    """
    hop = nwin // 4
    n = len(x)
    xp = np.concatenate([np.zeros(nwin), x, np.zeros(2 * nwin)])
    nfr = (len(xp) - nwin) // hop + 1
    idx = hop * np.arange(nfr)[:, None] + np.arange(nwin)[None, :]
    win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(nwin) / nwin)
    spec = np.fft.rfft(xp[idx] * win, axis=1)
    f = np.fft.rfftfreq(nwin, 1 / SR)
    tc = (hop * np.arange(nfr) + nwin / 2 - nwin) / SR
    spec *= resp_t(f[None, :], tc[:, None])
    frames = np.fft.irfft(spec, nwin, axis=1)
    out = np.zeros(len(xp) + nwin)
    for k in range(4):  # frames k, k+4, ... tile without overlap
        seg = frames[k::4].reshape(-1)
        out[k * hop:k * hop + seg.size] += seg
    return out[nwin:nwin + n] / 2.0  # periodic Hann at 75% overlap sums to 2


def fftconv(a, b):
    n = len(a) + len(b) - 1
    nfft = 1 << int(math.ceil(math.log2(n)))
    return np.fft.irfft(np.fft.rfft(a, nfft) * np.fft.rfft(b, nfft), nfft)[:n]


# ---------------------------------------------------------------- envelopes
def adsr(n, a, d, s, r, length):
    """Attack/decay/sustain, then exponential release from `length` seconds."""
    t = tvec(n)
    a, d, r = max(a, 1e-4), max(d, 1e-4), max(r, 1e-4)
    e = np.where(t < a, t / a, s + (1 - s) * np.exp(-(t - a) / d))
    lv = length / a if length < a else s + (1 - s) * math.exp(-(length - a) / d)
    rel = t >= length
    e[rel] = lv * np.exp(-(t[rel] - length) / r)
    return e


def perc(n, a, decay):
    t = tvec(n)
    return np.minimum(1.0, t / max(a, 1e-4)) * np.exp(-t / decay)


def gate(n, length, r=0.04):
    t = tvec(n)
    return np.where(t < length, 1.0, np.exp(-(t - length) / r))


# ---------------------------------------------------------------- oscillators
def phase(f, n):
    if np.isscalar(f):
        return f * tvec(n)
    return np.cumsum(f) / SR


def sine(f, n, ph0=0.0):
    return np.sin(2 * np.pi * (phase(f, n) + ph0))


def _blep(p, dt):
    y = np.zeros_like(p)
    m = p < dt
    x = p[m] / dt[m]
    y[m] = x + x - x * x - 1
    m = p > 1 - dt
    x = (p[m] - 1) / dt[m]
    y[m] = x * x + x + x + 1
    return y


def _dt(f, n):
    return np.broadcast_to(np.abs(np.asarray(f, float)) / SR, (n,)).copy()


def saw(f, n, ph0=None):
    ph0 = RNG.random() if ph0 is None else ph0
    p = (phase(f, n) + ph0) % 1.0
    return 2 * p - 1 - _blep(p, _dt(f, n))


def pulse(f, n, duty=0.5, ph0=None):
    ph0 = RNG.random() if ph0 is None else ph0
    p = (phase(f, n) + ph0) % 1.0
    p2 = (p + duty) % 1.0
    dt = _dt(f, n)
    return (2 * p - 1 - _blep(p, dt)) - (2 * p2 - 1 - _blep(p2, dt))


def tri(f, n, ph0=0.0):
    p = (phase(f, n) + ph0) % 1.0
    return 4 * np.abs(p - 0.5) - 1


def pan_gains(pan: float):
    a = (pan + 1) * math.pi / 4
    return math.cos(a) * math.sqrt(2), math.sin(a) * math.sqrt(2)


def to_stereo(x, pan=0.0):
    gl, gr = pan_gains(pan)
    return np.vstack([x * gl, x * gr])


# ---------------------------------------------------------------- mixing
class Bus:
    """Stereo dry bus plus a stereo reverb send."""

    def __init__(self, n: int):
        self.n = n
        self.dry = np.zeros((2, n))
        self.wet = np.zeros((2, n))

    def add(self, sig, t, gain=1.0, pan=0.0, send=0.15):
        sig = np.asarray(sig, float)
        if sig.ndim == 1:
            sig = to_stereo(sig, pan)
        elif pan:
            sig = sig * np.array([[min(1.0, 1 - pan)], [min(1.0, 1 + pan)]])
        i0 = int(round(t * SR))
        s0 = 0
        if i0 < 0:
            s0, i0 = -i0, 0
        m = min(sig.shape[1] - s0, self.n - i0)
        if m <= 0:
            return
        seg = sig[:, s0:s0 + m] * gain
        self.dry[:, i0:i0 + m] += seg
        if send:
            self.wet[:, i0:i0 + m] += seg * send


def make_ir(rt60=1.9, predelay=0.016, damp=3800.0, seed=5):
    """Synthetic stereo hall: early taps, then decorrelated noise tails that darken as they decay."""
    rng = np.random.default_rng(seed)
    n = ns(rt60 * 1.15 + predelay)
    t = tvec(n)
    chans = []
    for ch in range(2):
        nz = rng.standard_normal(n)
        lo = lowpass(nz, damp)
        hi = nz - lo
        tail = lo * np.exp(-6.91 * t / rt60) + 0.6 * hi * np.exp(-6.91 * t / (rt60 * 0.3))
        tail *= smoothstep(t, 0.0, 0.03)
        er = np.zeros(n)
        for k in range(9):
            d = rng.uniform(0.004, 0.045)
            er[ns(d)] += rng.uniform(0.3, 0.8) * (1 if rng.random() > 0.5 else -1) * math.exp(-d / 0.05)
        ir = np.concatenate([np.zeros(ns(predelay)), er * 6 + tail])[:n]
        chans.append(ir / math.sqrt(np.sum(ir ** 2)))
    return np.vstack(chans)


def convolve_stereo(x, ir):
    n = x.shape[1]
    nfft = 1 << int(math.ceil(math.log2(n + ir.shape[1])))
    out = np.fft.irfft(np.fft.rfft(x, nfft, axis=1) * np.fft.rfft(ir, nfft, axis=1), nfft, axis=1)
    return out[:, :n]


def moving_avg(x, seconds):
    w = ns(seconds)
    c = np.cumsum(np.concatenate([np.zeros(w), x]))
    return (c[w:] - c[:-w]) / w
