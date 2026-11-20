package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Large backdrops: the spotlight, the 60s tie-dye swirl and the 80s neon grid. */
internal class BackdropDrawer(private val kit: ActorKit) {

  // ---- SPOTLIGHT ---------------------------------------------------------------------------------

  private val coneLen = 1400f
  private var coneColor = 0
  private var coneShader: LinearGradient? = null
  private var poolShader: RadialGradient? = null

  private fun shadersFor(color: Int) {
    if (coneShader != null && coneColor == color) return
    coneColor = color
    coneShader = LinearGradient(0f, 0f, 0f, coneLen, intArrayOf(withAlpha(color, 0.75f), withAlpha(color, 0.35f), withAlpha(color, 0.12f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    poolShader = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(color, 0.45f), withAlpha(color, 0f)), null, Shader.TileMode.CLAMP)
  }

  /** A light fixture at (0, 0) throwing a soft cone of [color] about 1400 px straight down. */
  fun spotlight(c: Canvas, color: Int, time: Float) {
    val col = color or 0xFF000000.toInt()
    shadersFor(col)
    val f = kit.fill
    // Layered cones, each wider and fainter, give a soft edge.
    val flicker = 0.96f + 0.04f * sin(time * 13f) * sin(time * 7.3f)
    for (i in 0 until 4) {
      val widen = 1f + i * 0.13f
      val p = kit.path
      p.reset()
      p.moveTo(-40f * widen, 20f); p.lineTo(40f * widen, 20f)
      p.lineTo(330f * widen, coneLen); p.lineTo(-330f * widen, coneLen); p.close()
      f.shader = coneShader; f.color = 0xFFFFFFFF.toInt(); f.alpha = ((if (i == 0) 0.42f else 0.2f) * flicker * 255).toInt()
      c.drawPath(p, f)
    }
    // Floor pool.
    c.save(); c.translate(0f, coneLen); c.scale(430f, 80f)
    f.shader = poolShader; f.color = 0xFFFFFFFF.toInt(); f.alpha = 255
    c.drawCircle(0f, 0f, 1f, f)
    c.restore()
    f.shader = null
    // Fixture: yoke, can body, rim and glowing lens.
    c.drawLine(-62f, -40f, -62f, -120f, kit.line(0xFF3A363D.toInt(), 10f))
    c.drawLine(62f, -40f, 62f, -120f, kit.line(0xFF3A363D.toInt(), 10f))
    c.drawLine(-62f, -120f, 62f, -120f, kit.line(0xFF3A363D.toInt(), 10f))
    c.drawLine(0f, -120f, 0f, -170f, kit.line(0xFF3A363D.toInt(), 12f))
    kit.rect.set(-52f, -100f, 52f, 6f)
    c.drawRoundRect(kit.rect, 22f, 22f, kit.flat(0xFF2E2B31.toInt()))
    kit.rect.set(-40f, -96f, -24f, -6f)
    c.drawRoundRect(kit.rect, 8f, 8f, kit.flat(0x26FFFFFF))
    c.drawCircle(-62f, -40f, 9f, kit.flat(0xFF56515A.toInt()))
    c.drawCircle(62f, -40f, 9f, kit.flat(0xFF56515A.toInt()))
    kit.rect.set(-58f, -6f, 58f, 22f)
    c.drawRoundRect(kit.rect, 14f, 14f, kit.flat(0xFF4A464D.toInt()))
    c.drawCircle(0f, 18f, 70f * flicker, kit.flat(withAlpha(col, 0.3f)))
    kit.rect.set(-44f, 4f, 44f, 30f)
    c.drawOval(kit.rect, kit.flat(lighten(col, 0.55f)))
    kit.rect.set(-24f, 9f, 24f, 24f)
    c.drawOval(kit.rect, kit.flat(0xFFFFFFFF.toInt()))
  }

  // ---- PEACE_BG ----------------------------------------------------------------------------------

  private val swirlR = 460f
  private val dyeColors = intArrayOf(
    0xFFFF6FA8.toInt(), 0xFFFFB547.toInt(), 0xFFFFE45C.toInt(), 0xFF7BE0A0.toInt(),
    0xFF5CC8FF.toInt(), 0xFFA07BFF.toInt(), 0xFFFF6FA8.toInt(),
  )
  private val dyeShader = ComposeShader(
    SweepGradient(0f, 0f, dyeColors, null),
    RadialGradient(0f, 0f, swirlR, intArrayOf(0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP),
    PorterDuff.Mode.DST_IN,
  )
  private val bandShader = RadialGradient(0f, 0f, swirlR, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.2f, 0.6f, 1f), Shader.TileMode.CLAMP)
  private val centreShader = RadialGradient(0f, 0f, 150f, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
  private val bands = Path().apply {
    // Five spiral arms that widen as they curl outward.
    for (arm in 0 until 5) {
      val base = arm * 72f * DEG
      val steps = 28
      for (i in 0..steps) {
        val r = 20f + (swirlR - 20f) * i / steps
        val a = base + r * 0.0105f
        val x = cos(a) * r; val y = sin(a) * r
        if (i == 0) moveTo(x, y) else lineTo(x, y)
      }
      for (i in steps downTo 0) {
        val r = 20f + (swirlR - 20f) * i / steps
        val a = base + r * 0.0105f + 0.08f + 0.32f * (r / swirlR)
        lineTo(cos(a) * r, sin(a) * r)
      }
      close()
    }
  }

  /** A soft tie-dye swirl about 920 px across, low alpha, turned by [p] (one turn per 1.0). */
  fun peaceBg(c: Canvas, p: Float, time: Float) {
    c.save()
    c.rotate(p * 360f)
    val breathe = 1f + 0.02f * sin(time * 1.5f)
    c.scale(breathe, breathe)
    val f = kit.fill
    f.shader = dyeShader; f.color = 0xFFFFFFFF.toInt(); f.alpha = 120
    c.drawCircle(0f, 0f, swirlR, f)
    f.shader = bandShader; f.alpha = 255
    c.drawPath(bands, f)
    c.rotate(36f); c.scale(0.7f, 0.7f)
    f.alpha = 150
    c.drawPath(bands, f)
    f.shader = centreShader; f.alpha = 255
    c.drawCircle(0f, 0f, 150f / 0.7f, f)
    f.shader = null
    c.restore()
  }

  // ---- NEON_GRID ---------------------------------------------------------------------------------

  private val halfW = 540f
  private val floorH = 700f
  private val sunR = 230f
  private val sunCy = -70f
  private val sunShader = LinearGradient(0f, sunCy - sunR, 0f, 0f, intArrayOf(0xFFFFE66B.toInt(), 0xFFFF9A4D.toInt(), 0xFFFF3E9A.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
  private val skyShader = LinearGradient(0f, -440f, 0f, 0f, intArrayOf(0x002A0B52, 0x882A0B52.toInt(), 0xCCFF3EA5.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
  private val floorShader = LinearGradient(0f, 0f, 0f, floorH, intArrayOf(0xF0200840.toInt(), 0xF0381060.toInt()), null, Shader.TileMode.CLAMP)

  /**
   * An 80s synthwave backdrop 1080 px wide: striped sun and sky above the horizon at y = 0, a perspective
   * grid floor below to y = 700. [p] scrolls the grid toward the viewer (six rows per 1.0).
   */
  fun neonGrid(c: Canvas, p: Float, time: Float) {
    val f = kit.fill
    kit.rect.set(-halfW, -440f, halfW, 0f)
    f.shader = skyShader; f.color = 0xFFFFFFFF.toInt()
    c.drawRect(kit.rect, f)
    f.shader = null
    // Stars.
    for (i in 0 until 14) {
      val tw = 0.4f + 0.6f * max(0f, sin(time * (1.5f + hash01(i + 5)) + i * 2.3f))
      c.drawCircle(-halfW + 1080f * hash01(i + 100), -420f + 260f * hash01(i + 200), 2f + 2f * hash01(i + 300), kit.flat(withAlpha(0xFFFFFFFF.toInt(), tw)))
    }
    // Sun glow, then the sun clipped to the horizon with stripes cut out of its lower half.
    c.drawCircle(0f, sunCy, sunR + 50f, kit.flat(0x30FF3EA5))
    c.save()
    c.clipRect(-halfW, -440f, halfW, 0f)
    for (n in 0 until 7) {
      val y = -6f - n * 24f
      val th = 13f - n * 1.7f
      if (th > 1f) c.clipOutRect(-sunR, y - th / 2f, sunR, y + th / 2f)
    }
    f.shader = sunShader; f.color = 0xFFFFFFFF.toInt()
    c.drawCircle(0f, sunCy, sunR, f)
    f.shader = null
    c.restore()
    // Floor.
    kit.rect.set(-halfW, 0f, halfW, floorH)
    f.shader = floorShader; f.color = 0xFFFFFFFF.toInt()
    c.drawRect(kit.rect, f)
    f.shader = null
    c.save()
    c.clipRect(kit.rect)
    // Horizontal lines (magenta), denser toward the horizon, scrolling toward the viewer.
    val s = fract(p * 6f)
    for (n in 0 until 26) {
      val z = 1f + n - s
      if (z < 0.35f) continue
      val y = floorH * 0.55f / z
      if (y > floorH + 10f) continue
      val a = clamp01(y / floorH * 2.2f)
      c.drawLine(-halfW, y, halfW, y, kit.line(withAlpha(0xFFFF3EC8.toInt(), a * 0.3f), 10f))
      c.drawLine(-halfW, y, halfW, y, kit.line(withAlpha(0xFFFF6AD8.toInt(), a), 3.5f))
    }
    // Vertical lines (cyan) converging on the horizon centre.
    for (i in -12..12) {
      val bx = i * 150f
      c.drawLine(i * 6f, 0f, bx, floorH, kit.line(0x4D3EF0FF, 10f))
      c.drawLine(i * 6f, 0f, bx, floorH, kit.line(0xFF3EF0FF.toInt(), 3f))
    }
    c.restore()
    // Horizon glow line.
    c.drawLine(-halfW, 0f, halfW, 0f, kit.line(0x663EF0FF, 16f))
    c.drawLine(-halfW, 0f, halfW, 0f, kit.line(0xFFB8FBFF.toInt(), 4f))
  }
}
