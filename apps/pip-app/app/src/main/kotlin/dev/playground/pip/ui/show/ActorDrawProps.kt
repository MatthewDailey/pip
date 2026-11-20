package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Props: the scam card, the door, the moon and the disco ball. */
internal class PropsDrawer(private val kit: ActorKit) {

  // ---- SCAM_CARD ---------------------------------------------------------------------------------

  private val cardShader = LinearGradient(0f, -85f, 0f, 85f, 0xFFF4605A.toInt(), 0xFFD8352F.toInt(), Shader.TileMode.CLAMP)
  private val warn = Path().apply {
    moveTo(0f, -30f); lineTo(32f, 26f); lineTo(-32f, 26f); close()
  }
  private val cut = Path()
  private val leftHalf = Path()
  private val rightHalf = Path()
  private val cutX = FloatArray(9)
  private val cutY = FloatArray(9)

  init {
    // A torn zigzag from just above the top edge to just below the bottom, leaning right.
    for (i in 0 until 9) {
      val t = i / 8f
      val jag = if (i == 0 || i == 8) 0f else if (i % 2 == 0) 11f else -11f
      cutX[i] = lerp(-46f, 46f, t) + jag
      cutY[i] = lerp(-120f, 120f, t)
    }
    cut.moveTo(cutX[0], cutY[0]); for (i in 1 until 9) cut.lineTo(cutX[i], cutY[i])
    leftHalf.moveTo(-240f, -120f); for (i in 0 until 9) leftHalf.lineTo(cutX[i], cutY[i]); leftHalf.lineTo(-240f, 120f); leftHalf.close()
    rightHalf.moveTo(240f, -120f); for (i in 0 until 9) rightHalf.lineTo(cutX[i], cutY[i]); rightHalf.lineTo(240f, 120f); rightHalf.close()
  }

