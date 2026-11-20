package showreel

import kotlin.math.abs

/** One evaluated frame: the scene, Pip's values and the scene's actors' values. */
class Frame(val t: Double, val scene: ShowExport.Scene, val pip: Map<String, Any?>, val actors: Map<String, Map<String, Any?>>) {
    fun pipNum(name: String): Double = (pip[name] as? Double) ?: Double.NaN
    fun actorNum(id: String, name: String): Double = (actors[id]?.get(name) as? Double) ?: Double.NaN
}

/**
 * Evaluates the export at any time, following only EXPORT.md: start from the scene's bases, apply
 * the scene's cascade (`stack`) in order, add additive float tracks, replace the rest, and let a
 * discrete `unset` key leave the value beneath alone.
 *
 * Additive animations are summed here, at conversion time, because Rive keyframes replace values.
 */
class Evaluator(val show: ShowExport) {
    fun frame(t: Double): Frame {
        val scene = show.sceneAt(t)
        val pip = HashMap(scene.pipBase)
        val actors = scene.actorIds.associateWith { HashMap(show.actors.getValue(it).base) }
        for (id in scene.stack) {
            val a = show.animations.getValue(id)
            val target: MutableMap<String, Any?> = if (a.target == "pip") pip else actors[a.target] ?: continue
            apply(a, t, target)
        }
        return Frame(t, scene, pip, actors)
    }

    private fun apply(a: ShowExport.Animation, t: Double, into: MutableMap<String, Any?>) {
        val mode = when {
            t < a.start -> if (a.fillBefore) Mode.FIRST else return
            a.end != null && t >= a.end -> if (a.fillAfter) Mode.LAST else return
            else -> Mode.AT
        }
        for (track in a.tracks) {
            val keys = track.keys
            if (keys.isEmpty()) continue
            val key: ShowExport.Key
            var v: Any?
            when (mode) {
                Mode.FIRST -> { key = keys.first(); v = key.v }
                Mode.LAST -> { key = keys.last(); v = key.v }
                Mode.AT -> {
                    val i = lastAtOrBefore(track.times, t)
                    if (i < 0) { key = keys.first(); v = key.v } else {
                        key = keys[i]
                        v = key.v
                        val next = keys.getOrNull(i + 1)
                        val ease = key.ease
                        if (!track.discrete && next != null && ease is ShowExport.Ease.Bezier && next.t > key.t) {
                            val u = (t - key.t) / (next.t - key.t)
                            val a0 = key.v as Double
                            val b0 = next.v as Double
                            v = a0 + (b0 - a0) * Bezier.ease(ease.x1, ease.y1, ease.x2, ease.y2, u)
                        }
                    }
                }
            }
            if (track.discrete) {
                if (!key.unset) into[track.property] = v
            } else {
                val d = v as? Double ?: continue
                into[track.property] = if (a.additive) ((into[track.property] as? Double) ?: 0.0) + d else d
            }
        }
    }

    private enum class Mode { FIRST, LAST, AT }

    companion object {
        /** Index of the last time `<= t` (the later of equal times wins), or -1. */
        fun lastAtOrBefore(times: DoubleArray, t: Double): Int {
            var lo = 0
            var hi = times.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }
}

/** CSS `cubic-bezier` timing: y at x = [u]. */
object Bezier {
    fun ease(x1: Double, y1: Double, x2: Double, y2: Double, u: Double): Double {
        if (u <= 0) return 0.0
        if (u >= 1) return 1.0
        if (x1 == y1 && x2 == y2) return u
        val s = solveX(x1, x2, u)
        return curve(y1, y2, s)
    }

    private fun curve(p1: Double, p2: Double, s: Double): Double {
        val m = 1 - s
        return 3 * m * m * s * p1 + 3 * m * s * s * p2 + s * s * s
    }

    private fun slope(p1: Double, p2: Double, s: Double): Double {
        val m = 1 - s
        return 3 * m * m * p1 + 6 * m * s * (p2 - p1) + 3 * s * s * (1 - p2)
    }

    private fun solveX(x1: Double, x2: Double, x: Double): Double {
        var s = x
        repeat(8) {
            val err = curve(x1, x2, s) - x
            if (abs(err) < 1e-9) return s
            val d = slope(x1, x2, s)
            if (abs(d) < 1e-7) return@repeat
            s -= err / d
        }
        var lo = 0.0
        var hi = 1.0
        s = x
        repeat(60) {
            val v = curve(x1, x2, s)
            if (abs(v - x) < 1e-10) return s
            if (v < x) lo = s else hi = s
            s = (lo + hi) / 2
        }
        return s
    }
}
