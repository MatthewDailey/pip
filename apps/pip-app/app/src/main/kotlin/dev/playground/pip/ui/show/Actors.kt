package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.playground.pip.ui.PipState

/**
 * Draws one actor. Pip-anchored actors are positioned relative to the top of Pip's head (x, y are offsets);
 * others use screen pixels. [alpha], [scale] and [rotation] are applied around the actor's position.
 * Each kind is drawn centred on (0, 0); see ActorDrawFx, ActorDrawProps and ActorDrawBackdrops.
 */
class ActorPainter(private val density: Float) {
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
  private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val path = Path()
  private val rect = RectF()
  private val values = FloatArray(9)

  private val kit = ActorKit(density)
  private val fx = FxDrawer(kit)
  private val props = PropsDrawer(kit)
  private val backdrops = BackdropDrawer(kit)

  fun draw(c: Canvas, a: ActorState, pip: PipState, stage: StageInfo, time: Float) {
    var x = a.x; var y = a.y
    if (a.anchorToPip) {
      // Top of Pip's head, ignoring rotation; bubbles shouldn't swing with his hops.
      x += pip.x
      y += pip.y - pip.size * (660f / 540f) * pip.squashY
    }
    val save = if (a.alpha < 1f) c.saveLayerAlpha(null, (a.alpha.coerceIn(0f, 1f) * 255).toInt()) else c.save()
    c.translate(x, y)
    c.rotate(a.rotation)
    c.scale(a.scale, a.scale)
    when (a.kind) {
      ActorKind.BUBBLE -> bubble(c, a.text, stage)
      ActorKind.BANNER -> banner(c, a.text, stage, a.scale)
      ActorKind.CUSTOM -> a.paint?.invoke(c, a, time)
      else -> {
        // Authored in 420-dpi pixels; keep proportions on other densities.
        c.scale(kit.k, kit.k)
        val p = a.progress
        when (a.kind) {
          ActorKind.SMOKE -> fx.smoke(c, p)
          ActorKind.SLASH -> fx.slash(c, p)
          ActorKind.SCAM_CARD -> props.scamCard(c, a.text, p, time)
          ActorKind.RAIN_CLOUD -> fx.rainCloud(c, p, time)
          ActorKind.SWEAT -> fx.sweat(c)
          ActorKind.MUSIC_NOTE -> fx.musicNote(c, a.text, a.color, (System.identityHashCode(a) % 1000) * 0.0137f, time)
          ActorKind.SPOTLIGHT -> backdrops.spotlight(c, a.color, time)
          ActorKind.DISCO_BALL -> props.discoBall(c, p, time)
          ActorKind.MOON -> props.moon(c, a.text, time)
          ActorKind.PEACE_BG -> backdrops.peaceBg(c, p, time)
          ActorKind.NEON_GRID -> backdrops.neonGrid(c, p, time)
          ActorKind.DOOR -> props.door(c, p, time)
          ActorKind.SPARKLE -> fx.sparkle(c, a.color, time)
          ActorKind.HEART -> fx.heart(c, a.color, time)
          ActorKind.CONFETTI -> fx.confetti(c, p, time)
          else -> {}
        }
      }
    }
    c.restoreToCount(save)
  }

  private var bubbleText: String? = null
  private var bubbleLayout: StaticLayout? = null

  /** Speech bubble whose tail points down at (0, 0). Clamped so it stays on screen. */
  private fun bubble(c: Canvas, s: String, stage: StageInfo) {
    text.color = Color.parseColor("#3A2A26"); text.textSize = 19f * density; text.typeface = Typeface.DEFAULT_BOLD
    text.style = Paint.Style.FILL; text.textAlign = Paint.Align.LEFT
    val layout = bubbleLayout?.takeIf { bubbleText == s } ?: run {
      val maxW = (260 * density).toInt()
      val w = minOf(maxW.toFloat(), text.measureText(s)).toInt().coerceAtLeast((40 * density).toInt())
      StaticLayout.Builder.obtain(s, 0, s.length, text, w).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        .also { bubbleText = s; bubbleLayout = it }
    }
    val pad = 14f * density
    val bw = layout.width + pad * 2; val bh = layout.height + pad * 2
    val gap = 14f * density
    // Keep the box on screen even when Pip stands near an edge.
    c.matrix.getValues(values)
    val screenX = values[2]
    var left = -bw / 2
    val margin = 12f * density
    if (screenX + left < margin) left = margin - screenX
    if (screenX + left + bw > stage.width - margin) left = stage.width - margin - screenX - bw
    val r = 18f * density
    // Soft drop shadow.
    rect.set(left + 2f * density, -gap - bh + 4f * density, left + bw + 2f * density, -gap + 4f * density)
    fill.shader = null; fill.color = 0x2E3A2A26
    c.drawRoundRect(rect, r, r, fill)
    rect.set(left, -gap - bh, left + bw, -gap)
    fill.color = Color.WHITE; stroke.color = Color.parseColor("#F05248"); stroke.strokeWidth = 3f * density
    path.reset(); path.moveTo(-10f * density, -gap - 1); path.lineTo(0f, 0f); path.lineTo(10f * density, -gap - 1); path.close()
    c.drawRoundRect(rect, r, r, fill)
    c.drawRoundRect(rect, r, r, stroke)
    c.drawPath(path, fill)
    c.drawLine(-10f * density, -gap, 0f, 0f, stroke); c.drawLine(0f, 0f, 10f * density, -gap, stroke)
    c.save(); c.translate(rect.left + pad, rect.top + pad); layout.draw(c); c.restore()
  }

  /**
   * Large outlined title text centred on (0, 0). At most 44 sp, shrunk so its on-screen width (after the
   * actor's [scale]) fits the screen minus 24 dp margins each side.
   */
  private fun banner(c: Canvas, s: String, stage: StageInfo, scale: Float) {
    text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    text.textAlign = Paint.Align.CENTER
    val maxSize = 44f * density
    text.textSize = maxSize
    val stroke = 7f * density
    val avail = (stage.width - 48f * density) / scale.coerceAtLeast(0.01f)
    val measured = text.measureText(s) + stroke
    val size = if (measured > avail) maxSize * avail / measured else maxSize
    text.textSize = size
    val sw = stroke * size / maxSize
    text.style = Paint.Style.FILL; text.color = 0x403A2A26
    c.drawText(s, 0f, sw * 0.6f, text)
    text.style = Paint.Style.STROKE; text.strokeWidth = sw; text.strokeJoin = Paint.Join.ROUND
    text.color = Color.parseColor("#3A2A26")
    c.drawText(s, 0f, 0f, text)
    text.style = Paint.Style.FILL; text.color = Color.WHITE
    c.drawText(s, 0f, 0f, text)
    text.textAlign = Paint.Align.LEFT
  }
}
