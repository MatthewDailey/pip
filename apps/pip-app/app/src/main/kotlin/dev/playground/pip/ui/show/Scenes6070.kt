package dev.playground.pip.ui.show

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The 1960s and 1970s: each decade is a medley of three iconic moments played inside a framed "set" behind Pip,
 * joined by whip pans, a spinning pop-art wipe, a hop and a poof. Every set piece is drawn here as a CUSTOM actor.
 */
fun sixtiesSeventies(): List<Scene> = listOf(sixties(), seventies())

private const val HOME_X = 540f
private const val HOME_Y = 1350f

// The set panel: a framed window centred behind Pip. Its local floor (Pip's feet) is y = 200.
private const val PCX = 540f
private const val PCY = 1150f
private const val PHW = 470f
private const val PHH = 380f
private const val PR = 64f

private const val POP_RED = 0xFFE8322B.toInt()
private const val POP_BLUE = 0xFF2A55C7.toInt()
private const val POP_YELLOW = 0xFFFFE14D.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** A whip pan: a slow start, nearly all the travel in a blur, then a hard brake. */
private val whip = Easing.cubicBezier(0.7f, 0f, 0.15f, 1f)
/** A punch or flick: almost instant, then a short settle. */
private val snap = Easing.cubicBezier(0.08f, 0.9f, 0.2f, 1f)
private val stride = Easing.cubicBezier(0.35f, 0.1f, 0.65f, 0.9f)

private typealias SetFn = (Canvas, Float) -> Unit
private typealias PaintFn = (Canvas, ActorState, Float) -> Unit

// ---------------------------------------------------------------------------------------------------------------
// Shared drawing state (allocated once; every drawing is a pure function of its inputs).
// ---------------------------------------------------------------------------------------------------------------

private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; strokeJoin = Paint.Join.ROUND }
private val pa = Path()
private val pb = Path()
private val rc = RectF()
private val frameShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x50000000; maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL) }
private val panelClip = Path().apply { addRoundRect(-PHW, -PHH, PHW, PHH, PR, PR, Path.Direction.CW) }
private val panelClipScreen = Path().apply { addRoundRect(PCX - PHW, PCY - PHH, PCX + PHW, PCY + PHH, PR, PR, Path.Direction.CW) }
private val popType: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
private val groovyType: Typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)

private fun ci(vararg v: Long) = IntArray(v.size) { v[it].toInt() }
private fun lg(y0: Float, y1: Float, colors: IntArray, stops: FloatArray? = null) =
  LinearGradient(0f, y0, 0f, y1, colors, stops, Shader.TileMode.CLAMP)

private fun solid(color: Int): Paint { fp.shader = null; fp.color = color; return fp }
private fun shade(s: Shader): Paint { fp.shader = s; fp.color = WHITE; return fp }
private fun ln(color: Int, w: Float): Paint { sp.shader = null; sp.color = color; sp.strokeWidth = w; return sp }
private fun mix(a: Int, b: Int, t: Float): Int {
  val u = clamp01(t)
  fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u)).toInt() shl s
  return ch(24) or ch(16) or ch(8) or ch(0)
}
private fun fillBg(c: Canvas, p: Paint) = c.drawRect(-PHW - 12f, -PHH - 12f, PHW + 12f, PHH + 12f, p)

/** A thin four-point twinkle. */
private fun glint(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
  if (r <= 0.5f) return
  pb.reset()
  pb.moveTo(x, y - r); pb.quadTo(x + r * 0.12f, y - r * 0.12f, x + r, y); pb.quadTo(x + r * 0.12f, y + r * 0.12f, x, y + r)
  pb.quadTo(x - r * 0.12f, y + r * 0.12f, x - r, y); pb.quadTo(x - r * 0.12f, y - r * 0.12f, x, y - r); pb.close()
  c.drawPath(pb, solid(color))
}

/** Moves the canvas into Pip's own 540 x 660 box, exactly as his renderer does. */
private fun pipTransform(c: Canvas, p: PipState) {
  val k = p.size / 540f
  c.translate(p.x, p.y); c.rotate(p.rotation); c.scale(k * p.squashX * p.facing, k * p.squashY); c.translate(-270f, -660f)
}

private val pt = FloatArray(2)
private fun pipPoint(p: PipState, bx: Float, by: Float): FloatArray {
  val k = p.size / 540f
  val lx = (bx - 270f) * k * p.squashX * p.facing; val ly = (by - 660f) * k * p.squashY
  val r = p.rotation * DEG
  pt[0] = p.x + lx * cos(r) - ly * sin(r); pt[1] = p.y + lx * sin(r) + ly * cos(r)
  return pt
}

// ---------------------------------------------------------------------------------------------------------------
// The set panel: progress n.f shows set n sliding toward set n+1 (a whip pan), with speed streaks.
// ---------------------------------------------------------------------------------------------------------------

private fun panelPaint(t0: Float, sets: Array<SetFn>, vertical: BooleanArray, frame: Boolean): PaintFn = { c, a, time ->
  val lt = time - t0
  val p = a.progress.coerceIn(0f, sets.size - 1f)
  val i = floor(p).toInt().coerceAtMost(sets.size - 1)
  val f = if (i >= sets.size - 1) 0f else p - i
  if (frame) c.drawRoundRect(-PHW + 6f, -PHH + 22f, PHW - 6f, PHH + 26f, PR, PR, frameShadow)
  c.save(); c.clipPath(panelClip)
  if (f < 0.001f) sets[i](c, lt) else {
    val v = vertical[i]
    val ox = if (v) 0f else -f * 2f * PHW
    val oy = if (v) f * 2f * PHH else 0f
    c.save(); c.translate(ox, oy); sets[i](c, lt); c.restore()
    c.save(); c.translate(if (v) 0f else ox + 2f * PHW, if (v) oy - 2f * PHH else 0f); sets[i + 1](c, lt); c.restore()
    if (frame) streaks(c, f, v)
  }
  c.restore()
  if (frame) {
    c.drawRoundRect(-PHW, -PHH, PHW, PHH, PR, PR, ln(0xFFFFF8EE.toInt(), 12f))
    c.drawRoundRect(-PHW - 7f, -PHH - 7f, PHW + 7f, PHH + 7f, PR + 7f, PR + 7f, ln(0x333A2A26, 2f))
  }
}

private fun streaks(c: Canvas, f: Float, vertical: Boolean) {
  val k = sin(PI.toFloat() * f)
  for (j in 0 until 24) {
    val across = -PHH + hash01(j * 5 + 3) * 2f * PHH
    val len = 140f + hash01(j * 11 + 1) * 420f
    val along = -PHW - len + fract(hash01(j * 7 + 2) + f * 1.7f) * (2f * PHW + 2f * len)
    val p = ln(withAlpha(WHITE, (0.25f + 0.45f * hash01(j * 3)) * k), 3f + 7f * hash01(j * 13))
    if (vertical) c.drawLine(across, along, across, along + len, p) else c.drawLine(along, across, along + len, across, p)
  }
}

private fun noSet(c: Canvas, lt: Float) {}

// ---------------------------------------------------------------------------------------------------------------
// 1960s sets
// ---------------------------------------------------------------------------------------------------------------

