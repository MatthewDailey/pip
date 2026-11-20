package rivc.spike

import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// Carried over from the riv-compiler spike. The art pass (rivc/art/) replaces it.

sealed interface SvgPart {
    val id: String
    val fill: Long
}

data class SvgPath(override val id: String, override val fill: Long, val vertices: List<Vtx>, val closed: Boolean) : SvgPart

data class SvgEllipse(
    override val id: String,
    override val fill: Long,
    val cx: Double,
    val cy: Double,
    val rx: Double,
    val ry: Double,
) : SvgPart

/** One point on a path in Rive's detached-cubic vertex model. */
data class Vtx(val x: Double, val y: Double, var inX: Double, var inY: Double, var outX: Double, var outY: Double) {
    val straight get() = inX == x && inY == y && outX == x && outY == y
    fun inRot() = atan2(inY - y, inX - x)
    fun inDist() = hypot(inX - x, inY - y)
    fun outRot() = atan2(outY - y, outX - x)
    fun outDist() = hypot(outX - x, outY - y)
}

/** Just enough SVG for Pip's spike art: absolute M/C/L/Z paths and ellipses with a hex fill, in paint order. */
object Svg {
    private val attr = Regex("""(\w+)="([^"]*)"""")
    private val element = Regex("""<(path|ellipse)\b([^>]*)/>""")

    fun parse(file: File): List<SvgPart> = parse(file.readText())

    fun parse(text: String): List<SvgPart> = element.findAll(text).map { m ->
        val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        val fill = 0xFF000000 or a.getValue("fill").removePrefix("#").toLong(16)
        val id = a.getValue("id")
        when (m.groupValues[1]) {
            "ellipse" -> SvgEllipse(id, fill, a.d("cx"), a.d("cy"), a.d("rx"), a.d("ry"))
            else -> path(id, fill, a.getValue("d"))
        }
    }.toList()

    private fun Map<String, String>.d(k: String) = getValue(k).toDouble()

    /**
     * SVG segments carry their control points; Rive stores them on the vertices
     * instead (a segment A->B uses A's out handle and B's in handle).
     */
    fun path(id: String, fill: Long, d: String): SvgPath {
        val tokens = Regex("""[MCLZmclz]|-?\d*\.?\d+(?:e-?\d+)?""").findAll(d).map { it.value }.toMutableList()
        val vs = mutableListOf<Vtx>()
        var closed = false
        var cmd = ""
        fun num() = tokens.removeAt(0).toDouble()
        while (tokens.isNotEmpty()) {
            if (tokens[0][0].isLetter()) cmd = tokens.removeAt(0)
            when (cmd) {
                "M" -> { val x = num(); val y = num(); vs += Vtx(x, y, x, y, x, y); cmd = "L" }
                "L" -> { val x = num(); val y = num(); vs += Vtx(x, y, x, y, x, y) }
                "C" -> {
                    val c1x = num(); val c1y = num(); val c2x = num(); val c2y = num(); val x = num(); val y = num()
                    vs.last().apply { outX = c1x; outY = c1y }
                    vs += Vtx(x, y, c2x, c2y, x, y)
                }
                "Z", "z" -> { closed = true; cmd = "" }
                else -> error("unsupported path command '$cmd' in $id")
            }
        }
        // A path that returns to its start ends on a duplicate of the first vertex: fold its in handle into the first.
        val first = vs.first(); val last = vs.last()
        if (closed && vs.size > 1 && abs(first.x - last.x) < 1e-3 && abs(first.y - last.y) < 1e-3) {
            first.inX = first.x + (last.inX - last.x); first.inY = first.y + (last.inY - last.y)
            vs.removeAt(vs.size - 1)
        }
        return SvgPath(id, fill, vs, closed)
    }
}
