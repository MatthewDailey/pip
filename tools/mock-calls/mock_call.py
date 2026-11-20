"""Scripted caller: `say` renders prepared lines to clips, which are injected straight into the emulator's
microphone during a fake call, the same path GPT-Live audio takes. The app records and transcribes them as call
audio. Each line also plays on the Mac so you can follow along (the emulator's own speaker is off).

  tools/mock-calls/mock_call.sh irs                # ring, wait for you to answer on the emulator, then speak
  tools/mock-calls/mock_call.sh grandson --auto    # answer automatically
  tools/mock-calls/mock_call.sh irs --pipe         # also send each line's text over adb (tests scoring without speech recognition)
  tools/mock-calls/mock_call.sh irs --quiet        # don't play locally
  tools/mock-calls/mock_call.sh irs --hold         # keep the call open after the script until it's hung up on the emulator
  tools/mock-calls/mock_call.sh --list

Scripts live in tools/mock-calls/lines/<name>.txt: one spoken line per row, optional first row "voice: <say voice>".
"""

import argparse
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

from emu_mic import EmuMic, read_wav

HERE = Path(__file__).parent
LINES = HERE / "lines"
CACHE = HERE / ".mock-audio"
RATE = 16_000
RECEIVER = "dev.playground.pip/.sources.SimTranscriptReceiver"


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def call_state() -> int:
    """0 idle, 1 ringing, 2 off-hook."""
    for line in adb("shell", "dumpsys", "telephony.registry").splitlines():
        if "mCallState=" in line:
            return int(line.split("=", 1)[1].strip())
    return 0


def load(name: str) -> tuple[str, list[str]]:
    voice, lines = "Eddy (English (US))", []
    for row in (LINES / f"{name}.txt").read_text().splitlines():
        if row.startswith("voice:"):
            voice = row.split(":", 1)[1].strip()
        elif row.strip():
            lines.append(row.strip())
    return voice, lines


def render(name: str, voice: str, lines: list[str]) -> list[Path]:
    """Renders each line once with `say`; re-renders when the script file changes."""
    src = LINES / f"{name}.txt"
    out = CACHE / name
    clips = [out / f"{i:02d}.wav" for i in range(len(lines))]
    if not out.exists() or src.stat().st_mtime > out.stat().st_mtime or not all(c.exists() for c in clips):
        out.mkdir(parents=True, exist_ok=True)
        for clip, text in zip(clips, lines):
            subprocess.run(["say", "-v", voice, f"--data-format=LEI16@{RATE}", "-o", str(clip), text], check=True)
        out.touch()
    return clips


def main() -> None:
    names = sorted(p.stem for p in LINES.glob("*.txt"))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("script", nargs="?", choices=names)
    ap.add_argument("--auto", action="store_true", help="answer the call automatically")
    ap.add_argument("--pipe", action="store_true", help="also send each line's text to the app over adb")
    ap.add_argument("--quiet", action="store_true", help="don't play the lines on the Mac")
    ap.add_argument("--pause", type=float, default=2.0, help="seconds of silence after each line")
    ap.add_argument("--hold", action="store_true", help="after the script, keep the call open until it's hung up on the emulator")
    ap.add_argument("--number", default="8005550123")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    if args.list or not args.script:
        print("\n".join(names))
        return

    # A runner stopping us (SIGTERM) still hangs the call up through the finally below.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    voice, lines = load(args.script)
    clips = render(args.script, voice, lines)

    adb("emu", "gsm", "call", args.number)
    print(f"Ringing the emulator from {args.number} ({args.script}, voice: {voice}).", flush=True)
    if args.auto:
        time.sleep(2)
        adb("emu", "gsm", "accept", args.number)
    else:
        print("Answer the call on the emulator...", flush=True)
    deadline = time.monotonic() + 60
    while call_state() != 2:
        if time.monotonic() > deadline:
            adb("emu", "gsm", "cancel", args.number)
            sys.exit("Call was not answered.")
        time.sleep(0.5)
    print("Connected.", flush=True)

    try:
        with EmuMic(RATE) as mic:
            time.sleep(1)
            for text, clip in zip(lines, clips):
                if call_state() != 2:
                    print("Call ended on the emulator.")
                    return
                print(f"CALLER: {text}", flush=True)
                if args.pipe:
                    cmd = f"am broadcast -n {RECEIVER} --es speaker caller --es call mock-{args.script} --es text {shlex.quote(text)}"
                    subprocess.Popen(["adb", "shell", cmd], stdout=subprocess.DEVNULL)
                local = None if args.quiet else subprocess.Popen(["afplay", str(clip)])
                mic.write(read_wav(str(clip))[1])
                mic.write(b"\0" * int(RATE * args.pause) * 2)
                mic.drain()
                if local:
                    local.wait()
            time.sleep(1)
        # Hold only after the mic stream has closed: an injection still open when the call ends crashes the emulator.
        if args.hold:
            print("Script done; the call stays open until you hang up on the emulator.", flush=True)
            while call_state() == 2:
                time.sleep(0.5)
    finally:
        if call_state() != 0:
            adb("emu", "gsm", "cancel", args.number)
        print("Hung up.")


if __name__ == "__main__":
    main()
