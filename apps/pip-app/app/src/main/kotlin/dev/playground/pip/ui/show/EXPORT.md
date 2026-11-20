# Showreel export (`pip.showreel/1`)

`ShowExport.kt` turns a prepared `Show` into plain data so the rivc compiler can rebuild the showreel in Rive
without running Kotlin. `ShowExporterTest` writes it to `apps/pip-app/app/build/showreel/showreel.json`:

```
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk \
  ./gradlew -q testDebugUnitTest --tests '*ShowExporterTest*'
```

The test reads the file back and replays it with a small evaluator that follows only this document. At 50 random
times and on a 3000-point sweep, every Pip and actor property matches `Show.frame()` within the tolerances listed
under "Accuracy". `apps/pip-app/app/build/showreel/verify.txt` has the maximum error for each property.

The stage is the emulator's 1080 x 2424 home screen with its seven launcher icons. Scenes read icon positions while
they are built, so a different stage gives different numbers.

## Clock and units

- Every time is in seconds on the global show clock. Scene `n` starts where scene `n - 1` ends.
- Positions are screen pixels. Pip's `x`, `y` is the bottom centre of his box, and `size` is the box width. Hand
  values are in Pip's own 540 x 660 box units.
- Angles are in degrees.
- A float that is `NaN` in Kotlin is written as `null`. Only `groundY` uses this, meaning "under his feet".

## Top level

| field | meaning |
| --- | --- |
| `format` | `"pip.showreel/1"` |
| `duration` | Show length in seconds. |
| `stage` | `width`, `height` and `icons` (name to `[left, top, right, bottom]`). |
| `scenes` | One entry per scene, in order. |
| `actors` | Every actor of every scene. |
| `animations` | Every animation: the global life layer, then each scene's own. |
| `cues` | Soundtrack events: `{t, name, scene}`. |
| `texts` | Speech bubbles, banners and any other actor with text, with the windows when each is on screen. |
| `customActors` | `CUSTOM` actors, whose drawing is Kotlin code that has to be redrawn as SVG. |
| `notes` | Anything the exporter could not express. Empty for the current showreel. |

## Scenes

Each scene has `index`, `name`, `start`, `duration` and `end`. It also carries:

- `pipBase`: Pip's values before any animation. These are his defaults (`PipState.reset()`), then the global base
  style, then the scene's base style. Discrete values are enum names, booleans, strings or `null`.
- `actors`: the ids of the actors this scene declares.
- `stack`: the ids of the animations that apply in this scene, in cascade order.

A scene is a hard cut. At time `t`, only the scene with `start <= t < end` is evaluated. Past the end of the show,
the last scene is evaluated. Pip and every actor restart from their base values on each frame, so no value carries
over from one scene to the next.

## Actors

Each actor has these fields:

- `id`: `<scene>-actor-<n>`, where `n` is the declaration index. The id is stable while the scene's declarations
  keep their order.
- `kind`, `text`, `layer`, `anchorToPip` and `color` (`#AARRGGBB`).
- `base`: the actor's base values, `x y scale rotation alpha progress text visible`.

Negative layers draw behind Pip and the rest draw in front. A bubble with `anchorToPip` is positioned relative to
Pip by the renderer. An actor is drawn only when `visible` is true and `alpha > 0`.

## Animations

| field | meaning |
| --- | --- |
| `id` | `global-anim-<n>` or `<scene>-anim-<n>` (declaration index). |
| `scope` | `"global"` (applies in every scene) or the scene name. |
| `target` | `"pip"` or an actor id. |
| `begins`, `order` | Cascade sort keys; `scenes[].stack` already applies them. |
| `window` | `[from, to]`: the span where the animation can matter (its scene, or the whole show). |
| `timing` | The declaration as written: `start`, `delay`, `duration`, `iterations` (a number or `"infinite"`), `direction`, `fill`, `composite`, `ease`. Informational; the keys below are already resolved. |
| `active` | `start`, `end` (absent when infinite), `fillBefore`, `fillAfter`, `additive`. |
| `tracks` | One track per animated property: `{property, type: "float" or "discrete", keys}`. |

Property names match `PipProps` / `LifeProps` (`x`, `squashX`, `leftAngle`, `lifeBlink`, ...) and `ActorProps`
(`x`, `y`, `scale`, `rotation`, `alpha`, `progress`, `text`, `visible`).

### Keys

A key is `{t, v, ease?, src?}`:

