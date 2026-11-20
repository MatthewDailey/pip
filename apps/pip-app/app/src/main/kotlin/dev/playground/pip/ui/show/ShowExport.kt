package dev.playground.pip.ui.show

import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Animation
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.DiscreteProp
import dev.playground.pip.ui.anim.Ease
import dev.playground.pip.ui.anim.EaseSpec
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.FloatProp
import dev.playground.pip.ui.anim.spec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Exports a prepared [Show] as plain data (see EXPORT.md next to this file) so another player, the rivc compiler,
 * can rebuild it without running Kotlin. Nothing is resampled that can be described exactly: keyframe stops, cubic
 * Bezier eases, iterations, directions and fills come straight from the declarations. Only eases that are not
 * Beziers (bounce, elastic, custom lambdas), segments cut by a scene edge, and stops that fall back to a moving
 * underlying value are baked into extra keys.
 */

const val SHOWREEL_FORMAT = "pip.showreel/1"

@Serializable
data class ShowreelExport(
  val format: String,
  val duration: Double,
  val stage: StageExport,
  val scenes: List<SceneExport>,
  val actors: List<ActorExport>,
  val animations: List<AnimationExport>,
  val cues: List<CueExport>,
  val texts: List<TextExport>,
  val customActors: List<CustomActorExport>,
  val notes: List<String>,
)

@Serializable
data class StageExport(val width: Double, val height: Double, val icons: Map<String, List<Double>>)

@Serializable
data class SceneExport(
  val index: Int,
  val name: String,
  val start: Double,
  val duration: Double,
  val end: Double,
  /** Pip's values before any animation: his defaults, then the global and this scene's base styles. */
  val pipBase: Map<String, JsonElement>,
  val actors: List<String>,
  /** Animation ids applied in this scene, in cascade order (later wins, or adds). */
  val stack: List<String>,
)

@Serializable
data class ActorExport(
  val id: String,
  val scene: String,
  val index: Int,
  val kind: String,
  val text: String,
  val layer: Int,
  val anchorToPip: Boolean,
  val color: String,
  val base: Map<String, JsonElement>,
)

@Serializable
data class AnimationExport(
  val id: String,
  val scope: String,
  val target: String,
  val begins: Double,
  val order: Int,
  val window: List<Double>,
  val timing: TimingExport,
  val active: ActiveExport,
  val tracks: List<TrackExport>,
)

@Serializable
data class TimingExport(
  val start: Double,
  val delay: Double,
  val duration: Double,
  val iterations: JsonElement,
  val direction: String,
  val fill: String,
  val composite: String,
  val ease: JsonElement,
)

@Serializable
data class ActiveExport(val start: Double, val end: Double? = null, val fillBefore: Boolean, val fillAfter: Boolean, val additive: Boolean)

@Serializable
data class TrackExport(val property: String, val type: String, val keys: List<KeyExport>)

@Serializable
data class KeyExport(val t: Double, val v: JsonElement, val ease: JsonElement? = null, val src: String? = null)

@Serializable
data class CueExport(val t: Double, val name: String, val scene: String)

@Serializable
data class TextExport(val actor: String, val scene: String, val kind: String, val text: String, val texts: List<String>, val shown: List<List<Double>>)

@Serializable
data class SourceRef(val file: String, val line: Int, val function: String? = null, val text: String? = null)

@Serializable
data class CustomActorExport(
  val id: String,
  val scene: String,
  val index: Int,
  val layer: Int,
  val anchorToPip: Boolean,
  val text: String,
  val painter: String? = null,
  val painterSource: SourceRef? = null,
  val source: SourceRef? = null,
  val declaredAt: List<SourceRef>,
)

val showreelJson = Json { prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = false }

fun ShowreelExport.toJson(): String = showreelJson.encodeToString(ShowreelExport.serializer(), this)

/**
 * Cubic-Bezier stand-ins for the sine eases, fitted to the exact curves (max error 0.05% for in-out, 0.18% for
 * in and out). Out is the time-reverse of in, so a reversed animation stays consistent.
 */
