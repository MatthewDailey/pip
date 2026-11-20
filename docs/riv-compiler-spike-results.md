# Riv compiler spike results: writing `.riv` ourselves, played by the stock Rive runtime

Date: 2026-11-20. Time spent: about 1 hour 15 minutes of the 2-3 hour budget.
Proof: `.proof/riv-compiler-spike/` (paths below are relative to it).
Compiler: `~/ref/pip-riv-compiler` (Kotlin/JVM, Gradle `application`, about 470 lines).

## Verdicts

| # | Step | Verdict |
|---|---|---|
| 1 | Find the format definition for the runtime rive-android 11.16.3 uses | **PASS.** Pinned commit found. `dev/defs/*.json` is no longer in the public repo, so the schema was taken from the generated `*_base.hpp` headers and the reader code. |
| 2 | Kotlin compiler that emits Pip as a `.riv` | **PASS.** Artboard, SVG paths and ellipses with fills, an idle bob and a blink clip, a state machine with `mood` and `blink`, and also a view model with data-bound conditions (the optional item). No Rive account, editor or CLI. |
| 3a | Desktop: load and render with the official runtime | **PASS.** `@rive-app/canvas` 2.52.0 in headless Chromium loads the file and renders it; the bob, blink and four moods all work, driven both through state machine inputs and through the view model. |
| 3b | Android: render in the spike app's overlay | **PASS.** rive-android 11.16.3 loads `pip.riv`, finds view model `Pip`, and draws Pip transparently over the home screen. |
| 3c | Android: drive `blink` and `mood` over adb | **PASS.** The existing receiver fired the trigger and set the number on the view model; the screenshots show eyes mid-blink and each mood colour. |

**Recommendation: go.** The binary format is small, regular and readable from the MIT runtime source. Our own
compiler produced a file that both pinned runtimes accepted on the first load, and the only bug I hit was in my
own state machine design, not the format. Pinning the runtime makes the format a fixed target. The cost is that
we have to own a schema-extraction step and an object-graph layer for each feature we use (estimates below).

## Versions: where the format came from

- `rive-app/rive-android` tag `11.16.3` (tag object `deed3207`, commit `6f4e83e3ba4aa25a4d066d0030268ae5b7b289e6`)
  pins the submodule `submodules/rive-runtime` at **`rive-app/rive-runtime@37b3bc71304d0e65ebd0a23de4b6857068be1f09`**
  (2026-11-10, "fix(scripting): close the gaps found porting luau trays to animascript (#15607)").
- The file format version in that commit is **7.5** (`include/rive/file.hpp`: `majorVersion = 7`, `minorVersion = 5`).
  The runtime rejects only a major mismatch.
