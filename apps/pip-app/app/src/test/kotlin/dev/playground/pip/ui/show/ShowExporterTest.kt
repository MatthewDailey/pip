package dev.playground.pip.ui.show

import dev.playground.pip.ui.anim.Easing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Exports the showreel to `build/showreel/showreel.json`, reads the file back and replays it with a small,
 * independent evaluator that follows EXPORT.md. At sampled times every Pip and actor property must match what the
 * real engine's [Show.frame] produces, and every cue must be an event at its time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShowExporterTest {
  @Test
  fun exportReplaysLikeTheEngine() {
    val show = pipShow()
    val exported = ShowExporter.export(show, emulatorStage(), File("src/main/kotlin"))
    val file = File(showreelOutDir(), "showreel.json")
    file.writeText(exported.toJson())
    val ex = showreelJson.decodeFromString(ShowreelExport.serializer(), file.readText())
    assertEquals(SHOWREEL_FORMAT, ex.format)
    assertEquals(show.scenes.size, ex.scenes.size)

    // Cues: each one the engine logs is an event at its time.
    val cues = show.cuesBetween(-1f, show.duration + 1f)
    assertEquals(cues.size, ex.cues.size)
    for ((t, name) in cues) assertTrue("cue $name at $t", ex.cues.any { it.name == name && abs(it.t - t) < 1e-5 })

    // Custom actors all have ids and a declaration site.
    val built = show.built!!
    val customCount = built.scopes.sumOf { (_, sc) -> sc.actors.count { it.kind == ActorKind.CUSTOM } }
    assertEquals(customCount, ex.customActors.size)
    assertTrue(ex.customActors.all { it.source != null })

    val eval = Evaluator(ex)
    val rnd = Random(7)
    val random = List(50) { rnd.nextDouble() * show.duration }
    val sweep = (0 until 3000).map { (it + 0.5) * show.duration / 3000 }
    val report = StringBuilder()
    val worst = compare(show, eval, random, report, "random50") + compare(show, eval, sweep, report, "sweep3000")
    File(showreelOutDir(), "verify.txt").writeText(report.toString())
    println(report)
    assertTrue("export differs from the engine:\n$report", worst.isEmpty())
  }

  /** The eases keep their exact original formulas. */
  @Test
  fun easesAreUnchanged() {
    val ref = mapOf<String, (Float) -> Float>(
      "inOutSine" to { (-(cos(Math.PI * it) - 1) / 2).toFloat() },
      "outSine" to { sin(it * Math.PI / 2).toFloat() },
      "inSine" to { (1 - cos(it * Math.PI / 2)).toFloat() },
      "outElastic" to { x -> if (x <= 0f || x >= 1f) x else (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * (2 * Math.PI / 3)) + 1).toFloat() },
      "steps4" to { floor(it * 4).coerceAtMost(3f) / 4 },
    )
    val now = mapOf("inOutSine" to Easing.easeInOutSine, "outSine" to Easing.easeOutSine, "inSine" to Easing.easeInSine,
      "outElastic" to Easing.outElastic, "steps4" to Easing.steps(4))
    for (i in 0..1000) {
      val x = i / 1000f
      for ((k, f) in ref) assertEquals("$k($x)", f(x).toRawBits(), now.getValue(k)(x).toRawBits())
    }
    // The Bezier stand-ins for the sine eases stay within 0.2% of the real curves.
    for ((spec, f) in listOf(SINE_IN_OUT to ref.getValue("inOutSine"), SINE_IN to ref.getValue("inSine"), SINE_OUT to ref.getValue("outSine"))) {
      for (i in 0..200) { val x = i / 200.0; assertTrue(abs(bezier(spec.map { it.toDouble() }, x) - f(x.toFloat())) < 0.002) }
    }
  }

  private class Stat(var max: Double = 0.0, var at: Double = 0.0, var n: Int = 0)

  /** Compares at [times]; returns the properties outside tolerance. */
  private fun compare(show: Show, eval: Evaluator, times: List<Double>, report: StringBuilder, label: String): List<String> {
    val built = show.built!!
    val engineActors = mutableListOf<ActorState>()
    val stats = sortedMapOf<String, Stat>()
    val failures = mutableListOf<String>()
    for (t in times) {
      val (pipE, actorsE) = eval.frame(t)
      show.frame(t.toFloat(), engineActors)
      val scope = built.scopes.lastOrNull { (start, sc) -> t.toFloat() >= start && t.toFloat() < start + sc.duration }?.second ?: built.scopes.last().second
      fun check(key: String, prop: String, engine: Any?, mine: JsonElement?) {
        val stat = stats.getOrPut(key) { Stat() }
        stat.n++
        val err = difference(engine, mine)
        if (err > stat.max) { stat.max = err; stat.at = t }
        if (err > tolerance(prop, engine)) failures += "$key t=$t engine=$engine export=$mine"
      }
      for ((k, v) in pipValues(built.pip)) check("pip.$k", k, v, pipE[k])
      for ((i, a) in scope.actors.withIndex()) {
        val id = "${scope.name}-actor-$i"
        val mine = actorsE.getValue(id)
        for ((k, v) in actorValues(a)) {
          if (k == "color" || k == "layer" || k == "anchorToPip") continue
          check("actor.$k", k, v, mine[k])
        }
      }
    }
    report.append("== $label: ${times.size} times, ${failures.size} mismatches\n")
    for ((k, s) in stats) if (s.max > 0) report.append("  %-18s max error %.6g at t=%.4f (%d samples)\n".format(k, s.max, s.at, s.n))
    failures.take(40).forEach { report.append("  FAIL ").append(it).append('\n') }
    return failures
  }

  /**
   * The Bezier stand-ins for sine eases are within 0.18% of a segment's change, everything else is exact up to float
   * rounding and 120 Hz baking. That is under 2.5 px for moves across the screen and 1.5 degrees on the 600-900 degree
   * spins; unit-range values (alpha, squash, look) stay within 0.004.
   */
  private fun tolerance(prop: String, engine: Any?): Double = when (prop) {
    "x", "y", "size", "groundY", "leftX", "leftY", "rightX", "rightY" -> 2.5
    "rotation", "leftAngle", "rightAngle" -> 1.5
    "progress" -> 1.0 + 0.002 * abs((engine as? Float ?: 0f).toDouble())
    else -> 0.004
  }

  private fun difference(engine: Any?, mine: JsonElement?): Double = when (engine) {
    is Float -> {
      val m = if (mine == null || mine is JsonNull) Double.NaN else mine.jsonPrimitive.double
      if (engine.isNaN() && m.isNaN()) 0.0 else if (engine.isNaN() || m.isNaN()) Double.POSITIVE_INFINITY else abs(engine - m)
    }
    is Boolean -> if ((mine as? JsonPrimitive)?.booleanOrNull == engine) 0.0 else Double.POSITIVE_INFINITY
    null -> if (mine == null || mine is JsonNull) 0.0 else Double.POSITIVE_INFINITY
    is Enum<*> -> if ((mine as? JsonPrimitive)?.content == engine.name) 0.0 else Double.POSITIVE_INFINITY
    else -> if ((mine as? JsonPrimitive)?.content == engine.toString()) 0.0 else Double.POSITIVE_INFINITY
  }
}

