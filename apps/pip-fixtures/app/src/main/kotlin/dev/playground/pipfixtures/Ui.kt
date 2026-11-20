package dev.playground.pipfixtures

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny view builders: the fixtures are plain Views so the APK builds fast and has no dependencies. */
internal fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

internal fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
  cornerRadius = radius.toFloat()
  setColor(color)
  if (stroke > 0) setStroke(stroke, strokeColor)
}

internal fun Context.text(value: String, sp: Float = 17f, bold: Boolean = false, color: Int = Color.parseColor("#1C1C1E")) =
  TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
  }

internal fun Context.column(padding: Int = 16) = LinearLayout(this).apply {
  orientation = LinearLayout.VERTICAL
  setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

internal fun Context.row() = LinearLayout(this).apply {
  orientation = LinearLayout.HORIZONTAL
  gravity = Gravity.CENTER_VERTICAL
}

internal fun Context.pill(label: String, bg: Int, fg: Int = Color.WHITE) = Button(this).apply {
  text = label
  isAllCaps = false
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
  setTextColor(fg)
  background = rounded(bg, dp(26))
  minHeight = dp(52)
}

internal fun Context.field(hint: String, digits: Boolean = false) = EditText(this).apply {
  this.hint = hint
  setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
  background = rounded(Color.WHITE, dp(10), dp(1), Color.parseColor("#C9C9D1"))
  setPadding(dp(12), dp(12), dp(12), dp(12))
  if (digits) inputType = android.text.InputType.TYPE_CLASS_NUMBER
}

internal fun Context.avatar(letter: String, color: Int) = TextView(this).apply {
  text = letter
  gravity = Gravity.CENTER
  setTextColor(Color.WHITE)
  typeface = Typeface.DEFAULT_BOLD
  background = rounded(color, dp(18))
  layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
}

/** A header row with a back arrow that finishes the activity, like the real apps the mocks show. */
internal fun Activity.header(title: String, lead: View? = null) = row().apply {
  setPadding(dp(8), dp(8), dp(16), dp(8))
  addView(text("‹", 30f).apply { setPadding(dp(8), 0, dp(12), dp(4)); setOnClickListener { finish() } })
  lead?.let(::addView)
  addView(text(title, 18f, bold = true))
}

internal fun bubble(context: Context, value: String, mine: Boolean = false) = context.text(value).apply {
  background = rounded(if (mine) Color.parseColor("#D3E3FD") else Color.parseColor("#E4E4EA"), context.dp(20))
  setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
  layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
    topMargin = context.dp(8)
    gravity = if (mine) Gravity.END else Gravity.START
  }
  maxWidth = context.dp(290)
}

/** Sets the content inside the system bars and above the keyboard (targetSdk 36 is always edge to edge). */
internal fun Activity.screen(content: View, scroll: Boolean = true, footer: View? = null) {
  val root = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Color.parseColor("#F7F7FA"))
  }
  val body = if (scroll) ScrollView(this).apply { addView(content) } else content
  root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
  footer?.let { root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
  root.setOnApplyWindowInsetsListener { v, insets ->
    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    WindowInsets.CONSUMED
  }
  setContentView(root)
}

internal val BLUE = Color.parseColor("#1A73E8")
internal val GREY = Color.parseColor("#6E6E78")
