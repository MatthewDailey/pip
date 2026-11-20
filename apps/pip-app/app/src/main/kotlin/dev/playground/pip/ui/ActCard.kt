package dev.playground.pip.ui

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.playground.pip.act.Choice
import dev.playground.pip.act.Escalation
import dev.playground.pip.act.WarningEvent

/**
 * Pip stepping in on a call, drawn from the "Pip mutes a live call" mock but compact: one card with a small Pip in
 * its corner, docked under the heads-up call notification and the caller's name, clear of the in-call screen's button grid (mute, keypad, speaker, add call, hold, end),
 * which stays visible and tappable. The act card is solid act red with white text; with the rule off, the danger
 * card is white with a red outline and offers "Mute me now".
 *
 * The window is a non-focusable TYPE_ACCESSIBILITY_OVERLAY the size of the card, with no scrim, so touches outside
 * it reach the phone app. This Pip is the card's own [PipRive], so the intervention works with the wandering overlay
 * Pip turned off. Colours are the re-skin's `--pip-*` tokens. [onChoice] gets the tapped [Choice] id. Main thread only.
 */
class ActCard(private val service: AccessibilityService, private val onChoice: (String) -> Unit) {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private var card: View? = null
  private var rive: PipRive? = null

  private val dark get() = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
  private val act get() = if (dark) 0xFFD9382C.toInt() else 0xFFA3170F.toInt()
  private val danger get() = if (dark) 0xFFFF6E62.toInt() else 0xFFCF3328.toInt()
  private val dangerC get() = if (dark) 0xFF3D1915.toInt() else 0xFFFDE3DF.toInt()
  private val info get() = if (dark) 0xFF7CC79C.toInt() else 0xFF2F7A55.toInt()
  private val infoC get() = if (dark) 0xFF1B2E23.toInt() else 0xFFE2F1E7.toInt()
  private val surface get() = if (dark) 0xFF241A17.toInt() else Color.WHITE
  private val ink get() = if (dark) 0xFFF7E9E2.toInt() else 0xFF2A1714.toInt()
  private val line get() = if (dark) 0xFF3D2F2A.toInt() else 0xFFE9DCD4.toInt()
  private val primary get() = if (dark) 0xFFFDE3D2.toInt() else 0xFF8B3F37.toInt()
  private val onPrimary get() = if (dark) 0xFF3A1A16.toInt() else Color.WHITE

  data class Placement(val top: Int, val bottom: Int, val clearOf: Int?)