internal val SINE_IN_OUT = floatArrayOf(0.364f, 0f, 0.636f, 1f)
internal val SINE_IN = floatArrayOf(0.2542f, 0.014f, 0.4764f, 0.157f)
internal val SINE_OUT = floatArrayOf(1 - 0.4764f, 1 - 0.157f, 1 - 0.2542f, 1 - 0.014f)

/** Walks a prepared show; see [ShowExporter.export]. */
class ShowExporter private constructor(private val show: Show, private val sourceRoot: File?) {
  private val b = show.built ?: error("prepare the show first")
  private val pip = b.pip
  private val scopes = b.scopes.map { it.second }
  private val global = b.globalScope
  private val notes = mutableListOf<String>()

  private val stacks = scopes.associateWith { sc -> (global.anims + sc.anims).sortedWith(compareBy({ it.begins }, { it.order })) }
  private val animIds = HashMap<Animation<*>, String>().apply {
    global.anims.forEachIndexed { i, a -> put(a, "global-anim-$i") }
    for (sc in scopes) sc.anims.forEachIndexed { i, a -> put(a, "${sc.name}-anim-$i") }
  }
  private val actorIds = HashMap<ActorState, String>().apply {
    for (sc in scopes) sc.actors.forEachIndexed { i, a -> put(a, "${sc.name}-actor-$i") }
    global.actors.forEachIndexed { i, a -> put(a, "global-actor-$i") }
  }

  companion object {
    /**
     * Prepares [show] on [stage], recording where actors are declared, and exports it. [sourceRoot] (the `kotlin`
     * source directory) lets custom actors point at their source lines. Don't call it while the show is playing:
     * it drives the show's own state.
     */
    fun export(show: Show, stage: StageInfo, sourceRoot: File? = null): ShowreelExport {
      show.recordActorSites = true
      try { show.prepare(stage) } finally { show.recordActorSites = false }
      return ShowExporter(show, sourceRoot).run()
    }

    /** Pip's float properties, by name, as animations and the renderer see them. */
    internal val pipFloatProps: List<FloatProp<PipState>> by lazy { floatPropsOf<PipState>(PipProps) + floatPropsOf<PipState>(LifeProps) }
    internal val actorFloatProps: List<FloatProp<ActorState>> by lazy { floatPropsOf<ActorState>(ActorProps) }

    internal val pipDiscreteGetters: Map<String, (PipState) -> Any?> = linkedMapOf(
      "brows" to { it.brows }, "mood" to { it.mood }, "mouth" to { it.mouth }, "headwear" to { it.headwear },
      "leftHand" to { it.left.kind }, "rightHand" to { it.right.kind }, "visible" to { it.visible },
      "hideBehind" to { it.hideBehind },
    )
    internal val actorDiscreteGetters: Map<String, (ActorState) -> Any?> = linkedMapOf("text" to { it.text }, "visible" to { it.visible })

    @Suppress("UNCHECKED_CAST")
    private fun <S> floatPropsOf(holder: Any): List<FloatProp<S>> = holder.javaClass.declaredFields
      .filter { FloatProp::class.java.isAssignableFrom(it.type) }
      .map { it.isAccessible = true; it.get(holder) as FloatProp<S> }

    fun round(v: Double): Double = if (v.isNaN() || v.isInfinite()) v else (v * 1e6).roundToLong() / 1e6
    fun num(v: Float): JsonElement = if (v.isNaN()) JsonNull else JsonPrimitive(round(v.toString().toDouble()))
    fun num(v: Double): JsonElement = if (v.isNaN()) JsonNull else JsonPrimitive(round(v))
    fun value(v: Any?): JsonElement = when (v) {
      null -> JsonNull
      is Float -> num(v)
      is Boolean -> JsonPrimitive(v)
      is Number -> JsonPrimitive(v)
      is Enum<*> -> JsonPrimitive(v.name)
      else -> JsonPrimitive(v.toString())
    }
    fun bezier(p: FloatArray): JsonElement = JsonArray(p.map { num(it) })
    val LINEAR: JsonElement = bezier(floatArrayOf(0f, 0f, 1f, 1f))
    val STEP: JsonElement = JsonPrimitive("step")
  }

