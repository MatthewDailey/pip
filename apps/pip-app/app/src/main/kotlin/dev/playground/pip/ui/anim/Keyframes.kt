package dev.playground.pip.ui.anim

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * A small CSS-animations model: `@keyframes` (stops at percentages, per-stop timing functions) plus the
 * `animation` shorthand (duration, delay, timing function, iteration count, direction, fill mode, composite).
 *
 * Everything is evaluated as a pure function of time, so a show renders identically on every run and can start
 * from any moment.
 */
typealias Ease = (Float) -> Float

/**
 * What an [Ease] is, as data, so a show can be exported to other players. Every [Easing] member carries one; a plain
 * lambda passed as an [Ease] is [Custom] (an exporter has to sample it).
 */
sealed class EaseSpec {
  data object Linear : EaseSpec()
  data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : EaseSpec()
  /** CSS `steps(n, jump-end)`. */
  data class Steps(val n: Int) : EaseSpec()
  data object InSine : EaseSpec()
  data object OutSine : EaseSpec()
  data object InOutSine : EaseSpec()
  data object OutBounce : EaseSpec()
  data object OutElastic : EaseSpec()
  data object Custom : EaseSpec()
}

/** An [Ease] that knows its [spec]. Evaluates exactly like the bare function it wraps. */
class DescribedEase(val spec: EaseSpec, private val f: (Float) -> Float) : (Float) -> Float {
  override fun invoke(x: Float): Float = f(x)
  override fun toString() = spec.toString()
}

val Ease.spec: EaseSpec get() = (this as? DescribedEase)?.spec ?: EaseSpec.Custom

object Easing {
  val linear: Ease = DescribedEase(EaseSpec.Linear) { it }
  val easeIn: Ease = cubicBezier(0.42f, 0f, 1f, 1f)
  val easeOut: Ease = cubicBezier(0f, 0f, 0.58f, 1f)
  val easeInOut: Ease = cubicBezier(0.42f, 0f, 0.58f, 1f)
  /** CSS `ease`, the default timing function. */
  val ease: Ease = cubicBezier(0.25f, 0.1f, 0.25f, 1f)
  /** Gentle sine curves: the playbook's choice for joining the stops of a keyframed spring. */
  val easeInOutSine: Ease = DescribedEase(EaseSpec.InOutSine) { (-(kotlin.math.cos(Math.PI * it) - 1) / 2).toFloat() }
  val easeOutSine: Ease = DescribedEase(EaseSpec.OutSine) { kotlin.math.sin(it * Math.PI / 2).toFloat() }
  val easeInSine: Ease = DescribedEase(EaseSpec.InSine) { (1 - kotlin.math.cos(it * Math.PI / 2)).toFloat() }
  val outBack: Ease = cubicBezier(0.34f, 1.56f, 0.64f, 1f)
  val inBack: Ease = cubicBezier(0.36f, 0f, 0.66f, -0.56f)
  val inOutBack: Ease = cubicBezier(0.68f, -0.6f, 0.32f, 1.6f)
  val outBounce: Ease = DescribedEase(EaseSpec.OutBounce) { x ->
    val n = 7.5625f; val d = 2.75f
    when {
      x < 1 / d -> n * x * x
      x < 2 / d -> { val y = x - 1.5f / d; n * y * y + 0.75f }
      x < 2.5 / d -> { val y = x - 2.25f / d; n * y * y + 0.9375f }
      else -> { val y = x - 2.625f / d; n * y * y + 0.984375f }
    }
  }
  val outElastic: Ease = DescribedEase(EaseSpec.OutElastic) { x ->
    if (x <= 0f || x >= 1f) x else (2.0.pow(-10.0 * x) * kotlin.math.sin((x * 10 - 0.75) * (2 * Math.PI / 3)) + 1).toFloat()
  }

  /** CSS `steps(n, jump-end)`. */
  fun steps(n: Int): Ease = DescribedEase(EaseSpec.Steps(n)) { floor(it * n).coerceAtMost(n - 1f) / n }

  /** CSS `cubic-bezier(x1, y1, x2, y2)`. */
  fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): Ease = DescribedEase(EaseSpec.CubicBezier(x1, y1, x2, y2)) { x ->
    fun bx(t: Float) = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t
    fun by(t: Float) = 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t
    fun dx(t: Float) = 3 * (1 - t) * (1 - t) * x1 + 6 * (1 - t) * t * (x2 - x1) + 3 * t * t * (1 - x2)
    if (x <= 0f) 0f else if (x >= 1f) 1f else {
      var t = x
      repeat(8) { val d = dx(t); if (abs(d) > 1e-5f) t -= (bx(t) - x) / d }
      if (abs(bx(t) - x) > 1e-3f) { // Newton failed to settle; bisect.
        var lo = 0f; var hi = 1f
        repeat(30) { t = (lo + hi) / 2; if (bx(t) < x) lo = t else hi = t }
      }
      by(t)
    }
  }
}

class FloatProp<S>(val name: String, val get: (S) -> Float, val set: (S, Float) -> Unit)
class DiscreteProp<S>(val name: String, val set: (S, Any?) -> Unit)

class Stop<S>(val at: Float, val ease: Ease?) {
  val floats = LinkedHashMap<FloatProp<S>, Float>()
  val discretes = LinkedHashMap<DiscreteProp<S>, Any?>()
}

