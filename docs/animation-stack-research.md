# Pip animation stack: research and recommendation

Researched 2026-11-20. Question: what is the least hacky, most declarative way to animate Pip over other apps on Android,
authored outside the app (ideally by AI agents as text, refined later by a designer), and played on device? A second
part asks whether a stylised, toy-like **3D Pip** is worth it and on which stack.

Facts marked **(verify)** come from secondary sources or could not be confirmed from primary docs; treat them as leads
for the spike, not as settled.

---

## 1. Recommendation (2D)

1. **Move Pip's rig and live reactions to Rive** (`app.rive:rive-android`, Rive Renderer on the GPU), driven by one state machine plus a view model (`mood`, `mouth`, `lookX/Y`, `blush`, `alert` trigger, `talking`).
2. Rive is the only option that is both declarative *and* interactive at runtime: state machines, blend states, data binding and Luau scripting are in the file, so the app only sets properties; Duolingo runs its talking characters this way.
3. It is now **agent-authorable**: the Rive CLI builds `.riv` from plain-text Rive Markup Language (RML) plus Luau, with `--verify`, `inspect` and `--screenshot` for agents, and the same file opens in the Rive Editor for a designer.
4. Keep the **showreel choreography as our Kotlin scene DSL** in phase 1, but have it drive the Rive view model instead of drawing Canvas paths; move individual scenes into Rive timelines only where a designer wants to own them.
5. Spike first (1 to 2 agent days): prove RML can express Pip from our SVG, and that a Rive view renders transparently and steadily inside the `TYPE_ACCESSIBILITY_OVERLAY` window. **Lottie/dotLottie is the fallback** if either fails.

## 1b. Recommendation (3D)

**No-go as the main stack today; a time-boxed "go" spike is reasonable if the toy look is a product goal.** If we do it:
**Blender (scripted by agents with `bpy`) → GLB with shape keys and actions → Filament `gltfio` in a transparent
TextureView in the overlay window**, with a custom toon material, procedural eyes, and 2D speech bubbles and props kept
in Rive or Compose on top. SceneView is an acceptable Compose wrapper over the same Filament core. Unity and Godot are
too heavy for an always-on overlay; Spline's Android runtime is too young and not text-authorable. Section 6 has the
detail.

---

## 2. Comparison table (2D options)

| | **Rive** | **Lottie (lottie-android)** | **dotLottie (dotlottie-android)** | **Compose animation** | **AVD / MotionLayout / RenderThread animators** | **Spine / DragonBones** | **Ours today** |
|---|---|---|---|---|---|---|---|
| **Authoring** | Rive Editor (vector, bones, meshes, timelines, state machines); Rive CLI from RML + Luau; Editor MCP and in-editor AI agent | After Effects + Bodymovin, LottieFiles Creator, or hand/code-generated JSON | Lottie Creator (state machines since Sep 2025) or JSON | Kotlin code | XML (AVD, MotionScene) or Kotlin | Spine Editor (paid) / DragonBones (abandoned) | Kotlin DSL + Canvas art in code |
| **Declarativeness** | High: rig, timelines, state graph, bindings all in the file | High for linear clips; logic lives in app code | High: state machine JSON inside `.lottie` | Medium: declarative API, imperative choreography | High for one-shot UI transitions only | High for skeletal clips | Medium: keyframes are data, art is code |
| **Live interactivity** | Native: state machine inputs, blend states, listeners/events, view-model data binding, scripting | Play segments by marker, dynamic properties (colour, transform) by KeyPath; no blending | State machine with inputs, guards, events | Full, since it is code | Poor: fixed animations, no blending | Animation mixing, IK, skins | Full (hand-rolled) |
| **Performance / GPU** | Own GPU renderer (Rive Renderer, GL by default; Vulkan backend exists, opt-in documented for React Native); Skia and Canvas removed in v10 | Android Canvas; `RenderMode.AUTOMATIC` uses HW acceleration; masks/mattes are the main cost | dotlottie-rs on ThorVG, software rasteriser by default (verify) | HWUI RenderThread; `graphicsLayer` animations are cheap | AVD runs on RenderThread (API 25+), cheapest possible | spine-android draws via Canvas/libGDX code | HWUI Canvas, ~50 fps on emulator |
| **Overlay compatibility** | Compose `Rive` composable (needs lifecycle owners in the overlay) or legacy `RiveAnimationView` (TextureView-based); transparency must be proven in the spike | Plain View; works in any window | View/Compose; works | `ComposeView` works in a WindowManager window once Lifecycle and SavedStateRegistry owners are set | Plain Views; works | View; works | Works today |
| **Agent-authorability** | Good and improving: RML + Luau text, schema via `rive schema`, verify/screenshot loop; `.riv` itself is binary | Good: JSON with a published community spec; agents can write it, but shape/keyframe JSON is verbose and error-prone by hand | Good: JSON animation + JSON state machine | Excellent (it is Kotlin) | Good for small XML; impractical for a character | Poor: binary/JSON exports from a GUI editor | Excellent, but the art is code |
| **Licence / cost** | Runtimes MIT; no runtime fees. Free plan exports carry a Rive splash and 50 CLI publishes/month; Cadet $108/seat/yr removes the splash | Apache 2.0, free | MIT, free; Creator has paid tiers | Apache 2.0 | Free (AOSP) | Spine: each user needs a Spine licence ($69 to $369+); DragonBones unmaintained | Free |
| **Maturity** | High for runtime (Duolingo, many apps); CLI/RML is new (verify stability) | Very high, years in production | Medium: v0.x Android API | Very high | Very high | Spine high; spine-android GA Sep 2024 | Ours to maintain |

