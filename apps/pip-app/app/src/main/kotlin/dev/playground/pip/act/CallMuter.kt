package dev.playground.pip.act

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.playground.pip.sources.GuardNotificationListener

/**
 * The mute effector. By default [set] runs [muteCall]: `AudioManager.setMicrophoneMute` first, confirmed on the phone
 * app's own call UI, with the call notification's Mute action and the in-call screen's Mute button as fallbacks.
 * Paths it reports: `audio-manager` (the call UI shows the mute), `global-mic` (no call UI to confirm it),
 * `call-notification` or `dialer-button` (a fallback ran). Each can be forced for tests: `call-notification`,
 * `dialer-button` (after `TelecomManager.showInCallScreen` when needed) and `audio-manager`.
 *
 * AOSP: AudioService's `setMicrophoneMute` checks MODIFY_AUDIO_SETTINGS (and the OP_MUTE_MICROPHONE app op only when
 * unmuting), with no call-state or dialer check. Telecom listens for `ACTION_MICROPHONE_MUTE_CHANGED` and adopts the
 * new state as the call's (`MUTE_EXTERNALLY_CHANGED`), except in an emergency call, where it forces mute off. It
 * clears mute when a new call is answered and when a call ends. Main thread only.
 */
class CallMuter(private val service: AccessibilityService) {
  private val audio = service.getSystemService(AudioManager::class.java)
  private val main = Handler(Looper.getMainLooper())

  /** Which path last changed the mute, so undo uses the same one. */
  var lastPath: String? = null
    private set

  fun isMuted(): Boolean = audio.isMicrophoneMute

  fun audioMode(): Int = audio.mode

  /**
   * Sets the mute to [mute]. [path] forces one path (for tests); null runs [muteCall] or [unmuteCall]. [done] gets the
   * result, the path that ran and the mic state read back.
   */
  fun set(mute: Boolean, path: String?, foreground: Boolean = false, done: (ActionResult, String, Boolean) -> Unit) {
    when (path) {
      NOTIFICATION -> pressNotification(mute, done) { r, m -> done(r, NOTIFICATION, m) }
      DIALER -> pressDialer(mute, done)
      AUDIO, GLOBAL -> setDirect(mute, done, fallback = null)
      else -> if (mute) muteCall(done, foreground) else unmuteCall(done)
    }
  }

  /**
   * TJ's ladder, each step logged as `MuteStep`:
   * 1. `AudioManager.setMicrophoneMute(true)` at once, so nothing leaks, then confirm Telecom adopted it as the call's
   *    mute (Android 13 to 16 listen for `ACTION_MICROPHONE_MUTE_CHANGED`): the call notification offers "Unmute".
   * 2. Otherwise fire the call notification's own Mute action.
   * 3. Otherwise bring the in-call screen forward and press its Mute button ([pressDialer]). Steps 1 and 2 never
   *    foreground anything; [foreground] forces step 3 for testing.
   * 4. Otherwise the global mic mute alone is the mute (path `global-mic`; Pip says "I muted your microphone").
   *
   * AudioService keeps one mic-mute flag and Telecom mutes calls through it, so the global mute is never lifted: that
   * would unmute the call. Telecom forces mute off in an emergency call and Pip never fights it: every step then fails
   * and Pip shows "I couldn't mute you".
   */
  private fun muteCall(done: (ActionResult, String, Boolean) -> Unit, foreground: Boolean = false) {
    val before = audio.isMicrophoneMute
    audio.setMicrophoneMute(true)
    ActLog.record("MuteStep", "step" to "1-global", "path" to AUDIO, "before" to before, "isMicrophoneMute" to audio.isMicrophoneMute)
    main.postDelayed({
      val label = GuardNotificationListener.instance?.callMuteAction()?.first
      val adopted = label.equals("Unmute", true)
      ActLog.record("MuteStep", "step" to "1-confirm", "path" to AUDIO, "notificationAction" to label, "callAdopted" to adopted,
        "isMicrophoneMute" to audio.isMicrophoneMute)
      // Step 4: whatever the call UI says, the global mute stays; it is the mute when nothing else confirms one.
      val keepGlobal = {
        val muted = audio.isMicrophoneMute
        ActLog.record("MuteStep", "step" to "4-global-only", "path" to GLOBAL, "isMicrophoneMute" to muted)
        if (muted) { lastPath = GLOBAL; done(ActionResult.Done(UndoToken("mute:$GLOBAL")), GLOBAL, true) }
        else done(ActionResult.Failed("Telecom reset the mute (an emergency call forces it off)"), GLOBAL, false)
      }
      val step3 = { pressDialer(true, done, foreground = true, fallback = keepGlobal) }
      when {
        foreground -> { ActLog.record("MuteStep", "step" to "3-forced"); step3() }
        adopted -> { liftGlobalNote(); lastPath = AUDIO; done(ActionResult.Done(UndoToken("mute:$AUDIO")), AUDIO, true) }
        label != null -> pressNotification(true, done) { _, _ -> step3() }
        else -> step3()
      }
    }, ADOPT_MS)
  }