/** Values a stop sets. Typed setters for each target live as extension functions next to that target. */
class StopScope<S>(@PublishedApi internal val stop: Stop<S>) {
  fun set(p: FloatProp<S>, v: Number) { stop.floats[p] = v.toFloat() }
  fun set(p: DiscreteProp<S>, v: Any?) { stop.discretes[p] = v }
}

class Keyframes<S>(stops: List<Stop<S>>) {
  private val stops = stops.sortedBy { it.at }
  private val floatProps = this.stops.flatMap { it.floats.keys }.distinct()
  private val discreteProps = this.stops.flatMap { it.discretes.keys }.distinct()

  /** Read-only views for exporters: stops in evaluation order (by percentage, ties in declaration order). */
  val stopList: List<Stop<S>> get() = stops
  val floatProperties: List<FloatProp<S>> get() = floatProps
  val discreteProperties: List<DiscreteProp<S>> get() = discreteProps

  /**
   * Like CSS, each property interpolates only between the stops that name it; a missing 0% or 100% uses the
   * underlying value (0 when composing additively). A stop's timing function governs the segment that starts there.
   */
  fun apply(target: S, p: Float, ease: Ease, additive: Boolean) {
    for (prop in floatProps) {
      val base = prop.get(target)
      val under = if (additive) 0f else base
      var aAt = 0f; var a = under; var aEase: Ease? = null
      var bAt = 1f; var b = under
      var foundB = false
      for (s in stops) {
        val v = s.floats[prop] ?: continue
        if (s.at <= p) { aAt = s.at; a = v; aEase = s.ease } else { bAt = s.at; b = v; foundB = true; break }
      }
      if (!foundB && aAt >= 1f) b = a
      val span = bAt - aAt
      val local = if (span <= 0f) 1f else ((p - aAt) / span).coerceIn(0f, 1f)
      val v = a + (b - a) * (aEase ?: ease)(local)
      prop.set(target, if (additive) base + v else v)
    }
    for (prop in discreteProps) {
      var value: Any? = null; var any = false
      for (s in stops) if (s.at <= p && s.discretes.containsKey(prop)) { value = s.discretes[prop]; any = true }
      if (any) prop.set(target, value)
    }
  }
}

class KeyframesBuilder<S> {
  private val stops = mutableListOf<Stop<S>>()

  /** A stop at [percent] (0..100). [ease] is the timing function from this stop to the next. */
  fun at(percent: Number, ease: Ease? = null, block: StopScope<S>.() -> Unit) {
    val s = Stop<S>(percent.toFloat() / 100f, ease); StopScope(s).block(); stops += s
  }
  fun from(ease: Ease? = null, block: StopScope<S>.() -> Unit) = at(0, ease, block)
  fun to(block: StopScope<S>.() -> Unit) = at(100, null, block)
  fun build() = Keyframes(stops.toList())
}

fun <S> keyframes(block: KeyframesBuilder<S>.() -> Unit): Keyframes<S> = KeyframesBuilder<S>().apply(block).build()

enum class Direction { NORMAL, REVERSE, ALTERNATE, ALTERNATE_REVERSE }
enum class Fill { NONE, FORWARDS, BACKWARDS, BOTH }
enum class Composite { REPLACE, ADD }

const val INFINITE = Float.POSITIVE_INFINITY

/** The CSS `animation` shorthand. Times are in seconds. */
data class Timing(
  val duration: Float,
  val delay: Float = 0f,
  val ease: Ease = Easing.ease,
  val iterations: Float = 1f,
  val direction: Direction = Direction.NORMAL,
  val fill: Fill = Fill.FORWARDS,
  val composite: Composite = Composite.REPLACE,
)

class Animation<S>(val target: S, val keyframes: Keyframes<S>, val start: Float, val timing: Timing, val order: Int) {
  val begins get() = start + timing.delay

  /** Applies this animation at time [t] (same clock as [start]); false when it has no effect at [t]. */
  fun applyAt(t: Float): Boolean {
    val p = progressAt(t)
    if (p.isNaN()) return false
    keyframes.apply(target, p, timing.ease, timing.composite == Composite.ADD)
    return true
  }

  /**
   * The keyframe progress (0..1, direction applied) this animation evaluates at time [t], or NaN when it has no
   * effect then (outside its active interval and not filling).
   */
  fun progressAt(t: Float): Float {
    val d = timing.duration.coerceAtLeast(1e-4f)
    val local = t - begins
    val total = d * timing.iterations
    val fill = timing.fill
    val p: Float
    val iteration: Int
    when {
      local < 0f -> {
        if (fill != Fill.BACKWARDS && fill != Fill.BOTH) return Float.NaN
        p = 0f; iteration = 0
      }
      local >= total -> {
        if (fill != Fill.FORWARDS && fill != Fill.BOTH) return Float.NaN
        val it = timing.iterations
        iteration = (kotlin.math.ceil(it) - 1).toInt().coerceAtLeast(0)
        p = if (it % 1f == 0f) 1f else it % 1f
      }
      else -> { iteration = floor(local / d).toInt(); p = (local - iteration * d) / d }
    }
    val forward = when (timing.direction) {
      Direction.NORMAL -> true
      Direction.REVERSE -> false
      Direction.ALTERNATE -> iteration % 2 == 0
      Direction.ALTERNATE_REVERSE -> iteration % 2 == 1
    }
    return if (forward) p else 1f - p
  }
}