Not considered further: **MotionLayout** (layout transitions, not character acting) and **AnimatedVectorDrawable** for the
whole character (no runtime blending, no state; fine for a single icon). Both remain useful for small UI chrome.

---

## 3. Findings by option

### 3.1 Rive

- **Android runtime.** `app.rive:rive-android` 11.x. The Compose API (`Rive` composable, `rememberRiveFile`, a worker
  that owns a render thread) is described as feature complete and recommended for new Compose projects; the legacy
  `RiveAnimationView` is still widely deployed but slated for deprecation. Play/pause is gone in the new API: the state
  machine settles and unsettles on its own, so an idle Pip costs nothing when nothing changes.
- **Renderer.** The Rive Renderer is Rive's own GPU path renderer (Metal, Vulkan, D3D, GL backends in `rive-runtime`).
  On Android it is the default and the only option in Compose; Canvas and Skia were removed in v10. Vector feathering
  (soft shadows, glows: useful for blush and soft-clay looks) needs it. The docs show a Vulkan opt-in only for React
  Native (Android 10+, falls back to GL) (verify for native).
- **State machines, events, data binding.** Inputs (bool, number, trigger), layers that run in parallel (face, body,
  hands, hat), blend states (e.g. a 2D blend on `lookX/lookY`), listeners and events back to the app. Data binding
  (view models) is the current model: the app binds a `ViewModelInstance` and sets properties; the composable binds it
  to the state machine automatically.
- **Scripting.** Luau scripts run inside the file (editor and runtimes share the engine), so procedural behaviour such
  as random idle blinks, look-around targets or a hat spring can live in the asset, not in Kotlin.
- **File format.** `.riv` is binary. It is no longer editor-only: the **Rive CLI** builds `.riv` (and editor-openable
  `.rev`) from **RML**, with `rive create` scaffolding `AGENTS.md`/`CLAUDE.md`, `rive docs`/`rive schema` for lookup,
  `--verify`, `inspect --summary` and `--screenshot` for the agent's check loop. The Editor also exposes an MCP server
  (desktop only; creates state machines, view models, etc.). Third-party MCP servers claim to serialise `.riv`
  directly (verify; unofficial). How completely RML covers bones, meshes and SVG import is not documented on the pages
  read: this is the spike's first question.
- **Overlays.** No first-party guidance for `TYPE_ACCESSIBILITY_OVERLAY`. The composable needs the same lifecycle
  owners as any `ComposeView` in a WindowManager window (section 3.3). Transparency and GPU-context loss (the docs have
  a guide for driver resets) must be tested on device.
- **Real world.** Duolingo's World Characters lip-sync through a Rive state machine fed with audio timing data, and the
  Lily AI video call drives mouth, expressions and camera from the state machine; Duolingo credits the state machine
  with making the project feasible at scale.

### 3.2 Lottie and dotLottie

- **Authoring.** After Effects + Bodymovin is the classic path; LottieFiles Creator is the web editor. The format has a
  community specification (Lottie Animation Community), so JSON can be generated from code. `python-lottie` generates
  JSON from Python but is **AGPL-3.0**, a problem for a commercial pipeline; no maintained Kotlin generator was found.
