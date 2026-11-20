# Rive spike results: Pip on Rive, agent-authored, in the accessibility overlay

Date: 2026-11-20. Time spent: about 1 hour of the 2-hour budget.
Proof: `.proof/rive-spike/` (paths below are relative to it).

## Verdicts

| # | Question | Verdict |
|---|---|---|
| 1 | Text authoring of a `.riv` without the editor | **PARTIAL.** The text format and the CLI are real, and an agent authored, built, verified and rendered a fully rigged Pip from text. Writing the `.riv` file needs a `rive login` session, which a person has to approve. |
| 2 | Transparent playback in a `TYPE_ACCESSIBILITY_OVERLAY` | **PASS** with a public sample `.riv`. The background is transparent, the file renders and animates, and the overlay lets touches through. Pip himself wasn't run on device because of the login gate in (1). |
| 3 | Live control over adb | **PASS (mechanism), PARTIAL (Pip).** A broadcast protected by `DUMP` sets view-model values and the rendering changes within a frame. Only the sample's number property was exercised on device; Pip's `lookX`, `mood`, `alert` and `blink` were proven in the CLI's headless renderer. |

**Recommendation: go.** One step is left before the result is complete: someone runs `rive login` once (a free
account works), then `rive pip --publish` and drops `pip.riv` into the spike app's assets. The app already loads
`pip.riv` in preference to the sample, and the receiver already maps `mood` names to numbers. That run would
upgrade questions 1 and 3 to PASS. Licence: on the Free plan, an RML project's published `.riv` carries the Rive
splash screen, and CLI pushes and publishes are capped at 50 a month. A paid seat removes both.

## Question 1: text authoring

### What exists (checked, not from memory)

- `rive-app/homebrew-tap` has a cask `rive-cli` (1.10.0, macOS arm64 and Linux x64), downloaded from
  `releases.rive.app`. There is no npm package (`@rive-app/cli` is a 404). The npm `@rive-app/*` packages are runtimes.
- Install: `brew install --cask rive-app/tap/rive-cli`, which gives `/opt/homebrew/bin/rive`, version `rive 1.10.0`.
  `rive analytics off` was run, because the CLI sends usage analytics by default.
- What the research doc claimed is real: `rive create` (writes `rive.yaml`, `scene.rml`, `AGENTS.md`, `CLAUDE.md`),
  RML markup, Luau and AnimaScript scripting, `rive . --verify`, `rive inspect --summary`, `--screenshot`,
  `rive schema`, `rive docs` (about 30 topics, offline), and `rive svg` (SVG to RML).
- There is more than the research doc said: `--data=<prop>=<value>` sets view-model values before a headless
  capture, `--advance=N` steps frames, `--probe=x,y` reads pixel colours, `--commands` scripts many captures in one
  process, `--test` runs script unit tests, and `rive . --android` / `--serve` push live builds to a Rive player
  app on a device. That last one was not used, because it installs Rive's player app on the shared emulator.
- **The gate:** `--verify`, `--screenshot`, `--data-dump`, the preview window and `--serve` all work signed out
  and offline. Only `--publish` (which writes the `.riv`), `--rev` (which writes the editor file) and `push` need a session:

  ```
  $ rive . --verify
  rive  verified (0 bytes, 11ms, 0 errors, 0 warnings)
  rive  not signed in, so no .riv file is saved. To publish one for an app or a web page, or open the project in the Rive Editor, run: rive login
  $ rive . --publish
  Not logged in. Run: rive login
  ```

  `rive login` is a device-code flow that a person approves in a browser. I didn't sign in.
  The docs say a project with no scripts still needs the session to write the file.

### What was built

The project is at `~/ref/pip-rive-spike-rml/pip/`. A copy of the markup is at `pip-scene.rml`.

1. `rive svg app/src/main/assets/pip/pip.svg` converted the hat path, the face path and both eye ellipses into RML
   `PointsPath` cubic vertices with the right colours. That alone rendered a correct Pip. One surprise: it writes
   `pip.rml` next to the input, inside the prototype repo. I moved the file out.
2. I rigged him by hand in RML: `root` (alert motion), then `bob` (idle), with `look` (data-bound eyes and brows),
   `blinkPivot`, `blush`, `face` and `hatPivot` (a pivot at the brim, so the sway reads as follow-through). Earlier
   siblings draw on top.
3. A **view model `Pip`** with `lookX` and `lookY` (numbers from -1 to 1, range-mapped to ±30 and ±22 px), `mood`
   (a number: 0 neutral, 1 happy, 2 angry, 3 worried), `alert` (a boolean) and `blink` (a trigger).
