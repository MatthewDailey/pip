package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Small effects: smoke, slash, sparkle, heart, confetti, sweat, music notes and the rain cloud. */
internal class FxDrawer(private val kit: ActorKit) {

  // ---- SMOKE -------------------------------------------------------------------------------------

  /** A ring of puffs that pops out, drifts apart and fades as [p] goes 0..1. */
  fun smoke(c: Canvas, p0: Float) {
    val p = clamp01(p0)
    if (p <= 0f) return
    val fade = 1f - smoothstep(0.3f, 1f, p)
    if (fade <= 0f) return
    val e = easeOutCubic(p)
    val pop = if (p < 0.14f) easeOutBack(p / 0.14f) else 1f
    val save = kit.fadeLayer(c, fade, -420f, -420f, 420f, 420f)
    for (layer in 0..2) {
      for (i in 0 until 9) {
        val ang = (i * 40f + p * 50f + hash01(i) * 16f) * DEG
        val dist = (24f + 150f * e) * (0.85f + 0.3f * hash01(i + 11))
        val r = (56f + 20f * hash01(i + 5)) * pop * (1f - 0.5f * e)
        val x = cos(ang) * dist
        val y = sin(ang) * dist * 0.82f - 40f * e
        when (layer) {
          0 -> c.drawCircle(x + 5f, y + 10f, r, kit.flat(0xFFCFC5BD.toInt()))
          1 -> c.drawCircle(x, y, r, kit.flat(0xFFF6F2EE.toInt()))
          else -> c.drawCircle(x - r * 0.28f, y - r * 0.3f, r * 0.38f, kit.flat(0xFFFFFFFF.toInt()))
        }
      }
      // A dense core that collapses quickly.
      val core = 78f * pop * (1f - smoothstep(0f, 0.55f, p))
      if (core > 0f) when (layer) {
        0 -> c.drawCircle(4f, 8f - 30f * e, core, kit.flat(0xFFCFC5BD.toInt()))
        1 -> c.drawCircle(0f, -30f * e, core, kit.flat(0xFFF6F2EE.toInt()))
      }
    }
    // Wisps that fly further out.
    for (i in 0 until 7) {
      val ang = (i * 51f + 20f + hash01(i + 40) * 20f) * DEG
      val dist = 60f + 250f * e * (0.8f + 0.4f * hash01(i + 50))
      val r = 16f * pop * (1f - e) + 4f
      c.drawCircle(cos(ang) * dist, sin(ang) * dist * 0.8f - 50f * e, r, kit.flat(0xFFEDE7E2.toInt()))
    }
    c.restoreToCount(save)
  }

  // ---- SLASH -------------------------------------------------------------------------------------

  private val slashCx = 0f
  private val slashCy = 100f
  private val slashR = 210f
  private val slashStart = 160f
  private val slashSweep = 220f
  private val slashTrail = 150f
  private val trailShader = SweepGradient(
    slashCx, slashCy,
    intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
    floatArrayOf(0f, slashTrail / 360f * 0.45f, slashTrail / 360f * 0.92f, slashTrail / 360f, slashTrail / 360f + 0.002f),
  )
  private val edgeShader = SweepGradient(
    slashCx, slashCy,
    intArrayOf(0x0036D6FF, 0x6036D6FF, 0xFF36D6FF.toInt(), 0x0036D6FF),
    floatArrayOf(0f, slashTrail / 360f * 0.5f, slashTrail / 360f, slashTrail / 360f + 0.002f),
  )

