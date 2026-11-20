package dev.playground.pip.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source

/** Receives the full SMS text with the sender number, which notification previews may truncate or hide. */
class SmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
    parts.groupBy { it.displayOriginatingAddress }.forEach { (from, msgs) ->
      Guard.report(Source.SMS, "SMS from $from", msgs.joinToString("") { it.displayMessageBody }, alertKey = "sms:$from")
    }
  }
}
