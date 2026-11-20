"""Renders Pip's first-run greeting lines in a GPT-Live voice, as clips the app bundles.

The greeting (packages/pip-rive/stories/hello-install) fires a `say:<line>` event for each spoken line. The app plays
the clip for that line from assets/greeting-voice/ when there is one, and falls back to the phone's text-to-speech
when there isn't. This script makes those clips: one GPT-Live session per line, told to say the line word for word in
the voice and style below, and writes a 24 kHz mono WAV per line plus lines.json (line -> file).

  tools/pip-voice/render_greeting.sh             # render lines that have no clip yet (or whose voice/style changed)
  tools/pip-voice/render_greeting.sh --force     # re-render every line
  tools/pip-voice/render_greeting.sh --dry-run   # list the lines and what would be rendered

Each take's transcript is checked against the line; a take that says something else is retried.
"""

import argparse
import asyncio
import base64
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
STORY = ROOT / "packages/pip-rive/stories/hello-install/hello-install.json"
OUT = ROOT / "apps/pip-app/app/src/main/assets/greeting-voice"
MANIFEST = OUT / "lines.json"

MODEL = "gpt-live-1.1"
VOICE = "ballad"
STYLE = (
    "Voice style: old-timey. You sound like a 1940s radio announcer or newsreel narrator: a warm, bright "
    "transatlantic accent, crisp diction, a little showmanship and a twinkle in the eye. Upbeat and brisk, never slow "
    "or sleepy. You are Pip, a small, friendly cartoon character who spots scams."
)
RULES = (
    "You only ever perform the one line you are given. Say it exactly, word for word, once, in English, with nothing "
    "before or after it: no greeting, no reply, no commentary, no repeating."
)

RATE = 24_000
QUIET_S = 1.5  # no audio for this long after speech started: the line is over
TAKE_TIMEOUT_S = 30
TRIES = 3
# GPT-Live only takes a turn after the user speaks, so each take is cued by a short spoken prompt (macOS `say`).
CUE_TEXT = "Go ahead."


def cue_pcm() -> bytes:
    """The spoken cue as 24 kHz mono PCM16, followed by a second of silence so the user's turn ends."""
    with tempfile.TemporaryDirectory() as d:
        path = Path(d) / "cue.wav"
        subprocess.run(["say", "-o", str(path), f"--data-format=LEI16@{RATE}", CUE_TEXT], check=True)
        with wave.open(str(path)) as w:
            pcm = w.readframes(w.getnframes())
    return pcm + bytes(2 * RATE)


def story_lines() -> list[str]:
    """The `say:` lines in the order the story speaks them (by first frame)."""
    src = json.loads(STORY.read_text())
    found: dict[str, float] = {}

    def walk(node):
        if isinstance(node, dict):
            name = node.get("name")
            if isinstance(name, str) and name.startswith("say:") and node.get("frames"):
                line = name[4:]
                found[line] = min(found.get(line, float("inf")), min(node["frames"]))
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(src)
    return sorted(found, key=lambda l: found[l])