  /** A crescent trail that sweeps across the top of a circle, then its tail catches up and it fades. */
  fun slash(c: Canvas, p0: Float) {
    val p = clamp01(p0)
    if (p <= 0f) return
    val fade = 1f - smoothstep(0.72f, 1f, p)
    if (fade <= 0f) return
    val head = slashStart + slashSweep * easeOutCubic(p / 0.5f)
    val reach = min(slashTrail, head - slashStart)
    val trail = reach * (1f - smoothstep(0.45f, 1f, p))
    if (trail < 0.5f) return
    val tail = head - trail
    val thick = 54f * (0.35f + 0.65f * min(1f, trail / slashTrail))
    val n = 32
    val path = kit.path
    path.reset()
    for (i in 0..n) {
      val a = (tail + trail * i / n) * DEG
      val x = slashCx + cos(a) * slashR; val y = slashCy + sin(a) * slashR
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in n downTo 0) {
      val t = i.toFloat() / n
      val w = thick * sin(PI.toFloat() * t.pow(1.8f))
      val a = (tail + trail * t) * DEG
      path.lineTo(slashCx + cos(a) * (slashR - w), slashCy + sin(a) * (slashR - w))
    }
    path.close()
    kit.matrix.setRotate(head - slashTrail, slashCx, slashCy)
    trailShader.setLocalMatrix(kit.matrix)
    edgeShader.setLocalMatrix(kit.matrix)
    val f = kit.fill
    f.shader = trailShader; f.color = 0xFFFFFFFF.toInt(); f.alpha = (fade * 255).toInt()
    c.drawPath(path, f)
    f.shader = null
    // Cyan edge along the outer rim.
    kit.rect.set(slashCx - slashR, slashCy - slashR, slashCx + slashR, slashCy + slashR)
    val s = kit.stroke
    s.shader = edgeShader; s.color = 0xFFFFFFFF.toInt(); s.alpha = (fade * 255).toInt(); s.strokeWidth = 6f
    c.drawArc(kit.rect, tail, trail, false, s)
    s.shader = null
    // A glint riding the head while it sweeps.
    val glint = (1f - smoothstep(0.4f, 0.7f, p)) * fade
    if (glint > 0f) {
      val a = head * DEG
      val gx = slashCx + cos(a) * (slashR - 6f); val gy = slashCy + sin(a) * (slashR - 6f)
      kit.drawStar(c, gx, gy, 30f * glint, head, withAlpha(0xFFFFFFFF.toInt(), glint))
      kit.drawStar(c, gx, gy, 14f * glint, head + 45f, withAlpha(0xFFB8F2FF.toInt(), glint))
    }
  }

  // ---- SPARKLE -----------------------------------------------------------------------------------

  private val glow = RadialGradient(0f, 0f, 100f, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)

  /** A twinkling four-point star with two satellites; [color] tints it (white means gold). */
  fun sparkle(c: Canvas, color: Int, time: Float) {
    val tint = if (color == 0xFFFFFFFF.toInt()) 0xFFFFD447.toInt() else color
    val tw = 0.82f + 0.18f * sin(time * 6.3f)
    val f = kit.fill
    f.shader = glow; f.color = 0xFFFFFFFF.toInt()
    c.save(); c.scale(tw, tw); c.drawCircle(0f, 0f, 100f, f); c.restore()
    f.shader = null
    val rot = sin(time * 1.7f) * 10f
    kit.drawStar(c, 0f, 0f, 64f * tw, rot, tint)
    kit.drawStar(c, 0f, 0f, 30f * tw, rot, 0xFFFFFFFF.toInt())
    val s1 = max(0f, sin(time * 5.1f + 2.1f))
    val s2 = max(0f, sin(time * 4.3f + 4.0f))
    kit.drawStar(c, 62f, -54f, 20f * s1, 0f, tint)
    kit.drawStar(c, -56f, 48f, 15f * s2, 0f, tint)
  }

  // ---- HEART -------------------------------------------------------------------------------------

  private val heart = Path().apply {
    moveTo(0f, 72f)
    cubicTo(-118f, 2f, -80f, -92f, 0f, -40f)
    cubicTo(80f, -92f, 118f, 2f, 0f, 72f)
    close()
  }

  /** A glossy heart that beats gently with time; [color] tints it (white means Pip red). */
  fun heart(c: Canvas, color: Int, time: Float) {
    val main = if (color == 0xFFFFFFFF.toInt()) HAT_RED else color
    val beat = max(0f, sin(time * 7f)).let { it * it }
    val s = 1f + 0.07f * beat
    c.save(); c.scale(s, s)
    c.save(); c.translate(6f, 9f); c.drawPath(heart, kit.flat(SHADOW)); c.restore()
    c.drawPath(heart, kit.flat(darken(main, 0.82f)))
    c.save(); c.translate(-3f, -5f); c.scale(0.92f, 0.92f); c.drawPath(heart, kit.flat(main)); c.restore()
    c.save(); c.translate(-38f, -34f); c.rotate(-35f)
    kit.rect.set(-15f, -9f, 15f, 9f); c.drawOval(kit.rect, kit.flat(0x99FFFFFF.toInt()))
    c.restore()
    c.drawCircle(-14f, -46f, 5f, kit.flat(0x88FFFFFF.toInt()))
    c.restore()
  }

  // ---- CONFETTI ----------------------------------------------------------------------------------

  private val pieces = 40
  private val confColors = intArrayOf(
    HAT_RED, 0xFFFFC93C.toInt(), 0xFF2EC4B6.toInt(), 0xFF4D96FF.toInt(), 0xFFFF7AB6.toInt(), 0xFF7BD389.toInt(), 0xFFA06CD5.toInt(),
  )
  private val cVx = FloatArray(pieces); private val cVy = FloatArray(pieces)
  private val cW = FloatArray(pieces); private val cH = FloatArray(pieces)
  private val cSpin = FloatArray(pieces); private val cPhase = FloatArray(pieces)
  private val cColor = IntArray(pieces); private val cShape = IntArray(pieces)

