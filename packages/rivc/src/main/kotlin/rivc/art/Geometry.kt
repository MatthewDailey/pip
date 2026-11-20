package rivc.art

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)
    fun dot(o: Pt) = x * o.x + y * o.y
    val length get() = hypot(x, y)
}

data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val center get() = Pt((minX + maxX) / 2, (minY + maxY) / 2)
    fun union(o: Box) = Box(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
    fun corners() = listOf(Pt(minX, minY), Pt(maxX, minY), Pt(maxX, maxY), Pt(minX, maxY))

    companion object {
        fun of(points: Iterable<Pt>): Box? {
            val ps = points.toList()
            if (ps.isEmpty()) return null
            return Box(ps.minOf { it.x }, ps.minOf { it.y }, ps.maxOf { it.x }, ps.maxOf { it.y })
        }
    }
}

/**
 * A 2D affine transform in SVG's `matrix(a b c d e f)` layout:
 * `x' = a·x + c·y + e`, `y' = b·x + d·y + f`. `p * q` applies `q` first.
 */
data class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0,
) {
    operator fun times(o: Affine) = Affine(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    fun apply(p: Pt) = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    fun applyVector(p: Pt) = Pt(a * p.x + c * p.y, b * p.x + d * p.y)

    val det get() = a * d - b * c

    val linear get() = copy(e = 0.0, f = 0.0)

    val isIdentity get() = this == IDENTITY

    fun isNear(o: Affine, eps: Double = 1e-9) =
        abs(a - o.a) < eps && abs(b - o.b) < eps && abs(c - o.c) < eps && abs(d - o.d) < eps && abs(e - o.e) < eps && abs(f - o.f) < eps

    /** The factor lengths scale by on average (exact for uniform scales). */
    val meanScale get() = sqrt(abs(det))

    fun inverse(): Affine {
        val det = det
        require(abs(det) > 1e-12) { "transform $this can't be inverted" }
        return Affine(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    /** Whether the linear part keeps angles (a rotation times a uniform scale, possibly mirrored). */
    fun isConformal(eps: Double = 1e-6): Boolean {
        val s = max(abs(a) + abs(b), abs(c) + abs(d)).coerceAtLeast(1e-12)
        return (abs(a - d) < eps * s && abs(b + c) < eps * s) || (abs(a + d) < eps * s && abs(b - c) < eps * s)
    }

    /**
     * Splits this into Rive's node transform `translate(x, y) · rotate(rotation) · scale(scaleX, scaleY)`,
     * or null when the linear part has a skew Rive nodes can't hold.
     */
    fun decompose(eps: Double = 1e-7): Trs? {
        val sx = hypot(a, b)
        if (sx < 1e-12) return null
        val rot = atan2(b, a)
        val cs = cos(rot)
        val sn = sin(rot)
        val skew = cs * c + sn * d
        val sy = -sn * c + cs * d
        if (abs(sy) < 1e-12) return null
        if (abs(skew) > eps * max(sx, abs(sy))) return null
        // A mirror can be read as rotate(θ)·scale(sx, -sy) or rotate(θ ± π)·scale(-sx, sy); keep
        // the smaller turn, so scale(-1, 1) stays a flip and doesn't become a half turn.
        if (sy < 0) {
            val alt = if (rot > 0) rot - PI else rot + PI
            if (abs(alt) < abs(rot) - 1e-12) return Trs(e, f, alt, -sx, -sy)
        }
        return Trs(e, f, rot, sx, sy)
    }

    companion object {
        val IDENTITY = Affine()
        fun translate(x: Double, y: Double) = Affine(e = x, f = y)
        fun translate(p: Pt) = translate(p.x, p.y)
        fun scale(sx: Double, sy: Double = sx) = Affine(a = sx, d = sy)
        fun rotate(radians: Double) = Affine(cos(radians), sin(radians), -sin(radians), cos(radians))
    }
}

/** A Rive node transform: translate, then rotate (radians), then scale. */
data class Trs(val x: Double, val y: Double, val rotation: Double, val scaleX: Double, val scaleY: Double) {
    fun toAffine(): Affine {
        val cs = if (rotation == 0.0) 1.0 else cos(rotation)
        val sn = if (rotation == 0.0) 0.0 else sin(rotation)
        return Affine(cs * scaleX, sn * scaleX, -sn * scaleY, cs * scaleY, x, y)
    }
}

/**
 * One point of a path in Rive's vertex model: the segment from A to B is the cubic
 * `A, A.out, B.in, B`, so a straight segment has its handles on the points.
 */
class Vtx(var p: Pt, var inH: Pt = p, var outH: Pt = p) {
    fun transformed(m: Affine) = Vtx(m.apply(p), m.apply(inH), m.apply(outH))
    override fun toString() = "Vtx($p in=$inH out=$outH)"
}

/** One subpath: its vertices and whether the last joins back to the first. */
class SubPath(val vertices: MutableList<Vtx>, var closed: Boolean) {
    fun transformed(m: Affine) = SubPath(vertices.mapTo(mutableListOf()) { it.transformed(m) }, closed)

    /** The cubic segments, as control-point quadruples, including the closing one. */
    fun segments(): List<Array<Pt>> {
        val out = mutableListOf<Array<Pt>>()
        for (i in 0 until vertices.size - 1) out += seg(vertices[i], vertices[i + 1])
        if (closed && vertices.size > 1) out += seg(vertices.last(), vertices.first())
        return out
    }

    private fun seg(a: Vtx, b: Vtx) = arrayOf(a.p, a.outH, b.inH, b.p)

    /**
     * A copy with [count] vertices drawing the same curve: the segment with the longest control
     * polygon is split in half (de Casteljau) until the count is reached. Splitting the longest
     * first spreads the new vertices along the outline, so two resampled paths morph by
     * matching vertices that sit at similar places along their length.
     */
    fun subdivided(count: Int): SubPath {
        require(count >= vertices.size) { "can't subdivide ${vertices.size} vertices down to $count" }
        val vs = vertices.mapTo(mutableListOf()) { Vtx(it.p, it.inH, it.outH) }
        if (vs.isEmpty()) return SubPath(vs, closed)
        while (vs.size < count) {
            val n = if (closed) vs.size else vs.size - 1
            if (n == 0) { vs += Vtx(vs[0].p); continue }
            val i = (0 until n).maxBy { k ->
                val a = vs[k]; val b = vs[(k + 1) % vs.size]
                (a.outH - a.p).length + (b.inH - a.outH).length + (b.p - b.inH).length
            }
            val a = vs[i]; val b = vs[(i + 1) % vs.size]
            val p01 = mid(a.p, a.outH); val p12 = mid(a.outH, b.inH); val p23 = mid(b.inH, b.p)
            val p012 = mid(p01, p12); val p123 = mid(p12, p23)
            val m = mid(p012, p123)
            a.outH = p01; b.inH = p23
            vs.add(i + 1, Vtx(m, p012, p123))
        }
        return SubPath(vs, closed)
    }

    private fun mid(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)

    /** Exact bounds of the drawn curve (not of the handles). */
    fun bounds(): Box? {
        if (vertices.isEmpty()) return null
        var box = Box(vertices[0].p.x, vertices[0].p.y, vertices[0].p.x, vertices[0].p.y)
        for (s in segments()) for (t in cubicExtrema(s)) {
            val q = cubicAt(s, t)
            box = box.union(Box(q.x, q.y, q.x, q.y))
        }
        for (v in vertices) box = box.union(Box(v.p.x, v.p.y, v.p.x, v.p.y))
        return box
    }

    companion object {
        fun cubicAt(s: Array<Pt>, t: Double): Pt {
            val u = 1 - t
            val w0 = u * u * u
            val w1 = 3 * u * u * t
            val w2 = 3 * u * t * t
            val w3 = t * t * t
            return Pt(
                w0 * s[0].x + w1 * s[1].x + w2 * s[2].x + w3 * s[3].x,
                w0 * s[0].y + w1 * s[1].y + w2 * s[2].y + w3 * s[3].y,
            )
        }

        /** Parameters in (0, 1) where the cubic's x or y derivative is zero. */
        fun cubicExtrema(s: Array<Pt>): List<Double> {
            val out = mutableListOf<Double>()
            for (axis in 0..1) {
                val p = s.map { if (axis == 0) it.x else it.y }
                // Derivative / 3: a t^2 + b t + c.
                val a = -p[0] + 3 * p[1] - 3 * p[2] + p[3]
                val b = 2 * (p[0] - 2 * p[1] + p[2])
                val c = p[1] - p[0]
                if (abs(a) < 1e-12) {
                    if (abs(b) > 1e-12) out += -c / b
                } else {
                    val disc = b * b - 4 * a * c
                    if (disc >= 0) {
                        val r = sqrt(disc)
                        out += (-b + r) / (2 * a)
                        out += (-b - r) / (2 * a)
                    }
                }
            }
            return out.filter { it > 0 && it < 1 }
        }
    }
}

fun List<SubPath>.bounds(): Box? = mapNotNull { it.bounds() }.reduceOrNull { x, y -> x.union(y) }