- **Runtime control (lottie-android).** Play ranges by marker name, set min/max frame, and override colours,
  transforms and paths at runtime through `KeyPath` value callbacks (`rememberLottieDynamicProperties` in Compose). No
  state machine or cross-fade: interactive characters become many clips glued by app code, which is what we already have.
- **Performance.** Draws through Android Canvas; `RenderMode.AUTOMATIC` uses hardware acceleration except for dash
  paths before Pie. Masks and mattes are the main cost. No offscreen GPU renderer of its own.
- **dotLottie.** A `.lottie` zip holds animations, themes and state machines (spec 1.0: states, transitions, guards,
  actions, interactions). `dotlottie-android` (MIT, Compose `DotLottieAnimation`) renders through dotlottie-rs/ThorVG;
  the Android API is still v0.x. State machines arrived in Lottie Creator in September 2025.
- **Verdict.** Best fallback: free, mature, agent-writable JSON. Weaker than Rive at the thing Pip needs most:
  layered, blended, live facial acting.

### 3.3 Jetpack Compose animation

- `keyframes {}`, `updateTransition`, `rememberInfiniteTransition`, `Animatable` and `graphicsLayer` cover everything our
  engine does, and `Canvas`/`drawWithCache` can draw the SVG paths (or `rememberVectorPainter` on an `ImageVector`).
- **In an AccessibilityService overlay**: a `ComposeView` added with `WindowManager.addView` must have a
  `LifecycleOwner` and `SavedStateRegistryOwner` (and a `ViewModelStoreOwner` if `viewModel()` is used) set via
  `setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner` before `addView`. The usual pattern is a small
  `OverlayLifecycleOwner` (a `LifecycleRegistry` + `SavedStateRegistryController`) moved to RESUMED on show and
  DESTROYED on removal, with the owners cleared to avoid leaks.
- **Verdict.** Excellent glue (bubbles, layout, accessibility) and a fine host for the Rive composable; as the
  character engine it is the same "art is code" situation as today, just in a nicer API.

### 3.4 Platform declarative options

- **AnimatedVectorDrawable**: XML, path morphing (paths must be compatible), runs on RenderThread from API 25 so it
  stays smooth under main-thread jank. No runtime parameters or blending: good for icons, not for a character.
- **MotionLayout**: declarative layout transitions with keyframes; not a character system.
- **RenderThread animators** (`ViewPropertyAnimator.withLayer`, the hidden `RenderNodeAnimator`): only transform/alpha
  properties of whole views. Useful for moving the overlay window's content around cheaply; not for faces.

### 3.5 Skeletal 2D: Spine and DragonBones

- **Spine** is excellent for skeletal animation, mixing and IK, and `spine-android` went GA in September 2024 (Maven
  Central, built on spine-libgdx). The runtime licence requires that the integrating party hold a Spine Editor
  licence; the editor is a GUI with JSON/binary export, so agent authoring is poor. Pip is a head with floating hands:
  he barely needs a skeleton.
- **DragonBones** is effectively unmaintained. Skip.

### 3.6 "3D canvas" for a 2D mascot

- **AGSL `RuntimeShader` (API 33+)** is worth having for effects (shine, wobble, glow, dissolve) on top of whatever draws
  Pip; it is not an animation system. Rive's own shaders (GPU Canvas, below) are an alternative inside the asset.
- **Filament / SceneView / Unity / Godot** are not warranted for 2D Pip: they add a 3D pipeline, a GPU surface and
  megabytes for something a vector renderer already does. They only make sense for a 3D Pip (section 6).

### 3.7 How the pros do it

- **Duolingo**: Rive state machines for World Characters and the Lily video call (primary sources below).
- **Google**: Material motion (Material 3 Expressive, 2025) is spring-based Compose motion for UI; Google apps use
  Lottie widely for illustration (verify per app). No public Pixel-mascot pipeline.
- **Microsoft Copilot "Mico"** (Oct 2025): a floating, colour-changing face that reacts in real time during voice chat;
  designed with PSYOP as "a character defined by behavior"; its runtime stack is not public.
- **Headspace** and **OEM assistants**: no primary source on their character pipelines was found (verify before citing).

---

## 4. Migration sketch from the current engine

**What we keep**

- `pip.svg` as the source of truth for the art: import it into Rive (Editor SVG import, or RML generated from it by
  an agent) as named groups: `hat`, `hatTip`, `face`, `eyeL/R` + `lidL/R`, `browL/R`, `mouth_*`, `blushL/R`, `handL/R`.
