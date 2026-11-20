# Showreel Rive features spike: results

Date: 2026-11-20. Time spent: about 1 hour 15 minutes of the 3-hour budget.
Proof: `.proof/showreel-rive-spike/` (paths below are relative to it).
Code: `~/ref/pip-riv-compiler/src/main/kotlin/pip/riv/Spike.kt` (new, about 300 lines). `Main.kt` was
refactored so Pip's artboard can be emitted into a multi-artboard file (`emitPipArtboard`). The web harness is in
`pip-riv-compiler/tools/web/spike.mjs`, and the contact-sheet script is next to it.
Runtimes: `@rive-app/canvas` 2.52.0 in headless Chromium (chrome-headless-shell 1228), and rive-android 11.16.3
(rive-runtime `37b3bc71`) on the emulator, 1080x2424.

## Verdicts

| # | Feature | Web | Android | Verdict |
|---|---|---|---|---|
| 1 | Parent timeline drives a nested Pip's face | yes | yes | **PASS.** Two recipes work: keyed nested state-machine inputs, and keyed `NestedRemapAnimation` time and mix. |
| 2 | Text with an embedded font, wrapping, alignment | yes | yes | **PASS.** Inter Bold (OFL) embedded in band. A fixed-width box wraps, with left and centre alignment. |
| 3 | Named events fired from a timeline | yes | yes, **legacy view only** | **PASS** with one caveat: on Android 11.16.3, only the legacy `RiveAnimationView` reports events. The Compose `app.rive.Rive` API has no event callback. |
| 4 | Runtime value moves a node and its clipping shape | yes | yes | **PASS.** View-model numbers are bound to a node's x and y, and a `ClippingShape` on Pip's group uses a circle under that node. Both "visible only inside" and "hidden inside" (a hole cut with even-odd) work. |

Every file loaded on the first try in both runtimes. No recipe needed a second attempt, so I did not try the
data-binding route for feature 1 (parent view model to nested view model). Recipe 1A is simpler and is what
the editor itself exports for keyed nested inputs.

## Shared rules (apply to every recipe)

- **Multi-artboard files.** Artboards are numbered in stream order (`BackboardImporter::addArtboard`). The first
  artboard is the default one both runtimes open, so the main stage goes first, then Pip and the set pieces.
  `NestedArtboard.artboardId` (197) is that file-level artboard index. Component ids restart at 0 for each
  artboard (`RivFile.artboard()` resets the slot counter).
- **The main timeline must be played by a state machine.** Events and nested-trigger callbacks are reported through
  `StateMachineInstance`, which is the `KeyedCallbackReporter`, so a bare `LinearAnimation` would not deliver them.
  Every spike file has the state machine `Main`: one layer, Entry → `AnimationState(animationId 0)`, a 60 fps
  timeline, and a loop.
- **Callback properties** (`Event.trigger` 395, `NestedTrigger.fire` 401) are keyed with `KeyFrameCallback` (171),
  which carries only `frame` (67) under a normal `KeyedObject`/`KeyedProperty`.
- **Draw order:** the first-written drawable is drawn on top. Write the background last.

## 1. Nested Pip driven by the parent timeline: PASS

File `nested.riv` (2,643 bytes). Artboard 0 is `Main` (1080x2424), artboard 1 is `Pip` (the spike rig compiled with
`Control.INPUTS`, with no view model). The timeline is 6 s.

**Recipe 1A, nested state-machine inputs (the one to use for mood and blink):**

```
NestedArtboard(92)        name, parentId=<main or group>, x(13), y(14), scaleX/Y(16/17), artboardId(197)=1
  NestedStateMachine(95)  parentId=<nested artboard>, animationId(198)=0   # index of Pip's state machine
    NestedNumber(124)     parentId=<nested SM>, inputId(237)=0 ("mood"), nestedValue(239)=0
    NestedTrigger(122)    parentId=<nested SM>, inputId(237)=1 ("blink")
LinearAnimation "timeline" on Main:
  KeyedObject(objectId=<NestedNumber>)  KeyedProperty(53=239)  KeyFrameDouble hold(68=0): 0@0 1@60 2@120 3@180 0@240
  KeyedObject(objectId=<NestedTrigger>) KeyedProperty(53=401)  KeyFrameCallback(171) frame 30, 210, 300
  KeyedObject(objectId=<NestedArtboard>) x and rotation keys: the parent also moves and tilts the instance
```