  init {
    for (i in 0 until pieces) {
      val ang = (-90f + (hash01(i * 3 + 1) - 0.5f) * 150f) * DEG
      val speed = 900f + 1300f * hash01(i * 7 + 2)
      cVx[i] = cos(ang) * speed; cVy[i] = sin(ang) * speed
      cW[i] = 16f + 12f * hash01(i * 5 + 3); cH[i] = 9f + 5f * hash01(i * 11 + 4)
      cSpin[i] = (hash01(i * 13 + 5) - 0.5f) * 900f
      cPhase[i] = hash01(i * 17 + 6) * TAU
      cColor[i] = confColors[i % confColors.size]
      cShape[i] = (hash01(i * 19 + 7) * 3f).toInt()
    }
  }

  /** About 40 pieces burst from (0, 0) and flutter down under gravity as [p] goes 0..1. */
  fun confetti(c: Canvas, p0: Float, time: Float) {
    val p = clamp01(p0)
    if (p <= 0f) return
    val fade = 1f - smoothstep(0.8f, 1f, p)
    if (fade <= 0f) return
    val t = p * 2.6f
    val drag = 3.2f
    val spread = (1f - exp(-drag * t)) / drag
    val fall = 360f * (t - spread)
    val pop = if (p < 0.05f) p / 0.05f else 1f
    for (i in 0 until pieces) {
      val sway = sin(t * 3f + cPhase[i]) * 26f * min(1f, t)
      val x = cVx[i] * spread + sway
      val y = cVy[i] * spread + fall
      val flip = cos(time * (6f + 4f * hash01(i + 90)) + cPhase[i])
      c.save()
      c.translate(x, y)
      c.rotate(cSpin[i] * t + cPhase[i] * 57f)
      c.scale(pop, pop * flip)
      val col = withAlpha(if (flip < 0f) darken(cColor[i], 0.8f) else cColor[i], fade)
      when (cShape[i]) {
        0 -> { kit.rect.set(-cW[i] / 2, -cH[i] / 2, cW[i] / 2, cH[i] / 2); c.drawRect(kit.rect, kit.flat(col)) }
        1 -> c.drawCircle(0f, 0f, cH[i] * 0.75f, kit.flat(col))
        else -> { kit.rect.set(-cW[i] * 0.7f, -4f, cW[i] * 0.7f, 4f); c.drawRoundRect(kit.rect, 4f, 4f, kit.flat(col)) }
      }
      c.restore()
    }
  }

  // ---- SWEAT -------------------------------------------------------------------------------------

  private val drop = Path().apply {
    moveTo(0f, -50f)
    cubicTo(8f, -30f, 32f, -4f, 32f, 16f)
    cubicTo(32f, 36f, 18f, 48f, 0f, 48f)
    cubicTo(-18f, 48f, -32f, 36f, -32f, 16f)
    cubicTo(-32f, -4f, -8f, -30f, 0f, -50f)
    close()
  }

  /** A blue teardrop (about 64 x 98 px), tip up, with a glossy highlight. */
  fun sweat(c: Canvas) {
    c.save(); c.translate(3f, 5f); c.drawPath(drop, kit.flat(0x223A2A26)); c.restore()
    c.drawPath(drop, kit.flat(0xFF4BA3DE.toInt()))
    c.save(); c.translate(-2f, -3f); c.scale(0.88f, 0.9f); c.drawPath(drop, kit.flat(0xFF79C6F2.toInt())); c.restore()
    c.save(); c.translate(-12f, 14f); c.rotate(-20f)
    kit.rect.set(-6f, -13f, 6f, 13f); c.drawOval(kit.rect, kit.flat(0xCCFFFFFF.toInt()))
    c.restore()
    c.drawCircle(-6f, -14f, 4f, kit.flat(0xAAFFFFFF.toInt()))
  }

  // ---- MUSIC_NOTE --------------------------------------------------------------------------------

  private val single = Path().apply {
    // Stem and flag; the head is an oval drawn separately.
    addRect(14f, -62f, 23f, 30f, Path.Direction.CW)
    moveTo(14f, -64f)
    cubicTo(30f, -52f, 62f, -40f, 54f, 2f)
    cubicTo(52f, -22f, 38f, -32f, 14f, -36f)
    close()
  }
  private val double = Path().apply {
    addRect(-14f, -52f, -5f, 36f, Path.Direction.CW)
    addRect(50f, -70f, 59f, 22f, Path.Direction.CW)
    moveTo(-14f, -56f); lineTo(59f, -76f); lineTo(59f, -54f); lineTo(-14f, -34f); close()
  }