  private fun run(): ShowreelExport {
    val sceneExports = mutableListOf<SceneExport>()
    val actorExports = mutableListOf<ActorExport>()
    for ((i, sc) in scopes.withIndex()) {
      pip.reset(); global.applyBase(); sc.applyBase()
      val base = LinkedHashMap<String, JsonElement>()
      for (p in pipFloatProps) base[p.name] = num(p.get(pip))
      for ((k, g) in pipDiscreteGetters) base[k] = value(g(pip))
      for (a in sc.actors) actorExports += actorExport(sc, a)
      sceneExports += SceneExport(
        i, sc.name, round(sc.start.toDouble()), round(sc.duration.toDouble()), round((sc.start + sc.duration).toDouble()),
        base, sc.actors.map { actorIds.getValue(it) }, stacks.getValue(sc).map { animIds.getValue(it) },
      )
    }
    if (global.actors.isNotEmpty()) notes += "global scope declares actors; they are ignored by the engine and not exported"

    val anims = mutableListOf<AnimationExport>()
    for (a in global.anims) anims += animation(a, null)
    for (sc in scopes) for (a in sc.anims) anims += animation(a, sc)

    val cues = scopes.flatMap { sc -> sc.cues.map { (t, n) -> CueExport(round(t.toDouble()), n, sc.name) } }.sortedBy { it.t }
    val texts = textExports()
    val customs = scopes.flatMap { sc -> sc.actors.withIndex().filter { it.value.kind == ActorKind.CUSTOM }.map { (i, a) -> customExport(sc, i, a) } }
    return ShowreelExport(
      SHOWREEL_FORMAT, round(show.duration.toDouble()),
      StageExport(b.stage.width.toDouble(), b.stage.height.toDouble(),
        b.stage.icons.mapValues { (_, r) -> listOf(r.left, r.top, r.right, r.bottom).map { it.toDouble() } }),
      sceneExports, actorExports, anims, cues, texts, customs, notes.distinct(),
    )
  }

  private fun actorExport(sc: SceneScope, a: ActorState): ActorExport {
    val base = LinkedHashMap<String, JsonElement>()
    for (p in actorFloatProps) base[p.name] = num(p.get(a))
    for ((k, g) in actorDiscreteGetters) base[k] = value(g(a))
    return ActorExport(
      actorIds.getValue(a), sc.name, sc.actors.indexOf(a), a.kind.name, a.text, a.layer, a.anchorToPip,
      "#%08X".format(a.color), base,
    )
  }

  // ------------------------------------------------------------------------------------------------ animations

  private fun targetId(a: Animation<*>): String = when (val t = a.target) {
    pip -> "pip"
    is ActorState -> actorIds[t] ?: "unknown-actor"
    else -> "unknown"
  }

  private fun animation(a: Animation<*>, scope: SceneScope?): AnimationExport {
    val tm = a.timing
    val w0 = (scope?.start ?: 0f).toDouble()
    val w1 = scope?.let { (it.start + it.duration).toDouble() } ?: show.duration.toDouble()
    val d = max(tm.duration, 1e-4f).toDouble()
    val begins = a.begins.toDouble()
    val infinite = tm.iterations.isInfinite()
    val activeEnd = if (infinite) Double.POSITIVE_INFINITY else begins + d * tm.iterations
    val lo = max(begins, w0)
    val hi = min(activeEnd, w1)
    val tracks = mutableListOf<TrackExport>()
    if (hi <= lo) {
      notes += "${animIds[a]} has no active time inside its window; exported without keys"
    } else {
      val iterations = if (infinite) max(1, ceil((w1 - begins) / d).toInt()) else ceil(tm.iterations.toDouble()).toInt().coerceAtLeast(1)
      for (p in a.keyframes.floatProperties) tracks += floatTrack(a, scope, p, d, iterations, lo, hi)
      for (p in a.keyframes.discreteProperties) tracks += discreteTrack(a, p, d, iterations, lo, hi)
    }
    return AnimationExport(
      animIds.getValue(a), scope?.name ?: "global", targetId(a), round(begins), a.order,
      listOf(round(w0), round(w1)),
      TimingExport(
        round(a.start.toDouble()), round(tm.delay.toDouble()), round(tm.duration.toDouble()),
        if (infinite) JsonPrimitive("infinite") else num(tm.iterations),
        tm.direction.name.lowercase(), tm.fill.name.lowercase(), tm.composite.name.lowercase(), easeName(tm.ease),
      ),
      ActiveExport(round(begins), if (infinite) null else round(activeEnd),
        tm.fill == Fill.BACKWARDS || tm.fill == Fill.BOTH, tm.fill == Fill.FORWARDS || tm.fill == Fill.BOTH,
        tm.composite == Composite.ADD),
      tracks,
    )
  }

