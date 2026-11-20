package dev.playground.pip.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer flags, kept on the phone in shared preferences.
 *
 * [exploratory] gates every experiment a person could see: the overlay Pip wandering and his idle bubbles, roam,
 * the Kotlin and Rive showreels, the sampler and peeks, generic overlay and notification warnings, and the
 * playground screen. It is off by default in every build, so the app shows Pip only for a shipping intervention
 * (act-mute's card) and the first-run greeting. Detection, logging, grants and the debug broadcasts stay on.
 *
 * Turn it on with a long press on Pip on the About screen, or from adb:
 * ```
 * adb shell am broadcast -a dev.playground.pip.FLAGS --ez exploratory true   # or: turbo run pip:exploratory -- on
 * ```
 */
object PipFlags {
  const val TAG = "PipFlags"
  private const val PREFS = "pip_flags"
  private const val EXPLORATORY = "exploratory"
  private const val SETUP_DONE = "setup_done"

  private var prefs: SharedPreferences? = null
  private val exploratoryFlow = MutableStateFlow(false)
  private val setupDoneFlow = MutableStateFlow(false)

  /** Experiments on screen. Readable from any thread; false until [init] runs. */
  val exploratory: Boolean get() = exploratoryFlow.value
  val exploratoryState: StateFlow<Boolean> = exploratoryFlow.asStateFlow()

  /** The first-run setup was finished or skipped, so launches land on the conversation. */
  val setupDone: StateFlow<Boolean> = setupDoneFlow.asStateFlow()

  fun init(context: Context) {
    val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs = p
    exploratoryFlow.value = p.getBoolean(EXPLORATORY, false)
    setupDoneFlow.value = p.getBoolean(SETUP_DONE, false)
  }

  private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

  /** For views outside Compose (the overlay); called on the thread that changed the flag. */
  fun addExploratoryListener(listener: (Boolean) -> Unit) { listeners += listener }
  fun removeExploratoryListener(listener: (Boolean) -> Unit) { listeners -= listener }

  fun setExploratory(on: Boolean) {
    prefs?.edit()?.putBoolean(EXPLORATORY, on)?.apply()
    exploratoryFlow.value = on
    Log.i(TAG, "exploratory=$on")
    listeners.forEach { it(on) }
  }

  fun setSetupDone(done: Boolean) {
    prefs?.edit()?.putBoolean(SETUP_DONE, done)?.apply()
    setupDoneFlow.value = done
  }
}
