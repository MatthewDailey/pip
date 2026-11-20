package dev.playground.pip.core

/**
 * Maps tripwire rules and Jev reasons to plain language for the warning screen,
 * notification, Pip's speech bubble, and the screen-fraud banner. Internal names
 * like "Tripwire gift_card_readout:" must never reach the user.
 */
object WarningCopy {
  /** "Pip is 97% sure this is a scam", or null when [risk] is missing or out of range. */
  fun confidenceLine(risk: Double?): String? {
    val r = risk ?: return null
    if (r < 0.0 || r > 1.0) return null
    val pct = kotlin.math.round(r * 100.0).toInt().coerceIn(0, 100)
    return "Pip is $pct% sure this is a scam"
  }

  fun forCall(tripwireRule: String?, reason: String?): String {
    ruleLine(tripwireRule)?.let { return it }
    val raw = reason.orEmpty().trim()
    val embedded = Regex("""(?i)tripwire\s+([a-z0-9_]+)""").find(raw)?.groupValues?.get(1)
    ruleLine(embedded)?.let { return it }
    if (raw.isBlank() || looksInternal(raw)) {
      return "This call looks like a scam. Don't send money, gift cards, or codes."
    }
    return raw
  }

  fun forScreen(fraudType: String?, suggestedLine: String?, reason: String?): String {
    val suggested = suggestedLine?.trim().orEmpty()
    if (suggested.isNotEmpty() && !looksInternal(suggested)) return suggested
    screenLine(fraudType)?.let { return it }
    val raw = reason.orEmpty().trim()
    if (raw.isNotEmpty() && !looksInternal(raw)) return raw
    return "This page looks like a scam. Don't type a password or card number."
  }

  private fun ruleLine(rule: String?): String? = when (rule) {
    "gift_card_readout" ->
      "You're reading gift card numbers to a caller. Real agencies never ask for gift cards."
    "gift_card_unknown_caller" ->
      "An unknown caller is asking about gift cards."
    else -> null
  }

  private fun screenLine(fraudType: String?): String? = when (fraudType) {
    "phishing_login" -> "Stop! This page isn't who it says it is. Don't type your password or card here."
    "fake_payment" -> "Wait! This is asking for money in a way real companies never do. Don't pay."
    "scam_message" -> "This message looks like a scam. Don't tap the link or send anything."
    "fake_shopping" -> "This store looks fake. Don't enter your card."
    "tech_support_popup" -> "This warning is fake. Don't call that number or let anyone into your phone."
    "remote_access" -> "Stop! Don't let anyone control your phone. Real banks and companies never ask for this."
    else -> null
  }

  private fun looksInternal(text: String): Boolean {
    if (text.contains("Tripwire", ignoreCase = true)) return true
    if (Regex("""\b(gift_card_readout|gift_card_unknown_caller|phishing_login|tech_support_popup|fake_payment|scam_message|remote_access|fake_shopping|other_scam)\b""").containsMatchIn(text)) return true
    if (Regex("""risk\s+[0-9.]+\s*[≥>=]""").containsMatchIn(text)) return true
    return false
  }
}
