package dev.playground.pip.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Path

/**
 * Pip's artwork, loaded from `assets/pip/pip.svg` (traced from the original illustration by tools/pip-art).
 *
 * Paths are kept as absolute points rather than baked [Path]s so the renderer can deform them every frame:
 * bending the hat tip, drooping it, squishing the face. Only the subset the build script writes is parsed:
 * `<path id fill d>` where every segment repeats its command letter (absolute M/L/C/Z), and `<ellipse id cx cy rx ry>`.
 */
class PipArt private constructor(val shapes: Map<String, Shape>, val ellipses: Map<String, Ellipse>) {

  /** Moves one point; writes the result into [out] (x at 0, y at 1). */
  fun interface Warp { fun apply(x: Float, y: Float, out: FloatArray) }

  /** A path as a flat command list: op codes in [ops], their points (x, y pairs) in [pts]. */
  class Shape(val color: Int, private val ops: ByteArray, private val pts: FloatArray) {
    private val a = FloatArray(2); private val b = FloatArray(2); private val c = FloatArray(2)

    /** Rebuilds [out] from this shape with every point passed through [warp]. */
    fun build(out: Path, warp: Warp) {
      out.reset()
      var p = 0
      for (op in ops) when (op) {
        MOVE -> { warp.apply(pts[p], pts[p + 1], a); p += 2; out.moveTo(a[0], a[1]) }
        LINE -> { warp.apply(pts[p], pts[p + 1], a); p += 2; out.lineTo(a[0], a[1]) }
        CUBIC -> {
          warp.apply(pts[p], pts[p + 1], a); warp.apply(pts[p + 2], pts[p + 3], b); warp.apply(pts[p + 4], pts[p + 5], c)
          p += 6
          out.cubicTo(a[0], a[1], b[0], b[1], c[0], c[1])
        }
        CLOSE -> out.close()
      }
    }
  }

  class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

  companion object {
    const val MOVE: Byte = 0
    const val LINE: Byte = 1
    const val CUBIC: Byte = 2
    const val CLOSE: Byte = 3

    @Volatile private var cached: PipArt? = null

    fun get(context: Context): PipArt = cached ?: synchronized(this) {
      cached ?: parse(context.assets.open("pip/pip.svg").bufferedReader().use { it.readText() }).also { cached = it }
    }

    fun parse(svg: String): PipArt {
      val shapes = mutableMapOf<String, Shape>()
      for (m in Regex("""<path\s+id="([^"]+)"\s+fill="([^"]+)"\s+d="([^"]+)"""").findAll(svg)) {
        val (id, fill, d) = m.destructured
        val ops = ArrayList<Byte>(); val pts = ArrayList<Float>()
        for (tok in Regex("""[MLCZ]|-?\d+(?:\.\d+)?""").findAll(d).map { it.value }) when (tok) {
          "M" -> ops += MOVE
          "L" -> ops += LINE
          "C" -> ops += CUBIC
          "Z" -> ops += CLOSE
          else -> pts += tok.toFloat()
        }
        shapes[id] = Shape(Color.parseColor(fill), ops.toByteArray(), pts.toFloatArray())
      }
      val ellipses = Regex("""<ellipse\s+id="([^"]+)"\s+cx="([^"]+)"\s+cy="([^"]+)"\s+rx="([^"]+)"\s+ry="([^"]+)"""").findAll(svg)
        .associate { m -> val (id, cx, cy, rx, ry) = m.destructured; id to Ellipse(cx.toFloat(), cy.toFloat(), rx.toFloat(), ry.toFloat()) }
      return PipArt(shapes, ellipses)
    }
  }
}
