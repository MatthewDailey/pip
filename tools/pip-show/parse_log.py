#!/usr/bin/env python3
"""Turn `adb logcat -v epoch -s PipShow` output into cues.json and the video->show offset.

  parse_log.py --log logcat.txt --rec-start <device epoch secs> --cues-out cues.json [--shift 112]

Prints the offset in seconds (show time 0 relative to the first video frame) on stdout.
Show time 0 is estimated from the cue lines themselves (each PIPCUE is logged on the first
frame at or after its time, so log_time - t is a slightly late estimate of the start; the
lowest decile is used). The `PIPSHOW start` line (logged when the overlay is added, about a
frame before the first animated frame) is the fallback.

--shift adds that many seconds to every cue and moves show zero back by the same amount: a clip that
starts at show time 112 logs cues from 0, and is shifted so the cues and offset are in full-show time.
"""
from __future__ import annotations

import argparse
import json
import re
import sys

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+PipShow\s*:\s*(.*)$")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True)
    ap.add_argument("--rec-start", type=float, required=True, help="device epoch when screenrecord started")
    ap.add_argument("--cues-out", required=True)
    ap.add_argument("--shift", type=float, default=0.0, help="show time at which the played clip starts")
    args = ap.parse_args()

    cues, starts, start_offset = [], [], 0.0
    with open(args.log, errors="replace") as fh:
        for raw in fh:
            m = LINE.match(raw)
            if not m:
                continue
            ts, msg = float(m.group(1)), m.group(2).strip()
            parts = msg.split()
            if parts[:2] == ["PIPSHOW", "start"]:
                starts.append(ts)
                for p in parts[2:]:
                    if p.startswith("offset="):
                        start_offset = float(p.split("=", 1)[1])
            elif parts[:1] == ["PIPCUE"] and len(parts) >= 3:
                # The time is the last field: a spoken cue's name (`say:<line>`) has spaces of its own.
                cues.append({"name": " ".join(parts[1:-1]), "t": float(parts[-1]), "logged": ts})

    if not starts and not cues:
        sys.exit("error: no PIPSHOW start or PIPCUE lines in the log")
    if starts:  # only keep the last run
        cues = [c for c in cues if c["logged"] >= starts[-1] - 0.5]
    if cues:
        est = sorted(c["logged"] - c["t"] for c in cues)
        show_zero = est[len(est) // 10]
        if starts and abs(show_zero - (starts[-1] - start_offset)) > 0.5:
            print(f"warning: cue-based start {show_zero:.3f} differs from PIPSHOW start "
                  f"{starts[-1] - start_offset:.3f}; using the start line", file=sys.stderr)
            show_zero = starts[-1] - start_offset + 0.016
    else:
        show_zero = starts[-1] - start_offset + 0.016

    show_zero -= args.shift
    with open(args.cues_out, "w") as fh:
        json.dump([{"name": c["name"], "t": round(c["t"] + args.shift, 4)} for c in cues], fh, indent=1)
    print(f"parsed {len(cues)} cues, show zero at device epoch {show_zero:.3f}", file=sys.stderr)
    print(f"{show_zero - args.rec_start:.4f}")


if __name__ == "__main__":
    main()