private val spaceSky = lg(-PHH, 160f, ci(0xFF05081D, 0xFF141846, 0xFF2A2E62), floatArrayOf(0f, 0.6f, 1f))
private val moonDirt = lg(100f, PHH, ci(0xFFE2E4EC, 0xFFB9BCCB, 0xFF8A8EA3), floatArrayOf(0f, 0.45f, 1f))
private val earthGlow = RadialGradient(300f, -235f, 112f, ci(0x667FC4FF, 0x227FC4FF, 0x007FC4FF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
private val earthSea = LinearGradient(250f, -290f, 350f, -180f, 0xFF62B2F5.toInt(), 0xFF2A5FB5.toInt(), Shader.TileMode.CLAMP)
private val craters = floatArrayOf(-330f, 268f, 66f, -150f, 345f, 46f, 250f, 312f, 86f, 400f, 196f, 38f, -250f, 182f, 30f, 92f, 172f, 22f, -20f, 300f, 16f)

private fun moonSet(c: Canvas, lt: Float) {
  fillBg(c, shade(spaceSky))
  for (i in 0 until 50) {
    val x = -PHW + hash01(i * 7 + 1) * 2f * PHW; val y = -PHH + hash01(i * 13 + 5) * 500f
    val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(lt * (1.3f + 3f * hash01(i * 3)) + i))
    c.drawCircle(x, y, 1.4f + 2.4f * hash01(i * 3 + 2), solid(withAlpha(WHITE, tw)))
    if (i % 8 == 0) glint(c, x, y, 13f * tw, withAlpha(0xFFEAF4FF.toInt(), 0.8f))
  }
  // Earth, slowly turning.
  c.drawCircle(300f, -235f, 112f, shade(earthGlow))
  c.save(); pa.reset(); pa.addCircle(300f, -235f, 58f, Path.Direction.CW); c.clipPath(pa)
  c.drawCircle(300f, -235f, 58f, shade(earthSea))
  for (j in 0 until 5) {
    val bx = 225f + fract(hash01(j * 3) + lt * 0.035f) * 170f; val by = -272f + hash01(j * 5 + 1) * 70f
    rc.set(bx - 24f, by - 12f - 6f * hash01(j), bx + 20f, by + 14f); c.drawOval(rc, solid(0xFF69C489.toInt()))
  }
  for (j in 0 until 3) {
    val bx = 230f + fract(hash01(j * 9 + 4) + lt * 0.05f) * 160f; val by = -262f + j * 22f
    c.drawLine(bx - 22f, by, bx + 18f, by - 4f, ln(0xBBFFFFFF.toInt(), 5f))
  }
  c.drawCircle(327f, -226f, 60f, solid(0x99050822.toInt()))
  c.restore()
  c.drawCircle(300f, -235f, 59f, ln(0x66BFE3FF, 3f))
  // Far ridge with the lander parked on it.
  pa.reset(); pa.moveTo(-PHW - 12f, 118f); pa.lineTo(-400f, 96f); pa.quadTo(-300f, 66f, -220f, 100f); pa.quadTo(-120f, 120f, -40f, 98f)
  pa.quadTo(80f, 68f, 170f, 100f); pa.quadTo(300f, 124f, 380f, 88f); pa.lineTo(PHW + 12f, 104f); pa.lineTo(PHW + 12f, 220f); pa.lineTo(-PHW - 12f, 220f); pa.close()
  c.drawPath(pa, solid(0xFF5A5E78.toInt()))
  lander(c, -318f, 110f, 0.62f)
  // The near ground: a gentle dome.
  pa.reset(); pa.moveTo(-PHW - 12f, 150f); pa.quadTo(0f, 106f, PHW + 12f, 142f); pa.lineTo(PHW + 12f, PHH + 12f); pa.lineTo(-PHW - 12f, PHH + 12f); pa.close()
  c.drawPath(pa, shade(moonDirt))
  pb.reset(); pb.moveTo(-PHW - 12f, 150f); pb.quadTo(0f, 106f, PHW + 12f, 142f)
  c.drawPath(pb, ln(0x88FFFFFF.toInt(), 3f))
  var i = 0
  while (i < craters.size) { crater(c, craters[i], craters[i + 1], craters[i + 2]); i += 3 }
  // Footprints leading to where Pip landed.
  for (k in 0 until 9) {
    val u = k / 8f
    val x = lerp(-440f, -90f, u); val y = lerp(338f, 214f, u) + (if (k % 2 == 0) -9f else 9f)
    val s = lerp(1.1f, 0.6f, u)
    c.save(); c.translate(x, y); c.rotate(-18f); c.scale(s, s)
    rc.set(-15f, -6f, 15f, 6f); c.drawRoundRect(rc, 6f, 6f, solid(0x668A8EA3))
    c.restore()
  }
}

private fun crater(c: Canvas, x: Float, y: Float, r: Float) {
  val ry = r * 0.34f
  rc.set(x - r, y - ry, x + r, y + ry); c.drawOval(rc, solid(0xFFEDEEF4.toInt()))
  rc.set(x - r * 0.86f, y - ry * 0.78f, x + r * 0.86f, y + ry * 0.7f); c.drawOval(rc, solid(0xFF8C90A6.toInt()))
  rc.set(x - r * 0.6f, y - ry * 0.15f, x + r * 0.72f, y + ry * 0.62f); c.drawOval(rc, solid(0xFFB2B6C7.toInt()))
}

private fun lander(c: Canvas, x: Float, y: Float, s: Float) {
  c.save(); c.translate(x, y); c.scale(s, s)
  val leg = ln(0xFFB9BDCB.toInt(), 5f)
  c.drawLine(-38f, -6f, -72f, 34f, leg); c.drawLine(38f, -6f, 72f, 34f, leg); c.drawLine(-14f, 0f, -24f, 38f, leg); c.drawLine(14f, 0f, 24f, 38f, leg)
  rc.set(-84f, 30f, -60f, 40f); c.drawOval(rc, solid(0xFFB9BDCB.toInt())); rc.set(60f, 30f, 84f, 40f); c.drawOval(rc, solid(0xFFB9BDCB.toInt()))
  pa.reset(); pa.moveTo(-34f, -34f); pa.lineTo(34f, -34f); pa.lineTo(46f, -18f); pa.lineTo(46f, 2f); pa.lineTo(34f, 10f); pa.lineTo(-34f, 10f); pa.lineTo(-46f, 2f); pa.lineTo(-46f, -18f); pa.close()
  c.drawPath(pa, solid(0xFFD9A441.toInt()))
  rc.set(-40f, -28f, 40f, -16f); c.drawRect(rc, solid(0xFFF2D27A.toInt()))
  rc.set(-28f, -76f, 28f, -34f); c.drawRoundRect(rc, 8f, 8f, solid(0xFFCDD0DA.toInt()))
  rc.set(-12f, -66f, 6f, -50f); c.drawRoundRect(rc, 4f, 4f, solid(0xFF2A2E48.toInt()))
  c.drawLine(18f, -76f, 30f, -100f, ln(0xFFB9BDCB.toInt(), 3f)); c.drawCircle(30f, -102f, 5f, solid(0xFFE8EAF0.toInt()))
  c.restore()
}

private val londonSky = lg(-PHH, -10f, ci(0xFF86C3EC, 0xFFC9E5F2, 0xFFF1F4EA), floatArrayOf(0f, 0.6f, 1f))
private val roadShade = lg(-20f, PHH, ci(0xFF8A8D93, 0xFF5E626A), null)
private const val HORIZON = -20f
private const val VPX = 40f
private fun halfRoad(y: Float) = (y - HORIZON) / (PHH + 10f - HORIZON) * 820f

private fun zebraSet(c: Canvas, lt: Float) {
  fillBg(c, shade(londonSky))
  // A terrace of townhouses on the horizon.
  c.drawRect(-PHW - 12f, -150f, PHW + 12f, HORIZON, solid(0xFFEADCC2.toInt()))
  for (h in 0 until 9) {
    val hx = -PHW + h * 110f
    c.drawRect(hx, -168f + 10f * hash01(h), hx + 104f, -150f, solid(0xFFD9C6A6.toInt()))
    for (w in 0 until 3) for (r in 0 until 2) {
      rc.set(hx + 14f + w * 30f, -132f + r * 46f, hx + 32f + w * 30f, -104f + r * 46f); c.drawRect(rc, solid(0xFF7E90A3.toInt()))
    }
    c.drawLine(hx + 104f, -168f, hx + 104f, HORIZON, ln(0x22000000, 2f))
  }
  // Pavement, then the road fanning toward us.
  c.drawRect(-PHW - 12f, HORIZON, PHW + 12f, PHH + 12f, solid(0xFFD3CFC6.toInt()))
  pa.reset(); pa.moveTo(VPX - 30f, HORIZON); pa.lineTo(VPX + 30f, HORIZON)
  pa.lineTo(VPX + halfRoad(PHH + 10f), PHH + 10f); pa.lineTo(VPX - halfRoad(PHH + 10f), PHH + 10f); pa.close()
  c.drawPath(pa, shade(roadShade))
  for (s in intArrayOf(-1, 1)) c.drawLine(VPX + s * 30f, HORIZON, VPX + s * halfRoad(PHH + 10f), PHH + 10f, ln(0xFFF4F1EA.toInt(), 6f))
  // Centre dashes up to the crossing.
  for (d in 0 until 5) {
    val y0 = lerp(HORIZON + 6f, 140f, d / 5f); val y1 = y0 + lerp(6f, 18f, d / 5f)
    c.drawLine(VPX, y0, VPX, y1, ln(0xCCF4F1EA.toInt(), lerp(2f, 6f, d / 5f)))
  }
  // The crossing: bars laid along the road, so a walker steps over them one by one.
  val yf = 152f; val yn = 268f
  for (j in 0 until 11) {
    val u0 = -0.94f + j * 0.176f; val u1 = u0 + 0.096f
    pa.reset()
    pa.moveTo(VPX + u0 * halfRoad(yf), yf); pa.lineTo(VPX + u1 * halfRoad(yf), yf)
    pa.lineTo(VPX + u1 * halfRoad(yn), yn); pa.lineTo(VPX + u0 * halfRoad(yn), yn); pa.close()
    c.save(); c.translate(0f, 4f); c.drawPath(pa, solid(0x33000000)); c.restore()
    c.drawPath(pa, solid(0xFFF8F6F0.toInt()))
  }
  // Lollipop trees behind the pavements.
  tree(c, -420f, -36f, 74f); tree(c, -290f, -28f, 46f); tree(c, 330f, -30f, 54f); tree(c, 446f, -44f, 84f)
  // Striped beacon poles with blinking amber globes.
  val blink = 0.5f + 0.5f * sin(lt * 6.4f)
  beacon(c, -372f, 176f, blink); beacon(c, 446f, 176f, 1f - blink)
}

private fun tree(c: Canvas, x: Float, y: Float, r: Float) {
  c.drawRect(x - r * 0.08f, y, x + r * 0.08f, y + r * 1.2f, solid(0xFF7A5A42.toInt()))
  c.drawCircle(x, y - r * 0.2f, r, solid(0xFF4F9A5A.toInt()))
  c.drawCircle(x - r * 0.28f, y - r * 0.42f, r * 0.62f, solid(0xFF67B36E.toInt()))
}

private fun beacon(c: Canvas, x: Float, y: Float, glow: Float) {
  val top = y - 196f
  for (b in 0 until 8) c.drawRect(x - 7f, top + 24f + b * 21.5f, x + 7f, top + 24f + (b + 1) * 21.5f, solid(if (b % 2 == 0) 0xFF2A2523.toInt() else WHITE))
  c.drawCircle(x, top, 46f, solid(withAlpha(0xFFFFB13B.toInt(), 0.35f * glow)))
  c.drawCircle(x, top, 24f, solid(mix(0xFFC77A1C.toInt(), 0xFFFFC04D.toInt(), glow)))
  c.drawCircle(x - 7f, top - 8f, 7f, solid(withAlpha(WHITE, 0.5f + 0.4f * glow)))
}

private fun comicSet(c: Canvas, lt: Float) {
  c.save(); c.rotate(-7f, 0f, 60f) // the villain-lair Dutch angle
  fillBg(c, solid(0xFFFFC531.toInt()))
  for (r in 0 until 24) {
    if (r % 2 == 1) continue
    pa.reset(); pa.moveTo(0f, -40f); pa.arcTo(-900f, -940f, 900f, 860f, r * 15f + lt * 6f, 15f, false); pa.close()
    c.drawPath(pa, solid(0xFFFFDC55.toInt()))
  }
  var gy = -PHH - 80f
  while (gy < PHH + 80f) {
    var gx = -PHW - 80f
    while (gx < PHW + 80f) {
      val d = clamp01((gx + gy + 700f) / 1500f)
      c.drawCircle(gx, gy, 1.5f + 8f * d, solid(0x40E8322B))
      gx += 34f
    }
    gy += 34f
  }
  c.drawRect(-PHW - 90f, 200f, PHW + 90f, PHH + 120f, solid(0xFF5E2B8C.toInt()))
  gy = 214f
  while (gy < PHH + 100f) {
    var gx = -PHW - 80f
    while (gx < PHW + 80f) { c.drawCircle(gx, gy, 5f, solid(0xFF7C45B0.toInt())); gx += 30f }
    gy += 30f
  }
  c.drawLine(-PHW - 90f, 200f, PHW + 90f, 200f, ln(INK, 8f))
  c.restore()
}

/** The spinning red-and-blue pop-art wipe; progress 0..0.5 covers the set, 0.5..1 uncovers it. */
private fun starburstPaint(t0: Float): PaintFn = { c, a, time ->
  val s = a.progress
  if (s > 0f && s < 1f) {
    val cover = if (s < 0.5f) s * 2f else (1f - s) * 2f
    val r = 760f * easeOutCubic(cover)
    c.save(); c.clipPath(panelClip); c.translate(0f, 40f); c.rotate((time - t0) * 540f)
    for (i in 0 until 16) {
      pa.reset(); pa.moveTo(0f, 0f); pa.arcTo(-r, -r, r, r, i * 22.5f, 22.5f, false); pa.close()
      c.drawPath(pa, solid(if (i % 2 == 0) POP_RED else POP_BLUE))
    }
    c.drawCircle(0f, 0f, r * 0.2f, solid(POP_YELLOW)); c.drawCircle(0f, 0f, r * 0.2f, ln(INK, 8f))
    c.restore()
  }
}

/** A jagged comic sound-effect burst with a word in it. */
private fun burstPaint(word: String, bg: Int, ring: Int, fg: Int, seed: Int): PaintFn {
  val star = Path()
  val n = 13
  for (i in 0 until n * 2) {
    val r = if (i % 2 == 0) 150f + hash01(seed * 31 + i) * 46f else 104f + hash01(seed * 17 + i) * 16f
    val ang = i * PI.toFloat() / n - PI.toFloat() / 2f
    val x = cos(ang) * r * 1.18f; val y = sin(ang) * r * 0.86f
    if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
  }
  star.close()
  return { c, _, _ ->
    c.save(); c.translate(12f, 14f); c.drawPath(star, solid(0x553A2A26)); c.restore()
    c.drawPath(star, solid(ring))
    c.save(); c.scale(0.78f, 0.78f); c.drawPath(star, solid(bg)); c.restore()
    c.drawPath(star, ln(INK, 9f))
    tp.typeface = popType; tp.textSize = 96f; tp.textSkewX = -0.16f
    tp.style = Paint.Style.STROKE; tp.strokeWidth = 16f; tp.color = INK; c.drawText(word, 0f, 34f, tp)
    tp.style = Paint.Style.FILL; tp.color = fg; c.drawText(word, 0f, 34f, tp)
    tp.textSkewX = 0f
  }
}

// --- 1960s props that ride on Pip ---

private val helmetGlass = RadialGradient(285f, 360f, 340f, ci(0x00FFFFFF, 0x0CBFE6FF, 0x50D6F0FF), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
private val collarShade = lg(640f, 708f, ci(0xFFF4F6FA, 0xFFC5CAD6), null)

/** A fishbowl space helmet around all of Pip; progress is its pop-in scale. */
private fun helmetPaint(pip: PipState): PaintFn = { c, a, _ ->
  if (pip.visible && a.progress > 0f) {
    c.save(); pipTransform(c, pip)
    c.scale(a.progress, a.progress, 285f, 380f)
    c.drawCircle(285f, 355f, 340f, shade(helmetGlass))
    c.drawCircle(285f, 355f, 340f, ln(0xAAFFFFFF.toInt(), 7f))
    rc.set(285f - 296f, 355f - 296f, 285f + 296f, 355f + 296f)
    c.drawArc(rc, 196f, 62f, false, ln(0xCCFFFFFF.toInt(), 18f))
    c.drawArc(rc, 266f, 10f, false, ln(0xAAFFFFFF.toInt(), 14f))
    c.drawCircle(498f, 470f, 10f, solid(0x88FFFFFF.toInt()))
    rc.set(64f, 640f, 506f, 708f)
    c.drawRoundRect(rc, 34f, 34f, shade(collarShade)); c.drawRoundRect(rc, 34f, 34f, ln(0xFF969DAD.toInt(), 6f))
    c.drawLine(92f, 676f, 478f, 676f, ln(0x55969DAD, 4f))
    c.drawCircle(430f, 662f, 10f, solid(HAT_RED))
    c.restore()
  }
}

private val shieldPath = Path().apply {
  moveTo(0f, -22f); cubicTo(10f, -16f, 16f, -16f, 20f, -17f); lineTo(20f, 0f); cubicTo(20f, 12f, 10f, 20f, 0f, 25f)
  cubicTo(-10f, 20f, -20f, 12f, -20f, 0f); lineTo(-20f, -17f); cubicTo(-16f, -16f, -10f, -16f, 0f, -22f); close()
}
private val heartPath = Path().apply { moveTo(0f, 9f); cubicTo(-15f, -2f, -10f, -15f, 0f, -7f); cubicTo(10f, -15f, 15f, -2f, 0f, 9f); close() }

/** A stiff moon flag (its rod holds the cloth out). progress < 0.5: held in Pip's right hand; else planted at x, y. */
private fun flagPaint(pip: PipState): PaintFn = { c, a, time ->
  c.save()
  c.rotate(-a.rotation); c.translate(-a.x, -a.y)
  c.clipPath(panelClipScreen)
  var draw = true
  if (a.progress < 0.5f) {
    if (pip.right.kind == Hand.NONE || !pip.visible) draw = false
    else { val q = pipPoint(pip, pip.right.x, pip.right.y); c.translate(q[0], q[1]); c.rotate(pip.rotation); c.translate(0f, 95f) }
  } else { c.translate(a.x, a.y); c.rotate(a.rotation) }
  if (draw) {
    c.drawLine(0f, 0f, 0f, -320f, ln(0xFFC3C8D3.toInt(), 8f)); c.drawLine(-2f, -10f, -2f, -316f, ln(0x99FFFFFF.toInt(), 2f))
    c.drawCircle(0f, -324f, 7f, solid(0xFFD9A441.toInt()))
    c.drawLine(0f, -312f, 132f, -312f, ln(0xFFC3C8D3.toInt(), 5f))
    pa.reset(); pa.moveTo(4f, -310f); pa.lineTo(130f, -310f)
    var x = 130f
    while (x >= 4f) { pa.lineTo(x, -226f + 3f * sin(x * 0.09f + time * 1.1f)); x -= 9f }
    pa.close()
    c.drawPath(pa, solid(HAT_RED))
    c.drawRect(44f, -308f, 54f, -228f, solid(0x22000000)); c.drawRect(92f, -308f, 100f, -228f, solid(0x18FFFFFF))
    c.save(); c.translate(68f, -268f); c.scale(0.9f, 0.9f)
    c.drawPath(shieldPath, solid(WHITE)); c.drawPath(heartPath, solid(HAT_RED))
    c.restore()
  }
  c.restore()
}

/** Little floating walking shoes for the crossing; progress is the stride phase (whole numbers = feet spread wide). */
private fun feetPaint(pip: PipState): PaintFn = { c, a, _ ->
  if (pip.visible) {
    c.save(); pipTransform(c, pip)
    val ph = a.progress * PI.toFloat()
    val ca = cos(ph); val sa = sin(ph)
    val liftA = max(0f, -sa); val liftB = max(0f, sa)
    // The lifted foot is the one swinging through; draw the planted one first.
    if (liftA > liftB) { shoe(c, 270f - 120f * ca, 672f - liftB * 50f, -liftB * 16f); shoe(c, 270f + 120f * ca, 672f - liftA * 50f, -liftA * 16f) }
    else { shoe(c, 270f + 120f * ca, 672f - liftA * 50f, -liftA * 16f); shoe(c, 270f - 120f * ca, 672f - liftB * 50f, -liftB * 16f) }
    c.restore()
  }
}

private fun shoe(c: Canvas, x: Float, y: Float, tilt: Float) {
  c.save(); c.translate(x, y); c.rotate(tilt)
  rc.set(-50f, -28f, 60f, 14f); c.drawRoundRect(rc, 22f, 22f, solid(0xFF4A3530.toInt()))
  rc.set(12f, -24f, 54f, -2f); c.drawOval(rc, solid(0x30FFFFFF))
  c.drawLine(-46f, 12f, 56f, 12f, ln(0xFF2A1E1B.toInt(), 9f))
  c.restore()
}

private fun banner60(c: Canvas, a: ActorState, time: Float) {
  c.save(); c.rotate(-4f)
  rc.set(-270f, -84f, 270f, 84f)
  c.save(); c.translate(12f, 12f); c.drawRoundRect(rc, 26f, 26f, solid(INK)); c.restore()
  c.drawRoundRect(rc, 26f, 26f, solid(POP_YELLOW))
  c.save(); pa.reset(); pa.addRoundRect(rc, 26f, 26f, Path.Direction.CW); c.clipPath(pa)
  var gy = -84f
  while (gy <= 84f) { var gx = -270f; while (gx <= 270f) { c.drawCircle(gx, gy, 1f + 6f * clamp01((gx + 270f) / 540f), solid(0x40E8322B)); gx += 22f }; gy += 22f }
  c.restore()
  c.drawRoundRect(rc, 26f, 26f, ln(INK, 7f))
  tp.typeface = popType; tp.textSize = 132f; tp.textSkewX = 0f
  tp.style = Paint.Style.FILL; tp.color = INK; c.drawText("1960s", 8f, 54f, tp)
  tp.style = Paint.Style.STROKE; tp.strokeWidth = 13f; c.drawText("1960s", 0f, 46f, tp)
  tp.style = Paint.Style.FILL; tp.color = POP_RED; c.drawText("1960s", 0f, 46f, tp)
  c.restore()
}

// ---------------------------------------------------------------------------------------------------------------
// 1970s sets
// ---------------------------------------------------------------------------------------------------------------

private val seaSky = lg(-PHH, -10f, ci(0xFFE0705A, 0xFFF39F5E, 0xFFFFE1A6), floatArrayOf(0f, 0.5f, 1f))
private val retroSun = lg(-140f, -10f, ci(0xFFFFE27C, 0xFFFF5E3A), null)
private val seaWater = lg(-10f, PHH, ci(0xFF6CC3D6, 0xFF2B88AE, 0xFF0D466E), floatArrayOf(0f, 0.35f, 1f))
private const val WATER_Y = 120f
private fun waveY(x: Float, lt: Float) = WATER_Y + 5f * sin(x * 0.018f + lt * 2.6f) + 3f * sin(x * 0.041f - lt * 1.7f)

private fun oceanBack(c: Canvas, lt: Float) {
  fillBg(c, shade(seaSky))
  // A striped retro sunset.
  c.save(); c.clipRect(-PHW, -PHH, PHW, -10f)
  for (g in 0 until 4) { val gy = -66f + g * 15f; c.clipOutRect(-200f, gy, 200f, gy + 3f + g * 2.4f) }
  c.drawCircle(0f, -10f, 130f, shade(retroSun))
  c.restore()
  for (g in 0..1) {
    val gx = -260f + g * 90f + lt * 16f + sin(lt * 0.6f + g) * 20f; val gy = -230f + g * 34f + sin(lt * 2f + g) * 6f
    val flap = sin(lt * 7f + g * 2f) * 8f
    pb.reset(); pb.moveTo(gx - 16f, gy + flap * 0.3f); pb.quadTo(gx - 8f, gy - 8f - flap, gx, gy); pb.quadTo(gx + 8f, gy - 8f - flap, gx + 16f, gy + flap * 0.3f)
    c.drawPath(pb, ln(0xB33A2A26.toInt(), 3f))
  }
  c.drawRect(-PHW - 12f, -10f, PHW + 12f, PHH + 12f, shade(seaWater))
  for (i in 0 until 7) {
    val y = -2f + i * 9f; val w = 120f - i * 13f + sin(lt * 3f + i) * 10f
    rc.set(-w, y, w, y + 3.5f); c.drawRoundRect(rc, 2f, 2f, solid(0x99FFD27A.toInt()))
  }
  for (i in 0 until 30) {
    val d = hash01(i * 3 + 7); val y = 2f + d * d * 380f; val w = 8f + d * 56f
    val x = -PHW + fract(hash01(i * 5) + lt * 0.02f * (1f + d)) * 2f * PHW
    c.drawLine(x - w, y, x + w, y, ln(withAlpha(WHITE, 0.25f + 0.2f * sin(lt * 2f + i)), 2f + d * 3f))
  }
  // A bobbing buoy far out.
  val by = 12f + sin(lt * 2.2f) * 4f
  c.save(); c.translate(360f, by); c.rotate(sin(lt * 1.7f) * 6f)
  pa.reset(); pa.moveTo(-16f, 0f); pa.lineTo(-8f, -40f); pa.lineTo(8f, -40f); pa.lineTo(16f, 0f); pa.close()
  c.drawPath(pa, solid(0xFFE8452E.toInt())); c.drawRect(-12f, -24f, 12f, -16f, solid(WHITE))
  c.drawCircle(0f, -46f, 5f, solid(0xFFFFE27C.toInt()))
  c.restore()
}

/** The water in front of Pip, so he can stand waist deep, dive and surface. */
private fun oceanFront(c: Canvas, lt: Float) {
  pa.reset(); pb.reset()
  var x = -PHW - 12f
  pa.moveTo(x, PHH + 12f); pa.lineTo(x, waveY(x, lt)); pb.moveTo(x, waveY(x, lt))
  while (x < PHW + 12f) { x += 16f; pa.lineTo(x, waveY(x, lt)); pb.lineTo(x, waveY(x, lt)) }
  pa.lineTo(x, PHH + 12f); pa.close()
  c.drawPath(pa, shade(seaWater))
  c.save(); c.translate(0f, 12f); c.drawPath(pb, ln(0x26FFFFFF, 18f)); c.restore()
  c.drawPath(pb, ln(0xDDFFFFFF.toInt(), 6f))
}

/** Pip's hat tip as a shark fin cutting the water; progress is its heading (1 right, -1 left). */
private fun finPaint(): PaintFn = { c, a, time ->
  val dir = a.progress
  rc.set(-100f, 8f, 100f, 46f); c.drawOval(rc, solid(0x40062A44))
  for (i in 0 until 4) {
    val back = -(if (dir >= 0f) 1f else -1f) * (46f + i * 40f)
    val w = 26f - i * 4f + 3f * sin(time * 9f + i)
    rc.set(back - w, -4f, back + w, 8f); c.drawOval(rc, solid(withAlpha(WHITE, 0.8f - i * 0.18f)))
  }
  c.save(); c.clipRect(-300f, -300f, 300f, 2f); c.scale(dir, 1f)
  pa.reset(); pa.moveTo(58f, 6f); pa.cubicTo(44f, -50f, 4f, -112f, -54f, -128f); pa.cubicTo(-32f, -92f, -32f, -40f, -50f, 6f); pa.close()
  c.drawPath(pa, solid(HAT_RED))
  pb.reset(); pb.moveTo(-50f, 6f); pb.cubicTo(-32f, -40f, -32f, -92f, -54f, -128f); pb.cubicTo(-20f, -100f, -6f, -50f, -14f, 6f); pb.close()
  c.drawPath(pb, solid(darken(HAT_RED, 0.8f)))
  pb.reset(); pb.moveTo(44f, -18f); pb.cubicTo(32f, -60f, 6f, -100f, -36f, -118f)
  c.drawPath(pb, ln(0x66FFFFFF, 6f))
  c.restore()
  c.drawLine(-64f, 2f, 64f, 2f, ln(0xDDFFFFFF.toInt(), 5f))
}

/** A burst of droplets thrown up from the surface; progress 0..1 runs it once. */
private fun splashPaint(): PaintFn = { c, a, _ ->
  val p = a.progress
  if (p > 0f && p < 1f) {
    val e = easeOutCubic(p)
    rc.set(-150f * e, -16f * e, 150f * e, 16f * e); c.drawOval(rc, ln(withAlpha(WHITE, 1f - p), 6f))
    for (i in 0 until 16) {
      val ang = (lerp(-165f, -15f, i / 15f) + (hash01(i) - 0.5f) * 14f) * DEG
      val v = 300f + hash01(i * 3) * 260f
      val x = cos(ang) * v * p * 0.9f; val y = sin(ang) * v * p + 820f * p * p
      if (y < 6f) c.drawCircle(x, y, (8f + hash01(i * 7) * 8f) * (1f - 0.5f * p), solid(withAlpha(if (i % 3 == 0) 0xFFBFEAF5.toInt() else WHITE, 1f - p * p)))
    }
  }
}

private val discoWall = lg(-PHH, 110f, ci(0xFF12061F, 0xFF2E0F4A, 0xFF5A217C), floatArrayOf(0f, 0.6f, 1f))
private val discoPal = ci(0xFFFF4FA3, 0xFF36D6E7, 0xFFFFD84D, 0xFFFF8A3D, 0xFFA66BFF)
private val ballShade = RadialGradient(-14f, -304f, 64f, ci(0x00000000, 0x00000000, 0x99000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
private val floorRows = floatArrayOf(110f, 148f, 196f, 258f, 336f, 430f)
private fun floorHalf(y: Float) = 330f + (y - 110f) / 320f * 560f

private fun discoSet(c: Canvas, lt: Float) {
  fillBg(c, shade(discoWall))
  for (i in 0 until 40) {
    val x = -PHW + hash01(i * 7 + 3) * 2f * PHW; val y = -PHH + hash01(i * 11 + 1) * 470f
    val tw = 0.5f + 0.5f * sin(lt * 4f + i * 1.7f)
    c.drawCircle(x, y, 1.5f + 2f * tw * tw, solid(withAlpha(WHITE, 0.2f + 0.6f * tw * tw * tw)))
  }
  // Coloured beams thrown off the mirror ball.
  for (b in 0 until 5) {
    val ang = (lt * 38f + b * 72f) * DEG + 0.4f * sin(lt * 0.7f + b)
    val dx = cos(ang); val dy = sin(ang)
    pa.reset(); pa.moveTo(0f, -290f)
    pa.lineTo(dx * 900f - dy * 70f, -290f + dy * 900f + dx * 70f); pa.lineTo(dx * 900f + dy * 70f, -290f + dy * 900f - dx * 70f); pa.close()
    c.drawPath(pa, solid(withAlpha(discoPal[b], 0.16f)))
  }
  // The light-up floor: each tile changes on the beat and decays between beats.
  val beat = floor(lt * 2f).toInt(); val pulse = 1f - 0.55f * fract(lt * 2f)
  for (r in 0 until floorRows.size - 1) for (col in 0 until 9) {
    val y0 = floorRows[r]; val y1 = floorRows[r + 1]
    val xa = (col - 4.5f) / 4.5f; val xb = (col - 3.5f) / 4.5f
    val gap = 0.06f
    val lx0 = lerp(xa, xb, gap) * floorHalf(y0); val rx0 = lerp(xb, xa, gap) * floorHalf(y0)
    val lx1 = lerp(xa, xb, gap) * floorHalf(y1); val rx1 = lerp(xb, xa, gap) * floorHalf(y1)
    val ins = (y1 - y0) * gap
    pa.reset(); pa.moveTo(lx0, y0 + ins); pa.lineTo(rx0, y0 + ins); pa.lineTo(rx1, y1 - ins); pa.lineTo(lx1, y1 - ins); pa.close()
    val color = discoPal[(r * 3 + col + beat) % 5]
    val lit = hash01(r * 31 + col * 7 + beat * 13) > 0.48f
    c.drawPath(pa, solid(if (lit) mix(darken(color, 0.4f), lighten(color, 0.25f), pulse) else darken(color, 0.2f)))
    if (lit) c.drawPath(pa, ln(withAlpha(WHITE, 0.35f * pulse), 2f))
  }
  // The mirror ball.
  c.drawLine(0f, -PHH - 10f, 0f, -346f, ln(0xFF9A9AB0.toInt(), 3f))
  c.save(); pa.reset(); pa.addCircle(0f, -290f, 56f, Path.Direction.CW); c.clipPath(pa)
  c.drawCircle(0f, -290f, 56f, solid(0xFF8D93A8.toInt()))
  val spin = lt * 0.9f; val sh = floor(spin).toInt(); val fr = fract(spin)
  for (row in -5..4) for (col in -6..5) {
    val x = (col + fr) * 12f; val y = -290f + row * 12f
    c.drawRect(x + 1f, y + 1f, x + 11f, y + 11f, solid(mix(0xFF5F6480.toInt(), 0xFFF2F5FF.toInt(), hash01((row + 9) * 19 + (col - sh + 40) * 7))))
  }
  c.drawCircle(0f, -290f, 56f, shade(ballShade))
  c.restore()
  for (g in 0 until 3) glint(c, -24f + g * 22f, -312f + g * 14f, 16f * (0.5f + 0.5f * sin(lt * 5f + g * 2.1f)), WHITE)
}

private val dawnSky = lg(-PHH, 150f, ci(0xFF4E63B0, 0xFFB98AB6, 0xFFFFB27E, 0xFFFFD9A3), floatArrayOf(0f, 0.45f, 0.8f, 1f))
private val sunGlow = RadialGradient(0f, 70f, 380f, ci(0xAAFFE6A8, 0x44FFC37A, 0x00FFB070), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
private val farTowers = floatArrayOf(-470f, 60f, 90f, -400f, 44f, 140f, -340f, 70f, 70f, -260f, 50f, 170f, 200f, 60f, 120f, 270f, 46f, 190f, 330f, 70f, 100f, 410f, 60f, 150f)
private val nearTowers = floatArrayOf(-460f, 80f, 60f, -370f, 60f, 96f, -300f, 90f, 50f, 230f, 80f, 70f, 320f, 56f, 120f, 390f, 90f, 66f)

private fun stepsSet(c: Canvas, lt: Float) {
  fillBg(c, shade(dawnSky))
  val bloom = smoothstep(6.4f, 7.2f, lt)
  c.save(); c.translate(0f, 70f); c.rotate(lt * 6f)
  for (i in 0 until 14) { pa.reset(); pa.moveTo(0f, 0f); pa.arcTo(-950f, -950f, 950f, 950f, i * 25.7f, 7f, false); pa.close(); c.drawPath(pa, solid(withAlpha(0xFFFFF0C8.toInt(), 0.1f + 0.14f * bloom))) }
  c.restore()
  c.save(); c.scale(1f + 0.35f * bloom, 1f + 0.35f * bloom, 0f, 70f); c.drawCircle(0f, 70f, 380f, shade(sunGlow)); c.restore()
  c.drawCircle(0f, 70f, 98f, solid(0xFFFFE9B0.toInt()))
  towers(c, farTowers, 0xFFC09AB8.toInt(), false)
  towers(c, nearTowers, 0xFF8C6F9F.toInt(), true)
  // Columns of the grand building, then the top landing and the steps down toward us.
  column(c, -405f); column(c, 405f)
  c.drawRect(-PHW - 12f, 160f, PHW + 12f, 224f, solid(0xFFEAD3BA.toInt()))
  c.drawLine(-PHW - 12f, 161f, PHW + 12f, 161f, ln(0xFFF8E9D6.toInt(), 4f))
  var y = 224f
  for (h in floatArrayOf(24f, 30f, 38f, 48f, 62f)) {
    c.drawRect(-PHW - 12f, y, PHW + 12f, y + h * 0.42f, solid(0xFFC6A487.toInt()))
    c.drawRect(-PHW - 12f, y + h * 0.42f, PHW + 12f, y + h, solid(0xFFE3C7AA.toInt()))
    c.drawLine(-PHW - 12f, y + h * 0.42f, PHW + 12f, y + h * 0.42f, ln(0xFFF4E1CB.toInt(), 3f))
    y += h
  }
}

private fun towers(c: Canvas, t: FloatArray, color: Int, windows: Boolean) {
  var i = 0
  while (i < t.size) {
    val x = t[i]; val w = t[i + 1]; val h = t[i + 2]
    c.drawRect(x, 160f - h, x + w, 162f, solid(color))
    if (windows) {
      var wy = 160f - h + 12f
      while (wy < 150f) {
        var wx = x + 8f
        while (wx < x + w - 10f) { if (hash01((wx + wy * 3f).toInt()) > 0.55f) c.drawRect(wx, wy, wx + 6f, wy + 8f, solid(0xCCFFE3A0.toInt())); wx += 14f }
        wy += 18f
      }
    }
    i += 3
  }
}

private fun column(c: Canvas, x: Float) {
  c.drawRect(x - 40f, -70f, x + 40f, 160f, solid(0xFFE6D2BC.toInt()))
  for (f in -2..2) c.drawLine(x + f * 14f, -60f, x + f * 14f, 150f, ln(0x1F000000, 4f))
  c.drawRect(x - 54f, -96f, x + 54f, -70f, solid(0xFFF1E1CE.toInt()))
  c.drawRect(x - 50f, 146f, x + 50f, 162f, solid(0xFFD9C2A8.toInt()))
}

private fun banner70(c: Canvas, a: ActorState, time: Float) {
  tp.typeface = groovyType; tp.textSize = 150f; tp.textSkewX = -0.1f; tp.style = Paint.Style.FILL
  val cols = ci(0xFF7A3B22, 0xFFD9661F, 0xFFF29B2E, 0xFFF6C744)
  for (i in 0 until 4) { val o = (4 - i) * 7f; tp.color = cols[i]; c.drawText("1970s", o, 52f + o, tp) }
  tp.style = Paint.Style.STROKE; tp.strokeWidth = 10f; tp.color = INK; c.drawText("1970s", 0f, 52f, tp)
  tp.style = Paint.Style.FILL; tp.color = 0xFFFFF3DC.toInt(); c.drawText("1970s", 0f, 52f, tp)
  tp.textSkewX = 0f
  for (i in 0 until 3) {
    rc.set(-230f, 96f + i * 13f, 230f, 160f + i * 13f)
    c.drawArc(rc, 200f, 140f, false, ln(cols[i + 1], 8f))
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Scene helpers
// ---------------------------------------------------------------------------------------------------------------

private fun SceneScope.homeBase() = pip {
  x = HOME_X; y = HOME_Y; size = 300f; groundY = HOME_Y
  right.x = 600f; right.y = 470f
  left.x = -60f; left.y = 470f
}

private fun SceneScope.custom(layer: Int, paint: PaintFn, style: ActorState.() -> Unit = {}): SceneScope.Actor {
  val a = actor(ActorKind.CUSTOM, layer = layer, style = style)
  a.state.paint = paint
  return a
}

/** The framed set: pops in at the start of the scene and out just before the end. */
private fun SceneScope.setPanel(sets: Array<SetFn>, vertical: BooleanArray, frame: Boolean): SceneScope.Actor {
  val a = custom(if (frame) -1 else 1, panelPaint(start, sets, vertical, frame)) { x = PCX; y = PCY; alpha = 0f; scale = 0.86f }
  a.animate(0, 0.5, Easing.linear) {
    from(Easing.outBack) { alpha(0); scale(0.86) }
    at(35) { alpha(1) }
    // A stop that omits alpha falls back to the base value (0), as in CSS; hold the panel visible explicitly.
    to { scale(1); alpha(1) }
  }
  a.animate(8.5, 0.42, Easing.inBack) { to { scale(0.86); alpha(0) } }
  return a
}

/** The decade title: drops in with a squash of overshoot, holds, then lifts away. */
private fun SceneScope.banner(paint: PaintFn) {
  val b = custom(2, paint) { x = PCX; y = 670f; alpha = 0f; scale = 0.4f }
  b.animate(0.05, 0.55, Easing.linear) {
    from(Easing.outBack) { alpha(0); scale(0.4); rotation(-14); y(630) }
    at(25) { alpha(1) }
    at(55, Easing.easeInOutSine) { scale(1.08); rotation(3) }
    to { scale(1); rotation(0); y(670); alpha(1) }
  }
  b.animate(0.6, 0.55, Easing.easeInOutSine) { to { y(664); rotation(-1.5) } }
  b.animate(1.15, 0.3, Easing.inBack) { to { y(570); alpha(0); scale(0.85) } }
}

/** Follow-through on the hat tip: starts ~80 ms after the move that caused it, overshoots and settles. */
private fun SceneScope.hatKick(at: Float, amt: Float, dur: Float = 0.55f) =
  animate(at + 0.08f, dur, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    from(Easing.easeOutSine) { hatSway(0) }
    at(25, Easing.easeInOutSine) { hatSway(amt) }
    at(58, Easing.easeInOutSine) { hatSway(-amt * 0.45f) }
    at(84, Easing.easeInOutSine) { hatSway(amt * 0.15f) }
    to { hatSway(0) }
  }

/** A thought-change double blink: lids close faster than they open. */
private fun SceneScope.doubleBlink(at: Float) = animate(at, 0.34, Easing.linear, fill = Fill.NONE) {
  from(Easing.easeIn) { blink(0) }
  at(15, Easing.easeOut) { blink(1) }
  at(38, Easing.easeIn) { blink(0) }
  at(52, Easing.easeOut) { blink(1) }
  at(78) { blink(0) }
  to { blink(0) }
}

private fun SceneScope.oneShot(a: SceneScope.Actor, at: Float, dur: Float, ease: dev.playground.pip.ui.anim.Ease = Easing.linear) =
  a.animate(at, dur, ease, fill = Fill.NONE) { from { progress(0) }; to { progress(1) } }

/** A comic word burst that slams in on a hit and shrinks away. */
private fun SceneScope.comicHit(word: String, at: Float, x: Float, y: Float, rot: Float, bg: Int, ring: Int, fg: Int, seed: Int) {
  val b = custom(2, burstPaint(word, bg, ring, fg, seed)) { this.x = x; this.y = y; alpha = 0f; scale = 0f; rotation = rot }
  b.animate(at, 0.6, Easing.linear, fill = Fill.NONE) {
    from(Easing.cubicBezier(0.1f, 0.9f, 0.3f, 1.4f)) { alpha(1); scale(0.2); rotation(rot - 25) }
    at(22, Easing.easeOutSine) { scale(1.12); rotation(rot + 4) }
    at(40, Easing.easeInOutSine) { scale(0.98); rotation(rot) }
    at(72, Easing.easeIn) { scale(1); alpha(1) }
    to { scale(0.3); alpha(0); rotation(rot + 18) }
  }
}

// ---------------------------------------------------------------------------------------------------------------
// d60s: moon landing (0–2.9), the zebra crossing (2.9–5.5), the campy '66 punch-up (5.5–9)
// ---------------------------------------------------------------------------------------------------------------

private fun sixties() = scene("d60s", 9) {
  homeBase()
  val panel = setPanel(arrayOf(::moonSet, ::zebraSet, ::comicSet), booleanArrayOf(false, false), frame = true)
  val wipe = custom(-1, starburstPaint(start)) { x = PCX; y = PCY }
  val flag = custom(-1, flagPaint(pip)) { alpha = 0f }
  val feet = custom(-1, feetPaint(pip)) { alpha = 0f }
  val helmet = custom(1, helmetPaint(pip)) { progress = 0f }
  val flash = custom(2, { c, _, _ -> c.drawRoundRect(-PHW, -PHH, PHW, PHH, PR, PR, solid(WHITE)) }) { x = PCX; y = PCY; alpha = 0f }
  banner(::banner60)

  // ---- 1. Moon landing ----
  helmet.animate(0.05, 0.45, Easing.linear) { from(Easing.outBack) { progress(0.5) }; to { progress(1) } }
  // Wonder: eyes dart up to the stars, then across to the Earth; the head follows ~120 ms behind.
  animate(0, 0.8, Easing.linear) {
    from(Easing.easeOutSine) { mouth(Mouth.O); brows(0.2, 0); eyeScale(1) }
    at(14, Easing.easeInOutSine) { look(-0.85, -0.55); eyeScale(1.22); brows(0.75, -0.25) }
    at(48, Easing.cubicBezier(0.6f, 0f, 0.2f, 1f)) { look(-0.85, -0.55) }
    at(62) { look(0.9, -0.65); eyeScale(1.3) }
    to { look(0.85, -0.6) }
  }
  animate(0.12, 0.7, Easing.linear) {
    from(Easing.easeInOutSine) { rotation(0) }
    at(32, Easing.easeInOutSine) { rotation(-5) }
    at(66, Easing.easeOutSine) { rotation(4) }
    to { rotation(3) }
  }
  hatKick(0.25f, 0.4f); hatKick(0.62f, -0.5f)
  // The flag pops into his fist as he squats; eyes drop to the ground first.
  animate(0.56, 0.22, Easing.outBack) { from { rightHand(Hand.FIST, x = 560, y = 560, angle = 0, scale = 0.3) }; to { rightHand(x = 620, y = 470, scale = 1) } }
  flag.animate(0.56, 0.1, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
  animate(0.56, 0.1, Easing.easeOut) { to { look(0.1, 0.55) } }
  animate(0.64, 0.18, Easing.easeOut) { from { mouth(Mouth.SMILE) }; to { squash(1.15, 0.87); rotation(0); eyeScale(1.05); squint(0.15) } }
  // A floaty low-gravity hop: slow rise, long hang, gentle drift down.
  cue(0.82, "hop")
  animate(0.82, 0.88, Easing.linear) {
    from(Easing.cubicBezier(0.15f, 0.75f, 0.4f, 1f)) { y(HOME_Y) }
    at(52, Easing.cubicBezier(0.6f, 0f, 0.85f, 0.4f)) { y(1222) }
    to { y(HOME_Y) }
  }
  animate(0.82, 0.88, Easing.linear) {
    from(Easing.easeOut) { squash(0.87, 1.15) }
    at(24, Easing.easeInOutSine) { squash(0.98, 1.02) }
    at(80, Easing.easeIn) { squash(1, 1) }
    to { squash(0.95, 1.05) }
  }
  animate(0.86, 0.45, Easing.easeOutSine) {
    to { squint(0.65); look(0, -0.2); eyeScale(1); brows(0.5, -0.1); rightHand(x = 650, y = 360, angle = 10); rotation(-2) }
  }
  animate(0.9, 0.45, Easing.easeOutSine) { from { leftHand(Hand.PALM, scale = 0.4) }; to { leftHand(x = -100, y = 360, angle = -35, scale = 1) } }
  hatKick(0.82f, -0.75f, 0.9f)
  cue(1.7, "land")
  val dust = actor(ActorKind.SMOKE) { x = 540f; y = 1345f; scale = 0.55f; visible = false }
  dust.animate(1.7, 0.6, Easing.easeOut, fill = Fill.NONE) { from { visible(true); progress(0) }; to { progress(1) } }
  animate(1.7, 0.3, Easing.linear) {
    from(Easing.easeOut) { squash(1.18, 0.85) }
    at(50, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  say("One small hop for Pip…", 1.72, 1.2)
  // Wind-up: the flag goes high, eyes pick the spot, then it's driven home on the beat.
  animate(1.74, 0.1, Easing.easeOut) { to { look(0.7, 0.35) } }
  animate(1.8, 0.16, Easing.easeOut) { from { leftHand(Hand.NONE) }; to { rightHand(x = 660, y = 330, angle = 8); rotation(-4); squint(0.2); brows(0.6, 0.4) } }
  animate(1.96, 0.06, Easing.easeIn) { to { rightHand(x = 666, y = 498, angle = 0); rotation(0) } }
  animate(1.96, 0.3, Easing.linear) { from(Easing.easeIn) { squash(1, 1) }; at(20, Easing.easeOut) { squash(1.06, 0.95) }; to { squash(1, 1) } }
  cue(2.0, "sting")
  flag.animate(2.02, 0.01, Easing.linear) { from { progress(0) }; to { progress(1); x(773); y(1360) } }
  flag.animate(2.03, 0.7, Easing.linear) {
    from(Easing.easeOut) { rotation(0) }
    at(15, Easing.easeInOutSine) { rotation(5) }
    at(40, Easing.easeInOutSine) { rotation(-3) }
    at(66, Easing.easeInOutSine) { rotation(1.2) }
    to { rotation(0) }
  }
  val dust2 = actor(ActorKind.SMOKE) { x = 773f; y = 1362f; scale = 0.4f; visible = false }
  dust2.animate(2.02, 0.5, Easing.easeOut, fill = Fill.NONE) { from { visible(true); progress(0) }; to { progress(1) } }
  // Proud salute, eyes to camera.
  animate(2.04, 0.08, Easing.easeOut) { to { look(0, 0) } }
  animate(2.08, 0.26, Easing.outBack) {
    from { rightHand(Hand.PALM) }
    to { rightHand(x = 470, y = 300, angle = -70); squash(0.96, 1.04); rotation(-3); squint(0.5); brows(0.5, 0); blush(0.3) }
  }
  hatKick(2.08f, 0.35f)
  // Idea! Eyes snap right, double blink, lean back... and whip.
  doubleBlink(2.56f)
  animate(2.62, 0.1, Easing.easeOut) { to { look(1, 0); eyeScale(1.15); brows(0.85, 0); squint(0); blush(0) } }
  animate(2.72, 0.18, Easing.easeOut) { from { rightHand(Hand.FIST) }; to { rotation(-9); squash(1.1, 0.91); rightHand(x = 560, y = 500, angle = 0); look(1, 0.1) } }
  cue(2.9, "whoosh")
  helmet.animate(2.9, 0.12, Easing.easeIn) { to { progress(0) } }
  panel.animate(2.9, 0.28, whip) { from { progress(0) }; to { progress(1) } }
  flag.animate(2.9, 0.28, whip) { from { x(773) }; to { x(773 - 940) } }
  animate(2.9, 0.28, whip) { from { x(HOME_X) }; to { x(330) } }
  animate(2.9, 0.55, Easing.linear) {
    from(Easing.easeOut) { squash(1.1, 0.91); rotation(-9) }
    at(12, Easing.cubicBezier(0.3f, 0f, 0.2f, 1f)) { squash(1.3, 0.77); rotation(16) }
    at(45, Easing.easeOut) { squash(0.9, 1.11); rotation(-7) }
    at(72, Easing.easeInOutSine) { squash(1.04, 0.96); rotation(2) }
    to { squash(1, 1); rotation(0) }
  }
  hatKick(2.9f, -1f, 0.6f)

  // ---- 2. The zebra crossing ----
  feet.animate(3.05, 0.2, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
  // Looks right, left, right before crossing, then strides off.
  animate(3.25, 0.5, Easing.linear) {
    from(Easing.easeInOutSine) { look(1, 0.1); mouth(Mouth.FLAT) }
    at(26, Easing.easeInOutSine) { look(-0.95, 0.1) }
    at(58, Easing.easeOutSine) { look(0.95, 0.1) }
    to { look(1, 0); eyeScale(1); brows(0.3, 0); lidTop(0.18) }
  }
  animate(3.33, 0.42, Easing.linear) { from(Easing.easeInOutSine) { rotation(3) }; at(40, Easing.easeInOutSine) { rotation(-3) }; to { rotation(5) } }
  animate(3.38, 0.12, Easing.outBack) {
    from { rightHand(Hand.FIST, x = 610, y = 520, scale = 0); leftHand(Hand.FIST, x = -70, y = 520, scale = 0); mouth(Mouth.SMILE) }
    to { rightHand(scale = 1); leftHand(scale = 1) }
  }
  animate(3.5, 1.5, Easing.linear) {
    from(stride) { x(330) }
    at(33.33, stride) { x(473) }
    at(66.67, stride) { x(617) }
    to { x(760) }
  }
  animate(3.5, 0.5, Easing.linear, iterations = 3f, composite = Composite.ADD, fill = Fill.NONE) {
    from(Easing.easeOutSine) { y(0) }
    at(50, Easing.easeInSine) { y(-16) }
    to { y(0) }
  }
  animate(3.5, 0.5, Easing.easeInOutSine, iterations = 3f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { rightHand(x = -40, angle = -14); leftHand(x = 36, angle = 12) }
    to { rightHand(x = 40, angle = 14); leftHand(x = -36, angle = -12) }
  }
  animate(3.58, 0.5, Easing.easeInOutSine, iterations = 3f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0.35) }; to { hatSway(-0.35) }
  }
  feet.animate(3.5, 1.5, Easing.linear) { from { progress(0) }; to { progress(3) } }
  // Snap! The album-cover freeze mid-stride.
  cue(5.0, "pop")
  flash.animate(5.0, 0.4, Easing.easeOut, fill = Fill.NONE) { from { alpha(0.9) }; to { alpha(0) } }
  animate(5.0, 0.1, Easing.easeOut) { to { rotation(3) } }
  animate(5.0, 0.16, Easing.linear, fill = Fill.NONE) { from { life(0) }; to { life(0) } }
  // Caught on camera: eyes slide to us (with a blink), then a bashful grin.
  animate(5.14, 0.12, Easing.linear, fill = Fill.NONE) { from(Easing.easeIn) { blink(0) }; at(40, Easing.easeOut) { blink(1) }; to { blink(0) } }
  animate(5.18, 0.14, Easing.easeOut) { to { look(-0.1, 0.15); eyeScale(1.18); brows(0.8, -0.1); lidTop(0) } }
  animate(5.3, 0.18, Easing.easeOut) { to { squint(0.6); blush(0.6); eyeScale(1); rotation(-3) } }

  // ---- 3. The '66 punch-up: a spinning pop-art wipe, then POW! BAM! ZAP! ----
  feet.animate(5.4, 0.1, Easing.linear) { to { alpha(0) } }
  animate(5.38, 0.12, Easing.easeOut) { to { squash(1.14, 0.87); rightHand(scale = 0); leftHand(scale = 0); look(-0.6, -0.3); blush(0) } }
  cue(5.5, "whoosh")
  animate(5.5, 0.45, Easing.easeInOutSine) { from { x(760) }; to { x(HOME_X) } }
  animate(5.5, 0.45, Easing.linear) { from(Easing.easeOutSine) { y(HOME_Y) }; at(50, Easing.easeInSine) { y(1170) }; to { y(HOME_Y) } }
  animate(5.5, 0.4, Easing.linear) { from { facing(1) }; at(25) { facing(-1) }; at(50) { facing(1) }; at(75) { facing(-1) }; to { facing(1) } }
  animate(5.5, 0.45, Easing.linear) {
    from(Easing.easeOut) { squash(0.86, 1.16); rotation(0); mouth(Mouth.FLAT); squint(0) }
    at(30, Easing.easeInOutSine) { squash(1, 1) }
    to { squash(0.94, 1.06) }
  }
  wipe.animate(5.45, 0.85, Easing.linear) {
    from(Easing.easeOut) { progress(0) }
    at(47) { progress(0.5) }
    at(53, Easing.easeIn) { progress(0.5) }
    to { progress(1) }
  }
  panel.animate(5.86, 0.01, Easing.linear) { from { progress(1) }; to { progress(2) } }
  cue(5.95, "land")
  animate(5.95, 0.35, Easing.linear) {
    from(Easing.easeOut) { squash(1.2, 0.84) }
    at(40, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  animate(5.95, 0.22, Easing.outBack) {
    from { rightHand(Hand.FIST, x = 560, y = 440, scale = 0.3); leftHand(Hand.FIST, x = 10, y = 470, scale = 0.3) }
    to { rightHand(angle = 10, scale = 1); leftHand(angle = -10, scale = 1); brows(-0.2, 0.8); squint(0.35); look(1, 0); lidTop(0.1); eyeScale(1) }
  }
  hatKick(5.95f, 0.5f)
  // The villain: a scam call, of course.
  val card = actor(ActorKind.SCAM_CARD, "SCAM CALL", layer = 1) { x = 1300f; y = 1170f; alpha = 0f }
  cue(6.02, "whoosh")
  card.animate(6.02, 0.4, Easing.linear) {
    from(Easing.cubicBezier(0.2f, 0.8f, 0.3f, 1f)) { x(1300); alpha(1); rotation(25) }
    at(75, Easing.easeInOutSine) { x(810); rotation(-6) }
    to { x(830); rotation(0) }
  }
  card.animate(6.42, 0.5, Easing.easeInOutSine, iterations = 3f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) { from { y(-8) }; to { y(8) } }
  // Boxer's bounce under everything.
  animate(6.2, 0.25, Easing.easeInOutSine, iterations = 6f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { y(0) }; to { y(-12) }
  }
  // POW: a right jab.
  animate(6.32, 0.14, Easing.easeOut) { to { rightHand(x = 500, y = 470); rotation(-6); squash(1.05, 0.95); squint(0.5) } }
  cue(6.5, "pow")
  animate(6.46, 0.07, snap) { to { rightHand(x = 790, y = 420); rotation(9); x(570); squash(0.93, 1.07); squint(0.85) } }
  animate(6.6, 0.3, Easing.outBack) { to { rightHand(x = 560, y = 440); rotation(0); x(550); squash(1, 1); squint(0.35) } }
  card.animate(6.5, 0.45, Easing.linear, composite = Composite.ADD) {
    from(Easing.cubicBezier(0.1f, 0.9f, 0.2f, 1f)) { x(0); rotation(0) }
    at(20, Easing.easeInOutSine) { x(70); rotation(22) }
    at(60, Easing.easeInOutSine) { x(-10); rotation(-6) }
    to { x(0); rotation(0) }
  }
  comicHit("POW!", 6.5f, 860f, 990f, -10f, POP_YELLOW, POP_RED, POP_RED, 1)
  hatKick(6.46f, -0.5f, 0.4f)
  // BAM: a left hook across his face.
  animate(6.84, 0.14, Easing.easeOut) { to { leftHand(x = -120, y = 520); rotation(-8); squint(0.5) } }
  cue(7.0, "pow")
  animate(6.97, 0.07, snap) { to { leftHand(x = 720, y = 380); rotation(12); x(585); squint(0.9); squash(1.06, 0.95) } }
  animate(7.1, 0.3, Easing.outBack) { to { leftHand(x = 10, y = 470); rotation(0); x(560); squash(1, 1); squint(0.35) } }
  card.animate(7.0, 0.45, Easing.linear, composite = Composite.ADD) {
    from(Easing.cubicBezier(0.1f, 0.9f, 0.2f, 1f)) { x(0); y(0); rotation(0) }
    at(20, Easing.easeInOutSine) { x(50); y(-30); rotation(-40) }
    at(60, Easing.easeInOutSine) { x(-8); y(4); rotation(8) }
    to { x(0); y(0); rotation(0) }
  }
  comicHit("BAM!", 7.0f, 720f, 900f, 12f, 0xFF4FC3F7.toInt(), POP_BLUE, WHITE, 2)
  hatKick(6.97f, 0.6f, 0.4f)
  // ZAP: a rising uppercut sends the scam flying.
  animate(7.34, 0.14, Easing.easeOut) { to { squash(1.16, 0.86); rightHand(x = 600, y = 640); look(1, -0.3); squint(0.5) } }
  cue(7.5, "zap")
  animate(7.47, 0.08, snap) { to { squash(0.84, 1.19); rightHand(x = 720, y = 160); rotation(10); squint(0.95); x(575) } }
  animate(7.47, 0.4, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) { from(Easing.easeOutSine) { y(0) }; at(45, Easing.easeInSine) { y(-50) }; to { y(0) } }
  card.animate(7.5, 0.55, Easing.cubicBezier(0.1f, 0.7f, 0.4f, 1f)) { to { x(1250); y(560); rotation(620); scale(0.5) } }
  card.animate(7.85, 0.15, Easing.easeIn) { to { alpha(0) } }
  comicHit("ZAP!", 7.5f, 880f, 1080f, -6f, POP_RED, POP_YELLOW, POP_YELLOW, 3)
  hatKick(7.47f, -0.8f, 0.5f)
  animate(7.6, 0.18, Easing.easeOut) { to { look(1, -1); eyeScale(1.2); squint(0); rotation(4); squash(1, 1) } }
  // Heroic hands-on-hips, gazing into the distance, hat flapping like a cape.
  cue(8.0, "sting")
  animate(7.82, 0.08, Easing.easeOut) { to { look(0.3, -0.25) } }
  animate(7.86, 0.26, Easing.outBack) {
    from { mouth(Mouth.SMILE) }
    to { rightHand(x = 540, y = 575, angle = 40); leftHand(x = 0, y = 575, angle = -40); squash(0.95, 1.05); rotation(-2); x(HOME_X); squint(0.45); brows(0.4, 0.35); lidTop(0.1); eyeScale(1) }
  }
  animate(8.0, 0.55, Easing.easeInOutSine, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }; at(25) { hatSway(0.6) }; at(50) { hatSway(-0.3) }; at(75) { hatSway(0.4) }; to { hatSway(0) }
  }
  // Relax and hand back to the next decade.
  animate(8.55, 0.4, Easing.linear) {
    from(Easing.easeInOutSine) { look(0.3, -0.25) }
    at(45, Easing.easeOutSine) { squash(1.05, 0.95); rightHand(scale = 0); leftHand(scale = 0); look(0, 0) }
    to { squash(1, 1); rotation(0); squint(0); lidTop(0); brows(0, 0, false); rightHand(Hand.NONE); leftHand(Hand.NONE) }
  }
  hatKick(8.6f, 0.3f)
}

// ---------------------------------------------------------------------------------------------------------------
// d70s: the shark fin (0–2.95), the disco point (2.95–5.8), the boxer on the steps (5.8–9)
// ---------------------------------------------------------------------------------------------------------------

private fun seventies() = scene("d70s", 9) {
  homeBase()
  val sets = arrayOf<SetFn>(::oceanBack, ::discoSet, ::stepsSet)
  val vertical = booleanArrayOf(true, false)
  val panel = setPanel(sets, vertical, frame = true)
  val front = setPanel(arrayOf(::oceanFront, ::noSet, ::noSet), vertical, frame = false)
  val fin = custom(2, finPaint()) { x = 200f; y = PCY + WATER_Y; scale = 0f; progress = 1f }
  val splash1 = custom(2, splashPaint()) { x = 540f; y = PCY + WATER_Y }
  val splash2 = custom(2, splashPaint()) { x = 545f; y = PCY + WATER_Y }
  banner(::banner70)
  // In the sea the water is the floor: no ground shadow until he lands on the dance floor.
  animate(0, 3.35, Easing.linear, fill = Fill.NONE) { from { shadow(0) }; to { shadow(0) } }
  animate(3.35, 0.12, Easing.easeOut, fill = Fill.NONE) { from { shadow(0) }; to { shadow(1) } }

  // ---- 1. Shark! ----
  // Waist deep, a conspiratorial check left and right, then a sly look at us.
  animate(0.08, 0.4, Easing.linear) {
    from(Easing.easeInOutSine) { look(0, 0); mouth(Mouth.SMILE) }
    at(30, Easing.easeInOutSine) { look(-0.8, 0.1); lidTop(0.35) }
    at(62, Easing.easeOutSine) { look(0.8, 0.1) }
    to { look(0, 0.05); lidTop(0.45); squint(0.2); brows(0.3, 0.6) }
  }
  animate(0.16, 0.36, Easing.linear) { from(Easing.easeInOutSine) { rotation(0) }; at(40, Easing.easeInOutSine) { rotation(-4) }; to { rotation(3) } }
  // Deep breath (eyes shut, cheeks full), then dive.
  animate(0.42, 0.13, Easing.easeOutSine) { from { mouth(Mouth.O) }; to { squash(0.9, 1.11); y(1335); eyesOpen(0.12); rotation(0); lidTop(0); squint(0) } }
  animate(0.55, 0.2, Easing.easeIn) { to { y(1520); squash(0.75, 0.3) } }
  cue(0.62, "splash")
  oneShot(splash1, 0.6f, 0.7f)
  hatKick(0.42f, 0.5f, 0.3f)
  animate(0.76, 0.01, Easing.linear) { to { visible(false) } }
  // The fin: rises, cruises right, turns, then charges at us faster and faster.
  fin.animate(0.82, 0.3, Easing.outBack) { to { scale(1) } }
  fin.animate(0.82, 0.93, Easing.easeInOutSine) { to { x(850) } }
  fin.animate(0.82, 0.5, Easing.easeInOutSine, iterations = 4f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { y(-4); rotation(-3) }; to { y(4); rotation(3) }
  }
  fin.animate(1.75, 0.17, Easing.easeInOutSine) { to { progress(-1); x(870) } }
  fin.animate(1.92, 0.36, Easing.easeIn) { to { x(545); scale(1.25) } }
  fin.animate(2.28, 0.1, Easing.easeIn) { to { scale(0) } }
  cue(1.0, "dundun"); cue(1.5, "dundun"); cue(2.0, "dundun")
  // Surprise! It was Pip all along, grinning.
  cue(2.38, "splash")
  oneShot(splash2, 2.36f, 0.7f)
  animate(2.38, 0.42, Easing.linear) {
    from(Easing.cubicBezier(0.1f, 0.8f, 0.3f, 1f)) { visible(true); y(1520); squash(0.7, 0.4); eyesOpen(1); mouth(Mouth.SMILE) }
    at(35, Easing.easeInOutSine) { y(1340); squash(0.87, 1.15) }
    at(65, Easing.easeInOutSine) { y(HOME_Y); squash(1.07, 0.94) }
    to { squash(1, 1) }
  }
  animate(2.4, 0.2, Easing.easeOut) { to { squint(0.6); brows(0.9, -0.1); blush(0.45); look(0, 0.1); lidTop(0); eyeScale(1.05) } }
  hatKick(2.4f, -0.9f, 0.6f)
  // Eyebrow waggle: heh heh.
  animate(2.6, 0.24, Easing.easeInOutSine) { from { brows(0.9) }; at(50) { brows(0.3) }; to { brows(0.9) } }
  // Anticipation, then a big leap out of the sea.
  animate(2.8, 0.15, Easing.easeOut) { to { squash(1.18, 0.85); look(0, -0.8); eyeScale(1.15); squint(0.1); blush(0) } }
  cue(2.97, "hop")
  animate(2.97, 0.5, Easing.linear) {
    from(Easing.easeOutSine) { y(HOME_Y) }
    at(46, Easing.easeInSine) { y(1060) }
    to { y(HOME_Y) }
  }
  animate(2.97, 0.5, Easing.linear) {
    from(Easing.easeOut) { squash(0.82, 1.2) }
    at(40, Easing.easeInOutSine) { squash(1, 1) }
    to { squash(0.93, 1.07) }
  }
  hatKick(2.97f, -0.9f, 0.6f)
  cue(3.05, "whoosh")
  for (a in listOf(panel, front)) a.animate(3.05, 0.27, whip) { from { progress(0) }; to { progress(1) } }

  // ---- 2. Disco: point up, point down, on the beat ----
  cue(3.47, "land")
  animate(3.47, 0.3, Easing.linear) {
    from(Easing.easeOut) { squash(1.2, 0.84) }
    at(30, Easing.easeOutSine) { squash(0.95, 1.05) }
    to { squash(0.97, 1.03) }
  }
  val up = 0; val down = 1
  fun discoPose(at: Float, which: Int, beatAt: Float) {
    // eyes lead the point by ~100 ms
    animate(at - 0.1f, 0.1, Easing.easeOut) { to { if (which == up) look(0.7, -0.7) else look(-0.7, 0.6) } }
    animate(at, 0.16, Easing.outBack) {
      if (which == up) to { rightHand(x = 650, y = 140, angle = 32); leftHand(x = 10, y = 600, angle = -20); rotation(-6) }
      else to { rightHand(x = 70, y = 650, angle = -135); leftHand(x = -40, y = 560, angle = -10); rotation(7) }
    }
    animate(beatAt, 0.22, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
      from(Easing.easeOut) { squash(0.05, -0.05) }; to { squash(0, 0) }
    }
    hatKick(at, if (which == up) -0.5f else 0.5f, 0.4f)
  }
  animate(3.42, 0.1, Easing.easeOut) {
    from { rightHand(Hand.POINT, x = 600, y = 470, scale = 0.4); leftHand(Hand.FIST, x = -60, y = 520, scale = 0.4); mood(Mood.NEUTRAL) }
    to { rightHand(scale = 1); leftHand(scale = 1); lidTop(0.3); squint(0.3); brows(0.5, -0.1); eyeScale(1) }
  }
  discoPose(3.5f, up, 3.5f); discoPose(3.92f, down, 4.0f); discoPose(4.42f, up, 4.5f); discoPose(4.68f, down, 4.75f); discoPose(4.92f, up, 5.0f)
  animate(4.0, 1.0, Easing.easeInOutSine, iterations = 1f) { from { x(HOME_X) }; at(50) { x(565) }; to { x(HOME_X) } }
  // Hold the pose... and wink.
  cue(5.18, "ding")
  animate(5.15, 0.3, Easing.linear) {
    from(Easing.easeOut) { squint(0.3) }
    at(30, Easing.easeInOutSine) { squint(1); rotation(-12); blush(0.5); look(0, 0) }
    to { squint(0.4); rotation(-6); blush(0.2) }
  }
  val star = actor(ActorKind.SPARKLE) { x = 760f; y = 1010f; scale = 0f; color = 0xFFFFD84D.toInt() }
  star.animate(5.0, 0.5, Easing.linear, fill = Fill.NONE) { from(Easing.outBack) { scale(0) }; at(40, Easing.easeIn) { scale(1.2) }; to { scale(0) } }
  // Poof: off to the steps, already out of breath.
  animate(5.6, 0.15, Easing.easeOut) { to { squash(1.12, 0.89); rightHand(scale = 0); leftHand(scale = 0); rotation(0) } }
  cue(5.78, "poof")
  val poof = actor(ActorKind.SMOKE, anchorToPip = true) { y = 200f; scale = 2.3f; visible = false }
  poof.animate(5.76, 0.85, Easing.easeOut, fill = Fill.NONE) { from { visible(true); progress(0); scale(1.6) }; to { progress(1); scale(2.5) } }
  for (a in listOf(panel, front)) a.animate(5.9, 0.01, Easing.linear) { from { progress(1) }; to { progress(2) } }

  // ---- 3. The steps at dawn ----
  // Swapped while hidden in the smoke: hands on knees, panting, eyes heavy.
  animate(5.86, 0.05, Easing.linear) {
    from { rightHand(Hand.FIST, x = 430, y = 620, angle = 0, scale = 1); leftHand(Hand.FIST, x = 110, y = 620, angle = 0, scale = 1); mouth(Mouth.O); lidTop(0.5); squint(0); brows(-0.2, -0.6); rotation(4); look(0, 0.3); squash(1, 1) }
    to { }
  }
  animate(5.9, 0.3, Easing.easeInOutSine, iterations = 2f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { squash(0, 0) }; to { squash(0.04, -0.04) }
  }
  val sweat = actor(ActorKind.SWEAT, anchorToPip = true) { x = 120f; y = 70f; alpha = 0f }
  sweat.animate(5.95, 0.45, Easing.easeIn, fill = Fill.NONE) { from { alpha(1); y(60) }; to { alpha(0); y(120) } }
  // Realisation: eyes up to the city, a double blink, eyes go wide... hold the beat.
  animate(6.18, 0.12, Easing.easeOut) { to { look(0, -0.7) } }
  doubleBlink(6.2f)
  animate(6.3, 0.12, Easing.easeOut) { to { lidTop(0); eyeScale(1.22); brows(0.8, 0); rotation(0) } }
  animate(6.36, 0.14, Easing.easeOut) { to { squash(1.15, 0.87); rightHand(x = 480, y = 650); leftHand(x = 60, y = 650) } }
  // ARMS UP! Eyes squeezed shut in joy.
  cue(6.5, "crowd")
  animate(6.5, 0.1, Easing.easeOut) { from { mood(Mood.HAPPY); mouth(Mouth.SHOUT) }; to { squash(0.88, 1.14); rightHand(x = 640, y = 80, angle = 20); leftHand(x = -100, y = 80, angle = -20); brows(0.9, -0.3); eyeScale(1) } }
  animate(6.6, 0.35, Easing.outBack) { to { squash(0.97, 1.03) } }
  hatKick(6.5f, -1f, 0.7f)
  // Bouncing on his toes, fists pumping on the beats, then a hop-turn.
  animate(7.0, 0.5, Easing.linear, iterations = 1f, composite = Composite.ADD, fill = Fill.NONE) {
    from(Easing.easeOutSine) { y(0); rightHand(y = 0); leftHand(y = 0) }
    at(40, Easing.easeInSine) { y(-34); rightHand(y = -40); leftHand(y = -26) }
    to { y(0); rightHand(y = 0); leftHand(y = 0) }
  }
  hatKick(7.0f, 0.5f, 0.4f)
  animate(7.42, 0.1, Easing.easeOut) { to { squash(1.12, 0.89) } }
  animate(7.5, 0.5, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    from(Easing.easeOutSine) { y(0) }; at(48, Easing.easeInSine) { y(-90) }; to { y(0) }
  }
  animate(7.5, 0.5, Easing.linear) {
    from(Easing.easeOut) { squash(0.87, 1.15) }
    at(30) { facing(1) }
    at(50) { facing(-1); squash(1, 1) }
    at(70) { facing(1) }
    to { squash(0.95, 1.05) }
  }
  hatKick(7.5f, -0.8f, 0.6f)
  // Lands, eyes open to us, a cheeky wink.
  animate(8.0, 0.3, Easing.linear) {
    from(Easing.easeOut) { squash(1.16, 0.86) }
    at(40, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  animate(7.98, 0.06, Easing.linear) { from { mood(Mood.NEUTRAL); mouth(Mouth.SMILE) }; to { look(0, 0); squint(0.5); brows(0.5, 0) } }
  cue(8.22, "ding")
  animate(8.18, 0.3, Easing.linear) {
    from(Easing.easeOut) { squint(0.5) }
    at(30, Easing.easeInOutSine) { squint(1); rotation(-8); blush(0.4) }
    to { squint(0.45); rotation(-3) }
  }
  // Arms down, settle, hand off.
  animate(8.5, 0.45, Easing.linear) {
    from(Easing.easeInOutSine) { squash(1, 1) }
    at(45, Easing.easeOutSine) { squash(1.05, 0.95); rightHand(x = 600, y = 500, scale = 0); leftHand(x = -60, y = 500, scale = 0); rotation(1) }
    to { squash(1, 1); rotation(0); squint(0); blush(0); brows(0, 0, false); rightHand(Hand.NONE); leftHand(Hand.NONE) }
  }
  hatKick(8.55f, 0.3f)
}
