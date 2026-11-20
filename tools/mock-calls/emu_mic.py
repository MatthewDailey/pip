"""Feed audio into the running emulator's microphone over its gRPC control API (EmulatorController.injectAudio).

What the emulator hears this way is exactly what an app recording the mic gets, so a simulated caller's voice
reaches the app's call capture and speech recognizer without passing through the Mac's speakers.

  python emu_mic.py clip.wav [more.wav ...]   # play WAV files (any PCM16 mono rate up to 48 kHz) into the mic

From code, use EmuMic as a sink for live PCM16 mono:
  with EmuMic(rate=24000) as mic:
      mic.write(pcm_bytes)
"""

import os
import queue
import re
import subprocess
import sys
import threading
import time
import wave
from pathlib import Path

# Callers spawn afplay/adb while the stream runs; gRPC's fork handlers only add log noise for those children.
os.environ.setdefault("GRPC_ENABLE_FORK_SUPPORT", "0")
import grpc  # noqa: E402

HERE = Path(__file__).parent
GEN = HERE / ".gen"
PROTO_DIR = Path.home() / "Library/Android/sdk/emulator/lib"


def _stubs():
    """Generate Python stubs from the SDK's own proto once, so they always match the installed emulator."""
    if not (GEN / "emulator_controller_pb2_grpc.py").exists():
        GEN.mkdir(exist_ok=True)
        from grpc_tools import protoc
        import grpc_tools

        include = Path(grpc_tools.__file__).parent / "_proto"
        rc = protoc.main(["protoc", f"-I{PROTO_DIR}", f"-I{include}", f"--python_out={GEN}", f"--grpc_python_out={GEN}", str(PROTO_DIR / "emulator_controller.proto")])
        if rc != 0:
            raise RuntimeError("protoc failed on emulator_controller.proto")
    sys.path.insert(0, str(GEN))
    import emulator_controller_pb2 as pb
    import emulator_controller_pb2_grpc as rpc

    return pb, rpc


def _discovery() -> dict[str, str]:
    """The running emulator's discovery file holds its gRPC port and bearer token."""
    path = subprocess.run(["adb", "emu", "avd", "discoverypath"], capture_output=True, text=True).stdout.splitlines()[0].strip()
    return dict(re.findall(r"^([\w.]+)=(.*)$", Path(path).read_text(), re.M))


def guest_recording() -> bool:
    """True while some app inside Android has the microphone open (dumpsys lists it as an active source client)."""
    out = subprocess.run(["adb", "shell", "dumpsys", "audio"], capture_output=True, text=True).stdout
    return " -- source client=" in out


def wait_for_guest_recording(timeout: float = 15.0) -> None:
    deadline = time.monotonic() + timeout
    while not guest_recording():
        if time.monotonic() > deadline:
            raise RuntimeError("Nothing in the emulator is recording; injecting now would crash it. Is the call active and the app's accessibility service on?")
        time.sleep(0.3)


class EmuMic:
    """Streams PCM16 mono into the emulator mic. Silence fills gaps so the stream never starves."""

    CHUNK_MS = 20

    def __init__(self, rate: int = 24000):
        self.rate = rate
        self.pb, rpc = _stubs()
        info = _discovery()
        self.channel = grpc.insecure_channel(f"localhost:{info['grpc.port']}")
        self.stub = rpc.EmulatorControllerStub(self.channel)
        self.metadata = [("authorization", f"Bearer {info['grpc.token']}")] if info.get("grpc.token") else []
        self.q: queue.Queue[bytes | None] = queue.Queue()
        self.pending = bytearray()
        self.error: Exception | None = None

    def __enter__(self):
        # Emulator 37.2 segfaults in injectAudio (QemuAudioInputEngine::start -> audio_forwarder_enable reads a
        # null pointer) when the stream opens while nothing in Android is recording. Wait for a live recording.
        wait_for_guest_recording()
        # The Mac-mic link outranks injected audio, so switch it off for the call (only after a recording is live).
        from google.protobuf import empty_pb2

        if self.stub.getMicrophoneState(empty_pb2.Empty(), metadata=self.metadata).realAudioEnabled:
            self.stub.setMicrophoneState(self.pb.MicrophoneState(realAudioEnabled=False), metadata=self.metadata)
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        # Emulator 37.2 also segfaults (my_microphone_avail) when Android stops recording while a stream is still
        # open, e.g. the call is hung up mid-line. Watch for that and end the stream straight away.
        self.done = threading.Event()
        threading.Thread(target=self._watch, daemon=True).start()
        return self

    def _watch(self):
        while not self.done.wait(0.25):
            if not guest_recording():
                self.closed_by_guest = True
                self.q.put(None)
                return

    def __exit__(self, *exc):
        self.done.set()
        self.q.put(None)
        self.thread.join(5)
        self.channel.close()
        # The emulator flushes up to 300 ms of queued audio after a stream closes; let it finish before anyone
        # opens the next stream.
        time.sleep(0.5)
        # Closing the channel ourselves cancels a stream that was still finishing; that isn't a failure.
        cancelled = isinstance(self.error, grpc.RpcError) and self.error.code() == grpc.StatusCode.CANCELLED
        if self.error and not exc[0] and not cancelled:
            raise self.error

    closed_by_guest = False

    def write(self, pcm: bytes) -> None:
        if self.closed_by_guest:
            return
        if self.error:
            raise self.error
        self.q.put(pcm)

    def drain(self) -> None:
        """Blocks until everything written so far has been sent at real-time pace."""
        while (not self.q.empty() or self.pending) and not self.closed_by_guest:
            time.sleep(0.02)

    def _packets(self):
        fmt = self.pb.AudioFormat(samplingRate=self.rate, channels=self.pb.AudioFormat.Mono, format=self.pb.AudioFormat.AUD_FMT_S16)
        frame = self.rate * self.CHUNK_MS // 1000 * 2
        start = time.monotonic()
        sent = 0
        while True:
            try:
                while True:
                    item = self.q.get_nowait()
                    if item is None:
                        return
                    self.pending.extend(item)
            except queue.Empty:
                pass
            chunk = bytes(self.pending[:frame])
            del self.pending[:frame]
            chunk += b"\0" * (frame - len(chunk))
            yield self.pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1e6), audio=chunk)
            sent += 1
            # Pace at real time so live sources (a voice model) stay in sync with the call.
            ahead = start + sent * self.CHUNK_MS / 1000 - time.monotonic()
            if ahead > 0:
                time.sleep(ahead)

    def _run(self):
        try:
            self.stub.injectAudio(self._packets(), metadata=self.metadata)
        except Exception as e:  # surfaced on the next write or on exit
            self.error = e


def read_wav(path: str) -> tuple[int, bytes]:
    with wave.open(path) as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError(f"{path}: need PCM16 mono")
        return w.getframerate(), w.readframes(w.getnframes())


if __name__ == "__main__":
    files = sys.argv[1:]
    if not files:
        sys.exit(__doc__)
    rate = read_wav(files[0])[0]
    with EmuMic(rate) as mic:
        for f in files:
            r, pcm = read_wav(f)
            if r != rate:
                sys.exit(f"{f}: all clips must share one sample rate ({rate})")
            mic.write(pcm)
            mic.drain()