  /** Shows [w]. [keepClearOf] is the top of the phone app's button grid in screen pixels, when it was found. */
  fun show(w: WarningEvent, keepClearOf: Int?): Placement {
    val isAct = w.escalation == Escalation.ACT
    val fg = if (isAct) Color.WHITE else ink
    val box = column(if (isAct) act else surface, if (isAct) act else danger)
    val words = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    words.addView(pill(if (isAct) "Pip stepped in" else "Danger", if (isAct) 0x33FFFFFF else dangerC, if (isAct) Color.WHITE else danger))
    words.addView(text(w.headline, 22f, 28f, fg, bold = true), gap(6))
    words.addView(text(w.reason, 16f, 22f, fg), gap(2))
    box.addView(header(words))
    val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
    val choices = listOfNotNull(w.undo?.let { Choice("undo", it.label, primary = true) }) + w.choices
    choices.forEachIndexed { i, c ->
      val btn = when {
        isAct && c.primary -> button(c.label, Color.WHITE, act, null)
        isAct -> button(c.label, Color.TRANSPARENT, Color.WHITE, 0x99FFFFFF.toInt())
        c.primary -> button(c.label, primary, onPrimary, null)
        else -> button(c.label, Color.TRANSPARENT, ink, line)
      }
      btn.setOnClickListener { onChoice(c.id) }
      row.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (c.primary) 1.3f else 1f).apply { if (i > 0) marginStart = dp(8) })
    }
    box.addView(row, gap(10))
    return put(box, isAct, keepClearOf)
  }

  /** A short info-level card that replaces the act card, e.g. after undo. */
  fun confirm(text: String, millis: Long = 3500) {
    val box = column(surface, info)
    val words = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    words.addView(pill("Info", infoC, info))
    words.addView(text(text, 18f, 26f, ink), gap(6))
    box.addView(header(words))
    rive?.setEnum("mood", "happy")
    rive?.setNumber("mouth", -1f)
    put(box, firm = null, keepClearOf = null)
    main.postDelayed({ if (card?.tag === box) dismiss() }, millis)
  }

  fun dismiss() {
    card?.let { runCatching { wm.removeView(it) } }
    card = null
  }


  /** Talk animation while Pip's line is spoken. */
  fun talk(millis: Long) {
    rive?.setBoolean("talking", true)
    main.postDelayed({ rive?.setBoolean("talking", false) }, millis)
  }

  /** The card's top row: its words, and Pip standing in the top-right corner, so the two read as one unit. */
  private fun header(words: View): LinearLayout {
    val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    val pip = rive ?: PipRive(service).also { rive = it }
    (pip.view.parent as? ViewGroup)?.removeView(pip.view)
    row.addView(pip.view, LinearLayout.LayoutParams(dp(PIP_DP), (dp(PIP_DP) * 1.15f).toInt()).apply { marginStart = dp(6) })
    return row
  }

  /** [firm] true: Pip is firm (the rig's `angry` with the flat mouth, the closest to the mock's face); false: alarmed. */
  private fun put(box: LinearLayout, firm: Boolean?, keepClearOf: Int?): Placement {
    dismiss()
    box.tag = box
    rive?.installOwners(box)
    if (firm != null) {
      rive?.setEnum("mood", if (firm) "angry" else "surprised")
      rive?.setNumber("mouth", if (firm) 5f else -1f)
      rive?.fire("alert")
    }
    val width = wm.currentWindowMetrics.bounds.width() - dp(24)
    // Measure the words and buttons only: Pip's Compose view can't be measured before it has a window.
    val height = estimateHeight(box, width)
    // Below the call notification's heads-up and the caller's name, above the in-call button grid.
    var top = dp(TOP_DP)
    if (keepClearOf != null && top + height > keepClearOf - dp(8)) {
      top = (keepClearOf - dp(8) - height).coerceAtLeast(dp(40))
    }
    wm.addView(box, params(width, touchable = true).apply { y = top })
    // The token scale's 200 ms bubble pop.
    box.scaleX = 0.7f; box.scaleY = 0.7f; box.alpha = 0f
    box.pivotX = width.toFloat(); box.pivotY = 0f
    box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(OvershootInterpolator(1.4f)).start()
    card = box
    return Placement(top, top + height, keepClearOf)
  }

  private fun estimateHeight(box: LinearLayout, width: Int): Int {
    var h = box.paddingTop + box.paddingBottom
    for (i in 0 until box.childCount) {
      val child = box.getChildAt(i)
      val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
      h += lp?.topMargin ?: 0
      h += if (child is LinearLayout && child.orientation == LinearLayout.HORIZONTAL && child.childCount == 2 && child.getChildAt(1) === rive?.view) {
        val words = child.getChildAt(0)
        words.measure(View.MeasureSpec.makeMeasureSpec(width - box.paddingLeft - box.paddingRight - dp(PIP_DP + 6), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        maxOf(words.measuredHeight, (dp(PIP_DP) * 1.15f).toInt())
      } else {
        child.measure(View.MeasureSpec.makeMeasureSpec(width - box.paddingLeft - box.paddingRight, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        child.measuredHeight
      }
    }
    return h
  }

  private fun params(width: Int, touchable: Boolean) = WindowManager.LayoutParams(
    width, WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT,
  ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

  private fun column(bg: Int, border: Int) = LinearLayout(service).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(12), dp(14), dp(14))
    background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(bg); setStroke(dp(2), border) }
    elevation = dp(10).toFloat()
  }

  private fun text(s: String, sp: Float, lineSp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
    text = s
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineSp, service.resources.displayMetrics).toInt()
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, 600, false)
  }

  private fun pill(s: String, bg: Int, fg: Int, big: Boolean = false) = TextView(service).apply {
    text = "●  $s"
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 15f else 13f)
    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    setPadding(dp(10), dp(3), dp(12), dp(3))
    background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); setColor(bg) }
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
  }

  private fun button(s: String, bg: Int, fg: Int, stroke: Int?) = TextView(service).apply {
    text = s
    gravity = Gravity.CENTER
    minHeight = dp(44)
    setPadding(dp(8), 0, dp(8), 0)
    setTextColor(fg)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(bg); stroke?.let { setStroke(dp(2), it) } }
    isClickable = true
  }

  private fun gap(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

  private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

  /** TOP_DP: just below the heads-up call notification and the in-call screen's caller name on a phone-sized screen. */
  private companion object {
    const val TOP_DP = 200
    const val PIP_DP = 72
  }
}