4. A **state machine `Pip`** with four layers:
   - **Idle:** a looping bob plus hat sway.
   - **Blink:** the trigger plays a 12-frame blink, then returns via exit time.
   - **Mood:** AnyState transitions on `mood` with 150 ms cross-fades. Angry shows slanted brows and a redder face,
     happy shows blush, worried shows raised brows.
   - **Alert:** `alert = true` plays the jump (anticipation dip, then a 70 px rise), then loops a 0.2 s shake until
     `alert = false`.

   The artboard has no fill, so it is transparent.

The CLI docs mark state machine *inputs* (`StateMachineBool`, `StateMachineNumber`, `StateMachineTrigger`) as
deprecated, so I used the view-model form. The names match the brief, and the Android Compose API only drives
view models anyway.

Result: it **verified clean on the first full write** (0 errors). The only warning is `artboard-without-style`,
which matters for the editor only. `inspect` counts 259 objects: 10 animations, 4 layers, 7 view-model conditions
and 9 data binds. The whole loop (author, verify, look) takes seconds.

### Evidence that each input changes the rendering (headless, real Rive renderer)

| Capture | Command (`rive . --screenshot=<file> ...`) | Shows |
|---|---|---|
| `q1-neutral.png` | `--advance=1` | rest pose |
| `q1-idle-1s.png` | `--advance=60` | bobbed up, hat swayed |
| `q1-look-right-down.png` | `--data=lookX=1 --data=lookY=1 --advance=1` | eyes moved right and down |
| `q1-mood-angry.png` | `--data=mood=2 --advance=20` | angry brows, redder face |
| `q1-mood-happy.png` | `--data=mood=1 --advance=20` | blush |
| `q1-mood-worried.png` | `--data=mood=3 --advance=20` | worried brows |
| `q1-blink-closed.png` | `--advance=2 --data=blink=1 --advance=5` | eyes closed mid-blink |
| `q1-alert-jump-peak.png` | `--data=alert=true --advance=17` | Pip at the top of the jump |
| `q1-alert-shake.png` | `--data=alert=true --advance=36` | Pip offset sideways in the shake |

All nine are on one page in `q1-contact-sheet.png`. The dark background in these captures comes from the capture
itself: `--probe=5,5` reads `FF1D1D1D`. The artboard has no fill.

## Question 2: transparent overlay playback

The spike app is at `~/ref/pip-rive-spike` (`dev.playground.pipspike`, minSdk 33, AGP 9.0.1, Kotlin
2.3.20, Compose BOM 2026.03.01, JDK 21). It uses `app.rive:rive-android:11.16.3`, the latest on Maven Central
(released 2026-11-11).

- `PipOverlayService` (an AccessibilityService) adds a 280×340 dp `TYPE_ACCESSIBILITY_OVERLAY` window with flags
  `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN` and `PixelFormat.TRANSLUCENT`. The window holds a `ComposeView`
  with its own `LifecycleOwner` and `SavedStateRegistryOwner`, and that view hosts the new Compose API:
  `rememberRiveWorkerOrNull`, `rememberRiveFile(Bytes)`, `rememberViewModelInstanceResult` and `Rive(...)`.
- The build, install and enable all worked first time. To enable the service I appended it to
  `enabled_accessibility_services` with a colon, after the existing Pip app entry.

Findings:

- **First run crashed in the worker:** `UnsatisfiedLinkError ... RenderContextGL.cppConstructor`. The Compose API
  doesn't load the native library. Fix: call `app.rive.runtime.kotlin.core.Rive.init(context)` before composing,
  which is one line in `onServiceConnected`.
- **The background is transparent.** `q2-overlay-sample-t0.png` shows the sample `rating_animation_all.riv` in the
  overlay. The home wallpaper shows above and below the artboard inside the overlay window; the black band is that
  sample's own artboard fill. To confirm, I set a 25 % red clear colour (`--es bg 40FF0000`): `q2-sample-redclear.png`
  shows a pink letterbox with the app behind it visible through it. That means the surface composites with alpha.
  The runtime's `Rive()` uses a `TextureView` with `isOpaque = false`, and its default clear colour is transparent.
- **It renders a complex file:** `q2-sample-blinko-overlay.png` shows Rive's Blinko game sample in the overlay.
- **It animates:** see the frame sequence in Question 3. Once settled, the process shows 0.0 % CPU in `top`, which
  supports the "idle costs nothing" claim. RSS is about 200 MB in a debug build on the emulator.