- The animation playbook's rules (squash with volume preservation, hat follow-through 60 to 100 ms, eyes lead, blink
  ratios). They become timelines and state-machine transitions instead of Kotlin keyframes.
- The showreel's scene DSL (`ui/show/ShowDsl.kt`) and scene files for phase 1: they still own *when* things happen,
  stage layout, backdrops and props.

**How things map**

| Today | In Rive |
|---|---|
| `x/y`, `size`, `rotation`, `squash` per frame | Artboard root transform for travel (or keep in host view), plus `hop`, `land`, `shake` one-shot timelines with squash baked in |
| `eyesOpen`, `blink` | `blink` timeline on a face layer, fired by a Luau idle script (random 2 to 6 s) or a trigger |
| `look(x, y)` | 2D blend state on `lookX`/`lookY` view-model numbers |
| `hatSway`, `hatDroop` | Hat bones with a spring constraint or Luau follow-through script |
| `mood` (5) and `mouth` (6) enums | View-model enums driving face-layer states; swaps hidden behind blinks per the playbook |
| `blush` | Number property bound to blush opacity (feathered) |
| Scam warning | `alert` trigger: anticipation, jump, shake, worried face, then back to idle via exit transition |
| Idle | Default state: breathing, drift, blink and look-around script |
| Talking | `talking` bool (later visemes from TTS timings, as Duolingo does) |
| Speech bubbles, scenery, props | Stay in Compose/Canvas in phase 1; move to nested artboards in Rive when designers want them |

**Runtime wiring**

- Overlay: `GuardAccessibilityService` adds a `ComposeView` (with the overlay lifecycle owner) hosting the `Rive`
  composable; `Guard` events map to view-model writes (`alert.fire()`, `mood = WORRIED`).
- Showreel: `PipShow` keeps its timeline, but each actor's Pip becomes a Rive instance whose view model the DSL sets
  per frame or per cue; scene-specific moves (ninja fight, dance) become named timelines or nested artboards.

**How agents author content**

- An agent edits RML and Luau in `pip-rive/` (a `rive create` project checked into the repo), runs
  `rive pip-rive --verify`, `rive inspect --summary` and `--screenshot`, and commits both the RML source and the built
  `.riv` under `app/src/main/assets/`. A Gradle check fails if the `.riv` is stale against its source.
- A designer opens the `.rev` in the Rive Editor to refine curves and timing; the round trip back to RML must be
  confirmed in the spike (if it is one-way, the editor file becomes the source of truth after hand-off).
- Kotlin scene code stays agent-written as today.

**Spike checklist (before committing)**

1. RML builds Pip from `pip.svg` with named groups, a blink timeline, a `lookX/Y` blend and an `alert` trigger.
2. The `.riv` renders with a transparent background in the accessibility overlay on a real device and the emulator,
   survives screen rotation and app switches, and idles at near-zero GPU when settled.
3. Frame time with the showreel's busiest scene, compared with today's ~50 fps.
4. Confirm the licence terms for CLI-built exports (splash on Free) and buy Cadet seats if needed.

---

## 5. Risks (2D)

- **RML maturity and coverage** are not documented in depth on the pages read; if bones/meshes/state machines are not
  fully expressible, agents fall back to the Editor MCP (desktop app must be running) or to designers.
- **Vendor dependence**: the runtime is MIT, but authoring is tied to Rive's tools and paid plans (Free has a splash
  screen; CLI publish quotas per month).
- **GPU surface in an overlay**: context loss, transparency and memory in a long-lived service window need device
  testing across GPUs; a crash in a native renderer inside an accessibility service is worse than in an activity.
- **Two-way editing**: if Editor changes cannot round-trip to RML, agent and designer edits conflict.
- **Scope creep in the showreel**: moving all ~2 minutes of scenes into Rive at once is large; keep the DSL until scenes
  are proven.
- **Accessibility**: honour the system animator scale (Remove animations) by switching the state machine to a reduced
  layer, as the playbook already requires.

---

## 6. 3D deep dive: a toy-like 3D Pip

### 6a. Authoring pipeline