  private fun easeName(e: Ease): JsonElement = when (val s = e.spec) {
    EaseSpec.Linear -> JsonPrimitive("linear")
    is EaseSpec.CubicBezier -> bezier(floatArrayOf(s.x1, s.y1, s.x2, s.y2))
    is EaseSpec.Steps -> JsonPrimitive("steps(${s.n})")
    EaseSpec.InSine -> JsonPrimitive("inSine")
    EaseSpec.OutSine -> JsonPrimitive("outSine")
    EaseSpec.InOutSine -> JsonPrimitive("inOutSine")
    EaseSpec.OutBounce -> JsonPrimitive("outBounce")
    EaseSpec.OutElastic -> JsonPrimitive("outElastic")
    EaseSpec.Custom -> JsonPrimitive("custom")
  }

  private fun forward(dir: Direction, iteration: Int) = when (dir) {
    Direction.NORMAL -> true
    Direction.REVERSE -> false
    Direction.ALTERNATE -> iteration % 2 == 0
    Direction.ALTERNATE_REVERSE -> iteration % 2 == 1
  }

  /** Directed keyframe progress at [t], as the engine computes it while active; at or past the end, its end value. */
  private fun progress(a: Animation<*>, t: Double): Double {
    val tm = a.timing
    val d = max(tm.duration, 1e-4f).toDouble()
    val local = t - a.begins
    val it = tm.iterations.toDouble()
    val iteration: Int
    val p: Double
    if (local < 0) { iteration = 0; p = 0.0 } else if (local >= d * it) {
      iteration = (ceil(it) - 1).toInt().coerceAtLeast(0); p = if (it % 1.0 == 0.0) 1.0 else it % 1.0
    } else { iteration = floor(local / d).toInt(); p = local / d - iteration }
    return if (forward(tm.direction, iteration)) p else 1 - p
  }

  private fun scopeAt(t: Double): SceneScope =
    b.scopes.lastOrNull { (start, sc) -> t >= start && t < start + sc.duration }?.second ?: scopes.last()

  /** The value [prop] has just before [a] applies at [t]: base styles plus every earlier animation in the cascade. */
  @Suppress("UNCHECKED_CAST")
  private fun underlying(a: Animation<*>, scope: SceneScope?, prop: FloatProp<*>, t: Double): Float {
    val sc = scope ?: scopeAt(t)
    pip.reset(); global.applyBase(); sc.applyBase()
    for (x in stacks.getValue(sc)) { if (x === a) break; x.applyAt(t.toFloat()) }
    return (prop as FloatProp<Any>).get(a.target as Any)
  }

  private fun scratchFor(target: Any): Any = when (target) {
    is PipState -> PipState()
    is ActorState -> ActorState(target.kind, target.text, target.anchorToPip, target.layer)
    else -> error("unknown target $target")
  }

  /** The exact value [a] gives [prop] at [t] (the left limit at its end), computed by the engine's own keyframes. */
  @Suppress("UNCHECKED_CAST")
  private fun exact(a: Animation<*>, scope: SceneScope?, prop: FloatProp<*>, t: Double): Float {
    val additive = a.timing.composite == Composite.ADD
    val s = scratchFor(a.target as Any)
    val fp = prop as FloatProp<Any>
    fp.set(s, if (additive) 0f else underlying(a, scope, prop, t))
    (a as Animation<Any>).keyframes.apply(s, progress(a, t).toFloat(), a.timing.ease, additive)
    return fp.get(s)
  }

  /** A key before resolution: [value] null means "the underlying value at [t]"; [out] eases the segment to the next key. */
  private class Raw(val t: Double, val value: Float?, val out: Ease?, val reversed: Boolean)

