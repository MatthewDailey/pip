# rivc-preview

A local previewer and golden-image tests for Pip's compiled `.riv`, using the same pinned web
runtime (`@rive-app/canvas` 2.52.0, vendored in `vendor/`) for both.

Setup is the repo's: every turbo task runs `scripts/setup.sh` first, which installs the workspace with pnpm
(Playwright 1.61.0, pngjs and pixelmatch are pinned in `package.json`) and Playwright's Chromium. By hand:
`pnpm install` at the repo root and `turbo run playwright:install`.

## Previewer

```sh
turbo run preview                                                        # Pip's rig, http://localhost:8787/
PREVIEW_SRC=packages/pip-rive/stories/hello/hello.json turbo run preview # any source (relative to the repo root)
PORT=8797 PREVIEW_SRC=packages/pip-rive/showreel/hodor.json turbo run preview
```

`turbo dev` runs the same thing in its `@shady-pip/rivc-preview#dev` pane. Underneath it is
`packages/rivc-preview/serve.sh <source> [port]`.

`serve.sh` compiles the source to `packages/rivc-preview/pip.riv`, serves the folder, and watches the
source plus every relative file it references (for `pip.json`: its SVG and so on). On a change it
recompiles and the page swaps in the new file within about a second, keeping your control values.
A failed build leaves the last good file playing and shows the compiler's error in the header.
It uses `fswatch` if installed, otherwise polls twice a second.

The compile command is `RIVC_CMD`, with `{src}` and `{out}` placeholders:

- default when `packages/rivc/rivc` exists and the source is `.json`: `packages/rivc/rivc compile {src} -o {out}`
- otherwise the spike compiler on an SVG: `../pip-riv-compiler/build/install/pip-riv-compiler/bin/pip-riv-compiler {src} {out}`
  (override its path with `PIP_SPIKE_COMPILER`)

```sh
RIVC_CMD='java -jar my-rivc.jar {src} {out}' packages/rivc-preview/serve.sh design/pip.json
```

Without `serve.sh`, any static server works (`python3 -m http.server` in this folder); the page
then plays `fixtures/spike-pip.riv` if there is no `pip.riv`, and polls `build.json` for reloads.
`?riv=<url>` loads another file, `?artboard=<name>` opens an artboard other than the first, `?sm=<name>` picks a
state machine other than the artboard's first. `?t=<seconds>` opens paused at that time on the
timeline; `window.pipPreview.settled` turns true once the page has loaded and seeked, so a script can wait for it
and screenshot (golden specs set the time with `time` instead).

The page:

- plays the file's first artboard (what the device plays) through its first state machine; the Artboard picker
  opens any other, such as the nested `Pip` of a story. The canvas takes the artboard's shape, so a 1080 x 2424
  story stands tall.
- a Timeline slider, in seconds, over the playing artboard's first clip (a story's timeline). Scrubbing replays the file from
  t = 0 in fixed 1/60 s steps, so the frame shown is exactly the frame at t, state machines and events included.
- the latest Rive event in the header (stories cue sounds with events), and every event in the log.
- plays the first state machine and builds controls from the view model: sliders for numbers
  (default 0 to 10 in whole steps; the range and step are editable), checkboxes for booleans,
  buttons for triggers, dropdowns for enums. The state machine's legacy inputs get the same
  controls, so a file without a view model is still drivable.
- checkerboard background, or "Over home-screen screenshot" to see Pip on `.proof/pip1.png`
  (served as `/backdrop.png`; pass `--backdrop` to `server.mjs` or set `BACKDROP` for another).
- play/pause, speed (0.05 to 3x), and frame stepping (+1 frame, +10, +1 s at 60 fps; stepping
  pauses). Time is virtual: the page feeds the runtime its own frame timestamps.
- an event log of Rive events, state changes, view-model value changes and builds.

## Golden tests

```sh
turbo run goldens          # node golden.mjs: compare against goldens/*.png; exit 1 on a mismatch
turbo run golden:update    # node golden.mjs --update: rewrite the goldens after an intended change
```

Each entry in `goldens.json` names a state: `values` to set (view-model properties by name, or
`"input:mood"` for a legacy input), `triggers` to fire at `triggerAt` seconds, and a `time` to
advance to in fixed 1/60 s steps. Renders are bit-for-bit repeatable, so the default tolerance
(`maxDiffPixels`, 25) only absorbs antialiasing noise. A failure writes
`goldens/<name>.actual.png` and `goldens/<name>.diff.png` (changed pixels in red) and prints the
count.

Options: `--riv <file>` renders another file (e.g. fresh `rivc` output) instead of
`goldens.json`'s `riv`; `--only <name>`; `--max-diff <n>`; `--spec <json>`; `--out <dir>`.

The goldens cover the spike's file (`fixtures/spike-pip.riv`), the rig compiled by rivc
(`goldens/pip-rig.riv` and `pip-rig-inputs.riv`: moods, looks, a blink, alert, poke, talking) and the hello story
(`goldens/hello.riv`). Each entry's `riv` is a committed compiled file: recompile it after an intended change, then
`node golden.mjs --update --only <name>` and look at the new PNGs. The page error
`Could not find a View Model linked to Artboard ...` is expected for files without a view model.

## Animation-pass proof

```sh
packages/rivc/rivc compile packages/rivc/examples/anim/anim.json -o packages/rivc/examples/anim/anim.riv
node packages/rivc-preview/anim-proof.mjs     # writes .proof/rivc-anim/proof.{txt,json} and frames
```

Plays `examples/anim/anim.riv` through the same page in golden mode and measures pixels: the
bob clip's eased curve against easeInOut and linear, the `mood` enum and `alert` trigger
transitions' mix over their source durations, clip and state events reaching
`rive.on(EventType.RiveEvent)`, the 1D blend on `lookX` and the direct blend on `smile`.
Exit 1 if any check fails.

## Project proof

```sh
node project-proof.mjs [--riv ../rivc/examples/project/project.riv] [--out ../../.proof/rivc-project]
```

Plays the multi-artboard example (`packages/rivc/examples/project/`) on a 540x1212 canvas (the
1080x2424 main artboard at half scale) and writes `proof.txt`, `proof.json` and PNGs: frames at
the keyed times (each nested Pip's mood by face colour, Pip A's blink by eye pixels, the bubble's
text change, the banner fade-in), the Rive event log with timeline seconds, and frames with the
mask moved through the `holeX`/`holeY` view-model numbers.

## Art check (SVG import)

```sh
node art-compare.mjs --out ../../.proof/rivc-art path/to/pip.json ...   # --scale 2, --threshold 0.1
```

Compiles each source with `packages/rivc/rivc`, renders the `.riv`'s setup pose with the vendored
runtime and the source's SVG with Chromium's own renderer at the artboard's size (art placed inside
the padding), and writes `<name>.svg.png`, `.rive.png`, `.diff.png` and a side-by-side `.sheet.png`.
It prints two counts: pixelmatch's (antialiased pixels excluded) and a stricter one counting every
pixel whose channels differ by more than 24/255. `<text>` isn't drawn by rivc, so text shows up as
a difference by design.