`inputId` is the index in the nested state machine's input list. `NestedNumber::nestedValue(float)` writes
straight through to the live `SMINumber`, so each keyframe changes the mood input, and Pip's own Mood layer
transitions with its 150 ms mix. Pip's Idle layer keeps bobbing inside the nested instance.

**Recipe 1B, nested remap animations (use these to scrub a nested clip directly, without its state machine):**

```
NestedArtboard(92) artboardId=1
  NestedRemapAnimation(98) animationId(198)=1 (Pip's "blink" clip), mix(200)=1, time(202)=0
  NestedRemapAnimation(98) animationId(198)=4 ("mood_2"), mix(200)=0, time(202)=0
timeline keys: blink.time 0@60 -> 1@90 (one blink), 1@240 -> 0@270 (reverse); mood_2.mix 0@120 -> 1@180 (cross-fades to angry)
```

`time` is normalised from 0 to 1 over the clip's duration (`NestedRemapAnimation::timeChanged`), and `mix`
blends the clip over the setup pose.

Evidence (web, faceA is recipe 1A and faceB is recipe 1B, sampled from the canvas):

```
t 0.5 faceA [253,227,210] faceB [253,227,210]   neutral / neutral
t 1.5 faceA [255,184,200] faceB [253,227,210]   happy  / neutral
t 2.5 faceA [242,106,90]  faceB [242,106,90]    angry  / angry (mix reached 1 at 3 s; past 0.5 already reads angry)
t 3.5 faceA [201,216,240] faceB [242,106,90]    worried / angry
```

(The sample at 4.5 s missed the face because the parent had moved Pip. The screenshots cover that point.)

- `web-nested-sheet.png`: the top row is 1A (neutral, mid-blink at 0.53 s, happy, angry, worried, neutral
  again, moved and tilted). The bottom row is 1B (the remap blink, then the cross-fade to angry, then the reversed
  blink).
- `android-nested-burst.png`: ten consecutive `screencap`s on the emulator with `Fit.Fill`. The top row goes
  worried → mid-fade → neutral → happy, and the bottom row is the remap Pip angry, with blinks visible. The single
  frames are in `android-burst/`.

## 2. Text with an embedded font: PASS

File `text.riv` (68,544 bytes, of which 67,988 are the font).
Font: `Inter-Bold.ttf` from `ref/plan-app/server/assets/fonts/` (SIL OFL; licence `Inter-OFL.txt` next to it). It
was already on disk, so nothing was downloaded.

```
Backboard(23)
FontAsset(141)          name(203)="Inter-Bold", assetId(204)=0      # before the artboard; assets are not components
FileAssetContents(106)  bytes(212)=<the whole .ttf>                  # attaches to the FontAsset written before it
Artboard Main ...
  Text(134)             parentId, x, y, sizingValue(284)=2 fixed, width(285), height(286),
                        alignValue(281) 0 left | 2 centre, wrapValue(683)=0 wrap, overflowValue(287)=0,
                        verticalAlignValue(685)=2 middle
    TextStylePaint(137) parentId=<Text>, fontSize(274), fontAssetId(279)=0   # index into the file's assets, in stream order
      Fill(20) > SolidColor(18)                                             # the text colour; a Stroke works the same way
    TextValueRun(135)   parentId=<Text>, styleId(272)=<TextStylePaint component id>, text(268)
```

- Font embedding: the whole TTF goes in band. The runtime shapes text with HarfBuzz from those bytes, so
  **no glyph pre-processing is needed**. Neither runtime needed a CDN fetch or an asset loader. `FileAssetContents.signature`
  (911) is left unset, because only script and shader assets verify signatures. To cut size, subset the font
  (for example with `pyftsubset`, Latin only) before embedding. Inter Bold is already about 68 KB.
