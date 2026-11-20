#!/usr/bin/env python3
"""Render the Pip show soundtrack (48 kHz, 16-bit stereo WAV), all synthesised with numpy.

  soundtrack.py --cues cues.json --offset 1.42 --out pip_show.wav [--length 129.3]

cues.json is a list of {"name": str, "t": show seconds}, taken from the app's
`PIPCUE <name> <t>` log lines. --offset prepends that much silence so show time 0
lands at that point in the video (a negative offset trims the start instead).
--length pads or trims the final file to exactly that many seconds.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import wave

import numpy as np

import synth_dsp
from synth_dsp import SR, Bus, convolve_stereo, hp_resp, lp_resp, make_ir, moving_avg, ns, spec_filter, tvec
from synth_score import SECTIONS
from synth_sfx import FX

SHOW_LEN = 126.0
FX_LEVEL = 0.6  # effects sit ~4 dB above the music bed instead of ~10


def _window(n, start, end, tail):
    t = tvec(n)
    w = np.clip((t - (start - 0.01)) / 0.01, 0, 1)
    if tail > 0:
        w *= np.clip(1 - (t - end) / tail, 0, 1)
    else:
        w *= t <= end
    return w


def active_level(x, start, end):
    """80th percentile of 50 ms block RMS: loudness that ignores the gaps in sparse sections."""
    seg = x[:, ns(start):ns(end)].mean(axis=0)
    blk = ns(0.05)
    m = len(seg) // blk
    r = np.sqrt(np.mean(seg[: m * blk].reshape(m, blk) ** 2, axis=1))
    return float(np.percentile(r, 80)) + 1e-9


def render_music(n, log):
    out = Bus(n)
    for name, start, end, target, tail, fn in SECTIONS:
        b = Bus(n)
        fn(b)
        w = _window(n, start, end, tail)
        b.dry *= w
        b.wet *= w
        g = target / active_level(b.dry, start, end)
        out.dry += b.dry * g
        out.wet += b.wet * g
        log(f"  music {name:13s} {start:6.1f}-{end:6.1f}s  gain {g:6.2f}")
    return out


SAY = "say:"
VOICE_GAIN = 1.0


def line_slug(text):
    """The file name a line is rendered under: `Hi! I'm Pip.` -> `hi-im-pip`."""
    return re.sub(r"[^a-z0-9]+", "-", text.lower().replace("'", "")).strip("-")


def device_voice(text, voices):
    """The line as Pip's own voice rendered it on the device (`turbo run pip:voice -- render`), resampled to SR."""
    path = os.path.join(voices, line_slug(text) + ".wav") if voices else None
    if not path or not os.path.exists(path):
        return None
    with wave.open(path) as w:
        rate, raw = w.getframerate(), w.readframes(w.getnframes())
    x = np.frombuffer(raw, dtype="<i2").astype(float) / 32768.0
    if rate == SR:
        return x
    t = np.arange(int(len(x) * SR / rate)) * rate / SR
    return np.interp(t, np.arange(len(x)), x)


def say_voice(text, voices=None):
    """A spoken `say:<line>` cue for recordings: the phone speaks the line with its own text-to-speech, which a
    screen recording can't capture. Uses the device rendering in `voices` when it has the line, else macOS `say`.
    Mono float samples at SR, or None with neither."""
    sig = device_voice(text, voices)
    if sig is not None:
        return sig
    if not shutil.which("say"):
        return None
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "line.wav")
        subprocess.run(["say", "-r", "200", "-o", out, "--file-format=WAVE", f"--data-format=LEI16@{SR}", text], check=True)
        with wave.open(out) as w:
            raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(float) / 32768.0


