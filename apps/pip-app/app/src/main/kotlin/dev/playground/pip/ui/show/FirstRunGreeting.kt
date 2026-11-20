package dev.playground.pip.ui.show

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import dev.playground.pip.core.PipFlags
import dev.playground.pip.sources.GuardAccessibilityService
import dev.playground.pip.ui.Capabilities
import dev.playground.pip.ui.PipOverlay
import dev.playground.pip.ui.home.setupSteps
import dev.playground.pip.voice.PipVoice
import dev.playground.pip.voice.VoicePurpose

/**
 * Pip's first-run greeting: the `hello-install` story (packages/pip-rive/stories/hello-install), played once over
 * the home screen. It plays on the first launcher open of the app after setup is complete ([GreetingGate]): instead
 * of showing, the app closes and Pip performs over the home screen. The open on which setup finishes doesn't count,
 * and if a permission or role has gone missing by that open, setup shows again and the greeting waits for the first
 * open after it is complete. The "greeted" flag is written when the story starts, so leaving halfway doesn't
 * replay it.
 *
 * It plays through [RiveShowRunner] directly rather than the service's SHOW broadcast, which needs
 * [PipFlags.exploratory]; the greeting ships with that flag off. The story climbs out of Pip's own launcher icon,
 * eyes the Phone icon and sinks back into his own; when the launcher shows no "Pip" icon he rises from and ducks out
 * at the bottom edge instead, and without a "Phone" icon he eyes the dock ([GreetingSpot]).
 *
 * For testing (only the shell, which holds DUMP, can send it):
 * ```
 * adb shell am broadcast -a dev.playground.pip.GREET                  # replay now (or the next time home is in front)
 * adb shell am broadcast -a dev.playground.pip.GREET --es greet reset # clear "greeted" and setup's "done": the next
 *                                                                     # open shows setup, the one after greets
 * ```
 * Pip speaks his lines: the story fires `say:<line>` events where each line belongs (the script lives in the .riv
 * timeline), and they are queued so a long line delays the next rather than cutting it. A line plays its bundled
 * GPT-Live clip ([GreetingClips], rendered by `turbo run voice:greeting`) when it has one, and otherwise the phone's
 * text-to-speech in Pip's voice ([PipVoice]).
 *
 * Logs under the show tag: `GREET open <action>|armed|play|say <line>|done|reset`.
 */
