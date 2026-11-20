---
name: animating-pip
description: Change Pip's Rive rig (moods, blinks, glances, new inputs), make a new Pip story (a short Rive scene like the showreel's HODOR) or add a roam move (Pip roaming the home screen) in shady-pip, then preview it, check it and play it on the emulator. Use before editing packages/pip-rive, writing a rivc project, or animating Pip in any way.
---

# Animating Pip and writing stories

Pip is written as text and compiled, never drawn in the Rive editor:

```
JSON + SVG sources ──rivc──> .riv ──> rivc-preview (browser, hot reload, goldens)
 packages/pip-rive/             └──> Pip's overlay on the emulator (rive-android 11.16.3)
```

`packages/rivc` is our compiler, pinned to rive-android 11.16.3 (file format 7.5). Its source format is defined,
with a doc comment on every field, in
[`packages/rivc/src/main/kotlin/rivc/Source.kt`](../../../packages/rivc/src/main/kotlin/rivc/Source.kt). Read that
file before writing a source: unknown keys are errors, and it is the only complete reference. All commands below run
from the repo root.

## Pick the job

| You want to | Edit | Then |
| --- | --- | --- |
| Change how Pip looks or behaves everywhere (a mood, the blink rhythm, a new input) | `packages/pip-rive/pip/build-pip.mjs` | [Change the rig](#change-the-rig) |
| A new short scene with Pip in it | a new `packages/pip-rive/stories/<name>/<name>.json` | [Make a story](#make-a-story) |
| A new move for Pip's home-screen roam, or a change to how he picks moves | `packages/pip-rive/roam/build-roam.mjs`, `apps/pip-app/.../ui/show/RoamPlanner.kt` | [Roam](#roam-pip-on-the-home-screen) |
| Change a showreel scene | the Kotlin scene in `apps/pip-app/.../ui/show/Scenes*.kt` | [Kotlin path](#rivc-json-or-a-kotlin-scene) |
| Build or check Pip's reaction to a scam moment (a call, a text, a phishing page) | the overlay in `apps/pip-app/.../ui/`, a scenario in `apps/pip-scenarios/scenarios.json` | [Scenario stage](#scenario-stage) |

## Change the rig

`build-pip.mjs` is the source. It writes `pip.svg`, `pip.json` (driven by the `Pip` view model, which the overlay
uses) and `pip-inputs.json` (the same rig on state-machine inputs, which stories nest). Never hand-edit those three:
they are regenerated.

```sh
turbo run rive:rig                                    # regenerate pip.svg, pip.json, pip-inputs.json
PREVIEW_SRC=packages/pip-rive/pip/pip.json turbo run preview   # sliders for every input on :8787
```

How the rig composes, which is what trips agents up:

- **Rive layers replace, they don't add.** A layer that writes a property overwrites what an earlier layer wrote,
  and a 1D blend resets its properties to the setup pose every frame. So every animated property belongs to exactly
  one layer.
- **Effects that stack get their own node.** The mood's heavy lid, the `lidTop` channel, three blink rhythms, the
  look and the idle darts each move a separate node in a chain of nested nodes, so translations add, rotations add
  and scales multiply. To add an effect, add a node to the chain in the SVG builder and key only that node.
- **Effects that OR (either owner may show a part) draw the part twice**, each copy gated by its owner's opacity
  (brows, mouths).
- **Keys are setup pose + offset.** The script compiles the art once and reads every node's setup transform back with
  `rivc dump`, then keys `base(id) + offset`. Use its `track`/`key`/`poseTracks` helpers rather than raw numbers.

Where things live in `build-pip.mjs` (search for the section banners):

| Behaviour | Section | Shape |
| --- | --- | --- |
| Blinks, darts, breath, hat sway, brow flicker | `Life layer` | `lifeLayer(...)`: a 1D blend on `life` between a rest pose and a loop; periods are co-prime so they never line up |
| Glances | `Look layers` | `blendLayer("Look X", "lookX", ...)` |
| Moods | `Mood layer` | `MOODS` and `MOOD` table; one 1-frame pose clip per mood, `any -> mood` transitions on the `mood` enum (an ordinal number in `pip-inputs.json`) |
| Face channels the showreel keys (`eyesOpen`, `lidTop`, `squint`, `mouth`, ...) | `Channels` | `number(name, default)` + `blendLayer(name, name, {value: pose})` |
| Hands | `hands` loop under Channels | `handL/handR` kind (0 none, 1 fist, 2 point, 3 peace, 4 palm, 5 sword, 6 phone), `hand?X/Y` in Pip's 540 x 660 box units, `hand?Angle` in degrees |
| One-shot reactions | `Alert, Poke, Talk` | a rest clip + a reaction clip; trigger `any -> reaction`, `exitTimePercent: 100` back |

A new input: call `number(name, default)` (or push a trigger/boolean onto both `vmProps` and `inputs`, as `alert`
does), add its layer, and add the name to `order` near the end so the view model lists it where the app expects.
Then regenerate, look at it in the previewer, and run the goldens (below); add a golden for the new state.

## Make a story

A story is a rivc **project**: one JSON with top-level `artboards`, compiled to one `.riv`. The device player
(`RiveShowRunner`) plays the **first artboard** through a state machine called **`Main`**, full screen with
`Fit.FILL` on a 1080 x 2424 overlay, so artboard units are screen pixels. It stops after the first clip's length or
on an event named `showEnd`. Give the main artboard no `stateMachine` and rivc makes `Main` for you, playing the first
clip once.

[`stories/hello/hello.json`](../../../packages/pip-rive/stories/hello/hello.json) is "Pip waves hello" (5 s): Pip
hops in, turns happy, waves, a bubble pops up saying "Hi!", and it cues sounds. Every piece below is in it. Start
from it:

1. Copy the story and rename its JSON after the folder (the build looks for `stories/<name>/<name>.json`):

   ```sh
   cp -R packages/pip-rive/stories/hello packages/pip-rive/stories/<name>
   mv packages/pip-rive/stories/<name>/hello.json packages/pip-rive/stories/<name>/<name>.json
   ```

2. Decide on the stage. The copy brings `stage.svg`: sky, sun, ground, and a `bubble` group (a white rounded box
   and its tail). The JSON scales that group from its tail tip, which is why `art.groups` lists it with a `pivot`:
   `{"id": "bubble", "pivot": {"x": 640, "y": 1090}}`. Any SVG group you scale or rotate needs such an entry, or
   it turns about the artboard's corner. No bubble in your story: delete the `<g id="bubble">` in the SVG, its
   `art.groups` entry and the `bubble` tracks together. A different bubble: move the box, its tail and the pivot
   together, and move the text over it.
3. Rename the artboard (`"name"`), set the timeline's `frames`, and replace the tracks, texts and events.

### The pieces

**Artboards.** The first is the story (`"artboard": {"name": "Hello", "width": 1080, "height": 2424, "clip": true}`)
with its own background `art` (an SVG next to the JSON) and one `clips` entry, the timeline (frames at 60 fps by
default). Every other entry is something it nests.

**A nested Pip.** Add the rig as an artboard, then place it as an instance and key its inputs:

```json
{ "source": "../../pip/pip-inputs.json", "control": "inputs" }
```
```json
"instances": [{ "id": "pip", "artboard": "Pip", "x": 300, "y": 1100, "scale": 0.8 }]
```

- The nested artboard is 720 x 820: Pip's 540 x 660 box plus padding (90 left and right, 110 top, 50 bottom). The
  instance's `x`, `y` is that artboard's **top-left**, and `scale`/`rotation` act about it. Pip's feet are at
  (360, 770) inside it, so feet at screen (fx, fy) means `x = fx - 360 * scale`, `y = fy - 770 * scale`.
- Move him with tracks on `pip`: `x`, `y`, `opacity`, `rotation`, `scaleX`, `scaleY` (scale about the top-left, so
  a squash also needs an `x`/`y` correction).
- Key an input with `{"target": "pip/<input>", "property": "nestedValue", ...}`: number inputs take `"value"`,
  boolean ones (`talking`) take `"bool": true`. Key discrete inputs (kinds, `mood`, `mouth`, booleans) with
  `"ease": "hold"`.
- Fire a trigger (`alert`, `poke`) with `"fire": [{"input": "alert", "clip": "timeline", "frames": [120]}]` on the
  instance.
- The rig runs its own idle (blinks, darts, breath) inside; `life` 0 stops it. The list and defaults are always in
  `pip-inputs.json` under `stateMachine.inputs`.

Every input of the nested Pip ("box units" are Pip's 540 x 660 box, x right and y down from its top-left; left and
right are the viewer's):

| Input | Values (default) |
| --- | --- |
| `mood` | 0 neutral, 1 happy, 2 sad, 3 angry, 4 surprised (0) |
| `lookX`, `lookY` | -1 to 1: `lookX` -1 left, 1 right; `lookY` -1 up, 1 down; 0 centre (0) |
| `talking` | boolean: mouth flaps while true (false) |
| `alert`, `poke` | triggers: a startled jump; a squash and giggle |
| `eyesOpen` | 0 shut to 1 open (1); dip it to blink |
| `lidTop`, `squint` | 0 to 1: upper lids down; lower lids up (0) |
| `eyeScale` | 0 to 2, eye size (1) |
| `browRaise` | -1.2 to 1.2, positive raises (0); `browTilt` -1 to 1, inner ends (0); `brows` 0 or 1 shows brows without a mood (0) |
| `blush` | 0 to 1 (0) |
| `hatSway` | -1 to 1, the hat bends left or right (0); `hatDroop` 0 to 1, the hat tips back 14 degrees (0) |
| `mouth` | -1 follows the mood; 0 none, 1 smile, 2 frown, 3 o, 4 shout, 5 flat (-1) |
| `life` | 0 to 1, the idle layer's strength (1) |
| `handR`, `handL` | kind: 0 none, 1 fist, 2 point, 3 peace, 4 palm, 5 sword, 6 phone (0) |
| `handRX`, `handRY` / `handLX`, `handLY` | hand position in box units (R 500, 560; L 40, 560) |
| `handRAngle`, `handLAngle` | degrees (0); positive turns clockwise. `handL` is drawn as `handR` mirrored, so the same gesture takes opposite angles |
| `headwear` | 0 none, 1 ninja, 2 flowers, 3 sweatband (0). Added by pip-rig's PR #2 |
| `handRScale`, `handLScale` | 0 to 4, hand size (1). Added by pip-rig's PR #2 |

### Recipes

Frames at 60 fps; adapt the numbers, keep the shapes.

**Glance left, then right.** Eyes move fast and hold: `pip/lookX` keys `0 easeInOut` at f0, `-1` at f12, hold
`-1 easeInOut` to f40, `1` at f52, hold to f80 `easeInOut`, `0` at f92. Add a blink (`eyesOpen` 1 to 0 in 4 frames,
back to 1 in 6) on the swing across if it reads mechanical.

**Shrug.** Both palms up and out, a sad face, a small lift:

```json
{ "target": "pip/mood", "property": "nestedValue", "keys": [{ "frame": 100, "value": 2, "ease": "hold" }] },
{ "target": "pip/handL", "property": "nestedValue", "keys": [{ "frame": 0, "value": 0, "ease": "hold" }, { "frame": 100, "value": 4, "ease": "hold" }] },
{ "target": "pip/handR", "property": "nestedValue", "keys": [{ "frame": 0, "value": 0, "ease": "hold" }, { "frame": 100, "value": 4, "ease": "hold" }] },
{ "target": "pip/handLX", "property": "nestedValue", "keys": [{ "frame": 100, "value": 60, "ease": "easeOut" }, { "frame": 112, "value": -10 }] },
{ "target": "pip/handLY", "property": "nestedValue", "keys": [{ "frame": 100, "value": 560, "ease": "easeOut" }, { "frame": 112, "value": 470 }] },
{ "target": "pip/handLAngle", "property": "nestedValue", "keys": [{ "frame": 100, "value": 0, "ease": "easeOut" }, { "frame": 112, "value": -70 }] },
{ "target": "pip/handRX", "property": "nestedValue", "keys": [{ "frame": 101, "value": 480, "ease": "easeOut" }, { "frame": 113, "value": 550 }] },
{ "target": "pip/handRY", "property": "nestedValue", "keys": [{ "frame": 101, "value": 560, "ease": "easeOut" }, { "frame": 113, "value": 470 }] },
{ "target": "pip/handRAngle", "property": "nestedValue", "keys": [{ "frame": 101, "value": 0, "ease": "easeOut" }, { "frame": 113, "value": 70 }] },
{ "target": "pip", "property": "y", "keys": [{ "frame": 100, "value": 930, "ease": "easeOut" }, { "frame": 108, "value": 900, "ease": "easeIn" }, { "frame": 118, "value": 930 }] }
```

The right hand trails the left by a frame (never mirrored twins), and the hop is up with `easeOut`, down with
`easeIn`. The `pip` `y` values assume the instance rests at `y` 930, as in hello.

**Wave.** `handR` 4 (palm) with `"ease": "hold"`, raise it with `handRX`/`handRY` to about 585, 330 (beside his
head), and swing `handRAngle` between about -14 and 22 degrees every 10 frames. See hello's tracks.

**Clips and keys.** `{"frame": 30, "value": 1, "ease": "easeOut"}`; `ease` shapes the curve to the next key
(`linear`, `hold`, `easeIn`, `easeOut`, `easeInOut` or `{"cubic": [x1, y1, x2, y2]}`). Rotations are radians on
nodes and instances, but the rig's `hand?Angle` inputs are degrees.

**Texts.** Embed the font once at the top level, then add texts to an artboard:

```json
"assets": [{ "name": "Inter-Bold", "font": "../../showreel/fonts/Inter-Bold.ttf" }]
```
```json
"texts": [{ "id": "hi", "text": "Hi!", "font": "Inter-Bold", "size": 120, "color": "#FF3A2A26",
  "x": 560, "y": 760, "width": 360, "height": 170, "align": "center", "verticalAlign": "middle", "opacity": 0 }]
```

Key `hi` `opacity`/`x`/`y`, `hi/fill` `colorValue`, and swap the words with `changes`. A text can also take a
`stroke` (an outline, keyed as `hi/stroke`). By default texts draw above instances and art, so a bubble's box goes in
the art and its words in a text; see `stack` under Draw order to put a text under an instance.

**Events (sound cues).** `"events": [{"name": "ding", "clip": "timeline", "frames": [105]}]`. The device logs each as
`PIPCUE <name> <t>`, the previewer shows it in the header, and `tools/pip-show/record.sh` synthesises a sound for
every name in the `FX` table of
[`tools/pip-show/synth_sfx.py`](../../../tools/pip-show/synth_sfx.py) (`hop`, `land`, `pop`, `ding`, `whoosh`,
`sparkle`, ...). Other names are logged but silent, except `say:<line>`, a spoken line (see the greeting below).

**Set pieces from SVG.** Either an inline artboard (`{"artboard": {...}, "art": {"svg": "prop.svg"}, "clips": [...]}`)
or one of the drawn pieces in `packages/pip-rive/sets/` by its source:
`{"source": "../../sets/fx-heart.rivc.json"}` (its artboard name is the file's, e.g. `fx-heart`). Place it as an
instance like Pip. Clip tracks inside a piece target its SVG `<g id>`s; with `art.shapes` empty every `<g id>` is
kept as a node. Rive's own coordinate rules: a group's `pivot` is where it rotates and scales.

**Masks.** `"masks": [{"id": "hole", "x": 780, "y": 1560, "width": 420, "height": 420}]` and `"mask": "hole"` on an
instance clip it to the ellipse (or `"shape": "rectangle"`); `invert` hides it inside instead. See the masks in
[`packages/rivc/examples/project/project.json`](../../../packages/rivc/examples/project/project.json).

**Draw order**, top to bottom: texts, instances (later entries on top), mask markers, the art (later SVG elements
on top). To interleave texts and instances, give the artboard a `stack`: every text and instance id once, bottom to
top (see `ProjectArtboardSpec` in `Source.kt`).

### Bigger stories: generate them, and bind to the real home screen

[`stories/hello-install/`](../../../packages/pip-rive/stories/hello-install) is Pip's first-run greeting (about 18 s): too
many keys to write by hand, so `build.mjs` generates `hello-install.json` with helpers (`hop`, `think`, `pop`,
`play` for a set piece's remapped clip, a node chain per Pip so position, lean, size and squash each have one owner),
and `tools/pip-art/first-run/hello_install.py` draws its props (`turbo run rive:greet` runs both). Two patterns worth
copying (and one rule: his lines play as pre-rendered GPT-Live clips, so a `say(f, line)` cue has to leave room for
the clip before the next one; `build.mjs` fails when a clip would still be playing at the next line's cue, and its
comments note where each line's second half falls so it lands on its beat):

- **Icon-relative moves use bound positions.** `RiveShowRunner` sets `<icon>X/Y/D/LabelW/LabelH` on the story's
  view model for every launcher icon (the label in camel case: "Pip" is `pipX`). Declare them as view-model numbers
  (defaults = the emulator layout, so the previewer looks right), bind a project node to them
  (`{"id": "iconAt", "bind": {"x": "pipX", "y": "pipY"}}`) and hang Pip from it; an inverted mask bound to the same
  numbers hides him behind the icon. To hop one Pip between screen space and icons, the greeting's `hopVia` blends
  spaces with weight nodes (a node scaled by w over the bound node, 1 / w under it, keyed every frame), so his
  position is his keyed offset plus w times each icon's centre.
- **A track keyed only later must start at its rest value**, not its first key's: the generator prepends a frame-0
  key from the rig's defaults, the node's or group's setup, or the instance's opacity.

**Spoken lines.** An event named `say:<line>` is a line Pip speaks where it sits on the timeline (`say(frame, line)`
in `build.mjs`). On the phone the greeting's runner queues it on the system text-to-speech (friendly pitch and rate);
the previewer speaks it with the browser's speech synthesis (View > Voice, on by default) and captions it, so the
browser loop matches the phone. Keep lines short, about 3 words a second, or the queue pushes the next one late.
Recordings re-voice them with macOS `say`, since the phone's speech can't be captured.

**Iterate on the greeting:**

```sh
turbo run rive:greet        # after editing build.mjs or the art script: regenerates hello-install.json (and the SVGs)
PREVIEW_SRC=packages/pip-rive/stories/hello-install/hello-install.json turbo run preview   # scrub, hear, read captions
turbo run pip:greet         # on the emulator: pushes the build and replays it on the home screen (any flag state)
turbo run pip:greet -- reset   # clears "greeted" and setup: the next open shows setup, the open after it greets
turbo run pip:greet-record  # one take with sound into .proof/first-run/hello-install.mp4
```

The previewer's View > "Over home-screen screenshot" stays sized for the overlay rig; judge the greeting over a
home screen with a contact sheet or on the emulator.

### Build it in

Nothing to add: [`packages/pip-rive/build.sh`](../../../packages/pip-rive/build.sh) compiles every
`stories/<name>/<name>.json` to `dist/showreel/<name>.riv` (`turbo run build --filter=@shady-pip/pip-rive`), and the
app bundles that folder as `assets/showreel/`, where the device player looks. The JSON must be named after its
folder.

## Roam: Pip on the home screen

`turbo run pip:roam [-- <seed>]` (stop: `turbo run pip:roam-stop`) has Pip roam the home screen on his own: he hops
between icons, hides behind one and peeks out, lurks there a while, and web-swings from icons, widgets and the status
bar's edge. Rive owns the motion; a small Kotlin director picks the moves from what is really on screen.

**The pieces** are one rivc project, [`packages/pip-rive/roam/roam.json`](../../../packages/pip-rive/roam/roam.json),
generated by [`build-roam.mjs`](../../../packages/pip-rive/roam/build-roam.mjs) (`turbo run rive:roam`; its header is
the contract). Each move is a state entered on a view-model trigger (`hop`, `peekLeft`, `peekRight`, `peekTop`,
`lurk`, `swing`, `look`), sized by view-model numbers, and fires `moveEnd` a frame before it finishes.

- **Pip hangs from a chain of project `nodes`** (an instance's `parent`), so each effect owns one node: the bound
  `anchor` (his feet at rest; the web's anchor in a swing), the arm (`armBase` bound, `armSwing` keyed), the bound
  `armEnd` length, `reel`, the body's counter-rotation, the bound `hang` (`grip`), then `hopX`, `hopY`, `arc`,
  `peek` and `squash`. Translations add down the chain, so the web swing is just rotating `armSwing` with Pip at the
  end and the web line (`web` > `webShoot` > the Web artboard) along the arm.
- **Sizes come through 1D blends.** A layer blending a clip keyed all zero (at 0) with one keyed at `K` by a number `v`
  plays `u(t) * v` exactly, so one clip set serves every distance. Rive mixes a blend's lower clip first, so a negative
  value must be written as `-sqrt(-v * K)` (`Roam.blendValue` in Kotlin; `pair()` in the script explains it). Peek
  offsets are in icon diameters (a blend by `iconD`); face (-1 or 1) mirrors lean and look.
- **Every move ends where its numbers say**, holding values that are all proportional to numbers. The director then
  rebases: his feet into `anchorX/anchorY`, every other number to 0, then the `settle` trigger. Nothing pops.
- **Icons are always in front of Pip.** The `icons` mask is one disjoint inverted mask with 40 slots: each icon's
  circle (`icon<i>X/Y/D`, the drawn circle plus a 3 px margin, from `RoamIcon.fromCell`) and its label box
  (`label<i>Y/W/H`, starting where the circle ends so no two shapes overlap and one even-odd clip cuts them all). The
  director rewrites the slots whenever the page changes. A peek adds the `hole` box under its icon (`holeX/Y/W/H`),
  which hides the rest of him while he crouches. Keep him from crossing that box's straight sides in view: he drops
  into it from above, rises straight up until his feet clear the icon's centre, and only then leans.

**The director** is [`ui/show/RoamDirector.kt`](../../../apps/pip-app/app/src/main/kotlin/dev/playground/pip/ui/show/RoamDirector.kt)
(the overlay, reading the accessibility tree, writing the view model under the file lock, waiting for `moveEnd`) and
[`RoamPlanner.kt`](../../../apps/pip-app/app/src/main/kotlin/dev/playground/pip/ui/show/RoamPlanner.kt) (Android-free:
the stage, the weighted random choice, each move's numbers; `RoamPlannerTest` checks every move lands where the Rive
chain will put him). All randomness comes from the seed, logged as `ROAM start seed=<n>`; each move logs
`ROAM move #i <trigger> from=(x,y) to=(x,y) target=...`, and `ROAM stage ...` whenever the layout changes (it is
re-read before every move, and on launcher scrolls and page changes).

**To add a move:**

1. In `build-roam.mjs`, add its clip to the Main layer (`clip(...)`, ending at the rest values, with `cues(...)`
   including `moveEnd` at `end - 1`), and a state on each layer whose node it moves (`pair(...)` for anything sized by
   a number). Add the trigger to `TRIGGERS`. If it ends somewhere new, document where in the header.
2. `turbo run rive:roam`, then check it in the previewer: `PREVIEW_SRC=packages/pip-rive/roam/roam.json turbo run
   preview`, set the numbers and fire the trigger from the controls (the goldens' `values` are worked examples).
3. Add goldens (`roam-*` in `goldens.json`, `"riv": "goldens/roam.riv"`): compile `roam.json` to
   `packages/rivc-preview/goldens/roam.riv` and write only yours with `node golden.mjs --update --only <name>`.
4. In `RoamPlanner.kt`, add a `RoamKind`, its weight in `next()`, and a function building its `RoamMove` (the values,
   the trigger, its clip length in `Roam.SECONDS`, where his feet end); extend `RoamPlannerTest`.
5. `turbo run app:install --filter=@shady-pip/pip-app`, then `turbo run pip:roam -- 7` and read the `ROAM` lines.
   Record with `SHOW_ARGS="--es show roam --el seed 7 --ef seconds 40" OUT_NAME=roam SHOW_TIMEOUT=70 turbo run
   showreel:record` (a timed roam ends with `PIPSHOW end`, which the recorder waits for).

## rivc JSON or a Kotlin scene

| | Write rivc JSON (`stories/`) | Write a Kotlin scene and convert |
| --- | --- | --- |
| Use when | a new short scene, a one-off, anything you want to iterate on in seconds | a scene that belongs in the Kotlin showreel too, or needs the Kotlin DSL's choreography (cascading animations, actors anchored to Pip, scene cuts) |
| Loop | edit JSON, previewer reloads in about a second | edit Kotlin, export with a Robolectric test, convert, compile (minutes) |
| Output | exactly what you wrote | generated JSON you must not hand-edit, plus a coverage report of what didn't convert |

Default to rivc JSON. The Kotlin path, for completeness:

```sh
# 1. the scene: a scene("name", ...) { ... } block in apps/pip-app/app/src/main/kotlin/dev/playground/pip/ui/show/Scenes*.kt,
#    listed in PipShow.kt; docs/animation-playbook.md explains the DSL
# 2. export the choreography (format: ui/show/EXPORT.md)
cd apps/pip-app && . ../../scripts/env.sh && ./gradlew -q testDebugUnitTest --tests '*ShowExporterTest*' && cd ../..
# 3. convert one scene into a project and compile it (the HODOR scene is "dToday")
packages/showreel-to-rivc/showreel-to-rivc --scene dToday --out packages/pip-rive/showreel/hodor.json
```

The converter is work in progress; read the `compilerGaps` and `unmatched` lists in the `.report.json` it writes
next to the output.

## The iteration loop

**1. Preview with hot reload.**

```sh
PREVIEW_SRC=packages/pip-rive/stories/hello/hello.json turbo run preview   # http://localhost:8787/ (PORT=... for another)
```

In a fresh clone use `npx turbo` until setup has installed the turbo CLI. The first preview (and the first `rivc`
command) builds rivc with Gradle, about 8 minutes cold; later runs start in seconds.

It compiles on every save of the JSON or any file it references and swaps the new file in within about a second; a
compile error shows in the header and the last good file keeps playing. For a story: the Artboard picker opens any
artboard (the default is the first), the Timeline slider scrubs the main clip in seconds (it replays from 0, so what
you see is exactly time t), Play/Pause, Restart, frame stepping, and the latest event in the header with every event
in the log.

URL parameters: `?t=2.5` opens paused at 2.5 s on the timeline, `?artboard=Pip` opens a nested artboard. When the page
is ready (including that seek), `window.pipPreview.settled` is true, so a script can screenshot any moment:

```js
await page.goto("http://localhost:8787/?t=2.5");
await page.waitForFunction(() => window.pipPreview?.settled === true);
await page.screenshot({ path: ".proof/<name>-2.5s.png" });
```

(Playwright is installed in `packages/rivc-preview`.) To keep a frame as a regression check, make it a golden
(below); a golden's `time` is the same seconds.

If port 8787 is taken (another agent's preview), use `PORT=8797`. Live playback runs on the browser's frame clock,
so a hidden or backgrounded tab plays late and logs events late; for exact timing scrub or step (both run in fixed
1/60 s steps), and the log then shows each event one frame after its key.

**2. Read the compiled file.**

```sh
pnpm --filter @shady-pip/rivc run compile packages/pip-rive/stories/hello/hello.json -o /tmp/hello.riv
pnpm --filter @shady-pip/rivc run dump /tmp/hello.riv | less      # every object, one per line
pnpm --filter @shady-pip/rivc run diff /tmp/old.riv /tmp/hello.riv  # what a change did, object by object
```

`packages/rivc/rivc compile|dump|diff` is the same tool without pnpm.

**3. Goldens for regressions.** `turbo run goldens` renders each state in
[`packages/rivc-preview/goldens.json`](../../../packages/rivc-preview/goldens.json) in headless Chromium and compares
it with `goldens/<name>.png`. After an intended rig change: compile the rig into `goldens/pip-rig.riv`
(`packages/rivc/rivc compile packages/pip-rive/pip/pip.json -o packages/rivc-preview/goldens/pip-rig.riv`, and
`pip-inputs.json` into `pip-rig-inputs.riv`), run `turbo run golden:update`, and look at every changed PNG before
committing. A story gets goldens the same way: compile it to `packages/rivc-preview/goldens/<name>.riv`, add entries
with `"riv": "goldens/<name>.riv"` and a `time` (see `hello-landing` and `hello-wave`), then write only those with
`cd packages/rivc-preview && node golden.mjs --update --only <golden name>`. Goldens render on a 540 x 660 canvas, so a
tall story is letterboxed. The page error `Could not find a View Model linked to Artboard ...` is harmless.

**4. Play it on the emulator without reinstalling.** The app must be installed and set up once (`turbo run
app:setup`). Then:

```sh
turbo run pip:story -- packages/pip-rive/dist/showreel/hello.riv   # builds dist/, pushes your story and plays it, no reinstall
adb logcat -d -s PipShow:I | tail                                   # PIPSHOW start, PIPCUE <event> <t>, PIPSHOW end
```

`pip:story` builds `packages/pip-rive` first, then pushes the file to `/sdcard/Download/<name>.riv` and sends the SHOW broadcast
(`--es engine rive --es riv <name>`). The player prefers that file over the bundled `assets/showreel/<name>.riv`, so a
push is the whole deploy; `adb shell rm /sdcard/Download/<name>.riv` goes back to the bundled one. For the overlay rig rather than a
story, `turbo run pip:push` pushes `dist/pip/pip.riv` and reloads it, `turbo run pip:bundled` reverts, and
`turbo run pip:mood -- happy` / `turbo run pip:blink` drive it. A screenshot:
`adb exec-out screencap -p > .proof/<name>.png`.

**5. Record a proof video** with the synthesised soundtrack:

```sh
adb shell pidof screenrecord   # must print nothing: the emulator is shared, and two recordings break both
SHOW_ARGS="--es engine rive --es riv hello" OUT_NAME=hello SHOW_TIMEOUT=40 turbo run showreel:record   # -> .proof/hello.mp4
```

It waits for `PIPSHOW end`, then muxes a soundtrack: one sound per event name it knows, over the showreel's music
bed for the same seconds (the bed isn't yours to choose yet). Proof lives in `.proof/` (gitignored); never commit
it.

## Known gaps

rivc can't express everything yet. The current list is `GENERAL_GAPS` in
[`packages/showreel-to-rivc/src/main/kotlin/showreel/Main.kt`](../../../packages/showreel-to-rivc/src/main/kotlin/showreel/Main.kt),
and the per-scene detail is in `compilerGaps` and `unmatched` of
[`showreel/hodor.report.json`](../../../packages/pip-rive/showreel/hodor.report.json) and
`showreel/showreel.report.json`. The ones a story hits first: no colour inputs or
colour remaps; morphs need equal vertex counts; no trim paths; an instance can't be parented to a node.

## Craft notes

Read [`docs/animation-playbook.md`](../../../docs/animation-playbook.md) section 1 (the cheat sheet) before keying
anything, and its timing (4) and easing (5) tables when choosing numbers. The rules that matter most for Pip:

- Anticipate every move (squash or lean the other way first, 100-200 ms), then move on arcs, not straight lines.
- Up is ease-out, down is ease-in. Squash on landing, stretch on launch, keep `sx * sy` near 1.
- Eyes lead: change `lookX/lookY` 80-170 ms before he moves. Blink on every change of thought.
- Swap discrete things (`mood`, `mouth`, hand kind) while the eyes are shut (`eyesOpen` dips to 0) or mid-move.
- Never dead still: holds are moving holds. The rig's idle layer gives you this for free if `life` stays 1.
- Hands never move as mirrored twins; offset them by 1-2 frames.

[`docs/animation-stack-research.md`](../../../docs/animation-stack-research.md) explains why Rive, and the
`docs/*-spike-results.md` write-ups record what was proven on each runtime.

## Scenario stage

Pip's reactions are built against live situations, not mocks: `turbo run scenarios -- <id>` stages one moment from
the re-skin mocks on the emulator (a scripted scam call into the mic, a text, a phishing page in Chrome, a fixture app
with the keyboard up) and records Pip's reaction next to the trigger map's expectation, with a screenshot in
`.proof/scenarios/<id>.png`. `turbo run scenarios -- list` shows the ids; `turbo run scenarios:auto` runs them hands-off.

To iterate on a reaction: change the overlay or the rig, push it (`turbo run pip:push` for a rig change), then rerun
that scenario until its screenshot matches the mock. To cover a new moment, add an entry to
`apps/pip-scenarios/scenarios.json` as `AGENTS.md` describes under Scenarios.