- **`dev/defs/` is gone from the public runtime at this commit.** `dev/core_generator` still reads a defs directory,
  but the JSON isn't checked in. The generated headers (`include/rive/generated/**/*_base.hpp`, 360 classes, and
  `core_registry.hpp`) are what the reader actually deserializes, so they're the better source anyway.
  `pip-riv-compiler/tools/extract_schema.py` turns them into `tools/schema.json` (class, parent, type key, and
  each property's key and field type). Caveat: its regex misses a class whose `class X : public Y` declaration
  spans two lines (for example `TransitionPropertyViewModelComparator`, 479), so I checked those by hand.
- Browser: `@rive-app/canvas` **2.52.0** (latest on npm today). I didn't trace its embedded runtime commit. The same
  bytes load in both runtimes, which is itself evidence that the subset we use is stable.

## Format notes (what the compiler has to get right)

- Header: `"RIVE"`, then varuint major, minor and file id, then a property table of contents (varuint keys ending
  in 0, then 2-bit field types packed into uint32s). The ToC only matters for keys the runtime doesn't know, so
  we write it empty.
- Object stream: varuint type key, then (varuint property key, value) pairs, ending in 0. Field encodings: uint and
  id are LEB128 varuints; double is float32 LE; colour is uint32 LE ARGB; string and bytes are a varuint length then
  the bytes; bool is one byte.
- **Ids are positions, not names.** Each `Component` and `KeyFrameInterpolator` takes the next index in the open
  artboard's object list (the Artboard is 0). `parentId`, `KeyedObject.objectId` and `interpolatorId` are those
  indices. `AnimationState.animationId` indexes the artboard's animations, `stateToId` indexes the layer's states,
  `inputId` indexes the machine's inputs, and a data bind's `sourcePathIds` is `[viewModelIndex, propertyIndex]`
  packed as varuints in a bytes field.
- **Nesting is positional too.** An object attaches to the latest importer of the right kind on the import stack.
  So a transition belongs to the state written before it, a condition to the transition before it, and a
  `DataBindContext` targets the last non-bind object.
- Draw order: the artboard draws its drawables last to first, so SVG paint order is reversed when we emit.
- A file-level view model and its instance are written **before** the artboard. Otherwise the instance, which is
  a `Component`, gets numbered into the artboard and every later id shifts.
- Watermark: the runtime adds Rive's pre-roll only when the file carries a manifest asset with a watermark section
  (`File::attachWatermark`). Our files have no manifest, so nothing is added.

## Object types used (22 + 13 for view models)

Structure: `Backboard`(23), `Artboard`(1), `Node`(2), `Shape`(3), `Ellipse`(4), `PointsPath`(16),
`StraightVertex`(5), `CubicDetachedVertex`(6), `Fill`(20), `SolidColor`(18).
Animation: `LinearAnimation`(31), `KeyedObject`(25), `KeyedProperty`(26), `KeyFrameDouble`(30), `KeyFrameColor`(37),
`CubicEaseInterpolator`(28).
State machine: `StateMachine`(53), `StateMachineNumber`(56), `StateMachineTrigger`(58), `StateMachineLayer`(57),
`AnyState`(62), `EntryState`(63), `ExitState`(64), `AnimationState`(61), `StateTransition`(65),
`TransitionTriggerCondition`(68), `TransitionNumberCondition`(70).
View model: `ViewModel`(435), `ViewModelPropertyNumber`(431), `ViewModelPropertyTrigger`(502),
`ViewModelInstance`(437), `ViewModelInstanceNumber`(442), `ViewModelInstanceTrigger`(501),
`TransitionViewModelCondition`(482), `BindablePropertyNumber`(473), `BindablePropertyTrigger`(503),
`DataBindContext`(447), `TransitionPropertyViewModelComparator`(479), `TransitionValueNumberComparator`(484),
`TransitionValueTriggerComparator`(505).

The rig the compiler emits: `root` node (keyed `y` for a 2 s loop with ease-in-out, -18 px at 1 s), then `eyeL`
and `eyeR` (shapes at the ellipse centres, so `scaleY` squashes in place), `face` and `hat` (paths converted from
SVG cubics: a segment's two control points become the out handle of its start vertex and the in handle of its end
vertex, stored as rotation and distance). Clips are `idle` (loop), `blink` (14 frames, one-shot) and
`mood_0..3` (the face colour: neutral, happy pink, angry red, worried blue). State machine `Pip`, the artboard
default, has three layers: Idle (entry plays idle); Blink (any state goes to blink when `blink` fires, then to an
empty "open" state at 100 % exit time); Mood (any state goes to `mood_i` when `mood == i`, with a 150 ms mix).

The compiler's `--inputs` flag emits state machine inputs instead of view-model conditions. **Don't emit both:**
my first build had both, and the idle input `mood == 0` kept matching, so the layer fought every view-model
mood change (web: mood 3 stayed neutral and the others stuck mid-mix). Picking one fixed it.

## Step 3a: desktop (rive-wasm in headless Chromium)

```
$ build/install/pip-riv-compiler/bin/pip-riv-compiler .../assets/pip/pip.svg out/pip.riv
wrote out/pip.riv: 2186 bytes            # view-model control (sha1 55930508…)
$ ... out/pip-inputs.riv --inputs
wrote out/pip-inputs.riv: 2073 bytes     # state machine input control (sha1 8b4f96c8…)
$ MODE=vm node render.mjs out/pip.riv .proof/riv-compiler-spike
info {"animations":["idle","blink","mood_0","mood_1","mood_2","mood_3"],"stateMachines":["Pip"],
      "inputs":[{"name":"mood","type":56},{"name":"blink","type":58}],"playing":["Pip"],
      "viewModel":{"name":"Pip","props":[{"name":"mood","type":"number"},{"name":"blink","type":"trigger"}]}}
corner pixel rgba [0,0,0,0] face pixel rgba [253,227,210,255]
mood 1 face pixel [255,184,200,255]
mood 2 face pixel [242,106,90,255]
mood 3 face pixel [201,216,240,255]
mood 0 face pixel [253,227,210,255]
```

The render script (a throwaway in the session scratchpad) serves `rive.js` and `rive.wasm` from the npm package,
loads the file with `autoBind`, and drives either `stateMachineInputs()` or `viewModelInstance.number/trigger`. The
input run gives identical pixels. The canvas corner reads alpha 0 (transparent artboard), and each mood pixel is
exactly the compiled colour.

- `web-vm-contact-sheet.png`: start, bobbed at 1 s, mid-blink, then moods 1, 2 and 3, over a checkerboard.
- Single frames: `web-{vm,inputs}-01-start`, `-02-bob-1s`, `-03-blink-mid`, `-04-blink-done` and
  `-mood-{0..3}.png`.

I didn't build rive-runtime's native C++ viewer. The wasm runtime and the Android runtime together cover the
same question with less setup.

## Steps 3b and 3c: Android (rive-android 11.16.3, spike app overlay)

`pip.riv` was copied to `pip-rive-spike/app/src/main/assets/pip.riv`. The app already prefers it, and it stays
there. Then I rebuilt and ran `adb install -r`, and appended
`dev.playground.pipspike/dev.playground.pipspike.PipOverlayService` to `enabled_accessibility_services`.

```
I PipSpike: overlay added
I PipSpike: pip.riv artboards=[Pip]
I PipSpike: playing pip.riv, view model Pip
I PipSpike: pip.riv vm Pip: [Property(type=NUMBER, name=mood), Property(type=TRIGGER, name=blink)]
W Rive    : rive: no interlock mode supports this frame, drawing it in depthStencil with 4x MSAA   (emulator GPU, harmless)
$ adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --es fire blink
I PipSpike: applied Trigger(name=blink)
$ ... --es mood happy | angry | worried
I PipSpike: applied Number(name=mood, value=1.0) / 2.0 / 3.0
```

- `android-01-overlay-home.png`: Pip over the home screen. The app icons show through around him, so the
  background is transparent.
- `android-02-blink.png`: captured 80 ms after the trigger, with the eyes partly closed.
- `android-03-mood-{happy,angry,worried}.png`: the face goes pink, red and blue.
- `android-contact-sheet.png`: crops of all five side by side.
- The smaller shaded Pip at the left in the mood screenshots is the app's own Canvas overlay, which happened to
  appear. It isn't part of this test.
- Emulator housekeeping: I waited out the lead's screen-recording window before installing. The original
  `enabled_accessibility_services` value (in `a11y-original.txt`) was restored exactly (`RESTORED-EXACT` was
  checked by comparing strings), and I left the home screen showing. `dev.playground.pip` wasn't touched,
  and I didn't run screenrecord or restart the emulator.

Visible flaw: at the top of the bob, the hat's tip is cut flat. The idle clip moves `root` up to 18 px above the
artboard, and the surface ends at the artboard's edge. Artboard `clip` is false, so this is the view's bounds,
not Rive. Fix in the compiler: pad the artboard (for example a 20 px top margin) or bob downward.

## Hardest parts

1. **Finding the truth with no defs.** The JSON defs aren't public at this commit, so the schema had to be
   extracted from the generated C++. That worked, but it's a step we have to keep and re-run when we bump the
   runtime.
2. **Implicit structure.** Nothing in the file names a parent importer. Membership comes from stream position, and
   ids are positional indices whose numbering rules live in `claimsArtboardSlot` overrides. One misplaced object
   shifts every later id silently. A real compiler needs a validating object graph and a reader-side dump test.
3. **View-model conditions.** The editor's shape is a 5-object pattern (condition, bindable property, data-bind
   context with a path, property comparator, value comparator). I worked it out from `TransitionViewModelCondition`
   and the importers. It worked first time, but this is the area most likely to change upstream (the code is
   full of recent comments).
