package dev.playground.pip.sources

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.playground.pip.act.ScamCallResponder
import dev.playground.pip.core.Guard
import dev.playground.pip.core.PipFlags
import dev.playground.pip.core.ScreenGuard
import dev.playground.pip.core.Source
import dev.playground.pip.live.PipCallVerdicts
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.PipOverlay
import dev.playground.pip.ui.PipRive
import app.rive.runtime.kotlin.core.RendererType
import dev.playground.pip.ui.show.RiveShowRunner
import dev.playground.pip.ui.show.RoamDirector
import dev.playground.pip.ui.show.ShowRunner
import dev.playground.pip.ui.show.StagePlayer
import dev.playground.pip.ui.show.pipShow
import dev.playground.pip.ui.show.samplerShow
import dev.playground.pip.ui.show.peekShow
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telecom.TelecomManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two jobs: read on-screen text from every app (messaging, browser, email, dialer), and own call-time
 * audio capture, which Android only permits from an enabled accessibility service.
 */
class GuardAccessibilityService : AccessibilityService() {
  private lateinit var capture: CallAudioCapture
  private var pip: PipOverlay? = null
  private var show: StagePlayer? = null
  private var greeting: dev.playground.pip.ui.show.FirstRunGreeting? = null
  private var responder: ScamCallResponder? = null
  @Volatile private var callActive = false

