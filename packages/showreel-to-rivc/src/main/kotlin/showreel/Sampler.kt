package showreel

import rivc.KeySpec
import kotlin.math.abs

/** Per-frame memo, so a placement computed for `x` is reused for `y`, `rotation` and the rest. */
class At(val frame: Frame) {
    private val memo = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> memo(key: String, f: () -> T): T = if (memo.containsKey(key)) memo[key] as T else f().also { memo[key] = it }
}

/**
 * One keyed property of the main timeline: `target.property`, sampled by [fn] over frames
 * [first]..[last] (a NaN sample means "don't care"), fitted linearly within [tol] or as holds.
 */
class Producer(
    val target: String,
    val property: String,
    val first: Int,
    val last: Int,
    val tol: Double,
    val hold: Boolean = false,
    /** Value before [first] and after [last] (an instance outside its scene is hidden). */
    val outside: Double? = null,
    val fn: (At) -> Double,
)

/**
 * Samples every producer on the 60 fps grid of the main timeline (frame 0 at show time [t0]) and fits keys.
 *
 * A jump inside a frame interval (a scene cut, a step, a jump key, an icon switch) is found by
 * evaluating the middle of the interval: there a jump sits on one side, so it is far from the
 * linear midpoint, while smooth motion stays close to it. The key before such a jump holds.
 */
class Sampler(private val ev: Evaluator, val fps: Int, val frames: Int, private val t0: Double = 0.0) {
    fun run(producers: List<Producer>, progress: (Int) -> Unit = {}): Map<Producer, List<KeySpec>> {
        val vals = producers.associateWith { DoubleArray(it.last - it.first + 1) }
        val mids = producers.associateWith { DoubleArray(it.last - it.first + 1) }
        val byFrame = Array(frames + 1) { ArrayList<Producer>() }
        for (p in producers) for (f in p.first..p.last) byFrame[f] += p
        for (f in 0..frames) {
            val list = byFrame[f]
            if (list.isEmpty()) continue
            val at = At(ev.frame(t0 + f.toDouble() / fps))
            val mid = if (f < frames) At(ev.frame(t0 + (f + 0.5) / fps)) else null
            for (p in list) {
                vals.getValue(p)[f - p.first] = p.fn(at)
                if (mid != null && f < p.last) mids.getValue(p)[f - p.first] = p.fn(mid)
            }
            if (f % 600 == 0) progress(f)
        }
        val out = LinkedHashMap<Producer, List<KeySpec>>()
        for (p in producers) {
            val v = vals.getValue(p)
            val m = mids.getValue(p)
            val jumps = BooleanArray(v.size)
            if (!p.hold) for (i in 0 until v.size - 1) {
                val a = v[i]; val b = v[i + 1]; val c = m[i]
                if (a.isNaN() || b.isNaN() || c.isNaN()) continue
                val d = abs(b - a)
                if (d > p.tol * 4 && abs(c - (a + b) / 2) > 0.35 * d) jumps[i] = true
            }
            val ch = Channel(p.first, v, jumps)
            var keys = if (p.hold) KeyFit.hold(ch) else KeyFit.linear(ch, p.tol)
            if (p.outside != null) keys = withOutside(keys, p, frames)
            out[p] = keys
        }
        return out
    }

    /** Holds [Producer.outside] before the first sampled frame and after the last. */
    private fun withOutside(keys: List<KeySpec>, p: Producer, frames: Int): List<KeySpec> {
        val o = p.outside!!
        val out = ArrayList<KeySpec>()
        if (p.first > 0 && keys.firstOrNull()?.frame != 0) out += KeySpec(0, o, ease = rivc.Ease.Hold)
        out += keys
        if (p.last < frames) {
            val last = out.removeAt(out.size - 1)
            out += last.copy(ease = rivc.Ease.Hold)
            out += KeySpec(p.last + 1, o, ease = rivc.Ease.Hold)
        }
        return out
    }
}