  private var layoutText: String? = null
  private var layout: StaticLayout? = null
  private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  }

  /** Lays the card's label out once per distinct text, shrinking it until it fits the text area. */
  private fun cardLayout(s: String): StaticLayout {
    layout?.let { if (layoutText == s) return it }
    var size = 38f
    var l: StaticLayout
    while (true) {
      textPaint.textSize = size
      l = StaticLayout.Builder.obtain(s, 0, s.length, textPaint, 184)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 0.95f).build()
      var widest = 0f
      for (i in 0 until l.lineCount) widest = max(widest, l.getLineWidth(i))
      if ((l.height <= 132 && widest <= 184f && !breaksWords(l, s)) || size <= 18f) break
      size -= 2f
    }
    layoutText = s; layout = l
    return l
  }

  private fun breaksWords(l: StaticLayout, s: String): Boolean {
    for (i in 0 until l.lineCount - 1) {
      val end = l.getLineEnd(i)
      if (end in 1 until s.length && !s[end - 1].isWhitespace() && !s[end].isWhitespace()) return true
    }
    return false
  }

  private fun cardBody(c: Canvas, label: String) {
    kit.rect.set(-146f, -76f, 154f, 100f)
    c.drawRoundRect(kit.rect, 26f, 26f, kit.flat(0x333A2A26))
    kit.rect.set(-150f, -85f, 150f, 85f)
    val f = kit.fill
    f.shader = cardShader; f.color = 0xFFFFFFFF.toInt()
    c.drawRoundRect(kit.rect, 24f, 24f, f)
    f.shader = null
    kit.rect.set(-140f, -75f, 140f, 75f)
    c.drawRoundRect(kit.rect, 16f, 16f, kit.line(0x66FFFFFF, 3f))
    // Warning sign: rounded white triangle with a red exclamation mark.
    c.save(); c.translate(-96f, 2f)
    c.drawPath(warn, kit.line(0xFFFFFFFF.toInt(), 16f))
    c.drawPath(warn, kit.flat(0xFFFFFFFF.toInt()))
    kit.rect.set(-5f, -14f, 5f, 10f); c.drawRoundRect(kit.rect, 5f, 5f, kit.flat(0xFFD8352F.toInt()))
    c.drawCircle(0f, 19f, 5.5f, kit.flat(0xFFD8352F.toInt()))
    c.restore()
    val l = cardLayout(label.ifBlank { "SCAM!" })
    c.save(); c.translate(-50f, -l.height / 2f)
    // A soft dark offset gives the white text some lift off the red.
    c.translate(0f, 3f); textPaint.color = 0x40000000; l.draw(c)
    c.translate(0f, -3f); textPaint.color = 0xFFFFFFFF.toInt(); l.draw(c)
    c.restore()
  }

  /** A red warning card; as [p] goes 0..1 it cracks, splits diagonally and both halves tumble away. */
  fun scamCard(c: Canvas, label: String, p0: Float, time: Float) {
    val p = clamp01(p0)
    val crack = clamp01(p / 0.12f)
    val u = clamp01((p - 0.12f) / 0.88f)
    if (u <= 0f) {
      val shake = crack * (sin(time * 61f) * 5f)
      c.save(); c.translate(shake, 0f); c.rotate(crack * sin(time * 47f) * 1.5f)
      cardBody(c, label)
      if (crack > 0f) {
        c.save(); c.clipRect(-150f, -85f + 0f, 150f, -85f + 170f * crack)
        c.drawPath(cut, kit.line(0xFFFFFFFF.toInt(), 5f))
        c.drawPath(cut, kit.line(0xFF7A1714.toInt(), 2.5f))
        c.restore()
      }
      c.restore()
      return
    }
    val fade = 1f - smoothstep(0.55f, 1f, u)
    if (fade <= 0f) return
    for (side in intArrayOf(-1, 1)) {
      val dx = side * (16f + (if (side < 0) 130f else 105f) * easeOutCubic(u))
      val dy = 620f * u * u - 70f * u
      val rot = side * (if (side < 0) 30f else 24f) * u.pow(0.8f)
      val px = side * 70f
      val save = if (fade < 1f) kit.fadeLayer(c, fade, -260f + dx, -260f + dy, 260f + dx, 260f + dy) else c.save()
      c.translate(dx, dy)
      c.rotate(rot, px, 0f)
      c.clipPath(if (side < 0) leftHalf else rightHalf)
      cardBody(c, label)
      c.drawPath(cut, kit.line(0xCCFFFFFF.toInt(), 5f))
      c.restoreToCount(save)
    }
  }

  // ---- DOOR --------------------------------------------------------------------------------------

  /**
   * A plank door (260 x 520) in a stone frame. [p] 0..1 is how hard it is being pushed: it rattles,
   * the handle jiggles and dust shakes loose from the lintel.
   */
  fun door(c: Canvas, p0: Float, time: Float) {
    val p = clamp01(p0)
    val sx = p * (sin(time * 47f) * 6f + sin(time * 31f + 1.3f) * 3.5f)
    val sy = p * sin(time * 59f) * 2.5f
    val rot = p * (sin(time * 23f) * 1.6f + sin(time * 41f + 0.7f) * 0.8f)

    // Frame (barely moves).
    c.save(); c.translate(sx * 0.18f, sy * 0.18f)
    kit.rect.set(-178f, -300f, 186f, 300f)
    c.drawRoundRect(kit.rect, 18f, 18f, kit.flat(SHADOW))
    kit.rect.set(-182f, -308f, 182f, 290f)
    c.drawRoundRect(kit.rect, 18f, 18f, kit.flat(0xFF8B857F.toInt()))
    stoneBlocks(c)
    kit.rect.set(-136f, -266f, 136f, 266f)
    c.drawRoundRect(kit.rect, 8f, 8f, kit.flat(0xFF2B211C.toInt()))
    c.restore()

    // Door.
    c.save(); c.translate(sx, sy); c.rotate(rot, 0f, 260f)
    kit.rect.set(-130f, -260f, 130f, 260f)
    c.save(); c.clipRect(kit.rect)
    val plankW = 65f
    for (i in 0 until 4) {
      val l = -130f + i * plankW
      kit.rect2.set(l, -260f, l + plankW, 260f)
      c.drawRect(kit.rect2, kit.flat(if (i % 2 == 0) 0xFFB27646.toInt() else 0xFFA86B3C.toInt()))
      // Grain.
      val g = kit.line(0x30FFE2C0, 3f)
      for (j in 0 until 3) {
        val gx = l + 14f + j * 17f + hash01(i * 3 + j) * 6f
        val y0 = -230f + hash01(i * 7 + j) * 120f
        c.drawLine(gx, y0, gx + 3f, y0 + 140f + hash01(i + j * 5) * 120f, g)
      }
      if (i > 0) c.drawLine(l, -260f, l, 260f, kit.line(0xFF7A4A28.toInt(), 4f))
    }
    // Iron bands with rivets.
    for (by in floatArrayOf(-170f, 150f)) {
      kit.rect2.set(-134f, by - 14f, 134f, by + 14f)
      c.drawRect(kit.rect2, kit.flat(0xFF4A4440.toInt()))
      kit.rect2.set(-134f, by - 14f, 134f, by - 9f)
      c.drawRect(kit.rect2, kit.flat(0x40FFFFFF))
      for (rx in floatArrayOf(-108f, -43f, 22f, 87f)) {
        c.drawCircle(rx + 8f, by, 5.5f, kit.flat(0xFF8E8780.toInt()))
        c.drawCircle(rx + 6.5f, by - 1.5f, 2f, kit.flat(0xCCFFFFFF.toInt()))
      }
    }
    // Soft shading along the edges.
    kit.rect2.set(-130f, -260f, 130f, 260f)
    c.drawRect(kit.rect2, kit.line(0x553A2A26, 10f))
    c.restore()
    // Handle: backplate and a brass ring that jiggles.
    kit.rect.set(76f, -28f, 102f, 42f)
    c.drawRoundRect(kit.rect, 10f, 10f, kit.flat(0xFF3E3833.toInt()))
    c.save(); c.rotate(p * sin(time * 37f) * 18f, 89f, -4f)
    c.drawCircle(89f, 22f, 20f, kit.line(0xFFB8862E.toInt(), 8f))
    c.drawCircle(89f, 22f, 20f, kit.line(0xFFE4B24E.toInt(), 4f))
    c.restore()
    c.drawCircle(89f, -4f, 9f, kit.flat(0xFFD9A441.toInt()))
    c.drawCircle(86f, -7f, 3f, kit.flat(0xCCFFFFFF.toInt()))
    c.restore()

    // Dust shaken loose when pushed hard.
    val dust = smoothstep(0.35f, 0.8f, p)
    if (dust > 0f) {
      for (i in 0 until 8) {
        val ph = fract(time * (0.8f + 0.5f * hash01(i + 60)) + hash01(i + 70))
        val x = -120f + 240f * hash01(i + 80) + sin(ph * 6f + i) * 8f
        val y = -262f + ph * 220f
        val a = dust * (1f - ph)
        c.drawCircle(x, y, 3f + 3f * hash01(i + 90), kit.flat(withAlpha(0xFFCFC2B4.toInt(), a)))
      }
    }
  }

  private fun stoneBlocks(c: Canvas) {
    val seam = kit.line(0xFF6E6964.toInt(), 3f)
    val light = kit.fill
    // Jambs.
    for (sideX in floatArrayOf(-182f, 136f)) {
      var y = -266f; var row = 0
      while (y < 290f) {
        val h = 62f + hash01(row + (if (sideX < 0) 0 else 40)) * 22f
        val b = minOf(290f, y + h)
        kit.rect2.set(sideX + 3f, y + 3f, sideX + 43f, b - 3f)
        light.shader = null; light.color = if (row % 2 == 0) 0xFFA29C95.toInt() else 0xFF99938C.toInt()
        c.drawRoundRect(kit.rect2, 6f, 6f, light)
        c.drawLine(sideX + 3f, b, sideX + 43f, b, seam)
        y = b; row++
      }
    }
    // Lintel.
    var x = -182f; var col = 0
    while (x < 182f) {
      val w = 70f + hash01(col + 20) * 30f
      val r = minOf(182f, x + w)
      kit.rect2.set(x + 3f, -305f, r - 3f, -269f)
      light.shader = null; light.color = if (col % 2 == 0) 0xFFA8A29B.toInt() else 0xFF9D978F.toInt()
      c.drawRoundRect(kit.rect2, 6f, 6f, light)
      x = r; col++
    }
  }

  // ---- MOON --------------------------------------------------------------------------------------

  private val moonR = 130f
  private val moonGlow = RadialGradient(0f, 0f, 240f, intArrayOf(0x66FFF3C4, 0x22FFF3C4, 0x00FFF3C4), floatArrayOf(0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
  private val craters = floatArrayOf(
    -48f, -30f, 24f, 40f, 30f, 30f, -10f, 62f, 18f, 62f, -52f, 14f, -70f, 50f, 12f, 8f, -82f, 10f,
  )
  private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

  /** A full moon with craters and a planted plain flag; `text` "crescent" draws a crescent instead. */
  fun moon(c: Canvas, text: String, time: Float) {
    val crescent = text.contains("crescent", ignoreCase = true)
    val f = kit.fill
    f.shader = moonGlow; f.color = 0xFFFFFFFF.toInt()
    c.drawCircle(0f, 0f, 240f, f)
    f.shader = null
    kit.rect.set(-260f, -400f, 260f, 260f)
    val layer = c.saveLayer(kit.rect, null)
    c.drawCircle(0f, 0f, moonR, kit.flat(0xFFE0CC92.toInt()))
    c.save()
    kit.path.reset(); kit.path.addCircle(0f, 0f, moonR, Path.Direction.CW)
    c.clipPath(kit.path)
    c.drawCircle(-14f, -16f, moonR - 4f, kit.flat(0xFFF6EBC4.toInt()))
    var i = 0
    while (i < craters.size) {
      val cx = craters[i]; val cy = craters[i + 1]; val r = craters[i + 2]
      c.drawCircle(cx, cy, r, kit.flat(0xFFDDC88D.toInt()))
      c.drawCircle(cx + r * 0.18f, cy + r * 0.22f, r * 0.74f, kit.flat(0xFFE8D7A4.toInt()))
      i += 3
    }
    c.restore()
    if (crescent) c.drawCircle(62f, -40f, moonR * 0.96f, eraser)
    c.restoreToCount(layer)

    // Flag on the upper-left rim.
    val a = (if (crescent) -122f else -112f) * DEG
    val bx = cos(a) * (moonR - 6f); val by = sin(a) * (moonR - 6f)
    kit.rect.set(bx - 18f, by - 6f, bx + 18f, by + 8f)
    c.drawOval(kit.rect, kit.flat(0xFFD8C489.toInt()))
    c.save(); c.translate(bx, by); c.rotate(-8f)
    c.drawLine(0f, 0f, 0f, -124f, kit.line(0xFF6B6460.toInt(), 6f))
    c.drawCircle(0f, -126f, 5f, kit.flat(0xFFD9A441.toInt()))
    val p = kit.path
    p.reset()
    val fw = 76f; val fh = 46f; val top = -120f
    val n = 10
    for (k in 0..n) {
      val x = fw * k / n
      val y = top + wave(x, time)
      if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    for (k in n downTo 0) {
      val x = fw * k / n
      p.lineTo(x, top + fh + wave(x, time) * 1.1f)
    }
    p.close()
    c.save(); c.translate(3f, 4f); c.drawPath(p, kit.flat(SHADOW)); c.restore()
    c.drawPath(p, kit.flat(HAT_RED))
    // A soft fold highlight that travels with the wave.
    c.save(); c.clipPath(p)
    val fold = fract(time * 0.8f) * (fw + 30f) - 15f
    kit.rect.set(fold - 8f, top - 10f, fold + 8f, top + fh + 10f)
    c.drawRect(kit.rect, kit.flat(0x33FFFFFF))
    c.restore()
    c.restore()
  }

  private fun wave(x: Float, time: Float) = sin(x * 0.08f - time * 5f) * 5f * (x / 76f)

  // ---- DISCO_BALL --------------------------------------------------------------------------------

  private val ballR = 110f
  private val ballBase = RadialGradient(-38f, -42f, 170f, intArrayOf(0xFFB9C2CC.toInt(), 0xFF6E7884.toInt(), 0xFF3F4650.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
  private val ballGlow = RadialGradient(-40f, -44f, 70f, intArrayOf(0xAAFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
  private val tints = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFC2E2.toInt(), 0xFFB8F2FF.toInt(), 0xFFFFF0A8.toInt(), 0xFFD9C2FF.toInt())

  /**
   * A mirror ball (radius 110) hanging from a string about 420 px up. [p] spins it (one turn per 1.0);
   * tiles glint and sparkles twinkle with time.
   */
  fun discoBall(c: Canvas, p0: Float, time: Float) {
    val spin = p0 * 360f
    // Light rays sweeping behind the ball.
    for (i in 0 until 8) {
      val ang = (i * 45f + spin * 0.5f) * DEG
      val a = 0.10f + 0.06f * sin(time * 2f + i)
      val col = withAlpha(tints[1 + i % 4], a)
      val p = kit.path
      p.reset()
      val w = 0.07f
      p.moveTo(0f, 0f)
      p.lineTo(cos(ang - w) * 520f, sin(ang - w) * 520f)
      p.lineTo(cos(ang + w) * 520f, sin(ang + w) * 520f)
      p.close()
      c.drawPath(p, kit.flat(col))
    }
    // String and cap.
    c.drawLine(0f, -ballR, 0f, -ballR - 420f, kit.line(0xFF5A5450.toInt(), 4f))
    kit.rect.set(-16f, -ballR - 14f, 16f, -ballR + 6f)
    c.drawRoundRect(kit.rect, 5f, 5f, kit.flat(0xFF8C8C8C.toInt()))
    // Shadow, grout and base shading.
    c.drawCircle(6f, 10f, ballR, kit.flat(SHADOW))
    val f = kit.fill
    f.shader = ballBase; f.color = 0xFFFFFFFF.toInt()
    c.drawCircle(0f, 0f, ballR, f)
    f.shader = null
    // Tiles.
    val bands = 10; val cols = 20
    val p = kit.path
    for (b in 0 until bands) {
      val lat0 = (-90f + 180f * b / bands + 1.6f) * DEG
      val lat1 = (-90f + 180f * (b + 1) / bands - 1.6f) * DEG
      val latC = (lat0 + lat1) / 2f
      for (j in 0 until cols) {
        val lon0 = ((j * 360f / cols) + spin + (if (b % 2 == 0) 0f else 9f)) * DEG
        val lonC = lon0 + (180f / cols) * DEG
        if (cos(lonC) <= 0.05f) continue
        val lon1 = lon0 + (360f / cols - 3.2f) * DEG
        p.reset()
        p.moveTo(ballR * cos(lat0) * sin(lon0), ballR * sin(lat0))
        p.lineTo(ballR * cos(lat0) * sin(lon1), ballR * sin(lat0))
        p.lineTo(ballR * cos(lat1) * sin(lon1), ballR * sin(lat1))
        p.lineTo(ballR * cos(lat1) * sin(lon0), ballR * sin(lat1))
        p.close()
        // Light from the upper left and front.
        val nx = cos(latC) * sin(lonC); val ny = sin(latC); val nz = cos(latC) * cos(lonC)
        val lit = clamp01(-0.45f * nx - 0.55f * ny + 0.7f * nz)
        val seed = b * 31 + j
        val glint = max(0f, sin(time * (4f + 3f * hash01(seed)) + hash01(seed + 7) * 20f + p0 * 25f)).pow(10f)
        var col = lerpColor(0xFF6F7985.toInt(), 0xFFE9EEF3.toInt(), lit * (0.7f + 0.3f * hash01(seed + 3)))
        if (glint > 0.02f) col = lerpColor(col, tints[(hash01(seed + 11) * tints.size).toInt().coerceAtMost(tints.size - 1)], glint)
        c.drawPath(p, kit.flat(col))
      }
    }
    f.shader = ballGlow; f.color = 0xFFFFFFFF.toInt()
    c.drawCircle(-40f, -44f, 70f, f)
    f.shader = null
    c.drawCircle(0f, 0f, ballR, kit.line(0x553A2A26, 3f))
    // Sparkles.
    val spots = floatArrayOf(-150f, -62f, 146f, -110f, 162f, 92f, -128f, 120f, 40f, -160f)
    var k = 0
    while (k < spots.size) {
      val i = k / 2
      val tw = max(0f, sin(time * (2.6f + 0.5f * i) + i * 1.7f))
      kit.drawStar(c, spots[k], spots[k + 1], 30f * tw, time * 40f, 0xFFFFFFFF.toInt())
      kit.drawStar(c, spots[k], spots[k + 1], 13f * tw, 45f, tints[1 + i % 4])
      k += 2
    }
  }

  private fun lerpColor(a: Int, b: Int, t: Float): Int {
    val u = clamp01(t)
    fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u)).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }
}