  private fun liftGlobalNote() = ActLog.record("MuteStep", "step" to "lift-global", "lifted" to false, "sameFlag" to true,
    "why" to "Telecom's call mute is AudioService's one mic-mute flag; lifting it would unmute the call")

  /** Undo: the phone app's own Unmute when it owns the mute, then clear any global mute, then read back. */
  private fun unmuteCall(done: (ActionResult, String, Boolean) -> Unit) {
    val action = GuardNotificationListener.instance?.callMuteAction()
    val sent = action?.first.equals("Unmute", true) && runCatching { action!!.second.send() }.isSuccess
    ActLog.record("MuteStep", "step" to "phone-app-unmute", "notificationAction" to action?.first, "sent" to sent)
    main.postDelayed({
      if (audio.isMicrophoneMute) audio.setMicrophoneMute(false)
      val muted = audio.isMicrophoneMute
      val label = GuardNotificationListener.instance?.callMuteAction()?.first
      val path = if (sent) NOTIFICATION else GLOBAL
      ActLog.record("MuteStep", "step" to "clear-global", "path" to path, "isMicrophoneMute" to muted, "notificationActionAfter" to label)
      if (!muted) done(ActionResult.Done(null), path, false) else done(ActionResult.Failed("isMicrophoneMute still true"), path, true)
    }, if (sent) SETTLE_MS else 0L)
  }

  /** The call notification's Mute/Unmute action: the phone app mutes itself, so its UI and Telecom agree. */
  private fun pressNotification(mute: Boolean, done: (ActionResult, String, Boolean) -> Unit, orElse: (ActionResult, Boolean) -> Unit) {
    val action = GuardNotificationListener.instance?.callMuteAction()
    val before = audio.isMicrophoneMute
    if (action == null) {
      ActLog.record("CallNotification", "found" to false, "listener" to (GuardNotificationListener.instance != null))
      return orElse(ActionResult.NotDone(Unavailable.NotOnThisPlatform("No Mute action on the call notification")), before)
    }
    val (label, intent) = action
    // The action's label is the call's state: "Unmute" means the call is already muted.
    val already = label.equals(if (mute) "Unmute" else "Mute", true)
    val sent = already || runCatching { intent.send() }.isSuccess
    ActLog.record("MuteStep", "step" to "2-call-notification", "action" to label, "alreadyMuted" to already, "micMutedBefore" to before, "sent" to sent)
    main.postDelayed({
      val settled = callShowsMuted()
      ActLog.record("MuteReadback", "path" to NOTIFICATION, "requested" to mute, "callMuted" to settled, "isMicrophoneMute" to audio.isMicrophoneMute,
        "when" to "${SETTLE_MS}ms", "notificationAction" to GuardNotificationListener.instance?.callMuteAction()?.first)
      if (sent && settled == mute) { lastPath = NOTIFICATION; done(ActionResult.Done(UndoToken("mute:$NOTIFICATION")), NOTIFICATION, settled) }
      else orElse(ActionResult.Failed("Sent the call notification's $label but isMicrophoneMute reads $settled"), settled)
    }, SETTLE_MS)
  }