/** Replays an export exactly as EXPORT.md describes, knowing nothing about the Kotlin engine. */
private class Evaluator(private val ex: ShowreelExport) {
  private val anims = ex.animations.associateBy { it.id }
  private val actors = ex.actors.associateBy { it.id }

  fun frame(t: Double): Pair<Map<String, JsonElement>, Map<String, Map<String, JsonElement>>> {
    val scene = ex.scenes.lastOrNull { t >= it.start && t < it.end } ?: ex.scenes.last()
    val pip = scene.pipBase.toMutableMap()
    val acts = scene.actors.associateWith { actors.getValue(it).base.toMutableMap() }
    for (id in scene.stack) {
      val a = anims.getValue(id)
      val target = if (a.target == "pip") pip else acts[a.target] ?: continue
      val phase = when {
        t < a.active.start -> if (a.active.fillBefore) -1 else continue
        a.active.end != null && t >= a.active.end -> if (a.active.fillAfter) 1 else continue
        else -> 0
      }
      for (tr in a.tracks) {
        if (tr.keys.isEmpty()) continue
        if (tr.type == "discrete") {
          val k = when (phase) { -1 -> tr.keys.first(); 1 -> tr.keys.last(); else -> tr.keys.lastOrNull { it.t <= t } ?: tr.keys.first() }
          if (k.src != "unset") target[tr.property] = k.v
        } else {
          val v = when (phase) { -1 -> num(tr.keys.first().v); 1 -> num(tr.keys.last().v); else -> sample(tr.keys, t) }
          target[tr.property] = JsonPrimitive(if (a.active.additive) num(target[tr.property]) + v else v)
        }
      }
    }
    return pip to acts
  }

  private fun num(e: JsonElement?): Double = if (e == null || e is JsonNull) Double.NaN else e.jsonPrimitive.double

  private fun sample(keys: List<KeyExport>, t: Double): Double {
    val i = keys.indexOfLast { it.t <= t }.coerceAtLeast(0)
    val k = keys[i]
    val next = keys.getOrNull(i + 1) ?: return num(k.v)
    val e = k.ease
    if (e == null || (e is JsonPrimitive && e.content == "step")) return num(k.v)
    val u = ((t - k.t) / (next.t - k.t)).coerceIn(0.0, 1.0)
    val p = (e as JsonArray).map { it.jsonPrimitive.double }
    val a = num(k.v); val b = num(next.v)
    return a + (b - a) * bezier(p, u)
  }
}

/** A cubic-Bezier timing function, solved for x by bisection. */
internal fun bezier(p: List<Double>, x: Double): Double {
  if (x <= 0) return 0.0
  if (x >= 1) return 1.0
  val (x1, y1, x2, y2) = p
  var lo = 0.0; var hi = 1.0; var t = x
  repeat(60) {
    t = (lo + hi) / 2
    val bx = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t
    if (bx < x) lo = t else hi = t
  }
  return 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t
}
