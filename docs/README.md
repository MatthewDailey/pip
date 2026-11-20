# Design write-ups

Point-in-time write-ups from building Pip. They were written in the original prototype tree before this
repo existed; paths are updated to this layout, but commands and numbers describe the state on the
day they were written, and the code is the source of truth.

| Write-up | What it covers |
| --- | --- |
| [animation-stack-research.md](animation-stack-research.md) | Options for animating Pip (Compose keyframes, Lottie, Rive, ...) and the recommendation |
| [animation-playbook.md](animation-playbook.md) | How the Kotlin keyframe engine and showreel are built, and how to author scenes |
| [rive-spike-results.md](rive-spike-results.md) | Spike: Pip on the Rive runtime inside the accessibility overlay |
| [riv-compiler-spike-results.md](riv-compiler-spike-results.md) | Spike: writing `.riv` files ourselves and playing them on the stock runtime |
| [showreel-rive-spike-results.md](showreel-rive-spike-results.md) | Spike: the showreel features (nested artboards, text, cues) in `.riv` |

`pip.svg` / `pip.png` are Pip's traced artwork; [img/playground.png](img/playground.png) is the playground app.

Paths moved when the work became this repo:

| prototype tree | shady-pip |
| --- | --- |
| `app/`, Gradle files, `scripts/simulate.sh` | `apps/pip-app/` |
| `tools/rivc` | `packages/rivc` |
| `tools/rivc-preview` | `packages/rivc-preview` |
| `tools/showreel-to-rivc` | `packages/showreel-to-rivc` |
| `tools/mock_call.*`, `emu_mic.py`, `lines/` | `tools/mock-calls/` |
| `tools/pip_show` | `tools/pip-show` |
| `tools/pip_art` | `tools/pip-art` |
| `design/` | `docs/` |
| `.proof/` (evidence) | not committed; local `.proof/` at the repo root |