  private fun floatTrack(a: Animation<*>, scope: SceneScope?, prop: FloatProp<*>, d: Double, iterations: Int, lo: Double, hi: Double): TrackExport {
    val tm = a.timing
    val additive = tm.composite == Composite.ADD
    // Keyframe space, as Keyframes.apply reads it: a missing 0% or 100% stop falls back to the underlying value.
    data class K(val at: Float, val value: Float?, val ease: Ease?)
    val ks = mutableListOf<K>()
    val stops = a.keyframes.stopList.filter { it.floats.containsKey(prop) }
    if (stops.first().at > 0f) ks += K(0f, if (additive) 0f else null, tm.ease)
    for (s in stops) ks += K(s.at, s.floats[prop], s.ease ?: tm.ease)
    if (ks.last().at < 1f) ks += K(1f, if (additive) 0f else null, null)

    val raws = mutableListOf<Raw>()
    for (i in 0 until iterations) {
      val t0 = a.begins + i * d
      val fwd = forward(tm.direction, i)
      val iter = if (fwd) ks.map { Raw(t0 + it.at * d, it.value, it.ease, false) }
      else ks.indices.reversed().map { j -> Raw(t0 + (1 - ks[j].at) * d, ks[j].value, if (j > 0) ks[j - 1].ease else null, true) }
      // Iterations meet at one instant: drop the previous end when the next start carries the same value.
      if (raws.isNotEmpty() && abs(raws.last().t - iter.first().t) < 1e-9 && raws.last().value == iter.first().value && iter.first().value != null) raws.removeAt(raws.lastIndex)
      raws += iter
    }

    // Emit the part of each segment inside [lo, hi], the active span within the scene. A segment cut by an edge
    // keeps its exact curve (the matching piece of the Bezier); only non-Bezier eases and moving underlying values
    // are baked into linear keys.
    val keys = mutableListOf<KeyExport>()
    fun resolved(r: Raw) = r.value ?: underlying(a, scope, prop, r.t)
    fun src(r: Raw) = if (r.value == null) "underlying" else null
    var closed = false
    for ((n, k) in raws.withIndex()) {
      val next = raws.getOrNull(n + 1)
      if (next == null || k.t >= hi - 1e-9) {
        if (k.t >= lo - 1e-9 && k.t <= hi + 1e-9) { keys += KeyExport(round(k.t), num(resolved(k)), null, src(k)); closed = true }
        break
      }
      if (next.t <= lo + 1e-9 && next.t - k.t > 1e-9) continue
      val span = next.t - k.t
      if (span <= 1e-9) { if (k.t >= lo - 1e-9) keys += KeyExport(round(k.t), num(resolved(k)), STEP, src(k)); continue }
      val t0 = max(k.t, lo); val t1 = min(next.t, hi)
      val s0 = (t0 - k.t) / span; val s1 = (t1 - k.t) / span
      val cutStart = s0 > 1e-9; val cutEnd = s1 < 1 - 1e-9
      val spec = k.out?.spec
      val movingUnder = !additive && (k.value == null || next.value == null) && underlyingMoves(a, scope, prop, k.t, next.t)
      val curve = k.out?.let { if (spec is EaseSpec.Steps) null else segmentCurve(spec!!, k.reversed) }
      val sub = curve?.let { subCurve(it, s0, s1) }
      if (spec is EaseSpec.Steps && !movingUnder && !cutStart && !cutEnd) {
        // steps(n, jump-end): n flat treads; the riser at the segment end is the next key.
        val v = resolved(k); val nv = resolved(next)
        for (i in 0 until spec.n) {
          val f = i.toDouble() / spec.n
          // Played backwards the treads are the mirror image; read each just after its key (keys hold forward).
          val stepped = if (k.reversed) 1 - ceilFrac(1 - f - 1e-9, spec.n) else f
          keys += KeyExport(round(k.t + span * f), num(v + (nv - v) * stepped), STEP, if (i == 0) src(k) else "baked:steps")
        }
      } else if (sub == null || movingUnder) {
        val why = if (movingUnder) "underlying" else "ease"
        val count = max(4, ceil((t1 - t0) * 120).toInt())
        for (i in 0 until count) {
          val t = t0 + (t1 - t0) * i / count
          keys += KeyExport(round(t), num(exact(a, scope, prop, t)), LINEAR, "baked:$why")
        }
      } else {
        val v = resolved(k); val nv = resolved(next)
        val (y0, ease) = sub
        keys += KeyExport(round(t0), num(v + (nv - v) * y0), bezier(ease), if (cutStart) "cut" else src(k))
      }
      if (cutEnd) {
        // The scene (or a fractional last iteration) ends inside this segment: close the track there.
        val endValue: Double = if (sub != null && !movingUnder) resolved(k) + (resolved(next) - resolved(k)) * sub.third else exact(a, scope, prop, t1).toDouble()
        keys += KeyExport(round(t1), num(endValue), null, "cut")
        closed = true
        break
      }
    }
    if (!closed) notes += "${animIds[a]}.${prop.name}: track did not close at its active end"
    return TrackExport(prop.name, "float", dedupe(keys))
  }