- `t` is global time and `v` is the value.
- `ease` shapes the segment from this key to the next one. It is either `[x1, y1, x2, y2]` (a CSS `cubic-bezier`;
  linear is `[0, 0, 1, 1]`) or `"step"` (hold `v` until the next key). The last key has no `ease`.
- Two keys at the same `t` are a jump. The later key wins from `t` onward.
- `src` says where a key came from. When it is absent, the key is a keyframe stop as written in the source.
  - `"underlying"`: the stop was left out (CSS-style: no 0% or 100% stop for that property), so the engine
    interpolates from or to whatever lies beneath. The key holds that value resolved at its time: base styles plus
    every earlier animation in the cascade.
  - `"baked:underlying"`: the value beneath was moving during this segment, so the segment is sampled at 120 Hz with
    linear keys.
  - `"baked:ease"`: the ease is not a Bezier (bounce, elastic or a custom lambda), so it is sampled at 120 Hz.
  - `"baked:steps"`: an extra tread of a `steps(n)` ease.
  - `"cut"`: the segment is cut at a scene edge, at the end of a fractional last iteration, or where an infinite loop
    meets its window end. The key's `ease` is the exact matching piece of the original Bezier, renormalised.
  - `"unset"` (discrete tracks only): from here the animation sets nothing, and the property shows what lies
    beneath. `v` is `null`.

Iterations, directions and delays are already expanded into keys. A reversed iteration's Bezier is rotated half a turn
(`[1-x2, 1-y2, 1-x1, 1-y1]`). Infinite loops run to the window end.

### Evaluating a frame at time `t`

1. Pick the scene. Start Pip from `pipBase` and each of the scene's actors from its `base`.
2. For each id in `scene.stack`, in order:
   - If `t < active.start`, use the first key of each track when `fillBefore` is set; otherwise skip the animation.
   - If `active.end` is present and `t >= active.end`, use the last key when `fillAfter` is set; otherwise skip it.
   - Otherwise use the last key with `key.t <= t`. If a next key exists and the ease is a Bezier, interpolate with
     `u = (t - key.t) / (next.t - key.t)`.
   - Float tracks: when `active.additive` is set, add the value to the current one; otherwise replace it.
   - Discrete tracks: set the value unless the key is `"unset"`.
3. Later animations in the stack win. Additive ones add on top of everything before them.

## Cues, texts and custom actors

- `cues` are the moments the engine logs as `PIPCUE <name> <seconds>`.
- `texts[]` contains `{actor, scene, kind, text, texts, shown}`. `texts` lists every text the actor showed, and
  `shown` lists the `[from, to]` windows when it was on screen, sampled at 60 fps.
- `customActors[]` contains `{id, scene, index, layer, anchorToPip, text, painter?, painterSource?, source,
  declaredAt}`.
  - `painter` names the top-level painter value when there is one (for example `GHOST`), and `painterSource` points
    at its declaration.
  - `source` is the first declaring line outside the generic `custom()` helper.
  - `declaredAt` is the full chain of declaring frames, innermost first, each with its source line.
  - The actor's position, scale, rotation, alpha and `progress` are animated by ordinary tracks. Many painters also
    animate internally from the show time they receive, so that motion has to be redrawn too.

## Accuracy

These numbers come from `verify.txt`.

- Engine Bezier eases, linear and steps are exact.
- The sine eases have no Bezier form. They use fitted stand-ins, within 0.05% (in-out) and 0.18% (in, out) of each
  segment's change. That is at most about 2.2 px on full-screen moves and 1.3° on the 600-900° spins.
- Baked segments use 120 Hz linear samples.

## What does not map one to one

- **Additive animations** (`active.additive`): nine of the ten life-layer loops and 199 scene animations add to the
  value beneath. Rive keyframes replace values, so rivc has to sum these, for example through a separate input or
  property.
- **Underlying fallbacks**: a stop that is left out reads the live value beneath it on every frame. The export
  fixes that value at each key's time and bakes the segments where it moves. A `fillAfter` hold of such a key stays
  at that fixed value.
- **Cascade**: the order is by begin time across the global and scene animations, with ties going to the global
  scope first. `scenes[].stack` is that order.
- **Renderer-side composition**: some behaviour is not keyed values. The life channels (`life*`) are added onto the
  scene's look, blink, sway and breath by `PipRenderer`, scaled by `life`. `hideBehind` masks Pip with the named
  icon's circle and label area (`StageInfo.icon()`). `anchorToPip` bubbles follow Pip's head.
- **Custom painters** are Kotlin drawing code and are listed rather than exported.