def clip_name(line: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", line.lower().replace("'", "")).strip("-")[:32].strip("-")
    return f"{slug}-{hashlib.sha1(line.encode()).hexdigest()[:8]}.wav"


def words(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+", text.lower().replace("'", ""))


def trim(pcm: bytes) -> bytes:
    """Cuts leading and trailing near-silence, keeping a short pad. The tail uses a lower threshold and a longer pad
    than the head: a line's last sound is often a quiet fricative ("safe") that the speech threshold would cut off.
    A short fade-out keeps the cut from clicking."""
    a = np.frombuffer(pcm, dtype=np.int16)
    mag = np.abs(a.astype(np.int32))
    loud = np.flatnonzero(mag > 500)
    if loud.size == 0:
        return pcm
    quiet = np.flatnonzero(mag > 120)
    start = max(0, loud[0] - int(RATE * 0.04))
    end = min(len(a), quiet[-1] + int(RATE * 0.15))
    out = a[start:end].astype(np.float64)
    fade = min(len(out), int(RATE * 0.03))
    out[len(out) - fade:] *= np.linspace(1, 0, fade)
    return np.round(out).astype(np.int16).tobytes()


async def take(client, line: str, voice: str, style: str, cue: bytes) -> tuple[bytes, str]:
    session = {
        "model": MODEL,
        "audio": {"format": {"type": "audio/pcm", "rate": RATE}, "output": {"voice": voice}},
        "instructions": f"{style}\n\n{RULES}",
        "input": [{
            "type": "message",
            "role": "developer",
            "content": [{"type": "input_text", "text": f"Say this line now, word for word:\n{line}"}],
        }],
    }
    audio = bytearray()
    said: list[str] = []
    async with client.live.connect() as conn:
        await conn.session.start(session=session)
        loop = asyncio.get_running_loop()
        last = loop.time()
        deadline = last + TAKE_TIMEOUT_S
        heard = False  # the session streams silence until the model speaks; only speech counts as audio

        async def send_cue():
            chunk = RATE // 10 * 2  # 100 ms, sent in real time
            for i in range(0, len(cue), chunk):
                await conn.session.input_audio.append(audio=base64.b64encode(cue[i:i + chunk]).decode())
                await asyncio.sleep(0.1)

        async def receive():
            nonlocal last, heard
            async for event in conn:
                if event.type == "session.started":
                    asyncio.ensure_future(send_cue())
                elif event.type == "session.output_audio.delta":
                    chunk = base64.b64decode(event.delta)
                    audio.extend(chunk)
                    if np.abs(np.frombuffer(chunk, dtype=np.int16).astype(np.int32)).max(initial=0) > 500:
                        heard = True
                        last = loop.time()
                elif event.type == "session.output_transcript.delta":
                    said.append(event.delta)
                elif event.type == "error":
                    raise RuntimeError(event.model_dump_json())
                elif event.type == "session.closed":
                    return

        recv = asyncio.create_task(receive())
        try:
            while not recv.done():
                now = loop.time()
                if (heard and now - last > QUIET_S) or now > deadline:
                    break
                await asyncio.sleep(0.1)
            if recv.done():
                recv.result()  # surface an error event
        finally:
            if not recv.done():
                await conn.session.close()
                try:
                    await asyncio.wait_for(recv, timeout=5)
                except Exception:
                    recv.cancel()
    return trim(bytes(audio)), "".join(said).strip()


PEAK_DBFS = -3.0  # leaves headroom: a phone or emulator at high volume crackles on peaks near full scale


def normalize(pcm: bytes) -> bytes:
    """Raises a clip so its peak sits at PEAK_DBFS: GPT-Live renders peak around -12 dBFS, too quiet on a phone."""
    a = np.frombuffer(pcm, dtype=np.int16).astype(np.float64)
    peak = np.abs(a).max() if a.size else 0
    if peak == 0:
        return pcm
    gain = 32767 * 10 ** (PEAK_DBFS / 20) / peak
    return np.clip(np.round(a * gain), -32768, 32767).astype(np.int16).tobytes()


def write_wav(path: Path, pcm: bytes) -> None:
    pcm = normalize(pcm)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


async def run(args) -> None:
    lines = story_lines()
    if not lines:
        sys.exit(f"No say: lines in {STORY}")
    old = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    same_voice = old.get("model") == MODEL and old.get("voice") == args.voice and old.get("style") == args.style
    have = old.get("lines", {}) if same_voice else {}
    todo = [l for l in lines if args.force or l not in have or not (OUT / have[l]).exists()]

    for l in lines:
        print(f"  {'render' if l in todo else 'keep  '}  {l}")
    if args.dry_run or not todo:
        print("Nothing to render." if not todo else f"{len(todo)} line(s) would be rendered.")
        if args.dry_run:
            return

    OUT.mkdir(parents=True, exist_ok=True)
    clips = {l: have[l] for l in lines if l in have and l not in todo}
    if todo:
        from openai import AsyncOpenAI

        cue = cue_pcm()
        async with AsyncOpenAI() as client:
            for line in todo:
                for attempt in range(1, TRIES + 1):
                    pcm, said = await take(client, line, args.voice, args.style, cue)
                    ok = bool(pcm) and words(said) == words(line)
                    secs = len(pcm) / 2 / RATE
                    print(f"{'ok ' if ok else 'bad'} {secs:4.1f}s  {line!r} -> said {said!r}"
                          + ("" if ok or attempt == TRIES else "; retrying"), flush=True)
                    if ok or (attempt == TRIES and pcm and args.keep_last):
                        break
                else:
                    sys.exit(f"Couldn't get a clean take of {line!r} in {TRIES} tries; rerun, or pass --keep-last.")
                name = clip_name(line)
                write_wav(OUT / name, pcm)
                clips[line] = name

    # Drop clips for lines the story no longer speaks.
    for f in OUT.glob("*.wav"):
        if f.name not in clips.values():
            f.unlink()
            print(f"removed {f.name}")
    MANIFEST.write_text(json.dumps(
        {"model": MODEL, "voice": args.voice, "style": args.style, "lines": {l: clips[l] for l in lines}},
        indent=2, ensure_ascii=False) + "\n")
    print(f"Wrote {len(clips)} clip(s) and {MANIFEST.relative_to(ROOT)}. Rebuild the app (turbo demo) to bundle them.")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--voice", default=VOICE, help=f"GPT-Live built-in voice (default {VOICE})")
    ap.add_argument("--style", default=STYLE, help="how the voice should sound (the session instructions)")
    ap.add_argument("--force", action="store_true", help="re-render every line")
    ap.add_argument("--keep-last", action="store_true", help="keep the last take even if its transcript differs")
    ap.add_argument("--dry-run", action="store_true", help="list the lines and what would be rendered")
    asyncio.run(run(ap.parse_args()))


if __name__ == "__main__":
    main()