  /** Last resort: `AudioManager.setMicrophoneMute`. */
  private fun setDirect(mute: Boolean, done: (ActionResult, String, Boolean) -> Unit, fallback: (() -> Unit)?) {
    val before = audio.isMicrophoneMute
    runCatching { audio.setMicrophoneMute(mute) }.onFailure {
      ActLog.record("Acted", "kind" to "MUTE_MIC", "path" to AUDIO, "mute" to mute, "result" to "Failed", "error" to it.message)
      return done(ActionResult.Failed(it.message ?: "setMicrophoneMute threw"), AUDIO, before)
    }
    ActLog.record("MuteReadback", "path" to AUDIO, "requested" to mute, "before" to before, "isMicrophoneMute" to audio.isMicrophoneMute, "when" to "immediate")
    main.postDelayed({
      val settled = audio.isMicrophoneMute
      ActLog.record("MuteReadback", "path" to AUDIO, "requested" to mute, "isMicrophoneMute" to settled, "when" to "${SETTLE_MS}ms",
        "notificationAction" to GuardNotificationListener.instance?.callMuteAction()?.first)
      if (settled == mute) { lastPath = AUDIO; done(ActionResult.Done(UndoToken("mute:$AUDIO")), AUDIO, settled) }
      else fallback?.invoke() ?: done(ActionResult.Failed("isMicrophoneMute reads $settled after setMicrophoneMute($mute)"), AUDIO, settled)
    }, SETTLE_MS)
  }

  /**
   * The in-call screen's own Mute button. When the screen isn't in front (or [foreground] asks for it), Telecom brings
   * it up with `showInCallScreen`; Telecom starts the activity itself, so background-activity-launch limits on Pip
   * don't apply. Pip waits for the button to appear in the window list, then clicks it if the call isn't muted yet.
   */
  private fun pressDialer(
    mute: Boolean, done: (ActionResult, String, Boolean) -> Unit, foreground: Boolean = false, fallback: (() -> Unit)? = null,
  ) {
    if (foreground || buttonGridTop() == null) {
      val shown = service.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        runCatching { service.getSystemService(TelecomManager::class.java).showInCallScreen(false) }.isSuccess
      ActLog.record("MuteStep", "step" to "show-in-call-screen", "path" to DIALER, "requested" to shown, "forced" to foreground)
      return waitForInCallScreen(System.currentTimeMillis()) { pressDialer(mute, done, foreground = false, fallback = fallback) }
    }
    val button = findMuteButton()
    if (button == null) {
      ActLog.record("Acted", "kind" to "PRESS_DIALER_BUTTON", "path" to DIALER, "result" to "NotDone", "why" to "no Mute button on screen")
      if (fallback != null) return fallback()
      return done(ActionResult.NotDone(Unavailable.NotOnThisPlatform("The in-call screen's Mute button isn't on screen")), DIALER, audio.isMicrophoneMute)
    }
    val label = "${button.contentDescription ?: button.text ?: ""}"
    // Google's dialer keeps the label "Mute" either way and isn't checkable, so the call's state comes from its
    // notification ("Unmute" while muted), or the mic flag when there is none.
    val before = callShowsMuted()
    val clicked = if (before == mute) true else clickable(button)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    ActLog.record("DialerButton", "label" to label, "id" to button.viewIdResourceName, "selected" to button.isSelected,
      "state" to button.stateDescription, "callMutedBefore" to before, "clicked" to clicked)
    main.postDelayed({
      val settled = callShowsMuted()
      ActLog.record("MuteReadback", "path" to DIALER, "requested" to mute, "callMuted" to settled, "isMicrophoneMute" to audio.isMicrophoneMute,
        "notificationAction" to GuardNotificationListener.instance?.callMuteAction()?.first, "when" to "${SETTLE_MS}ms")
      if (clicked && settled == mute) { lastPath = DIALER; done(ActionResult.Done(UndoToken("mute:$DIALER")), DIALER, settled) }
      else fallback?.invoke() ?: done(ActionResult.Failed("Pressed the dialer's Mute ($clicked) but the call reads muted=$settled"), DIALER, settled)
    }, SETTLE_MS)
  }