- **The overlay doesn't take touches:** `dumpsys input` shows `inputConfig=NOT_FOCUSABLE | NOT_TOUCHABLE | TRUSTED_OVERLAY`.
- **Not tested** (still open from the research plan): rotation, app switches over a long session, GPU context loss,
  real hardware, and frame-time comparison with the current Canvas Pip.

## Question 3: live control over adb

`PipControlReceiver` is exported but protected by `android:permission="android.permission.DUMP"`, so only the shell
or system can send to it. It maps extras to view-model writes:

- `--ef name v` sets a number.
- `--ez name b` sets a boolean.
- `--es fire name` fires a trigger.
- `--es mood angry` maps the mood name to Pip's number (neutral 0, happy 1, angry 2, worried 3).
- Debug extras: `--es file x.riv` switches the asset and `--es bg AARRGGBB` sets the clear colour.

```
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --ef Number_Star 4.0
I PipSpike: command Number(name=Number_Star, value=4.0)
I PipSpike: applied Number(name=Number_Star, value=4.0)
```

In `q3-sample-sheet.png`, the overlay goes from 0 stars, through frames captured back to back during the
transition, to 4 stars, and then to 2 after `--ef Number_Star 2.0`. Full screenshots: `q3-sample-star0.png`,
`q3-sample-star4.png` and `q3-sample-star2.png`.

Pip's own properties (`lookX`, `lookY`, `mood`, `alert`, `blink`) are wired through the same code path, but they
need `pip.riv` on the device, which is the login gate from Question 1. Their effect on rendering is proven
headlessly in Question 1.

## Surprises and limits

- **The vendor discourages state machine inputs.** The CLI docs call them deprecated, and the new Compose API only
  drives view models. The research doc's "view model" plan is the right one.
- **The Rive CLI is very agent-friendly.** It ships its own `AGENTS.md`, offline docs and schema, and headless
  captures with data injection. Its rules for agents ("never generate RML from a script, edit it by hand", "use
  `rive illustrate` for detailed art") are opinionated. `rive illustrate` needs a login and spends paid credits.
- **The CLI makes the `.riv` hard to get.** Every useful output (`.riv`, `.rev` and web publish) needs an account
  session. Free-plan publishes of RML projects carry the Rive splash screen, and the Free plan has 50
  pushes/publishes a month per Workspace. Scripted files must be signed server-side or web runtimes reject them;
  Android wasn't tested with an unsigned scripted file. This is a vendor dependency in the build pipeline: CI would
  need a Rive token, or the `.riv` has to be committed.
- **Some older sample files render nothing in the new Compose API** (`mascot.riv`, `skills.riv`, `loopy.riv`,
  `what_a_state.riv`; none has a view model). Files built for the Compose path should have a default state machine
  and a view model, which Pip's does.
- **Debug APK size is 53 MB,** because of the Rive native libraries for every ABI. Ship ABI splits or an app bundle.
- **`RiveLog.LogcatLogger` dumps the entire file's bytes into logcat** on load. Don't enable it in production.
- **The emulator was shared.** Other actors were active during the run: Google Photos opened, the app's own Pip
  overlay roamed, and an in-call screen was up at the end. None of these came from this spike.
- **Not verified:** that an editor `.rev` round-trips back to RML. `rive pull` and `create --from-rev` exist and
  both need a login. Bones, meshes and Luau idle scripts weren't tried either; all of Pip's motion is keyframes plus
  data binds.

## Cleanup

- `enabled_accessibility_services` was restored to exactly its original value,
  `dev.playground.pip/dev.playground.pip.sources.GuardAccessibilityService` (saved in
  `original-enabled_accessibility_services.txt`). The spike overlay window is gone.
- `dev.playground.pipspike` is still installed, so the run can be repeated once `pip.riv` exists. It is inert
  while its service is disabled.
- dev.playground.pip wasn't touched.
- Home was pressed at the end. Another actor's in-call activity was in front, and I left it there.

## To finish (about 10 minutes, needs a person once)

```
rive login                                   # approve the code in a browser
cd ~/ref/pip-rive-spike-rml/pip && rive . --publish
cp build/pip.riv ~/ref/pip-rive-spike/app/src/main/assets/
cd ~/ref/pip-rive-spike && ./gradlew :app:installDebug
# enable the service (append to enabled_accessibility_services), then:
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --ef lookX 1.0
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --es mood angry
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --ez alert true
adb shell am broadcast -a dev.playground.pipspike.SET -p dev.playground.pipspike --es fire blink
```
