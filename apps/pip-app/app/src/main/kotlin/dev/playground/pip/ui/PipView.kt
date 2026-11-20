package dev.playground.pip.ui

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import dev.playground.pip.ui.anim.Animation
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.INFINITE
import dev.playground.pip.ui.anim.Timing
import dev.playground.pip.ui.anim.keyframes
import dev.playground.pip.ui.show.blink
import dev.playground.pip.ui.show.hatSway
import dev.playground.pip.ui.show.squash

/** Pip at rest: drawn by [PipRenderer] with an idle loop of blinks, a breathing squash and a swaying hat tip. */
class PipView(context: Context) : View(context), Choreographer.FrameCallback {
  private val renderer = PipRenderer(dev.playground.pip.ui.PipArt.get(context))
  private val state = PipState()
  private var start = 0L
  private var t = 0f
  private var running = false

  private val idle = listOf(
    Animation(state, keyframes { at(0) { blink(0) }; at(90) { blink(0) }; at(93) { blink(1) }; at(96) { blink(0) } }, 0f, Timing(4.2f, ease = Easing.linear, iterations = INFINITE), 0),
    Animation(state, keyframes { from { squash(1.0, 1.0) }; to { squash(1.03, 0.97) } }, 0f, Timing(1.4f, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE), 1),
    Animation(state, keyframes { from { hatSway(-0.3) }; to { hatSway(0.3) } }, 0.3f, Timing(1.7f, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = dev.playground.pip.ui.anim.Fill.BOTH), 2),
  )

  /** Extra pose applied on top of the idle loop, e.g. to look worried during a warning. */
  var pose: (PipState.() -> Unit)? = null

  override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; Choreographer.getInstance().postFrameCallback(this) }
  override fun onDetachedFromWindow() { running = false; Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow() }

  override fun doFrame(frameTimeNanos: Long) {
    if (!running) return
    if (start == 0L) start = frameTimeNanos
    t = (frameTimeNanos - start) / 1e9f
    invalidate()
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun onDraw(c: Canvas) {
    state.reset()
    // Pip only fills the middle of the view: the swaying hat tip, squash, hands and props all reach past his
    // 540 x 660 box, and a view clips anything drawn outside its bounds.
    state.size = width * BODY_FRACTION
    state.x = width / 2f
    state.y = height - height * 0.08f
    for (a in idle) a.applyAt(t)
    pose?.invoke(state)
    renderer.draw(c, state)
  }


  companion object {
    /** Share of the view's width Pip's body takes; the rest is room for motion and props. */
    const val BODY_FRACTION = 0.55f
  }
}
