package dev.playground.pip.act

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import dev.playground.pip.ui.ActCard
import dev.playground.pip.ui.PipOverlay
import dev.playground.pip.voice.PipVoice
import dev.playground.pip.voice.VoicePurpose

/**
 * Pip stepping in on a scam call, in the contracts' order: a detection ([ScamCallDetected]) becomes an [Assessment]
 * (danger), the policy ([ActRules]) turns it into a [Decision] (act when "mute me on scam calls" is on), the effector
 * ([CallMuter]) mutes, and the presentation ([ActCard], with its own small Pip) shows what Pip did with undo.
 * With the rule off, Pip warns at danger level and offers "Mute me now" instead.
 *
 * Every step logs one `PipEvents` line through [ActLog]. Main thread only.
 */
class ScamCallResponder(
  private val service: AccessibilityService,
  private val pip: () -> PipOverlay?,
  private val callActive: () -> Boolean,
  private val micLevel: () -> Int?,
) {
  private val main = Handler(Looper.getMainLooper())
  val rules = ActRules(service)
  private val muter = CallMuter(service)
  private val card = ActCard(service) { respond(it, from = "tap") }
  private var voice: PipVoice? = null
  private var current: Pending? = null
  private val timeline = mutableListOf<String>()
  private var hidOverlay = false
  private val ledger = MuteLedger()
  private val muteChanged = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val muted = muter.isMuted()
      if (!ledger.micChanged(muted)) return
      ActLog.record("MuteDropped", "isMicrophoneMute" to muted, "why" to "unmuted outside Pip: the phone app's Unmute or an audio route change")
      if (current?.warning?.escalation == Escalation.ACT) {
        current = null
        timeline += "You were unmuted"
        card.confirm("You're unmuted. They can hear you again.")
        main.postDelayed({ if (current == null) finish() }, 3600)
      }
    }
  }

  private data class Pending(val detection: ScamCallDetected, val warning: WarningEvent, val path: String?, val speak: Boolean)

  fun start() {
    service.registerReceiver(muteChanged, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
    voice = PipVoice(service, VoicePurpose.WARNING).also {
      it.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build())
    }
  }

  fun stop() {
    runCatching { service.unregisterReceiver(muteChanged) }
    card.dismiss()
    voice?.shutdown()
    voice = null
  }

  /**
   * A detector says the live call is a scam. Today that is the debug broadcast
   * (`--es detect scam-call [--es reason …] [--es evidence …] [--es path …] [--ez speak false]`); any other detector,
   * such as a server verdict on the call's transcript, calls this with its own [detector] name.
   */
  fun onDetect(reason: String?, evidence: String?, path: String?, speak: Boolean, detector: String = "debug-broadcast", foreground: Boolean = false) {
    val active = callActive()
    val callId = "call-${System.currentTimeMillis() / 1000}"
    val d = ScamCallDetected("sig-${System.currentTimeMillis()}", System.currentTimeMillis(), ConversationKey.call(callId),
      reason = reason?.takeIf { it.isNotBlank() } ?: "asked for gift cards", evidence = evidence, detector = detector)
    ActLog.record("SignalSeen", "signalId" to d.id, "source" to "detect:scam-call", "conversation" to callId,
      "reason" to d.reason, "evidence" to d.evidence, "callActive" to active, "audioMode" to muter.audioMode(),
      "isMicrophoneMute" to muter.isMuted())
    if (!active) {
      ActLog.record("Decided", "decisionId" to "none", "escalation" to "NONE", "suppressed" to "no active call")
      pip()?.say("No call to mute right now. I'm still listening for scams.", 4000)
      return
    }
    val a = Assessment(d.conversation, Escalation.DANGER, d.reason, d.detector)
    ActLog.record("Assessed", "conversation" to callId, "level" to a.level, "score" to 9, "engine" to a.engine, "latencyMs" to 0)
    val decision = rules.decide(a)
    ActLog.record("Decided", "decisionId" to decision.id, "escalation" to decision.escalation, "rule" to decision.rule,
      "suppressed" to decision.suppressed)
    timeline.clear()
    timeline += "They ${d.reason}"
    if (decision.escalation == Escalation.ACT) mute(d, path, speak, by = decision.rule ?: "rule", foreground = foreground)
    else warn(d, path, speak)
  }

  private fun mute(d: ScamCallDetected, path: String?, speak: Boolean, by: String, foreground: Boolean = false) {
    ActLog.record("Acting", "kind" to "MUTE_MIC", "requestedPath" to (path ?: "auto"), "by" to by, "foreground" to foreground)
    muter.set(true, path, foreground = foreground, done = { result, used, muted ->
      ActLog.record("Acted", "kind" to "MUTE_MIC", "path" to used, "result" to result.javaClass.simpleName,
        "isMicrophoneMute" to muted, "note" to (result as? ActionResult.Failed)?.message)
      val done = result as? ActionResult.Done
      if (done != null) ledger.muted(used)
      if (done == null) {
        // Couldn't mute: fall back to the danger warning so the person still hears why and can hang up.
        return@set warn(d, path, speak, couldNotMute = true)
      }
      // On the global-mic fallback Pip says what he actually did: the microphone, not the call.
      val headline = if (used == CallMuter.GLOBAL) "I muted your microphone." else "I muted you."
      val w = WarningEvent("warn-${d.id}", d.conversation, Escalation.ACT, headline,
        "This caller ${d.reason}. They can't hear you now.",
        listOf(Choice("ok", "Got it")),
        undo = UndoOffer(done.undo ?: UndoToken("mute"), "Unmute me", Long.MAX_VALUE))
      current = Pending(d, w, used, speak)
      timeline += "I muted you: they ${d.reason}"
      Guard.note(Source.SYSTEM, "Pip muted you", "This caller ${d.reason}. Path: $used")
      logMicLevel("muted", 1500)
      present(w, speak, "$headline This caller ${d.reason}. They can't hear you now.", Haptic.ONE_LONG)
    })
  }

  private fun warn(d: ScamCallDetected, path: String?, speak: Boolean, couldNotMute: Boolean = false) {
    val w = WarningEvent("warn-${d.id}", d.conversation, Escalation.DANGER,
      if (couldNotMute) "I couldn't mute you." else "This sounds like a scam.",
      "This caller ${d.reason}. Don't send money, gift cards or codes.",
      listOfNotNull(Choice("mute-now", "Mute me now", primary = true).takeUnless { couldNotMute }, Choice("safe", "Dismiss")))
    current = Pending(d, w, path, speak)
    present(w, speak, "Careful. This caller ${d.reason}. That's a scam.", Haptic.THREE_LONG)
  }

  private fun present(w: WarningEvent, speak: Boolean, line: String, haptic: Haptic) {
    // The intervention brings its own Pip; the wandering one (a developer extra) steps aside until it's over.
    pip()?.takeIf { it.isShown }?.let { it.hide(); hidOverlay = true }
    val gridTop = muter.buttonGridTop()
    val placed = card.show(w, keepClearOf = gridTop)
    buzz(haptic)
    val spoken = speak && voice?.speak(line, TextToSpeech.QUEUE_FLUSH, w.id) == true
    card.talk(if (spoken) 4500 else 1500)
    ActLog.record("Cued", "cue" to "Warn", "warningId" to w.id, "escalation" to w.escalation, "headline" to w.headline,
      "choices" to (listOfNotNull(w.undo?.let { "undo" }) + w.choices.map { it.id }).joinToString(","),
      "haptic" to haptic, "spoken" to spoken, "cardTop" to placed.top, "cardBottom" to placed.bottom, "buttonGridTop" to gridTop)
  }

  /** A tap on the card, or the `--es respond undo|ok|mute-now|safe` debug extra. */
  fun respond(choice: String, from: String) {
    val p = current
    ActLog.record("Responded", "response" to choice, "warningId" to p?.warning?.id, "from" to from)
    if (p == null) return
    when (choice) {
      "undo" -> ledger.unmuted().let { _ -> muter.set(false, p.path.takeIf { it == CallMuter.DIALER || it == CallMuter.AUDIO }) { result, used, muted ->
        ActLog.record("Acted", "kind" to "MUTE_MIC", "undo" to true, "path" to used, "result" to result.javaClass.simpleName,
          "isMicrophoneMute" to muted)
        logMicLevel("unmuted", 1500); logMicLevel("unmuted", 3000)
        timeline += "You unmuted"
        current = null
        card.confirm(if (!muted) "You're unmuted. They can hear you again." else "I couldn't unmute you. Use the phone's Mute button.")
        if (p.speak) voice?.speak(if (!muted) "You're unmuted." else "I couldn't unmute you.", TextToSpeech.QUEUE_FLUSH, "undo")
        main.postDelayed({ if (current == null) finish() }, 3600)
      } }
      "mute-now" -> { card.dismiss(); mute(p.detection, p.path, p.speak, by = "user tap") }
      "ok" -> {
        // The card goes; the mute stays, shown by the phone app's own Mute button and call notification.
        card.dismiss()
      }
      "safe", "dismiss" -> finish()
    }
  }

  /** The call ended (or the person dismissed the card): close everything and log what Pip did. */
  fun onCallEnded() {
    // The mic-mute flag is global: a mute Pip left behind would silence the next call and every app.
    if (ledger.callEnded(muter.isMuted())) muter.set(false, CallMuter.AUDIO) { r, _, m ->
      ActLog.record("Acted", "kind" to "MUTE_MIC", "undo" to true, "why" to "call ended", "result" to r.javaClass.simpleName, "isMicrophoneMute" to m)
    }
    if (current == null && timeline.isEmpty()) return
    finish()
  }

  private fun finish() {
    current = null
    card.dismiss()
    if (hidOverlay) pip()?.show()
    hidOverlay = false
    if (timeline.isNotEmpty()) {
      ActLog.record("Summary", "timeline" to timeline.joinToString(" | "))
      Guard.note(Source.SYSTEM, "What Pip did on this call", timeline.joinToString("\n"))
      timeline.clear()
    }
  }

  /** The call capture's recent peak RMS: near 0 while muted, back up once the mic is live and someone speaks. */
  private fun logMicLevel(state: String, afterMs: Long) = main.postDelayed({
    ActLog.record("MicLevel", "state" to state, "afterMs" to afterMs, "peakRms" to micLevel(), "isMicrophoneMute" to muter.isMuted())
  }, afterMs)

  private enum class Haptic { ONE_LONG, THREE_LONG }

  private fun buzz(h: Haptic) {
    val v = service.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
    val effect = when (h) {
      Haptic.ONE_LONG -> VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE)
      Haptic.THREE_LONG -> VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 500), -1)
    }
    runCatching { v.vibrate(effect) }
  }
}