- **Blender + `bpy`** is fully scriptable, which suits agents: a Python script can build Pip from primitives guided by
  `pip.svg` (a squashed sphere head, a cone/bent-tube hat with a bone chain for the floppy tip, flattened ellipsoid
  eyes, separate floating-hand meshes), add an armature (root, head, hat chain, eye bones, hand bones), create shape
  keys for eyelids, brows, mouths and blush, keyframe one **Action** per clip (idle, blink, hop, alert, talk) and lay
  scenes out as NLA strips. The glTF exporter writes GLB with skins, morph targets and one animation per action.
  Agents can render previews headless (`blender -b ... -f 1`) to check their work.
- **Spline** has a native Android runtime (`design.spline:spline-runtime`, Vulkan, Android 10+, v0.2.x) with a Kotlin
  API, but it is young, some features are still unsupported on Android, and the scene format is not text-authorable.
- **Rive and 3D**: Rive's **GPU Canvas** (reported live in the production editor with Android runtime support; verify,
  source is a repost) exposes a low-level GPU layer for 3D, shaders and effects inside a Rive file. It is not a 3D
  modelling and rigging pipeline, but it could host a simple shaded Pip later without a second engine.

### 6b. Android runtimes for glTF

| | **Filament + gltfio** | **SceneView** | **Unity as a Library** | **Godot as a library** | **Spline runtime** |
|---|---|---|---|---|---|
| What | Google's mobile PBR engine; `gltfio` loads GLB, `Animator` plays animations, skins and morph targets | Compose-native wrapper over Filament (`ModelNode.playAnimation`, reactive animation API) | Full Unity runtime embedded as an AAR | Godot AAR (`org.godotengine:godot`) hosted in `GodotFragment` | Spline scenes in `SplineView` |
| Live control | Set morph weights, bone transforms and animation time per frame; cross-fade between two animations | Same, via nodes | Animator state machines, blend trees, IK | AnimationTree, blend spaces, IK, GDScript | Spline events and variables API |
| Size | Small by design (measure; a few MB per ABI expected) | Filament plus wrapper (and ARCore if AR is used) | About 90 MB unloaded runtime on Android (one example) | Tens of MB (measure) | Unknown (measure) |
| Licence | Apache 2.0 | Apache 2.0 | Proprietary; Unity plans and terms | MIT | Spline plans |
| Overlay fit | Good: TextureView with `UiHelper.setOpaque(false)`, transparent clear, translucent view blend; known black-background regressions tied to post-processing | Same core; Compose host needs overlay lifecycle owners | Poor: built around an activity and a full-screen surface | Poor to fair: fragment/activity hosting | Unproven |
| Agent authoring | GLB from Blender scripts; materials as `.mat` text compiled by `matc` | Same | C# and scenes; heavy | GDScript + text scenes (`.tscn`) are agent-friendly | Low |

For an always-on accessibility overlay, **Filament (directly or via SceneView)** is the only engine of the right weight.

### 6c. Stylised look

- **Toon/cel**: a custom Filament material (unlit or custom surface shading) that quantises N·L into two or three bands
  from our flat palette (hat red, face peach), plus rim light for a vinyl look. **Soft clay**: lit model with high
  roughness, a subtle subsurface-like wrap term, and baked AO.
- **Outlines**: inverted-hull second pass (back faces, inflated along normals) in a dark warm tone; Filament has no
  built-in outline, so it is a second material on a duplicate mesh.
- **Matching the 2D palette**: drive base colours from the same tokens as `pip.svg`; keep eyes as flat, unlit decals so
  his face reads like the 2D Pip.
- **2D/3D hybrid** works well and is how most products do it: a 3D head in its own transparent surface with 2D speech
  bubbles, props and effects drawn in Compose or Rive above it. Watch for lighting mismatch (give 2D props a matching
  soft shadow) and for layering if a prop must pass behind his head (use two 2D layers around the 3D view).

### 6d. Live reactions in 3D

- Clips from Blender actions; `Animator.applyAnimation` per frame, `applyCrossFade` to blend from the previous clip, and
  additive layering done by applying an idle first and an overlay clip after.
- Expressions as morph-target weights set directly from app state (lerped in Kotlin or a small spring), independent of
  the body clip.
- **Look-at**: compute eye-bone (or eye-mesh UV offset) rotation from a target point each frame after the animator
  updates, with the playbook's "eyes lead by 80 to 170 ms" rule as a delay.
- Render on demand: stop the Choreographer loop when Pip is idle and settled, since a continuous 3D render over every
  app costs battery.

### 6e. Real examples of 3D mascots on mobile

