package dev.playground.pip.ui.show

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.playground.pip.ui.PipRenderer
import dev.playground.pip.ui.PipState

/** A full-screen show overlay: [start] adds it, and it removes itself when the show ends or on [stop]. */
interface StagePlayer {
  fun start(fromScene: String? = null)
  fun stop()
}

/** Full-screen, untouchable overlay that plays a [Show] from start to finish, then removes itself. */
class ShowRunner(private val service: AccessibilityService, private val show: Show, private val onDone: () -> Unit) : StagePlayer {
  private val wm = service.getSystemService(WindowManager::class.java)
  private var view: StageView? = null

  override fun start(fromScene: String?) {
    if (view != null) return
    val bounds = wm.currentWindowMetrics.bounds
    val stage = StageInfo(bounds.width().toFloat(), bounds.height().toFloat(), findLauncherIcons(service))
    val pip = show.prepare(stage)
    val offset = fromScene?.let { show.startOf(it) } ?: 0f
    val v = StageView(service, show, stage, pip, offset) { stop() }
    view = v
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    wm.addView(v, lp)
    Log.i(TAG, "PIPSHOW start offset=$offset duration=${show.duration} icons=${stage.icons.keys}")
  }

  override fun stop() {
    val v = view ?: return
    view = null
    v.halt()
    runCatching { wm.removeView(v) }
    Log.i(TAG, "PIPSHOW end")
    onDone()
  }

  companion object { const val TAG = "PipShow" }
}

/** Launcher icons on the current home screen, by label, in screen pixels. */
internal fun findLauncherIcons(service: AccessibilityService): Map<String, RectF> {
  val out = mutableMapOf<String, RectF>()
  fun walk(n: AccessibilityNodeInfo?, depth: Int) {
    if (n == null || depth > 30) return
    val label = n.text?.toString() ?: n.contentDescription?.toString()
    if (label != null && label.length < 30) {
      val r = android.graphics.Rect(); n.getBoundsInScreen(r)
      if (r.width() in 100..400 && r.height() in 100..400) out.putIfAbsent(label, RectF(r))
    }
    for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
  }
  runCatching { walk(service.rootInActiveWindow, 0) }
  return out
}

private class StageView(
  context: Context,
  private val show: Show,
  private val stage: StageInfo,
  private val pip: PipState,
  private val offset: Float,
  private val onFinished: () -> Unit,
) : View(context), Choreographer.FrameCallback {
  private val renderer = PipRenderer(dev.playground.pip.ui.PipArt.get(context))
  private val painter = ActorPainter(resources.displayMetrics.density)
  private val actors = mutableListOf<ActorState>()
  private val clip = Path()
  private var startNanos = 0L
  private var lastT = -1f
  private var running = true
  private var t = offset

  init { Choreographer.getInstance().postFrameCallback(this) }

  fun halt() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

  override fun doFrame(frameTimeNanos: Long) {
    if (!running) return
    if (startNanos == 0L) startNanos = frameTimeNanos
    t = offset + (frameTimeNanos - startNanos) / 1e9f
    for ((at, name) in show.cuesBetween(lastT, t)) Log.i(ShowRunner.TAG, "PIPCUE $name $at")
    lastT = t
    if (t >= show.duration) { onFinished(); return }
    invalidate()
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun onDraw(c: Canvas) {
    show.frame(t, actors)
    for (a in actors) if (a.layer < 0) painter.draw(c, a, pip, stage, t)
    val hide = pip.hideBehind
    if (hide != null) {
      // Mask Pip where the icon and its label are, so he looks tucked behind them.
      val spot = stage.icon(hide)
      clip.reset()
      clip.addCircle(spot.cx, spot.cy, spot.radius, Path.Direction.CW)
      clip.addRect(spot.cell.left, spot.cy, spot.cell.right, spot.cell.bottom + 40f, Path.Direction.CW)
      c.save(); c.clipOutPath(clip); renderer.draw(c, pip); c.restore()
    } else renderer.draw(c, pip)
    for (a in actors) if (a.layer >= 0) painter.draw(c, a, pip, stage, t)
  }
}