4. Small traps: SVG paint order must be reversed; a view-model instance must come before the artboard; and
   state machine inputs and view-model conditions must not both drive a layer.

## Rough estimate for a full rig (agent hours, with a working loop: compile, render in wasm, compare)

| Feature | Types (key) | Estimate |
|---|---|---|
| Strokes, trim paths, more shape kinds (rect, polygon) | `Stroke` 24, `TrimPath` 47 … | 2-3 h |
| Gradients | `LinearGradient` 22, `RadialGradient` 17, `GradientStop` 19 | 2 h |
| Clipping | `ClippingShape` 42 | 2 h |
| Bones and skinning (weights per vertex, bind pose) | `Bone` 40, `RootBone` 41, `Skin` 43, `Tendon` 44, `Weight` 45, `CubicWeight` 46; IK `IKConstraint` 81 | 1-2 days (the maths of the bind transforms, not the bytes) |
| Draw order changes (draw rules) | `DrawRules` 49, `DrawTarget` 48 | 3-4 h |
| Nested artboards and their state machines | `NestedArtboard` 92, `NestedStateMachine` 95 | 4-6 h |
| Events (keyframe callbacks, state machine fire events) and reading them on Android | `Event` 128, `KeyFrameCallback` 171, `StateMachineFireEvent` 169 | 3-4 h |
| Other view-model types (boolean, enum, colour) and direct property binds (`DataBindContext` on shape properties) | 4xx range | 3-4 h |
| Text (font asset embedding) | `Text` 134, `FontAsset` 141 | 1-2 days; avoid if possible |
| Infrastructure: validating object graph, `.riv` dump/diff tool, golden-image tests in wasm, an authoring text format (YAML/JSON) instead of Kotlin DSL | n/a | 1-2 days |

The total for a Pip-class rig (paths, gradients, clipping, bones, events, view models, plus infrastructure) is
about **5-8 agent days**. Text roughly doubles the risk.

## Reproduce

```
cd ~/ref/pip-riv-compiler
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ~/.gradle/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/bin/gradle installDist
build/install/pip-riv-compiler/bin/pip-riv-compiler <pip.svg> out/pip.riv [--inputs]
python3 tools/extract_schema.py <rive-runtime>/include/rive/generated > tools/schema.json
```

The runtime sources are at `~/ref/riv-spike-src/` (rive-android at tag 11.16.3, rive-runtime at
`37b3bc71`).
