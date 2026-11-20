package dev.playground.pip.ui.show

import android.graphics.RectF
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Animation
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Ease
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.Keyframes
import dev.playground.pip.ui.anim.KeyframesBuilder
import dev.playground.pip.ui.anim.Timing

/** What a scene can know about the screen: its size in pixels and where the home-screen icons sit. */
class StageInfo(val width: Float, val height: Float, val icons: Map<String, RectF>) {
  /** The round icon inside a launcher cell: centre x, centre y, radius. Falls back to a default spot. */
  fun icon(name: String): IconSpot {
    val r = icons[name] ?: RectF(815f, 1581f, 1030f, 1834f)
    return IconSpot(r.centerX(), r.top + r.height() * 0.375f, r.width() * 0.366f, r)
  }
}

class IconSpot(val cx: Float, val cy: Float, val radius: Float, val cell: RectF) {
  val top get() = cy - radius
}

/**
 * A show is a list of scenes played back to back. Each scene declares base styles and animations against its own
 * clock, like a stylesheet: nothing is imperative, and the frame at any time is computed from scratch.
 */
class Show(val scenes: List<Scene>, private val global: SceneScope.() -> Unit = {}) {
  val duration = scenes.sumOf { it.duration.toDouble() }.toFloat()
  fun startOf(name: String): Float {
    var t = 0f
    for (s in scenes) { if (s.name == name) return t; t += s.duration }
    return 0f
  }

  /** The prepared timeline; exporters (see ShowExport.kt) read it. */
  internal var built: Built? = null
    private set
  internal class Built(val stage: StageInfo, val pip: PipState, val scopes: List<Pair<Float, SceneScope>>, val globalScope: SceneScope)

  /** When set before [prepare], each scene records the stack where each actor was declared (for exporters only). */
  internal var recordActorSites = false

  /** Lays every scene out on one timeline. Call once the stage size is known. */
  fun prepare(stage: StageInfo): PipState {
    val pip = PipState()
    var t = 0f
    val scopes = scenes.map { s ->
      val scope = SceneScope(stage, pip, s.name, t, s.duration, recordActorSites); s.block(scope); (t to scope).also { t += s.duration }
    }
    val g = SceneScope(stage, pip, "global", 0f, duration, recordActorSites).apply(global)
    built = Built(stage, pip, scopes, g)
    return pip
  }

  /** Recomputes Pip and the visible actors at global time [t]. */
  fun frame(t: Float, out: MutableList<ActorState>) {
    val b = built ?: return
    b.pip.reset()
    out.clear()
    val scope = b.scopes.lastOrNull { (start, sc) -> t >= start && t < start + sc.duration }?.second ?: b.scopes.last().second
    b.globalScope.applyBase()
    scope.applyBase()
    val anims = (b.globalScope.anims + scope.anims).sortedWith(compareBy({ it.begins }, { it.order }))
    for (a in anims) a.applyAt(t)
    for (actor in scope.actors) if (actor.visible && actor.alpha > 0f) out += actor
  }

  fun cuesBetween(from: Float, to: Float): List<Pair<Float, String>> =
    (built?.scopes?.flatMap { it.second.cues } ?: emptyList()).filter { it.first > from && it.first <= to }
}

class Scene(val name: String, val duration: Float, val block: SceneScope.() -> Unit)

fun scene(name: String, duration: Number, block: SceneScope.() -> Unit) = Scene(name, duration.toFloat(), block)

/**
 * Declarations for one scene. Times passed here are seconds from the start of the scene.
 *
 * Cascade: base styles apply first, then animations in order of when they begin (later wins), so a later move
 * takes over a property from an earlier one that is holding its end value.
 */
class SceneScope(
  val stage: StageInfo, val pip: PipState, val name: String, val start: Float, val duration: Float,
  private val recordActorSites: Boolean = false,
) {
  internal val anims = mutableListOf<Animation<*>>()
  internal val actors = mutableListOf<ActorState>()
  internal val cues = mutableListOf<Pair<Float, String>>()
  /** Where each actor was declared, filled only when the show records sites for an exporter. */
  internal val actorSites = HashMap<ActorState, Array<StackTraceElement>>()
  private val bases = mutableListOf<() -> Unit>()
  private val actorBases = mutableListOf<Triple<ActorState, String, ActorState.() -> Unit>>()
  private var order = 0

  val W get() = stage.width
  val H get() = stage.height

  /** Base style for Pip during this scene (like a rule without animation). */
  fun pip(style: PipState.() -> Unit) { bases += { pip.style() } }

  /** Animates Pip: `animate(at = 1.2, duration = 0.6, ease = Easing.outBack) { from { y(900) }; to { y(700) } }`. */
  fun animate(
    at: Number, duration: Number, ease: Ease = Easing.ease, iterations: Float = 1f, direction: Direction = Direction.NORMAL,
    fill: Fill = Fill.FORWARDS, composite: Composite = Composite.REPLACE, delay: Number = 0,
    frames: KeyframesBuilder<PipState>.() -> Unit,
  ) = animate(at, Timing(duration.toFloat(), delay.toFloat(), ease, iterations, direction, fill, composite), KeyframesBuilder<PipState>().apply(frames).build())

  fun animate(at: Number, timing: Timing, frames: Keyframes<PipState>) {
    anims += Animation(pip, frames, start + at.toFloat(), timing, order++)
  }

  /** Adds an actor for this scene; style its base values in [style] and animate it with [Actor.animate]. */
  fun actor(kind: ActorKind, text: String = "", anchorToPip: Boolean = kind == ActorKind.BUBBLE, layer: Int = 1, style: ActorState.() -> Unit = {}): Actor {
    val a = ActorState(kind, text, anchorToPip, layer)
    actors += a
    if (recordActorSites) actorSites[a] = Throwable().stackTrace
    actorBases += Triple(a, text, style)
    return Actor(a)
  }

  /** Shorthand for a speech bubble shown from [at] for [duration] seconds with a pop-in and fade-out. */
  fun say(text: String, at: Number, duration: Number) {
    val t0 = at.toFloat(); val d = duration.toFloat()
    actor(ActorKind.BUBBLE, text) { alpha = 0f }.animate(t0, d, ease = Easing.linear, fill = Fill.NONE) {
      at(0, Easing.outBack) { scale(0.4); alpha(1) }
      at(100f * 0.25f / d) { scale(1) }
      at(100f * (d - 0.25f) / d) { alpha(1) }
      to { alpha(0); scale(0.9) }
    }
  }

  /** Marks a moment for the soundtrack (logged as `PIPCUE <name> <seconds>` while the show plays). */
  fun cue(at: Number, name: String) { cues += (start + at.toFloat()) to name }

  inner class Actor(val state: ActorState) {
    fun animate(
      at: Number, duration: Number, ease: Ease = Easing.ease, iterations: Float = 1f, direction: Direction = Direction.NORMAL,
      fill: Fill = Fill.FORWARDS, composite: Composite = Composite.REPLACE, delay: Number = 0,
      frames: KeyframesBuilder<ActorState>.() -> Unit,
    ): Actor {
      anims += Animation(state, KeyframesBuilder<ActorState>().apply(frames).build(), start + at.toFloat(),
        Timing(duration.toFloat(), delay.toFloat(), ease, iterations, direction, fill, composite), order++)
      return this
    }
  }

  internal fun applyBase() {
    for (b in bases) b()
    for ((a, text0, style) in actorBases) {
      a.text = text0
      a.x = 0f; a.y = 0f; a.scale = 1f; a.rotation = 0f; a.alpha = 1f; a.progress = 0f; a.visible = true
      a.style()
    }
  }
}