class FirstRunGreeting private constructor(
  private val service: AccessibilityService,
  private val overlay: () -> PipOverlay?,
) {
  private val main = Handler(Looper.getMainLooper())
  private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  /** A play is due the next time the launcher is in front: an open that greets, or the shell's replay. */
  private var pending = false
  private var runner: RiveShowRunner? = null
  private var homeSeen = 0
  private var voice: PipVoice? = null
  private var clips: GreetingClips? = null

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.getStringExtra("greet") == "reset") {
        prefs.edit().putBoolean(KEY_GREETED, false).apply()
        PipFlags.setSetupDone(false)
        Log.i(TAG, "GREET reset: the next open shows setup, the open after it greets")
      } else {
        pending = true
        arm()
      }
    }
  }

  private val poll = object : Runnable {
    override fun run() {
      if (GuardAccessibilityService.instance !== service) return detach()
      if (runner != null || !pending) return
      // Two checks in a row let the launcher's own open animation settle before Pip appears.
      homeSeen = if (launcherInFront()) homeSeen + 1 else 0
      if (homeSeen >= 2) play() else main.postDelayed(this, POLL_MS)
    }
  }

  private fun greeted() = prefs.getBoolean(KEY_GREETED, false)

  private fun arm() {
    main.removeCallbacks(poll)
    homeSeen = 0
    if (!pending) return
    Log.i(TAG, "GREET armed; waiting for the home screen")
    prepareVoice()
    main.post(poll)
  }

  private fun play() {
    pending = false
    prefs.edit().putBoolean(KEY_GREETED, true).apply()
    val bounds = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    val icons = findLauncherIcons(service).keys
    val spot = GreetingSpot.overrides(icons, bounds.width().toFloat(), bounds.height().toFloat())
    Log.i(TAG, "GREET play: icons=$icons" + (if (spot.isEmpty()) "" else " (stand-ins: ${spot.keys})"))
    overlay()?.hide()
    // Turn the volume up before Pip appears, so it doesn't jump when he starts talking.
    prepareVoice()
    clips?.loud()
    runner = RiveShowRunner(service, riv = STORY, overrides = spot, onEvent = ::onEvent) {
      runner = null
      Log.i(TAG, "GREET done")
      // Puts the overlay's window back; Pip himself shows only with the exploratory flag on.
      overlay()?.show()
      // Let the last queued line finish before releasing the engine.
      main.postDelayed({ if (runner == null) releaseVoice() }, VOICE_TAIL_MS)
    }.also { it.start() }
  }

  /** Loads the clips and starts the text-to-speech engine (for lines without one) ahead of the show. */
  private fun prepareVoice() {
    if (clips == null) clips = GreetingClips(service.applicationContext)
    if (voice != null) return
    voice = PipVoice(service.applicationContext, VoicePurpose.GREETING) { ok ->
      if (!ok) Log.w(TAG, "GREET text-to-speech unavailable; Pip's lines stay silent")
    }
  }

  private fun releaseVoice() {
    clips?.release()
    clips = null
    voice?.shutdown()
    voice = null
  }

  /** Rive's render thread: speak `say:` lines, queued; on the main thread, where the clips play. */
  private fun onEvent(name: String) {
    if (!name.startsWith(SAY)) return
    val line = name.removePrefix(SAY)
    main.post { say(line) }
  }

  private fun say(line: String) {
    val c = clips
    if (c != null && c.play(line)) { Log.i(TAG, "GREET say $line (clip, ${c.voice})"); return }
    Log.i(TAG, "GREET say $line (text-to-speech)")
    val v = voice
    if (v == null || !v.ready) { Log.w(TAG, "GREET voice not ready; skipped: $line"); return }
    v.speak(line, TextToSpeech.QUEUE_ADD, "pip-greet-${line.hashCode()}")
  }

  private fun launcherInFront(): Boolean {
    val front = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return false
    return front in launchers()
  }

  private fun launchers(): Set<String> {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return service.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
      .mapNotNull { it.activityInfo?.packageName }.toSet()
  }

  /** Stops listening for replays and drops the voice; the service calls it when it goes away. */
  fun detach() {
    if (live === this) live = null
    main.removeCallbacks(poll)
    releaseVoice()
    runCatching { service.unregisterReceiver(receiver) }
  }

  companion object {
    const val TAG = ShowRunner.TAG
    const val ACTION = "dev.playground.pip.GREET"
    const val STORY = "hello-install"
    private const val PREFS = "pip-first-run"
    private const val KEY_GREETED = "greeted"
    private const val POLL_MS = 700L
    private const val SAY = "say:"
    private const val VOICE_TAIL_MS = 8000L

    /** The greeting of the connected accessibility service, which draws the overlay; null while it is off. */
    @Volatile private var live: FirstRunGreeting? = null

    /** Called from the service's onServiceConnected; the greeting itself waits for an open ([onLauncherOpen]). */
    fun attach(service: AccessibilityService, overlay: () -> PipOverlay?): FirstRunGreeting =
      FirstRunGreeting(service, overlay).also {
        live?.detach()
        live = it
        service.registerReceiver(it.receiver, IntentFilter(ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
      }

    /**
     * Plays the greeting now, whatever the first-run state: the next time the home screen is in front, as the
     * shell's GREET broadcast does. For [dev.playground.pip.PipIntroActivity], the demo's "Pip Intro" icon. Returns
     * false when the accessibility service, which draws the overlay, isn't running.
     */
    fun replay(): Boolean {
      val greeting = live?.takeIf { GuardAccessibilityService.instance === it.service } ?: return false
      Log.i(TAG, "GREET replay from the app")
      greeting.main.post { greeting.pending = true; greeting.arm() }
      return true
    }

    /**
     * Called when the person opens the app from the launcher, before it shows. Returns true when the greeting is
     * starting instead, so the app should close; [GreetingGate.Action.SETUP] puts setup back on screen.
     */
    fun onLauncherOpen(context: Context): Boolean {
      val greeted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GREETED, false)
      val greeting = live?.takeIf { GuardAccessibilityService.instance === it.service }
      val action = GreetingGate.onOpen(
        setupDone = PipFlags.setupDone.value,
        greeted = greeted,
        allGranted = { setupSteps(Capabilities.all(context)).all { it.enabled } },
        overlayReady = greeting != null,
      )
      Log.i(TAG, "GREET open $action")
      when (action) {
        GreetingGate.Action.GREET -> greeting!!.main.post { greeting.pending = true; greeting.arm() }
        GreetingGate.Action.SETUP -> PipFlags.setSetupDone(false)
        GreetingGate.Action.APP -> {}
      }
      return action == GreetingGate.Action.GREET
    }
  }
}

/** What a launcher open of the app does about the greeting. Android-free. */
object GreetingGate {
  enum class Action {
    /** Show the app as it is: the conversation home, or setup while it isn't complete. */
    APP,
    /** A permission or role went missing since setup completed: show setup again, greet after it. */
    SETUP,
    /** Close the app and greet on the home screen. */
    GREET,
  }

  /**
   * [setupDone] is read before the open shows anything, so the open on which setup finishes never greets.
   * [allGranted] is checked live, and only when the greeting is due. Without the overlay (the accessibility
   * service isn't connected) the app opens as usual and the next open tries again.
   */
  fun onOpen(setupDone: Boolean, greeted: Boolean, allGranted: () -> Boolean, overlayReady: Boolean): Action = when {
    greeted || !setupDone -> Action.APP
    !allGranted() -> Action.SETUP
    !overlayReady -> Action.APP
    else -> Action.GREET
  }
}

/** Where the story's moves happen, as view-model numbers for [RiveShowRunner]'s overrides. Android-free. */
object GreetingSpot {
  /**
   * Empty when the launcher shows the icons the story binds to: [RiveShowRunner] then sets `pipX`/`pipY`/`pipD` and
   * `phoneX`/`phoneY` from them. Without a Pip icon, a "hole" just below the bottom edge, so he climbs up from there
   * and ducks out there at the end. Without a Phone icon, a spot over the dock's first slot, so he eyes the dock.
   */
  fun overrides(iconLabels: Collection<String>, width: Float, height: Float): Map<String, Float> {
    fun has(label: String) = iconLabels.any { RiveShowRunner.camel(it).equals(label, ignoreCase = true) }
    val spot = mutableMapOf<String, Float>()
    if (!has("pip")) spot += mapOf("pipX" to width / 2, "pipY" to height + 20f, "pipD" to 2f, "pipLabelW" to width)
    if (!has("phone")) spot += mapOf("phoneX" to width * DOCK_X, "phoneY" to height - DOCK_FROM_BOTTOM)
    return spot
  }

  /** The Pixel launcher's first dock slot on a 1080 x 2424 screen: 158 px in, 189 px up from the bottom. */
  private const val DOCK_X = 158f / 1080f
  private const val DOCK_FROM_BOTTOM = 189f
}
