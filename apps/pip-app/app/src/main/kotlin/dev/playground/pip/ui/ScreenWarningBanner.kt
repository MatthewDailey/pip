package dev.playground.pip.ui

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal full-width scam warning shown over the current app when Jev says to intervene
 * on a screen. Uses TYPE_ACCESSIBILITY_OVERLAY so it needs no "draw over other apps"
 * permission. Reports its height so Pip's speech bubble can sit below it, and is dismissed
 * when the person leaves the flagged page or a call warning takes the screen.
 */
class ScreenWarningBanner(
  private val service: AccessibilityService,
  /** Called with the bottom edge of the banner on screen, in px, or 0 when dismissed. */
  private val onReservedTop: (Int) -> Unit = {},
) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private var shown = false

  private val title = TextView(service).apply {
    text = "Stop — this looks like a scam"
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTypeface(typeface, Typeface.BOLD)
  }
  private val body = TextView(service).apply {
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setPadding(0, dp(6), 0, dp(4))
  }
  private val hint = TextView(service).apply {
    text = "Don't type a password, card, or code. Tap to dismiss."
    setTextColor(Color.parseColor("#FFD9D6"))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
  }
  private val root = LinearLayout(service).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = GradientDrawable().apply {
      cornerRadius = dp(0).toFloat()
      setColor(Color.parseColor("#C62828"))
    }
    elevation = dp(10).toFloat()
    addView(title)
    addView(body)
    addView(hint)
    setOnClickListener { dismiss() }
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  }

  private val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
  ).apply { gravity = Gravity.TOP }

  fun show(message: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { show(message) }
      return
    }
    body.text = message
    if (!shown) {
      try {
        wm.addView(root, params)
        shown = true
      } catch (_: Exception) {
        // Service may have disconnected; the notification / Pip bubble still fire.
      }
    }
    reportReservedTop()
  }

  fun dismiss() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { dismiss() }
      return
    }
    if (!shown) return
    try { wm.removeView(root) } catch (_: Exception) {}
    shown = false
    onReservedTop(0)
  }

  val isShowing: Boolean get() = shown

  private fun reportReservedTop() {
    root.post {
      if (!shown) return@post
      val loc = IntArray(2)
      root.getLocationOnScreen(loc)
      val bottom = loc[1] + root.height
      if (bottom > 0) onReservedTop(bottom)
    }
  }

  private fun dp(n: Int) = (n * service.resources.displayMetrics.density).toInt()
}