def render_fx(cues, n, log, voices=None):
    out = Bus(n)
    counts = {}
    for cue in cues:
        name, t = cue["name"], float(cue["t"])
        if name.startswith(SAY):
            line = name[len(SAY):]
            sig = say_voice(line, voices)
            if sig is None or not len(sig):
                log(f"  warning: no voice for '{line}' at {t:.2f}s (no rendering in --voices and no macOS say), skipped")
                continue
            out.add(sig / (np.max(np.abs(sig)) + 1e-12), t, gain=VOICE_GAIN, pan=0.0, send=0.05)
            counts["say"] = counts.get("say", 0) + 1
            continue
        if name not in FX:
            log(f"  warning: no sound for cue '{name}' at {t:.2f}s, skipped")
            continue
        fn, gain, pan, send = FX[name]
        sig = np.asarray(fn(), float)
        sig = sig / (np.max(np.abs(sig)) + 1e-12)
        jitter = float(synth_dsp.RNG.uniform(-0.12, 0.12)) if sig.ndim == 1 else 0.0
        out.add(sig, t, gain=gain * FX_LEVEL, pan=pan + jitter, send=send)
        counts[name] = counts.get(name, 0) + 1
    log("  fx " + ", ".join(f"{k} x{v}" for k, v in sorted(counts.items())))
    return out


def _tilt(f):
    # -2 dB below ~90 Hz, +2 dB above ~5 kHz: the kicks, 808s and taiko otherwise swamp the bed.
    return (1 - 0.2 * lp_resp(f, 90)) * (1 + 0.26 * hp_resp(f, 5000, 1))


def master(music, fx):
    music.dry = spec_filter(music.dry, _tilt)
    music.wet = spec_filter(music.wet, _tilt)
    fx_mono = np.abs(fx.dry).mean(axis=0)
    env = moving_avg(moving_avg(fx_mono, 0.03), 0.15)
    duck = 1 - 0.45 * np.clip(env / 0.06, 0, 1)  # music dips up to ~5 dB under effects
    wet = music.wet * duck + fx.wet
    mix = music.dry * duck + fx.dry + 0.9 * convolve_stereo(wet, make_ir(1.9))
    mix[:, -ns(0.6):] *= np.linspace(1, 0, ns(0.6)) ** 2
    mix -= mix.mean(axis=1, keepdims=True)
    pk = np.percentile(np.abs(mix), 99.95)
    mix *= 0.8 / pk
    thr = 0.75
    a = np.abs(mix)
    over = a > thr
    mix[over] = np.sign(mix[over]) * (thr + (1 - thr) * np.tanh((a[over] - thr) / (1 - thr)))
    return mix * (10 ** (-1 / 20) / np.max(np.abs(mix)))


def write_wav(path, st):
    dither = (synth_dsp.RNG.random(st.shape) - synth_dsp.RNG.random(st.shape)) / 32768
    pcm = np.clip(np.round((st + dither) * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.T.tobytes())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cues", required=True, help="JSON list of {name, t}")
    ap.add_argument("--offset", type=float, default=0.0, help="seconds of silence before show time 0")
    ap.add_argument("--out", required=True, help="output .wav")
    ap.add_argument("--length", type=float, default=None, help="pad/trim the file to this many seconds")
    ap.add_argument("--voices", default=os.environ.get("PIP_VOICE_DIR"),
                    help="directory of device-rendered say: lines (<slug>.wav); default $PIP_VOICE_DIR")
    ap.add_argument("--seed", type=int, default=20261120)
    args = ap.parse_args(argv)
    log = lambda s: print(s, file=sys.stderr)

    synth_dsp.reseed(args.seed)
    with open(args.cues) as fh:
        cues = json.load(fh)
    cues = sorted((c for c in cues if 0 <= float(c["t"]) < SHOW_LEN), key=lambda c: float(c["t"]))
    n = ns(SHOW_LEN)
    log(f"rendering {SHOW_LEN:.0f}s show, {len(cues)} cues")
    mix = master(render_music(n, log), render_fx(cues, n, log, args.voices))
    if not np.all(np.isfinite(mix)):
        raise SystemExit("error: non-finite samples in the mix")

    if args.offset >= 0:
        mix = np.concatenate([np.zeros((2, ns(args.offset))), mix], axis=1) if args.offset else mix
    else:
        mix = mix[:, ns(-args.offset):]
    if args.length is not None:
        want = ns(args.length)
        mix = mix[:, :want] if mix.shape[1] >= want else np.pad(mix, ((0, 0), (0, want - mix.shape[1])))
    write_wav(args.out, mix)
    peak_db = 20 * math.log10(np.max(np.abs(mix)) + 1e-12)
    log(f"wrote {args.out}: {mix.shape[1] / SR:.3f}s, peak {peak_db:.2f} dBFS, offset {args.offset:+.3f}s")


if __name__ == "__main__":
    main()
