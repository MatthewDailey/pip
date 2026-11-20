package dev.playground.pipfixtures

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout

/*
 * Every brand, number and address here is fictional: "Example*" names, 555 numbers, .example-style domains.
 * Extras let a scenario change the copy (`adb shell am start -n ... --es message "..."`); the defaults match the mocks.
 */

/** ExampleMail with a scam email already open: a fake payment-service hold with a call-back number. */
class MailActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val subject = intent.getStringExtra("subject") ?: "Your ExamplePay account is on hold"
    val from = intent.getStringExtra("from") ?: "ExamplePay Support"
    val address = intent.getStringExtra("address") ?: "service@examplepay-billing.co"
    val body = intent.getStringExtra("body")
      ?: "We noticed a charge of \$649.99 for SecureShield Antivirus.\n\nIf you didn't make this purchase, call 1-888-555-0170 within 24 hours to cancel it and get a refund.\n\nExamplePay Billing Team"
    val c = column()
    c.addView(header("Inbox"))
    c.addView(text(subject, 22f, bold = true).apply { setPadding(0, dp(12), 0, dp(12)) })
    c.addView(row().apply {
      addView(avatar(from.take(1), BLUE))
      addView(column(0).apply {
        addView(text(from, 16f, bold = true))
        addView(text(address, 14f, color = GREY))
      })
    })
    c.addView(text(body, 17f).apply { setPadding(0, dp(18), 0, dp(18)); setLineSpacing(0f, 1.25f) })
    c.addView(pill("Call support", BLUE))
    screen(c)
  }
}

/** ExampleChat: a thread from a stranger and a focused message box with the keyboard up. */
class ChatActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val contact = intent.getStringExtra("contact") ?: "James"
    val messages = (intent.getStringExtra("message") ?: "Scratch the back and send me the codes, darling.").split("\\n", "\n")
    val list = column()
    list.addView(header(contact, avatar(contact.trimStart('+').take(1).ifBlank { "?" }, Color.parseColor("#8E8E99"))))
    messages.filter { it.isNotBlank() }.forEach { list.addView(bubble(this, it)) }
    val input = field(intent.getStringExtra("hint") ?: "Message")
    val send = pill("Send", BLUE).apply {
      setOnClickListener {
        // Stays on the phone: the "sent" message only joins this screen's list.
        val typed = input.text.toString().trim()
        if (typed.isNotEmpty()) { list.addView(bubble(this@ChatActivity, typed, mine = true)); input.setText("") }
      }
    }
    val bar = row().apply {
      setPadding(dp(10), dp(8), dp(10), dp(8))
      addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
      addView(send)
    }
    screen(list, footer = bar)
    focus(input)
  }
}

/** A gift-card store page with a $500 card selected and a big Buy button. Buying does nothing. */
class GiftCardActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val c = column()
    c.addView(header("Example Play gift card"))
    c.addView(text("Example Play\n\$500", 26f, bold = true, color = Color.WHITE).apply {
      background = rounded(Color.parseColor("#2E7D5B"), dp(16))
      setPadding(dp(18), dp(18), dp(18), dp(36))
    })
    c.addView(text("Choose an amount", 15f, color = GREY).apply { setPadding(0, dp(18), 0, dp(8)) })
    c.addView(row().apply {
      listOf("\$25", "\$50", "\$100", "\$500").forEach { amount ->
        val on = amount == "\$500"
        addView(text(amount, 16f, color = if (on) BLUE else Color.BLACK).apply {
          background = rounded(Color.WHITE, dp(20), dp(2), if (on) BLUE else Color.parseColor("#C9C9D1"))
          setPadding(dp(16), dp(8), dp(16), dp(8))
          layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        })
      }
    })
    c.addView(text("Redeem on Example Play. Codes are delivered instantly by email and can't be refunded.", 14f, color = GREY).apply { setPadding(0, dp(18), 0, 0) })
    val note = text("", 15f, color = GREY).apply { setPadding(dp(16), 0, dp(16), dp(4)); gravity = Gravity.CENTER }
    val buy = pill("Buy · \$500", BLUE).apply { setOnClickListener { note.text = "Test store: nothing was bought." } }
    val footer = column(12).apply { addView(note); addView(buy) }
    screen(c, footer = footer)
  }
}

/** A "refund" form asking for bank numbers, account number focused. Submitting only shows a note. */
class FormActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val c = column()
    c.addView(header("ExampleRefunds"))
    c.addView(text(intent.getStringExtra("title") ?: "Claim your \$892.00 refund", 22f, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) })
    c.addView(text("You were overcharged. Enter your bank details within 24 hours to receive your refund.", 16f, color = GREY))
    val note = text("", 15f, color = GREY)
    val fields = listOf(field("Full name"), field("Bank routing number", digits = true), field("Account number", digits = true))
    fields.forEach { f -> c.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }) }
    c.addView(pill("Get my refund", BLUE).apply { setOnClickListener { note.text = "Test form: nothing was sent." } },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    c.addView(note)
    screen(c)
    focus(fields.last())
  }
}

/** A voicemail with Play and Share. Share sends the audio through Android's share sheet, where Pip would sit. */
class VoicemailActivity : Activity() {
  private var player: MediaPlayer? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val c = column()
    c.addView(header("Voicemail"))
    c.addView(column().apply {
      background = rounded(Color.WHITE, dp(24), dp(1), Color.parseColor("#E4E4EA"))
      addView(text("+1 (833) 555-0142", 18f, bold = true))
      addView(text("0:15 · Today 9:12", 14f, color = GREY))
      addView(row().apply {
        setPadding(0, dp(14), 0, 0)
        addView(pill("Play", BLUE).apply { setOnClickListener { play() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        addView(pill("Share", Color.parseColor("#5F6368")).apply { setOnClickListener { share() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      })
    })
    screen(c)
    if (intent.getBooleanExtra("share", false)) c.post { share() }
  }

  private fun play() {
    player?.release()
    player = MediaPlayer.create(this, R.raw.voicemail).apply { start() }
  }

  private fun share() {
    val send = Intent(Intent.ACTION_SEND).apply {
      type = "audio/mp4"
      putExtra(Intent.EXTRA_STREAM, Uri.parse("content://dev.playground.pipfixtures.voicemail/voicemail.m4a"))
      putExtra(Intent.EXTRA_SUBJECT, "Voicemail from +1 (833) 555-0142")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, "Share voicemail"))
  }

  override fun onDestroy() {
    player?.release()
    super.onDestroy()
  }
}

/** A photo grid with a screenshot of a scam text, for talking to Pip over another app. */
class GalleryActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val c = column(4)
    c.addView(header("Photos"))
    val grid = GridLayout(this).apply { columnCount = 3 }
    val size = (resources.displayMetrics.widthPixels - dp(24)) / 3
    repeat(12) { i ->
      val tile = if (i == 4) text("Bank of Example: your card is locked. Call 1-888-555-0123 now.", 11f).apply {
        setBackgroundColor(Color.WHITE)
        setPadding(dp(6), dp(6), dp(6), dp(6))
      } else text("").apply { setBackgroundColor(Color.parseColor(if (i % 2 == 0) "#CFD3DA" else "#BFC5CE")) }
      grid.addView(tile, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }
    c.addView(grid)
    screen(c)
  }
}

private fun Activity.focus(view: android.view.View) {
  view.requestFocus()
  view.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }, 300)
}
