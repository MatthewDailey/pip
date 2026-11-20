package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.PI
import kotlin.math.sin

/**
 * Shared paints, paths and helpers for actor drawings. Every drawing is authored in pixels for a
 * 1080 px wide, 420 dpi screen and scaled by [k] so other densities keep the same proportions.
 * Drawings are pure functions of their inputs: nothing here carries state between frames except caches
 * of immutable geometry and shaders.
 */
internal class ActorKit(val density: Float) {
  val k = density / 2.625f
  val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
  }
  val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
  val path = Path()
  val path2 = Path()
  val rect = RectF()
  val rect2 = RectF()
  val matrix = Matrix()

  /** A unit four-point star (tips at distance 1) with pinched, curved sides. */
  val star = Path().apply {
    val n = 0.16f
    moveTo(0f, -1f)
    quadTo(n, -n, 1f, 0f); quadTo(n, n, 0f, 1f); quadTo(-n, n, -1f, 0f); quadTo(-n, -n, 0f, -1f); close()
  }

  /** Draws [star] centred at (x, y) with tip radius [r], turned by [deg]. */
  fun drawStar(c: Canvas, x: Float, y: Float, r: Float, deg: Float, color: Int) {
    if (r <= 0.01f) return
    c.save(); c.translate(x, y); c.rotate(deg); c.scale(r, r); c.drawPath(star, flat(color)); c.restore()
  }

  /** The fill paint with a flat colour and no shader. */
  fun flat(color: Int): Paint { fill.shader = null; fill.color = color; return fill }

  /** The stroke paint with a flat colour, width in authoring pixels and no shader. */
  fun line(color: Int, width: Float): Paint {
    stroke.shader = null; stroke.color = color; stroke.strokeWidth = width; return stroke
  }

  /** Opens a layer that fades everything drawn into it; returns the save count to restore. */
  fun fadeLayer(c: Canvas, alpha: Float, l: Float, t: Float, r: Float, b: Float): Int {
    rect2.set(l, t, r, b)
    return c.saveLayerAlpha(rect2, (clamp01(alpha) * 255).toInt())
  }
}

internal const val INK = 0xFF3A2A26.toInt()
internal const val HAT_RED = 0xFFF05248.toInt()
internal const val SKIN = 0xFFFDE3D2.toInt()
internal const val EYE_BROWN = 0xFF8B3F37.toInt()
internal const val SHADOW = 0x2E3A2A26
internal const val DEG = (PI / 180.0).toFloat()
internal const val TAU = (PI * 2.0).toFloat()

internal fun clamp01(v: Float) = v.coerceIn(0f, 1f)
internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun easeOutCubic(t: Float): Float { val u = 1f - clamp01(t); return 1f - u * u * u }
internal fun easeInOut(t: Float): Float { val x = clamp01(t); return x * x * (3f - 2f * x) }
internal fun smoothstep(e0: Float, e1: Float, x: Float) = easeInOut((x - e0) / (e1 - e0))
internal fun easeOutBack(t: Float): Float {
  val x = clamp01(t) - 1f; val s = 1.70158f
  return 1f + x * x * ((s + 1f) * x + s)
}

/** Replaces a colour's alpha with its own alpha times [a]. */
internal fun withAlpha(color: Int, a: Float): Int {
  val base = (color ushr 24) and 0xFF
  return (color and 0x00FFFFFF) or ((base * clamp01(a)).toInt() shl 24)
}

/** Deterministic pseudo-random value in 0..1 for an integer seed. */
internal fun hash01(i: Int): Float {
  val v = sin(i * 12.9898f + 78.233f) * 43758.547f
  return v - kotlin.math.floor(v)
}

/** Fractional part, always in 0..1. */
internal fun fract(v: Float) = v - kotlin.math.floor(v)

/** Scales a colour's RGB channels by [f], keeping alpha. */
internal fun darken(color: Int, f: Float): Int {
  val r = ((color shr 16) and 0xFF) * f; val g = ((color shr 8) and 0xFF) * f; val b = (color and 0xFF) * f
  return (color and 0xFF000000.toInt()) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
}

/** Mixes [color] toward white by [t], keeping alpha. */
internal fun lighten(color: Int, t: Float): Int {
  fun ch(v: Int) = (v + (255 - v) * t).toInt().coerceIn(0, 255)
  return (color and 0xFF000000.toInt()) or (ch((color shr 16) and 0xFF) shl 16) or (ch((color shr 8) and 0xFF) shl 8) or ch(color and 0xFF)
}
