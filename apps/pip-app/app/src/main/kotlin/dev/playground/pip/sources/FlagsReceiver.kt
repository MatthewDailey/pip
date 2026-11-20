package dev.playground.pip.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.playground.pip.core.PipFlags

/**
 * Debug switch for [PipFlags], guarded by DUMP in the manifest so only the shell can send it:
 * ```
 * adb shell am broadcast -a dev.playground.pip.FLAGS -n dev.playground.pip/.sources.FlagsReceiver --ez exploratory true
 * adb shell am broadcast -a dev.playground.pip.FLAGS -n dev.playground.pip/.sources.FlagsReceiver --ez setup_done false
 * ```
 */
class FlagsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.hasExtra("exploratory")) PipFlags.setExploratory(intent.getBooleanExtra("exploratory", false))
    if (intent.hasExtra("setup_done")) PipFlags.setSetupDone(intent.getBooleanExtra("setup_done", false))
  }
}