  /**
   * A floating note that wobbles with time. `text` containing "♫", "2" or "double" draws a beamed pair;
   * [color] tints it (white means Pip red). [seed] desynchronises notes placed at different spots.
   */
  fun musicNote(c: Canvas, text: String, color: Int, seed: Float, time: Float) {
    val main = if (color == 0xFFFFFFFF.toInt()) HAT_RED else color
    val isDouble = text.contains('♫') || text.contains('2') || text.contains("double", ignoreCase = true)
    val ph = seed
    c.save()
    c.translate(0f, sin(time * 2.4f + ph) * 9f)
    c.rotate(sin(time * 3.2f + ph) * 14f)
    val sq = 1f + 0.05f * sin(time * 6.4f + ph)
    c.scale(1f / sq, sq)
    if (isDouble) c.translate(-20f, 4f) else c.translate(-16f, 4f)
    for (pass in 0..1) {
      val col = if (pass == 0) SHADOW else main
      c.save()
      if (pass == 0) c.translate(5f, 7f)
      if (isDouble) {
        c.drawPath(double, kit.flat(col))
        noteHead(c, -32f, 36f, col, pass == 1)
        noteHead(c, 32f, 22f, col, pass == 1)
      } else {
        c.drawPath(single, kit.flat(col))
        noteHead(c, -4f, 30f, col, pass == 1)
      }
      c.restore()
    }
    c.restore()
  }

  private fun noteHead(c: Canvas, x: Float, y: Float, col: Int, shine: Boolean) {
    c.save(); c.translate(x, y); c.rotate(-24f)
    kit.rect.set(-28f, -19f, 28f, 19f); c.drawOval(kit.rect, kit.flat(col))
    if (shine) { kit.rect.set(-17f, -11f, -3f, -4f); c.drawOval(kit.rect, kit.flat(0x88FFFFFF.toInt())) }
    c.restore()
  }

  // ---- RAIN_CLOUD --------------------------------------------------------------------------------

  private val cloud = Path().apply {
    addCircle(-78f, 8f, 50f, Path.Direction.CW)
    addCircle(-28f, -26f, 62f, Path.Direction.CW)
    addCircle(36f, -18f, 56f, Path.Direction.CW)
    addCircle(86f, 12f, 44f, Path.Direction.CW)
    addRoundRect(-126f, 0f, 128f, 58f, 29f, 29f, Path.Direction.CW)
  }

  /** A grey cloud with a mopey face; drops loop with time, denser as [p] rises (none at 0). */
  fun rainCloud(c: Canvas, p0: Float, time: Float) {
    val p = clamp01(p0)
    // Rain first so the cloud's underside covers where drops are born.
    if (p > 0f) {
      val strength = min(1f, p * 4f)
      for (i in 0 until 11) {
        if (hash01(i * 7 + 3) > p * 1.15f + 0.05f) continue
        val lane = -100f + i * 20f + (hash01(i + 21) - 0.5f) * 10f
        val ph = fract(time * (1.25f + 0.4f * hash01(i + 31)) + hash01(i + 41))
        val y = 48f + ph * 240f
        val a = strength * (1f - ph * ph)
        val s = kit.line(withAlpha(0xFF5FA8E0.toInt(), a), 7f)
        c.drawLine(lane - ph * 6f, y, lane - ph * 6f - 3f, y + 22f, s)
      }
    }
    val bob = sin(time * 1.6f) * 5f
    val breathe = 1f + 0.015f * sin(time * 1.6f + 1f)
    c.save()
    c.translate(0f, bob); c.scale(breathe, 1f / breathe)
    c.save(); c.translate(0f, 10f); c.drawPath(cloud, kit.flat(0x1F3A2A26)); c.restore()
    c.save(); c.clipPath(cloud)
    c.drawPath(cloud, kit.flat(0xFF7F8893.toInt()))
    c.translate(-8f, -11f); c.drawPath(cloud, kit.flat(0xFFA2AAB3.toInt()))
    c.translate(-10f, -14f); c.scale(0.55f, 0.5f); c.drawPath(cloud, kit.flat(0x33FFFFFF))
    c.restore()
    // Mopey face: heavy-lidded eyes, sad brows and a small frown.
    val eye = 0xFF4A4F57.toInt()
    for (sx in intArrayOf(-1, 1)) {
      val ex = 34f * sx
      kit.rect.set(ex - 11f, 4f, ex + 11f, 26f)
      c.drawArc(kit.rect, 0f, 180f, true, kit.flat(eye))
      c.drawLine(ex - 13f * sx, 2f, ex + 13f * sx, -5f, kit.line(eye, 4f))
    }
    kit.rect.set(-14f, 34f, 14f, 52f)
    c.drawArc(kit.rect, 200f, 140f, false, kit.line(eye, 4.5f))
    c.restore()
  }
}
