"""The score: one function per section, all original material.

0-48 s is a 100 BPM underscore. From 48.0 to 121.0 everything sits on a strict 120 BPM grid
(beat = 0.5 s); each 9 s decade is 18 beats with "moment" changes at beats 6 and 12, marked by a
fill on the beats before and a stinger on the change. Times are show seconds.
"""
from __future__ import annotations

import synth_dsp
from synth_dsp import chord, hz
from synth_instruments import (
    bass808, bass_pluck, bell, braam, chip_noise, chip_tri, choir, clap, crash, fm_bell, flute,
    gated_snare, hat, kalimba, kick, koto, marimba, noise_riser, organ, pizz, power_chord,
    pulse_lead, reverse_cymbal, ride, rim, shaker, snap, snare, supersaw, surf_twang, synth_bass,
    taiko, tom, trombone, warm_pad, woodblock,
)

BEAT = 0.5  # 120 BPM
DECADE_BEATS = 18


def hum(sd=0.004):
    return float(synth_dsp.RNG.normal(0, sd))


def vj(v, sd=0.08):
    return max(0.05, v * (1 + float(synth_dsp.RNG.normal(0, sd))))


def swing(slot, beat_len, amt=0.6):
    return (slot // 2) * beat_len + (amt * beat_len if slot % 2 else 0)


def at(t0):
    return lambda beat: t0 + beat * BEAT


def cycle(pattern, beat):
    """pattern: list of (length_beats, payload); returns payload at a beat, looping."""
    total = sum(p[0] for p in pattern)
    b = beat % total
    for length, payload in pattern:
        if b < length:
            return payload
        b -= length
    return pattern[-1][1]


# ================================================================ 0-48 s underscore (100 BPM)
U_BL = 0.6
U_PROG = [("F3", "A3", "C4"), ("D3", "F3", "A3"), ("Bb2", "D3", "F3"), ("C3", "E3", "G3")]
U_ROOTS = [("F2", "C3"), ("D2", "A2"), ("Bb1", "F2"), ("C2", "G2")]
PHRASE_A = [(0, "C5"), (2, "A4"), (3, "C5"), (4, "F5"), (7, "E5"), (8, "D5"), (10, "A4"), (12, "F4"), (13, "A4"), (14, "D5")]
PHRASE_B = [(0, "D5"), (2, "Bb4"), (3, "D5"), (4, "F5"), (6, "D5"), (8, "E5"), (9, "C5"), (10, "G4"), (12, "C5"), (14, "E5"), (15, "G5")]


def sec_intro_hops(b):
    bar_len = 4 * U_BL
    for i, nm in enumerate(["F5", "A5", "C6", "F6", "A6"]):
        b.add(kalimba(hz(nm)), 0.15 + i * 0.075, gain=0.5 - i * 0.05, pan=-0.4 + 0.2 * i, send=0.35)
    for bar in range(5):
        b.add(warm_pad(chord(*U_PROG[bar % 4]), bar_len), bar * bar_len, gain=0.3, send=0.3)
    for start_bar in (0, 2, 4):
        phrase = PHRASE_A if start_bar % 4 == 0 else PHRASE_B
        for slot, nm in phrase:
            t = start_bar * bar_len + swing(slot, U_BL)
            if t < 12:
                b.add(marimba(hz(nm)), t + hum(), gain=vj(0.5 + 0.3 * min(1, t / 6)), pan=0.15, send=0.2)
    for beat in range(10, 20):  # the hops: bass, shaker, woodblock
        t = beat * U_BL
        bar = beat // 4
        root, fifth = U_ROOTS[bar % 4]
        if beat % 2 == 0:
            b.add(bass_pluck(hz(root if beat % 4 == 0 else fifth)), t + hum(), gain=0.8, send=0.05)
        else:
            b.add(woodblock(900, 0.25), t + hum(), pan=-0.3, send=0.1)
        for k in (0, 1):
            b.add(shaker(0.35 if k else 0.5), t + swing(k, U_BL) + hum(), pan=0.35, send=0.05)


SNEAK_LOW = [(0, "D3", 1.0), (3, "D3", 0.6), (4, "F3", 0.85), (6, "E3", 0.75), (7, "Eb3", 0.7), (8, "D3", 0.9),
             (10, "A2", 0.7), (11, "A2", 0.5), (12, "C#3", 0.85), (14, "E3", 0.75), (15, "G3", 0.6)]
SNEAK_HIGH = [(5, "A4", 0.5), (13, "G4", 0.45)]
ICON_MEL = [(1, "D5"), (2, "F5"), (5, "A5"), (9, "G5"), (10, "E5"), (13, "C#5"), (14, "E5")]


def sec_sneaky(b):
    t0, eighth = 12.0, U_BL / 2
    b.add(supersaw([hz("D2")], 6.6, voices=3, det=8, cutoff=260, a=1.5, d=1, s=1, r=0.6), t0, gain=0.35, send=0.2)
    loop = 16 * eighth
    k = 0
    while t0 + k * loop < 28:
        base = t0 + k * loop
        for slot, nm, v in SNEAK_LOW:
            t = base + slot * eighth
            if t < 28:
                b.add(pizz(hz(nm)), t + hum(), gain=vj(v), pan=-0.2, send=0.15)
        for slot, nm, v in SNEAK_HIGH:
            t = base + slot * eighth
            if t < 28:
                b.add(pizz(hz(nm)), t + hum(), gain=vj(v * 0.8), pan=0.4, send=0.25)
        if base >= 19 - loop:
            for slot, nm in ICON_MEL:
                t = base + slot * eighth
                if 19 <= t < 28:
                    b.add(marimba(hz(nm), 0.45), t + hum(), pan=0.25, send=0.25)
        k += 1
    beat = 0
    while t0 + beat * U_BL < 28:
        t = t0 + beat * U_BL
        if t >= 19 and beat % 2 == 1:
            b.add(snap(0.55), t + hum(), pan=0.3, send=0.15)
        if beat % 4 == 3:
            b.add(woodblock(1300, 0.18), t + U_BL / 2, pan=-0.4, send=0.1)
        beat += 1


NINJA_TAIKO = [(0, 1.0), (3, 0.6), (4, 0.85), (6, 0.5), (7, 0.6), (8, 0.95), (11, 0.6), (12, 0.8), (13, 0.6), (14, 0.9), (15, 0.7)]
NINJA_KOTO = [(4, "D5", 0), (5, "Bb4", 0), (6, "A4", 0), (8, "G4", 0), (9, "A4", 0), (10, "Eb4", 0), (12, "D4", 0),
              (14, "G4", 0), (15, "A4", 0), (16, "Bb4", 0), (17, "A4", 1), (20, "D5", 0), (21, "Eb5", 0),
              (22, "D5", 0), (24, "Bb4", 0), (25, "A4", 0), (26, "G4", 0), (27, "A4", 0), (28, "D4", 0)]


def sec_ninja(b):
    t0, eighth = 28.0, U_BL / 2
    b.add(taiko(1.0, big=True), t0, gain=1.0, send=0.35)
    b.add(flute(hz("D5"), 1.0), t0 + 0.25, gain=0.5, pan=-0.2, send=0.4)
    b.add(warm_pad(chord("D2", "A2"), 8.6), t0, gain=0.4, send=0.2)
    for slot in range(1, 28):
        hit = dict(NINJA_TAIKO).get(slot % 16)
        if hit:
            b.add(taiko(vj(hit)), t0 + slot * eighth + hum(0.003), gain=0.8, pan=0.1 * ((slot % 3) - 1), send=0.25)
        elif slot % 2:
            b.add(rim(0.25), t0 + slot * eighth + hum(), pan=0.4, send=0.15)
    for slot, nm, bend in NINJA_KOTO:
        b.add(koto(hz(nm), bend=bend), t0 + slot * eighth + hum(), gain=vj(0.6), pan=-0.25, send=0.3)
    b.add(taiko(1.0, big=True), 36.1, gain=0.95, send=0.35)
    b.add(taiko(1.0, big=True), 36.4, gain=1.0, send=0.4)


def sec_sad(b):
    b.add(trombone(hz("Eb4"), hz("D4"), 0.55), 37.35, gain=0.7, send=0.25)
    b.add(trombone(hz("C4"), hz("B3"), 0.55), 38.05, gain=0.7, send=0.25)
    b.add(trombone(hz("Ab3"), hz("G3"), 2.0, wobble=1.0), 38.8, gain=0.75, send=0.3)
    b.add(warm_pad(chord("G2", "D3", "Bb3"), 5.2), 37.2, gain=0.25, send=0.3)
    b.add(pizz(hz("G2")), 41.0, gain=0.6, send=0.2)
    b.add(marimba(hz("G4"), 0.35), 41.7, send=0.3)
    b.add(marimba(hz("D4"), 0.3), 42.2, send=0.3)


def sec_nonono(b):
    t0 = 43.0
    for beat in (0, 0.5, 2, 3, 3.5, 5):
        t = t0 + beat * U_BL
        for nm in ("G2", "B3", "D4", "F4"):
            b.add(pizz(hz(nm)), t, gain=0.45, send=0.1)
        b.add(marimba(hz("F5"), 0.4), t, pan=0.2, send=0.1)
        b.add(marimba(hz("B4"), 0.4), t, pan=0.2, send=0.1)
        b.add(kick(0.5, decay=0.15), t, send=0.0)
    b.add(noise_riser(1.6), 46.4, gain=0.45, send=0.2)
    rolls = [47.0 + k * 0.125 for k in range(4)] + [47.5 + k * 0.0625 for k in range(8)]
    for i, t in enumerate(rolls):
        b.add(snare(0.25 + 0.6 * i / len(rolls)), t, pan=-0.1, send=0.15)


# ================================================================ 48-58 Pip's Jam (120 BPM)
JAM_CHORDS = [("C", ["C4", "E4", "G4"], "C2"), ("G", ["B3", "D4", "G4"], "G1"), ("Am", ["C4", "E4", "A4"], "A1"),
              ("F", ["C4", "F4", "A4"], "F1")]
JAM_HOOK = [
    [(0, "E5", 2), (2, "G5", 2), (4, "C6", 3), (7, "B5", 1), (8, "G5", 2), (10, "E5", 2), (12, "D5", 2), (14, "E5", 2)],
    [(0, "D5", 2), (2, "G5", 2), (4, "B5", 2), (6, "A5", 2), (8, "G5", 4), (12, "D5", 2), (14, "B4", 2)],
    [(0, "C5", 2), (2, "E5", 2), (4, "A5", 3), (7, "G5", 1), (8, "E5", 2), (10, "A5", 2), (12, "C6", 2), (14, "B5", 2)],
    [(0, "A5", 3), (3, "G5", 1), (4, "F5", 2), (6, "E5", 2), (8, "F5", 2), (10, "G5", 2), (12, "A5", 4)],
    [(0, "C6", 2), (2, "A5", 2), (4, "F5", 2), (6, "C5", 2), (8, "D5", 2), (10, "G5", 2), (12, "B5", 2), (14, "D6", 2)],
]


def sec_jam(b):
    t0, s16 = 48.0, BEAT / 4
    b.add(crash(0.6), t0, send=0.2)
    for bar in range(5):
        bt = t0 + bar * 2.0
        name, tones, root = JAM_CHORDS[bar % 4] if bar < 4 else JAM_CHORDS[3]
        for beat in range(4):
            t = bt + beat * BEAT
            if bar == 4 and beat >= 2:
                tones, root = ["B3", "D4", "G4"], "G1"
            b.add(kick(0.9), t, send=0.02)
            if beat % 2:
                b.add(clap(0.7), t, send=0.15)
            b.add(chip_noise(0.03, 0.5), t + BEAT / 2, pan=0.3, send=0.05)
            b.add(chip_noise(0.02, 0.25), t + 3 * s16, pan=-0.3, send=0.05)
            for k in range(2):
                b.add(chip_tri(hz(root) * (2 if k else 1) * 2, BEAT / 2 * 0.8), t + k * BEAT / 2, gain=0.9, send=0.0)
            for k in range(12):  # fast chip arpeggio
                b.add(pulse_lead(hz(tones[k % 3]) * 2, 1 / 24 * 0.9, duty=0.125), t + k / 24, gain=0.12, pan=-0.3, send=0.1)
        for slot, nm, ln in JAM_HOOK[bar]:
            t = bt + slot * s16
            b.add(pulse_lead(hz(nm), ln * s16 * 0.9), t, gain=0.9, pan=0.05, send=0.15)
            b.add(pulse_lead(hz(nm), ln * s16 * 0.9), t + 3 * s16, gain=0.3, pan=0.6, send=0.2)
    for k in range(4):
        b.add(chip_noise(0.05, 0.5 + 0.1 * k, hold=6), 57.5 + k * s16, send=0.1)


# ================================================================ decades (18 beats each)
def _fill_toms(b, t, n, step, freqs=(220, 165, 130, 98), gain=0.7, send=0.25):
    for k in range(n):
        b.add(tom(freqs[min(k * len(freqs) // n, len(freqs) - 1)], vj(gain)), t + k * step, pan=0.4 - 0.25 * (k % 4), send=send)


def sec_60s(b, t0=58.0):
    B = at(t0)
    third = BEAT / 3
    prog = [(4, (["E3", "G3", "B3"], ["E2", "G2", "A2", "B2"])), (2, (["C3", "E3", "G3"], ["C2", "E2"])),
            (2, (["D3", "F#3", "A3"], ["D2", "F#2"]))]
    riff = {0: ["E3", None, "E3", "G3", "A3", None, "G3", "E3"], 4: ["C4", "B3", "G3", "E3"], 6: ["D4", "C4", "A3", "F#3"]}
    for beat in range(DECADE_BEATS):
        t = B(beat)
        tones, walk = cycle(prog, beat)
        pos = beat % 8
        seg_start = 0 if pos < 4 else (4 if pos < 6 else 6)
        b.add(bass_pluck(hz(walk[(pos - seg_start) % len(walk)]), 0.9, dur=0.4), t, send=0.05)
        b.add(kick(0.75) if beat % 2 == 0 else snare(0.6, decay=0.12), t + hum(0.003), send=0.15)
        b.add(ride(0.45), t, pan=0.35, send=0.1)
        b.add(ride(0.25), t + 2 * third, pan=0.35, send=0.1)
        b.add(organ(chord(*tones), 0.16), t + 2 * third, gain=0.45, pan=-0.3, send=0.25)
        notes = riff[seg_start]
        for k in range(2):
            idx = (pos - seg_start) * 2 + k
            if idx < len(notes) and notes[idx]:
                b.add(surf_twang(hz(notes[idx]), 0.3), t + (2 * third if k else 0), gain=0.5, pan=0.25, send=0.45)
    for mark in (6, 12):  # moment stingers: tom triplets, then organ swell + crash
        _fill_toms(b, B(mark - 1), 3, third)
        b.add(crash(0.5), B(mark), send=0.2)
        b.add(organ(chord("E3", "B3", "E4", "G4"), 0.9), B(mark), gain=0.5, send=0.3)
    _fill_toms(b, B(16), 6, third, gain=0.8)


def sec_70s(b, t0=67.0):
    B = at(t0)
    prog = [(4, (["A3", "C4", "E4", "G4"], "A1")), (4, (["F#3", "A3", "C4", "E4"], "D2"))]
    for beat in range(DECADE_BEATS):
        t = B(beat)
        tones, root = cycle(prog, beat)
        b.add(kick(0.85), t, send=0.02)
        if beat % 2:
            b.add(clap(0.6), t, send=0.15)
            b.add(snare(0.35), t, send=0.1)
        b.add(hat(0.45, open_=True), t + BEAT / 2, pan=0.3, send=0.08)
        for q in (0.25, 0.75):
            b.add(hat(0.18), t + q * BEAT, pan=-0.3, send=0.03)
        for k in range(2):
            b.add(synth_bass(hz(root) * (2 if k else 1), 0.2, cutoff=700, fenv=3, sq=True), t + k * BEAT / 2, gain=0.8, send=0.0)
        if beat % 2 == 1:
            b.add(supersaw(chord(*tones), 0.12, voices=4, det=10, cutoff=5000, a=0.006, d=0.1, s=0.4, r=0.1), t + BEAT / 2, gain=0.55, send=0.3)
    for mark in (6, 12, 18):  # string rip up into each moment, then a stab + crash
        rip = [hz(x) for x in ("A3", "C4", "E4", "G4", "A4", "C5", "E5", "A5")]
        for k, f in enumerate(rip):
            b.add(supersaw([f], 0.06, voices=3, det=8, cutoff=6000, a=0.003, d=0.05, s=0.6, r=0.05), B(mark - 0.5) + k * 0.03, gain=0.35, send=0.3)
        if mark < 18:
            b.add(crash(0.5), B(mark), send=0.2)
            b.add(supersaw(chord("A3", "C4", "E4", "A4"), 0.35, voices=5, det=12, cutoff=5000, a=0.005, d=0.3, s=0.3, r=0.2), B(mark), gain=0.6, send=0.35)
    _fill_toms(b, B(16), 8, BEAT / 4, freqs=(260, 200, 150, 110))


def sec_80s(b, t0=76.0):
    B = at(t0)
    prog = [(4, (["D3", "F3", "A3", "D4"], "D2")), (2, (["Bb2", "D3", "F3", "Bb3"], "Bb1")), (2, (["C3", "E3", "G3", "C4"], "C2"))]
    for beat in range(DECADE_BEATS):
        t = B(beat)
        tones, root = cycle(prog, beat)
        if beat % 8 in (0, 2, 4, 6) or beat % 8 == 5:
            b.add(kick(0.9, decay=0.4), t, send=0.03)
        if beat % 2:
            b.add(gated_snare(0.7), t, send=0.05)
        for k in range(2):
            b.add(hat(0.25), t + k * BEAT / 2, pan=0.35, send=0.05)
            b.add(synth_bass(hz(root), 0.22, cutoff=350, fenv=5, fdecay=0.07), t + k * BEAT / 2, gain=0.8, send=0.0)
        for k in range(4):
            f = hz(tones[k]) * 2
            arp = supersaw([f], 0.1, voices=3, det=9, cutoff=1800, fenv=2.5, fdecay=0.05, a=0.002, d=0.08, s=0.3, r=0.08)
            b.add(arp, t + k * BEAT / 4, gain=0.35, send=0.3)
            b.add(arp, t + k * BEAT / 4 + 0.375, gain=0.15, pan=0.6, send=0.35)
        if beat % 8 in (0, 4, 6):
            ln = 4 if beat % 8 == 0 else 2
            b.add(supersaw(chord(*tones), ln * BEAT, voices=5, det=16, cutoff=1500, a=0.25, d=0.5, s=0.8, r=0.5), t, gain=0.4, send=0.4)
    for mark in (6, 12):
        _fill_toms(b, B(mark - 1), 4, BEAT / 4, freqs=(196, 147, 110, 82), send=0.45)
        b.add(crash(0.55), B(mark), send=0.3)
    _fill_toms(b, B(16), 8, BEAT / 4, freqs=(196, 147, 110, 82), gain=0.8, send=0.45)


BREAK = {"k": [0, 6, 10], "s": [4, 12], "g": [7, 9, 15]}


def sec_90s(b, t0=85.0):
    B = at(t0)
    s16 = BEAT / 4
    prog = [(4, (["G3", "Bb3", "D4", "F4"], "G1")), (2, (["G3", "Bb3", "Eb4", "G4"], "Eb2")), (2, (["A3", "C4", "F4", "A4"], "F1"))]
    for slot in range(DECADE_BEATS * 4):
        t = B(0) + slot * s16
        bar_slot = slot % 16
        if bar_slot in BREAK["k"]:
            b.add(kick(0.9, decay=0.25), t, send=0.02)
        if bar_slot in BREAK["s"]:
            b.add(snare(0.75, tone=210, decay=0.12), t, send=0.12)
        if bar_slot in BREAK["g"]:
            b.add(snare(0.22, tone=210, decay=0.06), t + hum(0.003), send=0.05)
        if slot % 2 == 0:
            b.add(hat(0.35 if slot % 4 == 2 else 0.22, open_=(bar_slot == 14)), t, pan=0.3, send=0.04)
    for beat in range(DECADE_BEATS):
        t = B(beat)
        tones, root = cycle(prog, beat)
        b.add(synth_bass(hz(root) * 2, 0.22, cutoff=500, fenv=4, fdecay=0.06), t + BEAT / 2, gain=0.85, send=0.0)
        stab = supersaw(chord(*tones), 0.14, voices=4, det=18, cutoff=2200, fenv=2.5, fdecay=0.06, a=0.002, d=0.12, s=0.3, r=0.06)
        b.add(stab, t + BEAT / 2, gain=0.5, send=0.25)
        if beat % 4 == 3:
            b.add(stab, t + 3 * BEAT / 4, gain=0.35, send=0.25)
    for mark in (6, 12):
        for k in range(4):
            b.add(snare(0.3 + 0.12 * k, tone=210, decay=0.08), B(mark - 1) + k * BEAT / 4, send=0.1)
        b.add(crash(0.55), B(mark), send=0.25)
        b.add(supersaw(chord("G3", "D4", "G4", "Bb4"), 0.3, voices=5, det=20, cutoff=3000, fenv=2, fdecay=0.1, a=0.002, d=0.2, s=0.3, r=0.15), B(mark), gain=0.6, send=0.3)
    b.add(noise_riser(2.0), B(14), gain=0.35, send=0.2)
    for k in range(8):
        b.add(snare(0.25 + 0.08 * k, tone=210, decay=0.07), B(16) + k * BEAT / 4, send=0.1)


def sec_00s(b, t0=94.0):
    B = at(t0)
    prog = [(2, "C3"), (2, "G2"), (2, "A2"), (2, "F2")]
    b.add(crash(0.6), B(0), send=0.15)
    for beat in range(DECADE_BEATS):
        t = B(beat)
        root = cycle(prog, beat)
        b.add(kick(0.85, decay=0.22), t, send=0.02)
        b.add(snare(0.65, tone=200, decay=0.12), t + BEAT / 2, send=0.12)
        b.add(ride(0.3), t, pan=0.4, send=0.05)
        b.add(ride(0.2), t + BEAT / 2, pan=0.4, send=0.05)
        for k in range(2):
            accent = beat % 2 == 0 and k == 0
            b.add(power_chord(hz(root), BEAT / 2 * 0.9, mute=not accent), t + k * BEAT / 2, gain=0.55 if accent else 0.45, send=0.05)
            b.add(synth_bass(hz(root) / 2, 0.22, cutoff=600, fenv=2, sq=True), t + k * BEAT / 2, gain=0.6, send=0.0)
    for mark in (6, 12):
        _fill_toms(b, B(mark - 1), 4, BEAT / 4, freqs=(200, 160, 120, 90))
        b.add(crash(0.7), B(mark), send=0.2)
        b.add(power_chord(hz("C3"), 1.2), B(mark), gain=0.5, send=0.15)
    for k in range(8):
        b.add(snare(0.3 + 0.08 * k, tone=200, decay=0.08), B(16) + k * BEAT / 4, send=0.1)


TRAP_808 = [(0, 1.25, "C2", None), (1.5, 0.5, "C2", None), (2.25, 0.75, "Eb2", None), (3, 1.0, "G1", "C2"),
            (4, 1.5, "Ab1", None), (5.5, 0.5, "Ab1", None), (6.5, 1.0, "Bb1", None), (7.5, 0.5, "C2", "G2")]
TRAP_BELL = [(0, "G5"), (0.75, "Eb5"), (1.5, "C5"), (2, "D5"), (2.75, "Eb5"), (3.5, "G4"), (4, "Ab5"), (4.75, "G5"),
             (5.5, "Eb5"), (6, "F5"), (6.75, "Eb5"), (7.5, "D5")]


def sec_10s(b, t0=103.0):
    B = at(t0)
    for loop in range(3):
        base = loop * 8
        for beat, ln, nm, glide in TRAP_808:
            bb = base + beat
            if bb >= DECADE_BEATS or (loop and 11.5 <= bb < 12):  # drop before the second moment
                continue
            b.add(bass808(hz(nm), ln * BEAT, glide_from=hz(glide) if glide else None), B(bb), gain=0.9, send=0.0)
            if beat in (0, 4):
                b.add(kick(0.6, decay=0.15), B(bb), send=0.0)
        for beat, nm in TRAP_BELL:
            bb = base + beat
            if bb < DECADE_BEATS:
                b.add(fm_bell(hz(nm), 1.0, idx=2.0, ratio=3.5), B(bb), gain=0.3, pan=0.2, send=0.4)
    for beat in (2, 6, 10, 14):
        b.add(clap(0.8), B(beat), send=0.2)
        b.add(snare(0.4, decay=0.1), B(beat), send=0.15)
    rolls = {1: 4, 3.5: 6, 7.5: 8, 9: 4, 13: 6, 15.5: 8}
    beat = 0.0
    while beat < DECADE_BEATS:
        if beat in rolls:
            div = rolls[beat]
            for k in range(div):
                b.add(hat(0.25 + 0.15 * k / div), B(beat) + k * BEAT / div, pan=0.3, send=0.03)
            beat += 1 if div != 6 else 0.5
            continue
        b.add(hat(0.3), B(beat), pan=0.3, send=0.03)
        beat += 0.5
    b.add(warm_pad(chord("C3", "Eb3", "G3"), 9.0), B(0), gain=0.2, send=0.3)
    for mark in (6, 12):
        b.add(reverse_cymbal(1.0), B(mark) - 1.0, gain=0.35, send=0.2)
        b.add(crash(0.4), B(mark), send=0.25)
    for k in range(9):
        b.add(snare(0.25 + 0.07 * k, decay=0.07), B(16) + k * BEAT / 4.5, send=0.1)


def sec_today(b, t0=112.0):
    """Epic: choir, braams, taiko, string ostinato; the door-holding moment lands at beat 12."""
    B = at(t0)
    harmony = [(0, 4, ("D3", "A3", "D4", "F4")), (4, 2, ("Bb2", "F3", "Bb3", "D4")), (6, 2, ("G2", "D3", "G3", "Bb3")),
               (8, 4, ("D3", "A3", "D4", "F4")), (12, 2, ("Bb2", "F3", "Bb3", "D4")), (14, 2, ("C3", "G3", "C4", "E4")),
               (16, 2, ("A2", "E3", "A3", "C#4"))]
    for start, ln, tones in harmony:
        b.add(choir(chord(*tones), ln * BEAT), B(start), gain=0.6, send=0.45)
        root = hz(tones[0])
        for k in range(ln * 2):
            mult = (1, 1, 2, 1)[k % 4]
            b.add(supersaw([root * mult], 0.18, voices=3, det=8, cutoff=2400, a=0.004, d=0.1, s=0.2, r=0.05), B(start) + k * BEAT / 2, gain=0.45, send=0.3)
    for mark, tones in ((0, ("D2", "A2", "D3")), (6, ("G1", "D2", "G2")), (12, ("Bb1", "F2", "Bb2"))):
        b.add(braam(chord(*tones), 1.6), B(mark), gain=0.7, send=0.4)
        b.add(taiko(1.0, big=True), B(mark), gain=1.0, send=0.45)
        b.add(crash(0.45, decay=2.0), B(mark), send=0.35)
        if mark:
            b.add(reverse_cymbal(1.5), B(mark) - 1.5, gain=0.4, send=0.3)
    pattern = [0, 3, 4, 7, 8, 10, 11]
    for slot in range(DECADE_BEATS * 2):
        if slot % 8 in pattern and slot % 12:
            b.add(taiko(vj(0.75)), B(0) + slot * BEAT / 2, gain=0.8, pan=0.15 * ((slot % 3) - 1), send=0.35)
    for k in range(8):
        b.add(taiko(0.5 + 0.06 * k), B(16) + k * BEAT / 4, gain=0.85, send=0.4)
    b.add(reverse_cymbal(1.0), B(18) - 1.0, gain=0.4, send=0.3)


def sec_outro(b, t0=121.0):
    B = at(t0)
    b.add(warm_pad(chord("D3", "A3", "C#4", "E4", "F#4"), 3.4), B(0), gain=0.5, send=0.4)
    b.add(bass_pluck(hz("D2"), 0.9, dur=1.2), B(0), send=0.1)
    b.add(bass_pluck(hz("A1"), 0.7, dur=0.8), B(2), send=0.1)
    b.add(bass_pluck(hz("D2"), 0.7, dur=0.8), B(4), send=0.1)
    arp = ["D4", "F#4", "A4", "D5", "E5", "C#5", "A4", "F#4", "G4", "B4", "D5", "F#5"]
    for k, nm in enumerate(arp):
        b.add(marimba(hz(nm), vj(0.5)), B(k * 0.5) + hum(), pan=0.2, send=0.3)
    b.add(supersaw(chord("A3", "C#4", "E4"), 0.1, voices=4, det=10, cutoff=2500, fenv=2, fdecay=0.05, a=0.003, d=0.08, s=0.5, r=0.05), B(6.5), gain=0.4, send=0.3)
    hit = B(7)
    b.add(supersaw(chord("D3", "F#3", "A3", "D4", "F#4", "A4"), 0.8, voices=5, det=12, cutoff=2600, fenv=2, fdecay=0.2, a=0.004, d=0.5, s=0.5, r=0.6), hit, gain=0.6, send=0.4)
    for nm in ("D4", "F#4", "A4", "D5"):
        b.add(marimba(hz(nm), 0.6), hit, pan=0.2, send=0.3)
    b.add(bell(hz("D6"), 1.5), hit, gain=0.35, send=0.5)
    b.add(kick(0.6), hit, send=0.1)
    b.add(tom(73, 0.6, decay=0.6), hit, send=0.3)
    b.add(crash(0.35, decay=2.0), hit, send=0.4)
    for k, nm in enumerate(["D6", "F#6", "A6", "D7"]):
        b.add(kalimba(hz(nm), 0.4), hit + 0.08 + k * 0.07, pan=-0.5 + 0.33 * k, send=0.5)


# (name, start, end, target loudness, tail seconds, renderer)
SECTIONS = [
    ("intro_hops", 0.0, 12.0, 0.050, 0.4, sec_intro_hops),
    ("sneaky", 12.0, 28.0, 0.045, 0.4, sec_sneaky),
    ("ninja", 28.0, 37.0, 0.070, 0.6, sec_ninja),
    ("disappointed", 37.0, 43.0, 0.045, 0.4, sec_sad),
    ("nonono", 43.0, 48.0, 0.060, 0.03, sec_nonono),
    ("jam", 48.0, 58.0, 0.095, 0.08, sec_jam),
    ("d60s", 58.0, 67.0, 0.095, 0.08, sec_60s),
    ("d70s", 67.0, 76.0, 0.095, 0.08, sec_70s),
    ("d80s", 76.0, 85.0, 0.095, 0.08, sec_80s),
    ("d90s", 85.0, 94.0, 0.095, 0.08, sec_90s),
    ("d00s", 94.0, 103.0, 0.095, 0.08, sec_00s),
    ("d10s", 103.0, 112.0, 0.095, 0.08, sec_10s),
    ("dToday", 112.0, 121.0, 0.105, 0.3, sec_today),
    ("outro", 121.0, 126.0, 0.075, 0.0, sec_outro),
]
