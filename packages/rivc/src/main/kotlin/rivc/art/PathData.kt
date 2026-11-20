package rivc.art

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A path-data string that doesn't parse; the message names the position. */
class PathDataException(message: String) : RuntimeException(message)

/**
 * SVG path data (`d`) to Rive vertices: every command (M L H V C S Q T A Z, absolute and
 * relative) with implicit repeats, quadratics raised to cubics, and arcs split into
 * cubics of at most 90 degrees each.
 */
object PathData {
    fun parse(d: String): List<SubPath> = Parser(d).run()

    /** A closed subpath that ends where it started repeats its first point; Rive wants it once. */
    private const val FOLD_EPS = 1e-3

    private class Parser(val s: String) {
        var i = 0
        val out = mutableListOf<SubPath>()
        var cur: SubPath? = null
        var pos = Pt(0.0, 0.0)
        var start = Pt(0.0, 0.0)
        // Reflection points for S and T: the previous segment's second control point, or null.
        var lastCubicCtrl: Pt? = null
        var lastQuadCtrl: Pt? = null

        fun fail(msg: String): Nothing = throw PathDataException("path data: $msg at offset $i in \"${s.take(60)}${if (s.length > 60) "…" else ""}\"")

        fun skipSeparators() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun number(): Double {
            skipSeparators()
            val begin = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) { i++; digits++ }
            }
            if (digits == 0) fail("expected a number")
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) {
                    while (i < s.length && s[i].isDigit()) i++
                } else {
                    i = save
                }
            }
            return s.substring(begin, i).toDouble()
        }

        /** Arc flags are a single 0 or 1 and may run into the next number ("a1 1 0 00 1 1"). */
        fun flag(): Boolean {
            skipSeparators()
            if (i >= s.length || (s[i] != '0' && s[i] != '1')) fail("expected an arc flag (0 or 1)")
            return s[i++] == '1'
        }

        fun point(rel: Boolean): Pt {
            val x = number()
            val y = number()
            return if (rel) Pt(pos.x + x, pos.y + y) else Pt(x, y)
        }

        fun ensureSubpath() {
            if (cur == null) {
                cur = SubPath(mutableListOf(Vtx(pos)), false).also { out += it }
            }
        }

        fun lineTo(p: Pt) {
            ensureSubpath()
            cur!!.vertices += Vtx(p)
            pos = p
        }

        fun cubicTo(c1: Pt, c2: Pt, p: Pt) {
            ensureSubpath()
            cur!!.vertices.last().outH = c1
            cur!!.vertices += Vtx(p, inH = c2)
            pos = p
        }

        fun quadTo(q: Pt, p: Pt) {
            val p0 = pos
            cubicTo(p0 + (q - p0) * (2.0 / 3), p + (q - p) * (2.0 / 3), p)
        }

        fun close() {
            val sp = cur ?: return
            sp.closed = true
            val vs = sp.vertices
            val first = vs.first()
            val last = vs.last()
            if (vs.size > 1 && abs(first.p.x - last.p.x) < FOLD_EPS && abs(first.p.y - last.p.y) < FOLD_EPS) {
                first.inH = first.p + (last.inH - last.p)
                vs.removeAt(vs.size - 1)
            }
            pos = start
            cur = null
        }

        fun run(): List<SubPath> {
            var cmd: Char? = null
            while (true) {
                skipSeparators()
                if (i >= s.length) break
                val ch = s[i]
                if (ch.isLetter() && ch != 'e' && ch != 'E') {
                    cmd = ch
                    i++
                } else if (cmd == null) {
                    fail("path data must start with a command")
                }
                val c = cmd
                val rel = c.isLowerCase()
                var wasCubic = false
                var wasQuad = false
                when (c.uppercaseChar()) {
                    'M' -> {
                        val p = point(rel)
                        if (cur != null && cur!!.vertices.size == 1 && !cur!!.closed) out.remove(cur!!)
                        cur = null
                        pos = p
                        start = p
                        ensureSubpath()
                        // Further pairs after a moveto are implicit linetos.
                        cmd = if (rel) 'l' else 'L'
                    }
                    'L' -> lineTo(point(rel))
                    'H' -> { val x = number(); lineTo(Pt(if (rel) pos.x + x else x, pos.y)) }
                    'V' -> { val y = number(); lineTo(Pt(pos.x, if (rel) pos.y + y else y)) }
                    'C' -> {
                        val c1 = point(rel); val c2 = point(rel); val p = point(rel)
                        cubicTo(c1, c2, p); lastCubicCtrl = c2; wasCubic = true
                    }
                    'S' -> {
                        val c1 = lastCubicCtrl?.let { pos + (pos - it) } ?: pos
                        val c2 = point(rel); val p = point(rel)
                        cubicTo(c1, c2, p); lastCubicCtrl = c2; wasCubic = true
                    }
                    'Q' -> {
                        val q = point(rel); val p = point(rel)
                        quadTo(q, p); lastQuadCtrl = q; wasQuad = true
                    }
                    'T' -> {
                        val q = lastQuadCtrl?.let { pos + (pos - it) } ?: pos
                        val p = point(rel)
                        quadTo(q, p); lastQuadCtrl = q; wasQuad = true
                    }
                    'A' -> {
                        val rx = number(); val ry = number(); val rot = number()
                        val large = flag(); val sweep = flag()
                        val p = point(rel)
                        arcTo(rx, ry, rot, large, sweep, p)
                    }
                    'Z' -> { close(); cmd = null }
                    else -> fail("unknown command '$c'")
                }
                if (!wasCubic) lastCubicCtrl = null
                if (!wasQuad) lastQuadCtrl = null
                // Numbers after a command's last argument repeat it (moveto repeats as lineto).
            }
            // A trailing lone moveto draws nothing.
            if (cur != null && cur!!.vertices.size == 1) out.remove(cur!!)
            return out
        }

        /** SVG implementation notes F.6.5/F.6.6: endpoint to centre parameterisation, then cubics. */
        fun arcTo(rx0: Double, ry0: Double, rotDeg: Double, large: Boolean, sweep: Boolean, p: Pt) {
            val p0 = pos
            if (p0 == p) return
            var rx = abs(rx0)
            var ry = abs(ry0)
            if (rx == 0.0 || ry == 0.0) { lineTo(p); return }
            val phi = Math.toRadians(rotDeg % 360)
            val cs = cos(phi)
            val sn = sin(phi)
            val dx = (p0.x - p.x) / 2
            val dy = (p0.y - p.y) / 2
            val x1 = cs * dx + sn * dy
            val y1 = -sn * dx + cs * dy
            val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
            if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
            val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
            val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
            var coef = sqrt((num / den).coerceAtLeast(0.0))
            if (large == sweep) coef = -coef
            val cxp = coef * rx * y1 / ry
            val cyp = -coef * ry * x1 / rx
            val cx = cs * cxp - sn * cyp + (p0.x + p.x) / 2
            val cy = sn * cxp + cs * cyp + (p0.y + p.y) / 2
            fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
                val dot = ux * vx + uy * vy
                val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
                val a = acos((dot / len).coerceIn(-1.0, 1.0))
                return if (ux * vy - uy * vx < 0) -a else a
            }
            val theta1 = angle(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
            var delta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
            if (!sweep && delta > 0) delta -= 2 * PI
            if (sweep && delta < 0) delta += 2 * PI
            val n = ceil(abs(delta) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
            val step = delta / n
            val k = 4.0 / 3.0 * tan(step / 4)
            fun onEllipse(t: Double) = Pt(cx + rx * cos(t) * cs - ry * sin(t) * sn, cy + rx * cos(t) * sn + ry * sin(t) * cs)
            fun derivative(t: Double) = Pt(-rx * sin(t) * cs - ry * cos(t) * sn, -rx * sin(t) * sn + ry * cos(t) * cs)
            var t = theta1
            for (seg in 0 until n) {
                val t2 = t + step
                val a = onEllipse(t)
                val b = if (seg == n - 1) p else onEllipse(t2)
                cubicTo(a + derivative(t) * k, b - derivative(t2) * k, b)
                t = t2
            }
        }
    }
}
