package dev.playground.pip.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.playground.pip.conversation.SpeechModel

/**
 * Debug driver for the on-device speech model, guarded by DUMP in the manifest so only the shell can send it. The
 * broadcast's result data is the model state (INSTALLED, PENDING, DOWNLOADABLE, UNSUPPORTED or UNKNOWN):
 * ```
 * adb shell am broadcast -n dev.playground.pip/.sources.SpeechModelReceiver --es do check
 * adb shell am broadcast -n dev.playground.pip/.sources.SpeechModelReceiver --es do download
 * ```
 * `download` returns once the recognizer has answered the request; the model may still be arriving, so poll `check`.
 * The app setup script uses it to fetch the model before any call, so the system's prompt never appears mid-call.
 */
class SpeechModelReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val reply: (SpeechModel.State) -> Unit = { state ->
      pending.resultData = state.name
      pending.finish()
    }
    when (intent.getStringExtra("do")) {
      "download" -> SpeechModel.download(context, reply)
      else -> SpeechModel.check(context, reply)
    }
  }
}