  /**
   * Plays Pip's showreel:
   * ```
   * adb shell am broadcast -a dev.playground.pip.SHOW [--es show sampler|peeks] [--es from <scene>]
   * adb shell am broadcast -a dev.playground.pip.SHOW --es show roam [--el seed 42] [--ef seconds 40]   # Pip roams
   * adb shell am broadcast -a dev.playground.pip.SHOW --es show stop                  # stops any show or roam
   * adb shell am broadcast -a dev.playground.pip.SHOW --es engine rive --es riv hodor|showreel \
   *   [--es vm holeX=700,holeY=1300] [--ef seconds 9] [--es renderer canvas]
   *   # view-model numbers; play length instead of the clip's; Android Canvas instead of the Rive GPU renderer
   * ```
   * The Kotlin engine is the default; `--es engine rive` plays a compiled `.riv` instead (see [RiveShowRunner]).
   */
  private val showReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      // Showreels, the sampler, peeks and roam are experiments: they play only with the exploratory flag on.
      if (!PipFlags.exploratory && intent.getStringExtra("show") != "stop") {
        Log.w(PipFlags.TAG, "SHOW ignored: exploratory is off (turbo run pip:exploratory -- on)")
        return
      }
      // A new request replaces whatever is playing, so you can jump between reels without waiting.
      show?.let { running -> show = null; running.stop() }
      val which = intent.getStringExtra("show")
      if (which == "stop") { pip?.show(); return }
      pip?.hide()
      lateinit var runner: StagePlayer
      val done = { if (show === runner) { show = null; pip?.show() } }
      runner = if (which == "roam") {
        val seed = intent.getLongExtra("seed", 0L).takeIf { it != 0L } ?: intent.getStringExtra("seed")?.toLongOrNull()
          ?: (System.nanoTime() % 1_000_000L)
        RoamDirector(this@GuardAccessibilityService, seed, intent.getFloatExtra("seconds", 0f).takeIf { it > 0f }, done)
      } else if (intent.getStringExtra("engine") == "rive") {
        RiveShowRunner(
          this@GuardAccessibilityService,
          riv = intent.getStringExtra("riv") ?: "showreel",
          overrides = parseNumbers(intent.getStringExtra("vm")),
          durationOverride = intent.getFloatExtra("seconds", 0f).takeIf { it > 0f },
          renderer = if (intent.getStringExtra("renderer") == "canvas") canvasRenderer() else RendererType.Rive,
          onDone = done,
        )
      } else {
        val reel = when (which) { "sampler" -> samplerShow(); "peeks" -> peekShow(); else -> pipShow() }
        ShowRunner(this@GuardAccessibilityService, reel, done)
      }
      show = runner
      runner.start(intent.getStringExtra("from"))
    }
  }

  @Suppress("DEPRECATION")
  private fun canvasRenderer() = RendererType.Canvas

  /** "a=1,b=2.5" -> {a: 1, b: 2.5}; malformed pairs are dropped. */
  private fun parseNumbers(spec: String?): Map<String, Float> =
    spec.orEmpty().split(',').mapNotNull { pair ->
      val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
      v.trim().toFloatOrNull()?.let { k.trim() to it }
    }.toMap()

  /**
   * Drives the overlay Pip's Rive view model from adb (the showreel keeps its own engine):
   * ```
   * adb shell am broadcast -a dev.playground.pip.PIP --es set mood --es value angry   # Pip's resting mood
   * adb shell am broadcast -a dev.playground.pip.PIP --es set mood --ei value 3       # the same, as the Mood ordinal
   * adb shell am broadcast -a dev.playground.pip.PIP --es set lookX --ef value 0.5    # any other property
   * adb shell am broadcast -a dev.playground.pip.PIP --es setb talking --ez value true
   * adb shell am broadcast -a dev.playground.pip.PIP --es fire alert
   * adb shell am broadcast -a dev.playground.pip.PIP --es reload true   # plays /sdcard/Download/pip.riv
   * adb shell am broadcast -a dev.playground.pip.PIP --es reload false  # back to the bundled file
   * adb shell am broadcast -a dev.playground.pip.PIP --es detect scam-call [--es reason 'asked for gift cards'] \
   *   [--es evidence '…'] [--es path call-notification|dialer-button|audio-manager] [--ez speak false] [--ez foreground true]   # see ScamCallResponder
   * adb shell am broadcast -a dev.playground.pip.PIP --es respond undo|ok|mute-now|safe   # as if tapped
   * adb shell am broadcast -a dev.playground.pip.PIP --es rule mute-scam-calls --es value on|off|default
   * adb shell am broadcast -a dev.playground.pip.PIP --es call show   # brings the phone app's in-call screen up
   * ```
   */
  private val pipReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val overlay = pip ?: return
      val rive = overlay.rive
      responder?.let { r ->
        if (intent.getStringExtra("detect") == "scam-call") {
          r.onDetect(intent.getStringExtra("reason"), intent.getStringExtra("evidence"), intent.getStringExtra("path"),
            intent.getBooleanExtra("speak", true), foreground = intent.getBooleanExtra("foreground", false))
        }
        intent.getStringExtra("respond")?.let { r.respond(it, from = "broadcast") }
        if (intent.getStringExtra("call") == "show" &&
          checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
          runCatching { getSystemService(TelecomManager::class.java).showInCallScreen(false) }
        }
        if (intent.getStringExtra("rule") == "mute-scam-calls") {
          r.rules.setMuteScamCalls(when (intent.getStringExtra("value")) { "on" -> true; "off" -> false; else -> null })
          val (on, source) = r.rules.muteScamCalls()
          Log.i(dev.playground.pip.core.EventLog.TAG, "{\"event\":\"RuleChanged\",\"rule\":\"mute_scam_calls\",\"on\":$on,\"source\":\"$source\"}")
        }
      }
      intent.getStringExtra("reload")?.let { rive.reload(fromDownload = it.toBooleanStrictOrNull() ?: true) }
      intent.getStringExtra("fire")?.let(rive::fire)
      intent.getStringExtra("setb")?.let { rive.setBoolean(it, intent.getBooleanExtra("value", true)) }
      intent.getStringExtra("set")?.let { name ->
        @Suppress("DEPRECATION")
        val value = intent.extras?.get("value")
        // The overlay owns the mood (a bubble borrows it and gives it back), so mood goes through it.
        if (name == "mood") {
          val mood = when (value) {
            is String -> value
            is Number -> Mood.entries.getOrNull(value.toInt())?.name?.lowercase()
            else -> null
          }
          if (mood != null) overlay.setMood(mood) else Log.w(PipRive.TAG, "PIP set mood: unsupported value $value")
          return@let
        }
        when (value) {
          is Float -> rive.setNumber(name, value)
          is Double -> rive.setNumber(name, value.toFloat())
          is Int -> rive.setNumber(name, value.toFloat())
          is Long -> rive.setNumber(name, value.toFloat())
          is Boolean -> rive.setBoolean(name, value)
          is String -> rive.setFromText(name, value)
          else -> Log.w(PipRive.TAG, "PIP set $name: missing or unsupported value extra $value")
        }
      }
    }
  }
  private var telephony: TelephonyManager? = null
  private val lastScreenText = mutableMapOf<String, String>()
  private val lastScreenAt = mutableMapOf<String, Long>()

  private val callState = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
    override fun onCallStateChanged(state: Int) {
      when (state) {
        TelephonyManager.CALL_STATE_RINGING -> Guard.note(Source.CALL_STATE, "Phone ringing")
        TelephonyManager.CALL_STATE_OFFHOOK -> {
          callActive = true
          Guard.note(Source.CALL_STATE, "Call active", "Starting mic capture; put the call on speaker to hear the caller")
          val id = "call-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
          PipCallVerdicts.watchOutgoingCaller()
          PipCallVerdicts.warmUp()
          capture.start(id)
        }
        TelephonyManager.CALL_STATE_IDLE -> {
          callActive = false
          responder?.onCallEnded()
          capture.stop()
          Guard.endCalls()
          PipCallVerdicts.endCall()
          Guard.note(Source.CALL_STATE, "Call idle")
        }
      }
    }
  }

  override fun onServiceConnected() {
    capture = CallAudioCapture(this)
    Guard.note(Source.SYSTEM, "Accessibility service connected")
    registerCallState()
    pip = PipOverlay(this).also { p ->
      p.show()
      Guard.onWarn = { text -> p.say(text, 9000, urgent = true) }
    }
    ScreenGuard.attach(this)
    responder = ScamCallResponder(this, { pip }, ::isCallActive, { if (::capture.isInitialized) capture.recentPeak else null }).also { it.start() }
    val (muteRule, ruleSource) = responder!!.rules.muteScamCalls()
    Log.i(dev.playground.pip.core.EventLog.TAG, "{\"event\":\"RuleLoaded\",\"rule\":\"mute_scam_calls\",\"on\":$muteRule,\"source\":\"$ruleSource\"}")
    greeting = dev.playground.pip.ui.show.FirstRunGreeting.attach(this) { pip }
    // Only the shell (which holds DUMP) can start the show.
    registerReceiver(showReceiver, IntentFilter(SHOW_ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
    registerReceiver(pipReceiver, IntentFilter(PIP_ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
  }

  /** Parks the mascot at the top so a full-screen warning's buttons stay tappable. */
  fun parkPipAtTop() { pip?.parkAtTop() }

  /** Parks the mascot in the empty middle of the call warning, visible, without covering buttons. */
  fun parkPipInMiddle() { pip?.parkInMiddle() }

  /** Lets Pip hide again after the call warning closes (when exploratory is off). */
  fun releasePipPark() { pip?.clearWarningPark() }

  /** Reserves the top of the screen for a banner so Pip's bubble sits below it. */
  fun reservePipTop(px: Int) { pip?.setReservedTop(px) }

  /** Presses the dialer's own Mute button, for phones that ignore a regular app's microphone mute during a call. */
  fun pressMute(): Boolean {
    val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
    for (root in roots) {
      val hit = sequenceOf("Mute", "mute").flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
        .firstOrNull { n -> !n.isChecked && (n.isClickable || n.parent?.isClickable == true) }
      if (hit != null) {
        val target = if (hit.isClickable) hit else hit.parent
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      }
    }
    return false
  }

  /**
   * Taps the phone app's End call button: the fallback when TelecomManager.endCall can't (no ANSWER_PHONE_CALLS,
   * or the dialer refuses). Matches the button's text or content description.
   */
  fun pressEndCall(): Boolean {
    val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
    for (root in roots) {
      val hit = sequenceOf("End call", "Hang up", "End").flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
        .firstOrNull { n -> n.isClickable || n.parent?.isClickable == true }
      if (hit != null) {
        val target = if (hit.isClickable) hit else hit.parent
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      }
    }
    return false
  }

  /** Off-hook (a call is active or dialing) per the call-state listener, which reports the current state when it registers. */
  private fun isCallActive(): Boolean = callActive

  /** Called again from the UI after READ_PHONE_STATE is granted, since the service may have connected first. */
  fun registerCallState() {
    if (telephony != null) return
    if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
      Guard.note(Source.SYSTEM, "Call monitoring off", "READ_PHONE_STATE not granted")
      return
    }
    telephony = getSystemService(TelephonyManager::class.java).also { it.registerTelephonyCallback(mainExecutor, callState) }
    Guard.note(Source.SYSTEM, "Call monitoring on")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    val pkg = event.packageName?.toString() ?: return
    // Roam follows the launcher's layout: a page swipe scrolls, a dragged icon changes content.
    if (pkg != packageName && event.eventType in ROAM_STAGE_EVENTS) (show as? RoamDirector)?.onScreenChanged()
    if (pkg == packageName || pkg == "com.android.systemui" && event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
      if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        ScreenGuard.onForegroundPackage(pkg)
      }
      return
    }
    when (event.eventType) {
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
        val text = event.text.joinToString(" ").trim()
        if (text.isNotEmpty()) Guard.report(Source.NOTIFICATION, "Toast/notification (a11y)", text, app = pkg)
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> captureScreen(pkg)
    }
  }

  /** Debounced per app: content-changed events fire many times a second while scrolling or typing. */
  private fun captureScreen(pkg: String) {
    val now = System.currentTimeMillis()
    if (now - (lastScreenAt[pkg] ?: 0) < 2000) return
    lastScreenAt[pkg] = now
    val root = rootInActiveWindow ?: return
    val sb = StringBuilder()
    collect(root, sb, 0)
    val text = sb.toString().trim().take(3000)
    val url = ScreenGuard.extractUrl(root)
    val fingerprint = "$pkg\n${url.orEmpty()}\n$text"
    if ((text.isEmpty() && url == null) || fingerprint == lastScreenText[pkg]) return
    lastScreenText[pkg] = fingerprint
    val title = root.window?.title?.toString() ?: pkg.substringAfterLast('.')
    Guard.report(Source.SCREEN, "Screen: $title", text, app = pkg)
    ScreenGuard.onForegroundPackage(pkg)
    ScreenGuard.evaluate(this, pkg, title, text, url)
  }

  private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
    if (depth > 40 || sb.length > 3000) return
    if (node.isPassword) return
    node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
    for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, sb, depth + 1) }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    telephony?.unregisterTelephonyCallback(callState)
    if (::capture.isInitialized) capture.stop()
    Guard.onWarn = null
    responder?.stop()
    runCatching { unregisterReceiver(showReceiver) }
    runCatching { unregisterReceiver(pipReceiver) }
    show?.stop()
    greeting?.detach()
    pip?.hide()
    ScreenGuard.detach()
    instance = null
    super.onDestroy()
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  companion object {
    @Volatile var instance: GuardAccessibilityService? = null
    const val SHOW_ACTION = "dev.playground.pip.SHOW"
    const val PIP_ACTION = "dev.playground.pip.PIP"
    private val ROAM_STAGE_EVENTS = setOf(
      AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
    )
  }
}
