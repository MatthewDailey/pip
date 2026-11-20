package showreel

import rivc.Ease
import rivc.KeySpec
import kotlin.math.abs
import kotlin.math.max

/**
 * A channel sampled on the main timeline's frame grid.
 *
 * [values] holds the value at each frame `first..first+values.size-1`; [jumpAfter] marks frame
 * intervals `i -> i+1` that contain a discontinuity (a scene cut, a step, a jump key): the value
 * just before frame `i+1` differs from the value at `i+1`. A NaN value means "don't care" (the
 * thing is hidden); [fillDontCare] replaces those so they cost no keys.
 */
class Channel(val first: Int, val values: DoubleArray, val jumpAfter: BooleanArray = BooleanArray(values.size)) {
    val last get() = first + values.size - 1

    /** Hidden stretches take the next visible value (so it is already in place when shown), trailing ones the last. */
    fun fillDontCare(): Channel {
        val v = values.copyOf()
        var next = Double.NaN
        for (i in v.indices.reversed()) if (v[i].isNaN()) v[i] = next else next = v[i]
        var prev = Double.NaN
        for (i in v.indices) if (v[i].isNaN()) v[i] = prev else prev = v[i]
        for (i in v.indices) if (v[i].isNaN()) v[i] = 0.0
        return Channel(first, v, jumpAfter)
    }
}

/** Turns sampled channels into rivc keys. */
object KeyFit {
    /**
     * Linear keys that stay within [tol] of every sample (Ramer-Douglas-Peucker between forced
     * breakpoints). A jump inside a frame interval becomes a hold key at its start, so Rive doesn't
     * interpolate across a cut. Constant channels collapse to one key.
     */
    fun linear(ch: Channel, tol: Double): List<KeySpec> {
        val c = ch.fillDontCare()
        val v = c.values
        val n = v.size
        if (n == 0) return emptyList()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val hold = BooleanArray(n)
        for (i in 0 until n - 1) if (c.jumpAfter[i]) {
            keep[i] = true; keep[i + 1] = true; hold[i] = true
        }
        // Equal neighbours across a jump are not a jump.
        for (i in 0 until n - 1) if (hold[i] && abs(v[i] - v[i + 1]) <= tol * 0.01) hold[i] = false
        var start = 0
        while (start < n - 1) {
            var end = start + 1
            while (!keep[end]) end++
            if (!hold[start]) rdp(v, start, end, tol, keep)
            start = end
        }
        val out = ArrayList<KeySpec>()
        for (i in 0 until n) if (keep[i]) out += KeySpec(frame = c.first + i, value = round(v[i]), ease = if (hold[i]) Ease.Hold else Ease.Linear)
        return dedupe(out)
    }

    /** Hold keys at each change of a stepped channel. */
    fun hold(ch: Channel): List<KeySpec> {
        val c = ch.fillDontCare()
        val out = ArrayList<KeySpec>()
        var prev = Double.NaN
        for (i in c.values.indices) {
            val x = c.values[i]
            if (i == 0 || x != prev) out += KeySpec(frame = c.first + i, value = round(x), ease = Ease.Hold)
            prev = x
        }
        return out
    }

    /** Drops interior keys of constant runs (a run of equal linear keys needs only its ends). */
    private fun dedupe(keys: List<KeySpec>): List<KeySpec> {
        if (keys.size <= 2) return if (keys.size == 2 && keys[0].value == keys[1].value) keys.take(1).map { it.copy(ease = Ease.Hold) } else keys
        val out = ArrayList<KeySpec>()
        for (i in keys.indices) {
            val k = keys[i]
            val p = keys.getOrNull(i - 1)
            val n = keys.getOrNull(i + 1)
            if (p != null && n != null && p.value == k.value && n.value == k.value && p.ease == Ease.Linear && k.ease == Ease.Linear) continue
            out += k
        }
        return if (out.all { it.value == out[0].value }) listOf(out[0].copy(ease = Ease.Hold)) else out
    }

    private fun rdp(v: DoubleArray, a: Int, b: Int, tol: Double, keep: BooleanArray) {
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(a to b)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            if (e - s < 2) continue
            var worst = -1
            var err = tol
            val dv = (v[e] - v[s]) / (e - s)
            for (i in s + 1 until e) {
                val d = abs(v[i] - (v[s] + dv * (i - s)))
                if (d > err) { err = d; worst = i }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(s to worst)
                stack.addLast(worst to e)
            }
        }
    }

    fun round(x: Double): Double {
        val m = max(1.0, abs(x))
        return if (m > 1000) Math.round(x * 100) / 100.0 else Math.round(x * 1e5) / 1e5
    }
}