- The speech-bubble line (52 px, left-aligned, 720 px wide box) wraps onto three lines. The banner ("Never share your
  code", 120 px, centred, 1000 px wide) wraps onto two centred lines. Both runtimes render the same thing.
- `web-text.png`, `android-text.png` (and the first panel of `android-text-clip-sheet.png`).
- I did not try binding showreel lines to a view-model string. Fixed runs are enough for a scripted reel.

## 3. Events from a timeline: PASS (Android needs the legacy view)

File `events.riv` (383 bytes). It has three general events, keyed in a 4 s looping timeline:

```
Event(128)  name(4)="bubble_in" | "banner_cue" | "bubble_out", parentId=0 (artboard)
timeline: KeyedObject(<event>) KeyedProperty(53=395) KeyFrameCallback(171) frame 30 | 90 | 180  (0.5 s, 1.5 s, 3.0 s)
```

Web (`r.on(rive.EventType.RiveEvent, ...)`; `at` is seconds since load, `delay` is the runtime's sub-frame offset):

```
{"name":"bubble_in","type":128,"delay":0.0005,"at":0.507}
{"name":"banner_cue","type":128,"delay":0.0005,"at":1.507}
{"name":"bubble_out","type":128,"delay":0.0005,"at":3.007}
{"name":"bubble_in","type":128,"delay":0.0085,"at":4.516} ... (the loop repeats)
```

Android (`PipRive.kt` `LegacyEventsPlayer`: `RiveAnimationView.setRiveBytes(bytes, stateMachineName="Main")` plus
`addEventListener`; `t` counts from composition, about 0.25 s before the first frame):

```
I PipSpike: rive event name=bubble_in type=GeneralEvent delay=0.0196 t=0.769s
I PipSpike: rive event name=banner_cue type=GeneralEvent delay=0.0218 t=1.735s
I PipSpike: rive event name=bubble_out type=GeneralEvent delay=0.0405 t=3.238s
I PipSpike: rive event name=bubble_in type=GeneralEvent delay=0.0036 t=4.700s
I PipSpike: rive event name=banner_cue type=GeneralEvent delay=0.4192 t=6.139s   # a frame hitch during screencap; delay reports it
I PipSpike: rive event name=bubble_out type=GeneralEvent delay=0.0028 t=7.200s
```

The gaps (about 1.0 s and 1.5 s) match the keys, and the events repeat each loop.

**Caveats that matter for the build:**

- In rive-android 11.16.3, the Compose API (`app.rive.Rive`, `RiveFile`, `ViewModelInstance`, which is what
  the spike and the Pip app use) **has no event listener**. A grep of `kotlin/src/main/kotlin/app/rive` finds no
  `reportedEvent` path. Events reach Kotlin only through the legacy `app.rive.runtime.kotlin.RiveAnimationView`
  and its `RiveFileController.RiveEventListener`. That view works fine inside a Compose `AndroidView` in the
  overlay window.
- The web runtime logs: "Subscribing to Rive Events at runtime is deprecated ... please use data binding
  instead." Rive is steering apps toward view-model values for runtime signals. If the app moves to the Compose
  API only, the same cues can be carried as view-model properties that the timeline drives and the app observes.
  I didn't try this; it would need a to-source bind from a keyed property.
- Blend modes also pass here: the large disc is `Shape.blendModeValue(23)=24` (multiply), and the yellow dot under
  it reads green in both runtimes (`web-events.png`, first panel of `android-events-many-sheet.png`). Enum:
  srcOver 3, screen 14, overlay 15, darken 16, lighten 17, ..., multiply 24, through luminosity 28. Fills and strokes
  also have their own `ShapePaint.blendModeValue` (747).

## 4. Runtime position moves a clip: PASS

Files `clip.riv` (circle mask) and `clip-out.riv` (circle hole). Both are about 2.5 KB.

```
ViewModel(435) "Stage" { ViewModelPropertyNumber(431) "holeX", "holeY" }      # before the artboards
ViewModelInstance(437) "Default" { ViewModelInstanceNumber(442) 540, 1200 }
Artboard Main viewModelId(583)=0
  Node "pipGroup" (parent artboard)
    NestedArtboard Pip + NestedStateMachine (keyed x so Pip slides across the edge)
    ClippingShape(42) parentId=<pipGroup>, sourceId(92)=<holeClip shape>, fillRule(93) 0 nonZero | 1 evenOdd, isVisible(94)=true
  Node "hole" (parent artboard) x=540 y=1200
    DataBindContext(447) propertyKey(586)=13 (x), flags(587)=0 (to target), sourcePathIds(588)=[0,0]   # directly after the node
    DataBindContext(447) propertyKey(586)=14 (y), flags(587)=0,             sourcePathIds(588)=[0,1]
    Shape "holeClip": Ellipse 360x360                       (clip.riv: Pip visible only inside the circle)
                      Rectangle 8000x8000 + Ellipse 360x360 (clip-out.riv, evenOdd: Pip hidden inside the circle = behind the icon)
    Shape "iconMark": a translucent disc, only to show where the circle is
```

- Two binds in a row on one node work, because each `DataBind` targets the last non-bind object.
- The clip follows the node it was bound to. The nested artboard is clipped like any drawable.
- Web: `r.viewModelInstance.number('holeX').value = 380` and similar. Android: the existing receiver,
  `adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --ef holeX 700 --ef holeY 1300`
  → `I PipSpike: applied Number(name=holeX, value=700.0)` / `holeY 1300.0`.
- With `Fit.Fill`, a 1080x2424 artboard and a full-screen overlay, **artboard units equal screen pixels**. An icon's
  `AccessibilityNodeInfo.getBoundsInScreen()` centre can therefore go straight into `holeX` and `holeY`.
- `web-clip-sheet.png`: three panels for `clip` (default, 380/1000, 700/1300), then three for `clip-out`.
  `android-text-clip-sheet.png` panels 2-5: `clip` default and 700/1300, then `clip-out` default and 380/1000.
  Both runtimes match.

## Other findings

- **Nested instancing:** `many.riv` has 40 `NestedArtboard`s of the same Pip artboard, each with its own
  `NestedStateMachine`, keyed mood and rotation, and a 120 s timeline (92 KB). `many-400.riv` has 400 instances
  (908 KB). Both load and play in both runtimes, and each instance keeps independent state (`web-many.png`,
  `android-many*.png`). Web load (fetch to `onLoad`) took 52 ms for 40 instances and 78 ms for 400. On Android, the
  400-instance file logged `Choreographer: Skipped 56 frames` while loading, about 0.9 s of main-thread work.
  The 40-instance file didn't. I did not measure steady-state render cost per instance. The showreel needs a few
  dozen nested instances at most, which is comfortable.
- **Maximum practical file size:** there's no format limit. Sizes are varuint lengths, and a 908 KB file loaded
  fine. In practice, size goes to embedded fonts (about 68 KB per weight) and to keyframes (about 6-8 bytes per
  double key). A 2-minute reel with about 30 set-piece artboards and a few thousand keys should stay well under
  1 MB, and a few MB would still be fine. Keep images out, or subset them.
- **Full screen on Android:** a `TYPE_ACCESSIBILITY_OVERLAY` window with `MATCH_PARENT`,
  `FLAG_LAYOUT_NO_LIMITS`, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` and `fitInsetsTypes = 0` measures **1080x2424**
  (logged `overlay size 1080x2424`), so it covers the status and navigation bars. `Fit.Fill` then maps the
  1080x2424 artboard 1:1, and every Android screenshot here is that setup. `Fit.Layout(scaleFactor)` is for
  artboards that use Rive's layout engine and resize to the view. That's not what we want for a fixed-coordinate
  reel. On another screen size, `Fill` would stretch, so use `Fit.Contain`/`Cover` with letterboxing, or compile
  the main artboard at the device size.
- **Text, events and view models coexist with nested artboards** in one file. Combining them means writing assets
  and view models before the first artboard, as in the previous spike.

## Changes to the Android spike app (`~/ref/pip-rive-spike`)

- The overlay is now full screen (it was 280x340 dp at the bottom end). `--es fit fill|contain|layout` picks the
  fit, and `--es file <asset>` picks the file. Files whose names start with `events` play in the legacy
  `RiveAnimationView` with an event listener that logs `rive event name=...`.
- The spike `.riv` files were copied into `app/src/main/assets/`. `pip.riv` was left unchanged and is still the
  default.

## Emulator housekeeping

- I installed `dev.playground.pipspike` with `adb install -r` and took screenshots with `screencap` only. There was
  no screenrecord (I checked `pgrep screenrecord` first) and no restart. `dev.playground.pip` wasn't touched.
- `enabled_accessibility_services` was saved to `a11y-original.txt`. I appended the spike service, then restored
  the original string, and a string compare printed `RESTORED-EXACT`.

## Reproduce

```
cd ~/ref/pip-riv-compiler
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ~/.gradle/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/bin/gradle installDist
build/install/pip-riv-compiler/bin/pip-riv-compiler --spike \
  docs/pip.svg ~/ref/ref/plan-app/server/assets/fonts/Inter-Bold.ttf out/spike
# web (needs playwright + @rive-app/canvas 2.52.0 installed next to the script; scenarios: nested text events clip clip-out many)
CHROME_BIN=~/Library/Caches/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-mac-arm64/chrome-headless-shell \
  node tools/web/spike.mjs out/spike/nested.riv <outDir> nested
# android
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --es fit fill --es file nested.riv
```

Output hashes (sha1): nested `436b7a60`, text `67614fc6`, events `12d9c8e8`, clip `977ed5cf`, clip-out `e332aab6`,
many `55c42bca`, many-400 `75930275`.