  /** For a reversed steps segment: the stepped progress of keyframe position [u] (jump-end), as the engine sees it. */
  private fun ceilFrac(u: Double, n: Int): Double = floor(u * n).coerceAtMost(n - 1.0) / n

  /** The Bezier for a segment's ease, time-reversed when the iteration plays backwards; null when not a Bezier. */
  private fun segmentCurve(spec: EaseSpec, reversed: Boolean): DoubleArray? {
    val p = when (spec) {
      EaseSpec.Linear -> floatArrayOf(0f, 0f, 1f, 1f)
      is EaseSpec.CubicBezier -> floatArrayOf(spec.x1, spec.y1, spec.x2, spec.y2)
      EaseSpec.InSine -> SINE_IN
      EaseSpec.OutSine -> SINE_OUT
      EaseSpec.InOutSine -> SINE_IN_OUT
      else -> return null
    }
    // Played backwards, value(s) = 1 - E(1 - s): the same curve rotated half a turn about (0.5, 0.5).
    val q = if (reversed) floatArrayOf(1 - p[2], 1 - p[3], 1 - p[0], 1 - p[1]) else p
    return DoubleArray(4) { q[it].toDouble() }
  }

  /**
   * The piece of ease curve [c] between times [s0] and [s1] (fractions of its segment), renormalised to a unit
   * Bezier. Returns (eased progress at s0, the piece's control points, eased progress at s1), or null when the
   * piece is flat at its ends but not inside, which no unit Bezier can describe.
   */
  private fun subCurve(c: DoubleArray, s0: Double, s1: Double): Triple<Double, FloatArray, Double>? {
    if (s0 <= 1e-9 && s1 >= 1 - 1e-9) return Triple(0.0, FloatArray(4) { c[it].toFloat() }, 1.0)
    var pts = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(c[0], c[1]), doubleArrayOf(c[2], c[3]), doubleArrayOf(1.0, 1.0))
    val tau1 = solveX(c, s1)
    val tau0 = solveX(c, s0)
    if (s1 < 1 - 1e-9) pts = split(pts, tau1).first
    if (s0 > 1e-9) pts = split(pts, if (s1 < 1 - 1e-9) tau0 / tau1 else tau0).second
    val (x0, y0) = pts[0].let { it[0] to it[1] }
    val (x3, y3) = pts[3].let { it[0] to it[1] }
    val dy = y3 - y0
    if (abs(dy) < 1e-6) return null
    val nx = { x: Double -> ((x - x0) / (x3 - x0)).toFloat() }
    val ny = { y: Double -> ((y - y0) / dy).toFloat() }
    return Triple(y0, floatArrayOf(nx(pts[1][0]), ny(pts[1][1]), nx(pts[2][0]), ny(pts[2][1])), y3)
  }

  private fun solveX(c: DoubleArray, x: Double): Double {
    var lo = 0.0; var hi = 1.0
    repeat(60) {
      val t = (lo + hi) / 2
      val bx = 3 * (1 - t) * (1 - t) * t * c[0] + 3 * (1 - t) * t * t * c[2] + t * t * t
      if (bx < x) lo = t else hi = t
    }
    return (lo + hi) / 2
  }

  /** de Casteljau: splits a cubic at parameter [t] into its two halves. */
  private fun split(p: Array<DoubleArray>, t: Double): Pair<Array<DoubleArray>, Array<DoubleArray>> {
    fun lerp(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    val p01 = lerp(p[0], p[1]); val p12 = lerp(p[1], p[2]); val p23 = lerp(p[2], p[3])
    val p012 = lerp(p01, p12); val p123 = lerp(p12, p23)
    val m = lerp(p012, p123)
    return arrayOf(p[0], p01, p012, m) to arrayOf(m, p123, p23, p[3])
  }

  private fun underlyingMoves(a: Animation<*>, scope: SceneScope?, prop: FloatProp<*>, t0: Double, t1: Double): Boolean {
    val vs = (0..8).map { underlying(a, scope, prop, t0 + (t1 - t0) * it / 8.0) }
    if (vs.any { it.isNaN() }) return vs.any { !it.isNaN() }
    val lo = vs.min(); val hi = vs.max()
    return hi - lo > 1e-4f * max(1f, max(abs(lo), abs(hi)))
  }

  /** Keys at one instant with the same value say nothing; keep the later one, whose ease leads on. */
  private fun dedupe(keys: List<KeyExport>): List<KeyExport> {
    val out = mutableListOf<KeyExport>()
    for (k in keys) {
      val prev = out.lastOrNull()
      if (prev != null && prev.t == k.t && prev.v == k.v) out[out.lastIndex] = k else out += k
    }
    return out
  }

  private fun discreteTrack(a: Animation<*>, prop: DiscreteProp<*>, d: Double, iterations: Int, lo: Double, hi: Double): TrackExport {
    val stops = a.keyframes.stopList.filter { it.discretes.containsKey(prop) }
    data class R(val t: Double, val v: Any?, val unset: Boolean)
    val raws = mutableListOf<R>()
    for (i in 0 until iterations) {
      val t0 = a.begins + i * d
      if (forward(a.timing.direction, i)) {
        if (stops.first().at > 0f) raws += R(t0, null, true)
        for (s in stops) raws += R(t0 + s.at * d, s.discretes[prop], false)
      } else {
        // Progress runs 1 -> 0: each stop's value holds until progress drops below its percentage.
        val last = stops.last()
        raws += if (last.at <= 1f) R(t0, last.discretes[prop], false) else R(t0, null, true)
        for (j in stops.indices.reversed()) {
          val tj = t0 + (1 - stops[j].at) * d
          raws += if (j > 0) R(tj, stops[j - 1].discretes[prop], false) else R(tj, null, true)
        }
      }
    }
    // Of several keys at one instant only the last matters (the value just after it).
    val distinct = raws.filterIndexed { i, r -> i == raws.lastIndex || raws[i + 1].t - r.t >= 1e-9 }
    val before = distinct.lastOrNull { it.t < lo - 1e-9 }
    val kept = distinct.filter { it.t >= lo - 1e-9 && it.t <= hi + 1e-9 }.toMutableList()
    if (before != null && (kept.isEmpty() || kept.first().t > lo + 1e-9)) kept.add(0, before.copy(t = lo))
    val keys = kept.map { KeyExport(round(it.t), if (it.unset) JsonNull else value(it.v), STEP, if (it.unset) "unset" else null) }
    return TrackExport(prop.name, "discrete", keys)
  }

  // ------------------------------------------------------------------------------------------------ texts and set pieces

  /** When each bubble and banner is on screen, sampled from the engine at 60 fps. */
  private fun textExports(): List<TextExport> {
    val watched = scopes.flatMap { sc -> sc.actors.filter { it.kind == ActorKind.BUBBLE || it.kind == ActorKind.BANNER || it.text.isNotEmpty() } }.toSet()
    val shown = HashMap<ActorState, MutableList<DoubleArray>>()
    val texts = HashMap<ActorState, LinkedHashSet<String>>()
    val out = mutableListOf<ActorState>()
    val step = 1.0 / 60
    var t = 0.0
    while (t < show.duration) {
      show.frame(t.toFloat(), out)
      for (a in out) if (a in watched) {
        val spans = shown.getOrPut(a) { mutableListOf() }
        val last = spans.lastOrNull()
        if (last != null && abs(last[1] - t) < step * 1.5) last[1] = t + step else spans += doubleArrayOf(t, t + step)
        texts.getOrPut(a) { LinkedHashSet() } += a.text
      }
      t += step
    }
    return scopes.flatMap { sc ->
      sc.actors.filter { it in watched }.map { a ->
        // Base text: frame() leaves the last applied text, so read it from the declaration via the base style.
        TextExport(actorIds.getValue(a), sc.name, a.kind.name, baseText(sc, a), texts[a]?.toList() ?: emptyList(),
          shown[a]?.map { listOf(round(it[0]), round(min(it[1], (sc.start + sc.duration).toDouble()))) } ?: emptyList())
      }
    }
  }

  private fun baseText(sc: SceneScope, a: ActorState): String { pip.reset(); global.applyBase(); sc.applyBase(); return a.text }

  private fun customExport(sc: SceneScope, index: Int, a: ActorState): CustomActorExport {
    val frames = sc.actorSites[a]?.let { siteFrames(it) } ?: emptyList()
    val painter = a.paint?.let { painterName(it, frames) }
    val source = frames.firstOrNull { it.function != "custom" } ?: frames.firstOrNull()
    return CustomActorExport(
      actorIds.getValue(a), sc.name, index, a.layer, a.anchorToPip, a.text,
      painter?.first, painter?.second, source, frames,
    )
  }

  /** The declaring frames between SceneScope.actor and Show.prepare, innermost first. */
  private fun siteFrames(stack: Array<StackTraceElement>): List<SourceRef> {
    val pkg = "dev.playground.pip.ui.show"
    val start = stack.indexOfLast { it.className.startsWith("$pkg.SceneScope") && it.methodName.startsWith("actor") }
    return stack.drop(start + 1)
      .takeWhile { !(it.className == "$pkg.Show" && it.methodName == "prepare") }
      .filter { it.className.startsWith(pkg) && it.fileName != null && it.lineNumber > 0 && it.fileName != "ShowDsl.kt" }
      .map { f ->
        val fn = f.methodName.removeSuffix("\$default").substringBefore('$').ifEmpty { f.methodName }
        SourceRef(sourcePath(f.className, f.fileName!!), f.lineNumber, fn, sourceLine(f.className, f.fileName!!, f.lineNumber))
      }
  }

  private fun sourcePath(className: String, fileName: String) =
    "app/src/main/kotlin/" + className.substringBeforeLast('.').replace('.', '/') + "/" + fileName

  private fun sourceFile(className: String, fileName: String): File? =
    sourceRoot?.let { File(it, className.substringBeforeLast('.').replace('.', '/') + "/" + fileName) }?.takeIf { it.isFile }

  private val fileLines = HashMap<File, List<String>>()
  private fun sourceLine(className: String, fileName: String, line: Int): String? =
    sourceFile(className, fileName)?.let { f -> fileLines.getOrPut(f) { f.readLines() }.getOrNull(line - 1)?.trim() }

  /** A painter kept in a top-level val (e.g. `private val MAZE: Painter`), found by identity in the declaring files. */
  private fun painterName(paint: Any, frames: List<SourceRef>): Pair<String, SourceRef?>? {
    val classes = frames.map { "dev.playground.pip.ui.show." + it.file.substringAfterLast('/').removeSuffix(".kt") + "Kt" }.distinct()
    for (cn in classes) {
      val cls = runCatching { Class.forName(cn) }.getOrNull() ?: continue
      for (f in cls.declaredFields) {
        if (!Modifier.isStatic(f.modifiers)) continue
        f.isAccessible = true
        if (runCatching { f.get(null) }.getOrNull() === paint) {
          val file = frames.first { cn.endsWith(it.file.substringAfterLast('/').removeSuffix(".kt") + "Kt") }.file
          val src = sourceRoot?.let { root ->
            val local = File(root, file.removePrefix("app/src/main/kotlin/"))
            if (!local.isFile) null else {
              val lines = fileLines.getOrPut(local) { local.readLines() }
              val i = lines.indexOfFirst { Regex("\\bval ${Regex.escape(f.name)}\\b").containsMatchIn(it) }
              if (i < 0) null else SourceRef(file, i + 1, f.name, lines[i].trim())
            }
          }
          return f.name to src
        }
      }
    }
    return null
  }
}