- **Talking Tom & Friends** (Outfit7) and **Pokémon GO** buddies: Unity, full-screen, not overlays.
- **Apple Memoji/Animoji**: real-time 3D faces with blend shapes, the closest reference for expression rigs.
- **Microsoft Copilot Mico** (2025): an expressive floating face in a chat surface; stack not public.
- **Duolingo** keeps its characters 2D in Rive, including the AI video call: evidence that 2D vector rigs are enough
  for an expressive, live-reacting character.

### 6f. 3D risks

- A second, heavier pipeline (Blender, glTF, custom materials) for a character that mostly needs face acting.
- Transparent GPU surfaces in an overlay window have had regressions in Filament; test on several GPUs.
- Battery and thermals for an always-on 3D view; mitigate with render-on-demand.
- The showreel's scenery, props and parodies would need 3D or a careful 2D/3D mix, multiplying asset work.
- Designer tooling: Blender is fine for 3D artists but far from the vector workflow Pip has today.

---

## 7. Sources

Rive
- Rive Android runtime docs: https://rive.app/docs/runtimes/android/android
- Rive data binding (Android): https://rive.app/docs/runtimes/android/data-binding
- Choosing a renderer: https://rive.app/docs/runtimes/choose-a-renderer/overview
- Migrating from the legacy Android API: https://rive.app/docs/runtimes/android/migrating-from-legacy
- rive-android repo: https://github.com/rive-app/rive-android
- Rive CLI overview: https://rive.app/docs/cli/overview
- Rive CLI for agents: https://rive.app/docs/cli/agents
- Rive Editor MCP: https://rive.app/docs/editor/ai/mcp
- Scripting is live in Rive: https://rive.app/blog/scripting-is-live-in-rive
- Why scripting runs on Luau: https://rive.app/blog/why-scripting-runs-on-luau
- Pricing: https://rive.app/docs/account-admin/pricing and https://rive.app/pricing
- Pricing change, no runtime fees: https://rive.app/blog/rive-s-new-9-mo-plan
- Duolingo, World Character visemes: https://blog.duolingo.com/world-character-visemes
- Rive case study, Duolingo Lily video call: https://rive.app/blog/duolingo-s-ai-powered-video-call-brings-lily-to-life
- Third-party `.riv` MCP server (unverified): https://mcpservers.org/servers/rive-mcp

Lottie and dotLottie
- lottie-android performance and RenderMode: https://airbnb.gitbook.io/lottie/android/performance
- lottie-android changelog: https://github.com/airbnb/lottie-android/blob/master/CHANGELOG.md
- dotLottie state machine spec: https://docs.lottiefiles.com/en/format/dotlottie/interactivity/state-machines/specification
- dotlottie-android: https://github.com/LottieFiles/dotlottie-android
- State machines in Lottie Creator: https://lottiefiles.com/blog/working-with-lottie-animations/state-machines-are-finally-in-lottie-creator
- python-lottie (AGPL): https://pypi.org/project/lottie/

Compose and platform
- ComposeView owner requirements: https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ComposeView
- Compose in a Service window (pattern): https://www.techyourchance.com/?p=12266 and https://gist.github.com/handstandsam/6ecff2f39da72c0b38c07aa80bbb5a2f

Skeletal 2D
- Spine runtimes licence: https://raw.github.com/EsotericSoftware/spine-runtimes/master/LICENSE
- spine-android release: https://uk.esotericsoftware.com/blog/spine-android-runtime-released

3D
- Filament: https://google.github.io/filament/dup/intro.html and repo https://github.com/google/filament
- Filament materials (`matc`, custom shading): https://google.github.io/filament/Materials.html
- Filament transparent TextureView discussion: https://github.com/google/filament/discussions/7220 and https://github.com/google/filament/issues/7794
- gltfio-android on Maven Central: https://central.sonatype.com/artifact/com.google.android.filament/gltfio-android/
- SceneView: https://github.com/sceneview/sceneview and https://sceneview.github.io/docs/
- Godot Android library: https://docs.godotengine.org/en/latest/tutorials/platform/android/android_library.html
- Unity as a Library size example: https://github.com/LinaEagle/unity-UaaL-IL2CPP-example/blob/feature/ar-foundation-example/README.md
- Spline Android runtime: https://docs.spline.design/exporting-your-scene/android/native-3d-embeds-for-android and https://central.sonatype.com/artifact/design.spline/spline-runtime
- Microsoft Copilot Mico: https://psyop.com/work/microsoft-ai-mico and https://www.fastcompany.com/91427839/microsoft-mico-copilot-ai-assistant