  /** Polls the window list until the in-call screen's buttons appear, or gives up after [SHOW_CALL_MS]. */
  private fun waitForInCallScreen(since: Long, then: () -> Unit) {
    val top = buttonGridTop()
    if (top != null || System.currentTimeMillis() - since > SHOW_CALL_MS) {
      ActLog.record("MuteStep", "step" to "in-call-screen", "inFront" to (top != null), "afterMs" to System.currentTimeMillis() - since)
      return then()
    }
    main.postDelayed({ waitForInCallScreen(since, then) }, 150)
  }

  /** Whether the call itself is muted: its notification offers "Unmute", or, with no notification, the mic flag. */
  private fun callShowsMuted(): Boolean =
    GuardNotificationListener.instance?.callMuteAction()?.first?.equals("Unmute", true) ?: audio.isMicrophoneMute

  /** The dialer's Mute toggle, in any interactive window (Pip's own overlay is usually the active one). */
  fun findMuteButton(): AccessibilityNodeInfo? {
    // App windows only: the call notification in the shade has a Mute action too.
    val roots = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
      .filter { it.packageName?.toString() != service.packageName }
    for (root in roots) find(root) { n ->
      val label = "${n.contentDescription ?: n.text ?: ""}".trim()
      label.equals("Mute", true) || label.equals("Unmute", true) || n.viewIdResourceName?.endsWith("/muteButton") == true
    }?.let { return it }
    return null
  }

  /** How the in-call screen's Mute button reads (label and states), or null when it isn't on screen. */
  fun dialerShowsMuted(): String? = findMuteButton()?.let { b ->
    "label=${b.contentDescription ?: b.text} selected=${b.isSelected} state=${b.stateDescription}"
  }

  /** Top of the in-call screen's button grid in screen pixels (the Mute button's row), or null when it isn't on screen. */
  fun buttonGridTop(): Int? {
    // Only the in-call screen itself: the call notification in the shade has a Mute action too, but it moves away.
    val roots = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
      .filter { it.packageName?.toString() != service.packageName }
    for (root in roots) find(root) { n -> "${n.contentDescription ?: n.text ?: ""}".trim().let { it.equals("Mute", true) || it.equals("Unmute", true) } }
      ?.let { b -> return Rect().also { b.getBoundsInScreen(it) }.top }
    return null
  }

  private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var n: AccessibilityNodeInfo? = node
    while (n != null && !n.isClickable) n = n.parent
    return n
  }

  private fun find(node: AccessibilityNodeInfo, depth: Int = 0, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (depth > 40) return null
    if (match(node)) return node
    for (i in 0 until node.childCount) node.getChild(i)?.let { c -> find(c, depth + 1, match)?.let { return it } }
    return null
  }

  companion object {
    const val NOTIFICATION = "call-notification"
    const val AUDIO = "audio-manager"
    const val GLOBAL = "global-mic"
    /** How long Telecom takes to adopt an outside mic mute into the call and the dialer to repost its notification. */
    const val ADOPT_MS = 700L
    const val DIALER = "dialer-button"
    const val SETTLE_MS = 1200L
    /** How long Pip waits for the in-call screen to come forward. */
    const val SHOW_CALL_MS = 2500L
  }
}
