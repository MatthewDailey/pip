package dev.playground.pip.ui

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.playground.pip.core.PipFlags
import kotlin.math.abs
import kotlin.random.Random

/**
 * Pip, drawn above every app. An accessibility service may add TYPE_ACCESSIBILITY_OVERLAY windows without the
 * "display over other apps" permission, so this needs nothing beyond the service already being on.
 *
 * Pip wanders between random spots, can be dragged, and shows a speech bubble when tapped or when [say] is called
 * (scam warnings route here). [rive] plays him from `pip.riv`; idle motion such as the bob lives in that file.
 * Overlay events drive its view model: a warning fires `alert` and sets `mood`, a tap fires `poke`, and `talking`
 * is true while the bubble is up.
 *
 * With [PipFlags.exploratory] off (the shipping default) Pip stays off screen between interventions: no idle
 * greeting, no wandering, no idle lines on tap. He appears for [say] and leaves when the bubble closes.
 *
 * The bubble's mood is temporary: when the bubble closes Pip goes back to his resting mood, which [setMood] sets
 * (the PIP debug broadcast routes `mood` here). A mood set while a bubble is up shows at once and is the one
 * restored when it closes.
 */
class PipOverlay(private val service: AccessibilityService) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private val root = LinearLayout(service)
  private val bubble = TextView(service)
  /** Exposed so the service's debug broadcast can drive the same view model. */
  val rive = PipRive(service)
  private val pip = rive.view
  private val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
  ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(40); y = dp(400) }

  private var move: ValueAnimator? = null
  private var talkingUntil = 0L
  /** The mood Pip returns to when a bubble closes. Main thread only. */
  private var restingMood = "neutral"
  private var shown = false
  /** Whether Pip's window is up, so an intervention that hides him knows whether to bring him back. */
  val isShown get() = shown
  /** Bottom edge of a top-of-screen banner, in px, so the bubble parks below it instead of under it. */
  private var reservedTopPx = 0
  /** Kept visible in the middle of a full-screen call warning, even with exploratory off. */
  private var parkedForWarning = false
  private val wanderTask = Runnable { wander() }
  private val onExploratory: (Boolean) -> Unit = { on -> main.post { if (shown) idle(on) } }

  private val idleLines = listOf(
    "Hi, I'm Pip! I'm keeping an eye out for scams.",
    "Nobody real asks for gift cards. Not even the IRS.",
    "If a caller rushes you, hang up and call family.",
    "Never read a code from a text to anyone on the phone.",
  )

  fun show() {
    if (shown) return
    shown = true
    root.orientation = LinearLayout.VERTICAL
    root.gravity = Gravity.CENTER_HORIZONTAL
    bubble.apply {
      setTextColor(Color.parseColor("#3A2A26"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      maxWidth = dp(240)
      setPadding(dp(14), dp(10), dp(14), dp(10))
      background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(2), Color.parseColor("#F05248")) }
      elevation = dp(6).toFloat()
      visibility = View.GONE
    }
    // show() runs again after every showreel; the views keep their parent between runs, only the window comes and goes.
    if (root.childCount == 0) {
      root.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
      root.addView(pip, LinearLayout.LayoutParams(dp(200), dp(200)))
      pip.setOnTouchListener(DragOrTap())
      rive.installOwners(root)
    }
    wm.addView(root, params)
    PipFlags.addExploratoryListener(onExploratory)
    idle(PipFlags.exploratory)
  }

  /** Exploratory: greet and wander. Otherwise: off screen until something needs saying. */
  private fun idle(exploratory: Boolean) {
    main.removeCallbacks(wanderTask)
    if (exploratory) {
      root.visibility = View.VISIBLE
      say(idleLines[0], 4000)
      main.postDelayed(wanderTask, 4500)
    } else if (System.currentTimeMillis() >= talkingUntil) {
      move?.cancel()
      root.visibility = View.GONE
    }
  }

  /**
   * Takes the window down for a show or an intervention. A bubble that was up is dropped with it rather than left
   * pending (its close callback goes with the other callbacks), so the next [show] decides visibility from the flag
   * alone: with [PipFlags.exploratory] off, Pip stays off screen until the next [say].
   */
  fun hide() {
    if (!shown) return
    shown = false
    PipFlags.removeExploratoryListener(onExploratory)
    main.removeCallbacksAndMessages(null)
    move?.cancel()
    talkingUntil = 0L
    bubble.visibility = View.GONE
    root.visibility = View.GONE
    rive.setBoolean("talking", false)
    rive.setEnum("mood", "neutral")
    runCatching { wm.removeView(root) }
  }

  /** Shows a speech bubble and holds Pip still while it is up. Safe from any thread. */
  fun say(text: String, millis: Long = 6000, urgent: Boolean = false) = main.post {
    if (!shown) return@post
    bubble.text = text
    bubble.visibility = View.VISIBLE
    root.visibility = View.VISIBLE
    talkingUntil = System.currentTimeMillis() + millis
    move?.cancel()
    rive.setBoolean("talking", true)
    rive.setEnum("mood", if (urgent) "surprised" else "happy")
    if (urgent) {
      rive.fire("alert")
      if (parkedForWarning) {
        // Stay in the middle of the call warning so Hang up stays tappable.
        shake()
      } else {
        // Sit just below any screen-fraud banner so the bubble isn't tucked under it.
        animateTo(topParkX(), topParkY()) { shake() }
      }
    }
    main.postDelayed({
      if (System.currentTimeMillis() >= talkingUntil) {
        bubble.visibility = View.GONE
        rive.setBoolean("talking", false)
        rive.setEnum("mood", restingMood)
        if (!PipFlags.exploratory && !parkedForWarning) root.visibility = View.GONE
        Log.i(PipRive.TAG, "bubble closed; mood back to $restingMood")
      }
    }, millis)
  }

  /**
   * How much of the top of the screen is occupied by a banner, in pixels. Pip's urgent
   * bubble parks just below this so it doesn't tuck under the screen-fraud warning.
   * Safe from any thread.
   */
  fun setReservedTop(px: Int) = main.post {
    reservedTopPx = px.coerceAtLeast(0)
    if (!parkedForWarning && shown && root.visibility == View.VISIBLE && System.currentTimeMillis() < talkingUntil) {
      params.x = topParkX()
      params.y = topParkY()
      runCatching { wm.updateViewLayout(root, params) }
    }
  }

  /**
   * Park Pip near the top (below any banner) so bottom actions stay tappable.
   * Safe from any thread.
   */
  fun parkAtTop() = park(middle = false)

  /**
   * Park Pip in the empty middle of a full-screen call warning, keep him visible even
   * when exploratory is off, and leave Hang up and the other buttons clear. Safe from any thread.
   */
  fun parkInMiddle() = park(middle = true)

  /** Lets Pip hide again after the call warning closes, if exploratory is off. Safe from any thread. */
  fun clearWarningPark() = main.post {
    parkedForWarning = false
    if (!PipFlags.exploratory && System.currentTimeMillis() >= talkingUntil) {
      root.visibility = View.GONE
    }
  }

  private fun park(middle: Boolean) = main.post {
    if (!shown) return@post
    move?.cancel()
    parkedForWarning = middle
    root.visibility = View.VISIBLE
    applyPark(middle)
    // Position depends on measured size; update again once the window has been laid out.
    root.post { if (shown && parkedForWarning == middle) applyPark(middle) }
  }

  private fun applyPark(middle: Boolean) {
    val (w, h) = screen()
    val rw = if (root.width > 0) root.width else dp(200)
    val rh = if (root.height > 0) root.height else dp(200)
    params.x = ((w - rw) / 2).coerceAtLeast(0)
    params.y = if (middle) {
      val topBound = reservedTopPx + dp(200)
      val bottomBound = (h - dp(240) - rh).coerceAtLeast(topBound)
      ((topBound + bottomBound) / 2).coerceIn(0, (h - rh).coerceAtLeast(0))
    } else {
      topParkY()
    }
    runCatching { wm.updateViewLayout(root, params) }
  }

  /** Sets Pip's resting mood (a Rive `mood` enum name) and shows it now, even over a bubble. Safe from any thread. */
  fun setMood(mood: String) = main.post {
    restingMood = mood
    rive.setEnum("mood", mood)
    Log.i(PipRive.TAG, "resting mood set to $mood")
  }

  private fun wander() {
    if (!shown || !PipFlags.exploratory) return
    if (System.currentTimeMillis() < talkingUntil) { main.postDelayed(wanderTask, 1000); return }
    val (w, h) = screen()
    val tx = Random.nextInt(0, (w - root.width).coerceAtLeast(1))
    val ty = Random.nextInt(dp(80), (h - root.height - dp(80)).coerceAtLeast(dp(81)))
    animateTo(tx, ty) { main.postDelayed(wanderTask, Random.nextLong(1500, 4000)) }
  }

  private fun animateTo(tx: Int, ty: Int, then: () -> Unit) {
    move?.cancel()
    val sx = params.x; val sy = params.y
    pip.scaleX = if (tx < sx) -1f else 1f // face the way Pip is walking
    move = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 1200L + (abs(tx - sx) + abs(ty - sy)) / 2
      interpolator = AccelerateDecelerateInterpolator()
      addUpdateListener {
        val f = it.animatedValue as Float
        params.x = (sx + (tx - sx) * f).toInt()
        // A small arc so it reads as hopping, not sliding.
        params.y = (sy + (ty - sy) * f - dp(30) * 4 * f * (1 - f)).toInt()
        if (shown) wm.updateViewLayout(root, params)
      }
      addListener(object : AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: Animator) { cancelled = true }
        override fun onAnimationEnd(animation: Animator) { if (!cancelled) { squash(); then() } }
      })
      start()
    }
  }

  private fun squash() {
    val face = pip.scaleX
    pip.animate().scaleY(0.85f).scaleX(face * 1.1f).setDuration(90).withEndAction {
      pip.animate().scaleY(1f).scaleX(face).setDuration(140).start()
    }.start()
  }

  private fun shake() {
    ObjectAnimator.ofFloat(pip, View.ROTATION, 0f, -12f, 12f, -10f, 10f, -6f, 6f, 0f).apply { duration = 700; start() }
  }

  private inner class DragOrTap : View.OnTouchListener {
    private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragged = false
    override fun onTouch(v: View, e: MotionEvent): Boolean {
      when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> { move?.cancel(); downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragged = false }
        MotionEvent.ACTION_MOVE -> {
          if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(8)) dragged = true
          if (dragged) { params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(root, params) }
        }
        MotionEvent.ACTION_UP -> {
          if (!dragged) { rive.fire("poke"); if (PipFlags.exploratory) say(idleLines.random(), 4000) } else squash()
          main.removeCallbacks(wanderTask)
          main.postDelayed(wanderTask, 5000)
        }
      }
      return true
    }
  }

  private fun screen(): Pair<Int, Int> = wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
  private fun topParkY() = (reservedTopPx + dp(8)).coerceAtLeast(dp(8))
  private fun topParkX(): Int {
    val width = if (root.width > 0) root.width else dp(200)
    return ((screen().first - width) / 2).coerceAtLeast(0)
  }
  private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
