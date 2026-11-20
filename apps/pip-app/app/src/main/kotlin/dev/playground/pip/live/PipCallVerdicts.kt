package dev.playground.pip.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import dev.playground.pip.BuildConfig
import dev.playground.pip.core.CallVerdict
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import dev.playground.pip.core.ScreenGuard
import dev.playground.pip.sources.GuardAccessibilityService
import dev.playground.pip.sources.OutgoingCallLookup
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Live scam check for ordinary phone calls. The phone transcribes the call itself (CallAudioCapture or the adb
 * simulator); each line goes to pip-call's POST /app/calls/<id>/lines, which asks Pip4 (Jev) and answers with
 * the verdict in the same response. Plain HTTP on one kept-alive connection.
 *
 * Partials (`final: false`) are throttled to once per 400 ms and only when the text changes, so Pip4's
 * gift-card + reading-digits tripwire can fire mid-sentence. Finals always go out.
 *
 * What the app does with the verdict, by Pip4's level:
 *   watch     Pip's speech bubble: careful, don't share personal details.
 *   alert     warning screen (ScamWarningActivity): hang up / call family / keep talking.
 *   takeover  mutes Mom's microphone (AudioManager, or the dialer's Mute button via the accessibility service)
 *             and turns the caller's voice down as far as her phone allows ([muteCaller]), then the red warning
 *             screen: hang up / call family / unmute.
 */
object PipCallVerdicts {
  private const val TAG = "PipCallVerdicts"
  private const val PARTIAL_MIN_MS = 400L
  /**
   * A partial that hasn't changed for this long is sent as a final line. On a real phone the recognizer can keep
   * growing one partial for a whole call and never finalize it, so Jev (which scores finals only) saw nothing.
   */
  private const val SETTLE_MS = 1500L

  enum class Level { CALM, WATCH, ALERT, TAKEOVER }

  data class Verdict(
    val callId: String,
    val level: Level,
    val risk: Double? = null,
    val scamType: String? = null,
    val reason: String? = null,
    val tripwireRule: String? = null,
    val tripwireFiredNow: Boolean = false,
    /** from handing the line over to having the verdict on the phone */
    val roundTripMs: Long = 0,
    val serverMs: Long? = null,
    val pip4Ms: Long? = null,
    val jevMs: Long? = null,
  )

  private val _verdict = MutableStateFlow<Verdict?>(null)
  val verdict: StateFlow<Verdict?> = _verdict

  /** One thread so lines reach the server in order, on one kept-alive connection. */
  private val io = Executors.newSingleThreadExecutor { Thread(it, "pip-call-live").apply { isDaemon = true } }
  private val main = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }
  private lateinit var app: Context

  private val callIds = mutableMapOf<String, String>()
  private var startedAt = 0L
  @Volatile private var context: Map<String, Any> = emptyMap()
  /** True when screening or a sim broadcast pinned the other party; call-log fallback must not overwrite that. */
  @Volatile private var callerPinned = false
  @Volatile private var shownLevel = Level.CALM
  /** "callId issue" pairs ([issueOf]) she chose to keep talking through; Pip doesn't warn about them again on that call. */
  private val acceptedIssues = mutableSetOf<String>()
  @Volatile private var mutedByPip = false
  /** The call volume before Pip silenced the caller ([muteCaller]); null when he hasn't. */
  @Volatile private var callerVolumeBefore: Int? = null
  private var idleWatch: TelephonyCallback? = null
  private var outgoingObserver: ContentObserver? = null
  private val retryOutgoing = Runnable { captureOutgoingCaller() }
  private var lastPartialText = ""
  private var lastPartialAt = 0L
  private var pendingPartial: Pending? = null
  private var loggedSkip = false
  private val flushPartial = Runnable { flushPendingPartial() }

  /** The newest partial, for [settlePartial]; and the part of the current utterance already sent as final lines. */
  private var latestPartial: Pending? = null
  private var settledText = ""
  private val settlePartial = Runnable { settle() }

  private data class Pending(val callId: String, val speaker: String, val text: String, val t: Double)

  val enabled get() = BuildConfig.PIP_APP_SECRET.isNotBlank()

  fun init(context: Context) {
    app = context.applicationContext
  }

  /**
   * What the call screening service (or a simulated broadcast) knows about the other party; sent with the next line.
   * [inContacts] overrides the "name present ⇒ in contacts" default when the sim passes it explicitly.
   */
  fun setCaller(
    number: String,
    contactName: String?,
    incoming: Boolean,
    verification: String? = null,
    inContacts: Boolean? = null,
  ) {
    applyCaller(number, contactName, incoming, verification, inContacts, pin = true)
  }

  private fun applyCaller(
    number: String,
    contactName: String?,
    incoming: Boolean,
    verification: String? = null,
    inContacts: Boolean? = null,
    pin: Boolean,
  ) {
    context = buildMap {
      put("direction", if (incoming) "inbound" else "outbound")
      put("caller_in_contacts", inContacts ?: (contactName != null))
      val name = contactName ?: number.ifBlank { "Unknown" }
      put("caller_id", name)
      put("caller_name", name)
      if (number.isNotBlank()) put("caller_number", number)
      if (!verification.isNullOrBlank()) put("verification", verification)
    }
    if (pin) callerPinned = true
  }

  /** Unknown inbound caller, so the demo gift-card tripwire can fire without extra flags. */
  fun ensureDefaultCaller() {
    if (context.isNotEmpty()) return
    applyCaller(number = "anonymous", contactName = null, incoming = true, verification = "not_verified", inContacts = false, pin = false)
  }

  /**
   * Fill pip4 context for an outbound call when CallScreeningService did not run.
   * Uses the call log (already-granted READ_CALL_LOG) and watches briefly if the row is not in yet.
   */
  fun watchOutgoingCaller() {
    if (!::app.isInitialized || callerPinned) return
    if (captureOutgoingCaller()) return
    if (outgoingObserver != null) return
    if (app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
    val observer = object : ContentObserver(main) {
      override fun onChange(selfChange: Boolean) {
        captureOutgoingCaller()
      }
    }
    outgoingObserver = observer
    runCatching {
      app.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
    }.onFailure {
      Log.w(TAG, "call log observer failed: ${it.message}")
      outgoingObserver = null
    }
    // The row is sometimes written a beat after OFFHOOK.
    main.postDelayed(retryOutgoing, 750)
    main.postDelayed(retryOutgoing, 2000)
  }

  /** @return true if outbound metadata was applied. */
  private fun captureOutgoingCaller(): Boolean {
    if (!::app.isInitialized || callerPinned) return callerPinned
    val party = OutgoingCallLookup.recent(app) ?: return false
    applyCaller(
      number = party.number,
      contactName = party.contactName,
      incoming = false,
      inContacts = party.inContacts,
      pin = true,
    )
    stopOutgoingWatch()
    Guard.note(
      Source.CALL_STATE,
      "Outbound call: ${party.contactName ?: party.number}",
      "number=${party.number}, in_contacts=${party.inContacts}",
    )
    Log.i(TAG, "outbound caller from call log: in_contacts=${party.inContacts}")
    return true
  }

  private fun stopOutgoingWatch() {
    main.removeCallbacks(retryOutgoing)
    outgoingObserver?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
    outgoingObserver = null
  }

  /** Opens the connection before the first line, so that line doesn't pay for the TLS handshake. */
  fun warmUp() {
    if (!enabled) return
    ensureDefaultCaller()
    io.execute { runCatching { request("GET", "${BuildConfig.PIP_CALL_URL}/health", null) } }
  }

  /**
   * A line of the call. [callKey] is the app's own key for the call (Guard's), [speaker] "user" for Mom
   * or "caller". Partials are coalesced; finals always go out. Returns at once; the verdict arrives on
   * [verdict] and drives the warning.
   */
  fun send(callKey: String, speaker: String, text: String, t: Double? = null, final: Boolean = true) {
    if (text.isBlank()) return
    if (!enabled) {
      if (!loggedSkip) {
        loggedSkip = true
        Log.w(TAG, "PIP_APP_SECRET is blank; not posting transcripts to pip-call")
      }
      return
    }
    ensureDefaultCaller()
    val callId = synchronized(callIds) {
      if (startedAt == 0L) startedAt = System.currentTimeMillis()
      callIds.getOrPut(callKey) {
        if (callKey.startsWith("call-") || callKey.startsWith("sim-") || callKey.startsWith("live-") || callKey.startsWith("app-")) callKey
        else "app-" + UUID.randomUUID().toString().take(12)
      }
    }
    val whenS = t ?: elapsedSeconds()
    if (final) {
      main.removeCallbacks(flushPartial)
      main.removeCallbacks(settlePartial)
      val rest = synchronized(callIds) {
        lastPartialText = ""
        pendingPartial = null
        latestPartial = null
        unsent(text.trim()).also { settledText = "" }
      }
      if (rest.isNotEmpty()) enqueue(callId, speaker, rest, whenS, final = true)
      return
    }
    synchronized(callIds) { latestPartial = Pending(callId, speaker, text.trim(), whenS) }
    main.removeCallbacks(settlePartial)
    main.postDelayed(settlePartial, SETTLE_MS)
    val now = System.currentTimeMillis()
    var sendNow: Pending? = null
    var delay = 0L
    synchronized(callIds) {
      val clean = text.trim()
      if (clean == lastPartialText) return
      val wait = PARTIAL_MIN_MS - (now - lastPartialAt)
      if (wait <= 0) {
        lastPartialText = clean
        lastPartialAt = now
        sendNow = Pending(callId, speaker, clean, whenS)
      } else {
        pendingPartial = Pending(callId, speaker, clean, whenS)
        delay = wait
      }
    }
    if (sendNow != null) {
      enqueue(sendNow!!.callId, sendNow!!.speaker, sendNow!!.text, sendNow!!.t, final = false)
    } else if (delay > 0) {
      main.removeCallbacks(flushPartial)
      main.postDelayed(flushPartial, delay)
    }
  }

  /** What of [text] hasn't gone out as a final line yet: all of it, or what follows the settled prefix. */
  private fun unsent(text: String): String = when {
    settledText.isEmpty() -> text
    text.startsWith(settledText) -> text.removePrefix(settledText).trim()
    else -> text // the recognizer rewrote earlier words; send the whole line rather than lose it
  }

  /** The partial stopped changing: send its new part as a final line, so Jev scores it. */
  private fun settle() {
    val line = synchronized(callIds) {
      val p = latestPartial ?: return
      latestPartial = null
      val rest = unsent(p.text)
      settledText = p.text
      if (rest.isEmpty()) return
      p.copy(text = rest)
    }
    Log.i(TAG, "settled a partial into a final line (${line.text.length} chars)")
    enqueue(line.callId, line.speaker, line.text, line.t, final = true)
  }

  private fun elapsedSeconds(): Double = if (startedAt == 0L) 0.0 else (System.currentTimeMillis() - startedAt) / 1000.0

  private fun flushPendingPartial() {
    val p = synchronized(callIds) {
      val pending = pendingPartial ?: return
      if (pending.text == lastPartialText) {
        pendingPartial = null
        return
      }
      lastPartialText = pending.text
      lastPartialAt = System.currentTimeMillis()
      pendingPartial = null
      pending
    }
    enqueue(p.callId, p.speaker, p.text, p.t, final = false)
  }

  private fun enqueue(callId: String, speaker: String, text: String, t: Double, final: Boolean) {
    val ctx = context
    val handedAt = System.nanoTime()
    io.execute {
      try {
      val body = buildJsonObject {
        putJsonArray("lines") {
          addJsonObject {
            put("speaker", speaker)
            put("text", text)
            put("t", t)
            put("final", final)
          }
        }
        put("listen", "user")
        put("context", buildJsonObject {
          for ((k, v) in ctx) when (v) {
            is Boolean -> put(k, v)
            is Number -> put(k, v.toDouble())
            else -> put(k, v.toString())
          }
          put("local_time", OffsetDateTime.now().toString())
        })
      }.toString()
      val reply = runCatching { request("POST", "${BuildConfig.PIP_CALL_URL}/app/calls/$callId/lines", body) }
        .onFailure { Log.w(TAG, "line not scored: ${it.message}") }
        .getOrNull() ?: return@execute
      val o = json.parseToJsonElement(reply).jsonObject
      fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
      fun obj(k: String) = o[k] as? kotlinx.serialization.json.JsonObject
      fun flag(k: String) = runCatching { o[k]?.jsonPrimitive }.getOrNull()?.let { prim ->
        prim.booleanOrNull ?: (prim.contentOrNull == "true")
      } ?: (obj(k) != null)
      val trip = obj("tripwire")
      val fired = obj("tripwireFiredNow")
      val v = Verdict(
        callId = callId,
        level = runCatching { Level.valueOf((s("level") ?: "calm").uppercase()) }.getOrDefault(Level.CALM),
        risk = runCatching { o["risk"]?.jsonPrimitive?.doubleOrNull }.getOrNull(),
        scamType = s("scamType"),
        reason = s("reason"),
        tripwireRule = s("tripwireRule") ?: fired?.get("rule")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: trip?.get("rule")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() },
        tripwireFiredNow = flag("tripwireFiredNow"),
        roundTripMs = (System.nanoTime() - handedAt) / 1_000_000,
        serverMs = o["serverMs"]?.jsonPrimitive?.longOrNull,
        pip4Ms = o["pip4Ms"]?.jsonPrimitive?.longOrNull,
        jevMs = o["jevMs"]?.jsonPrimitive?.longOrNull,
      )
      Log.i(TAG, "verdict ${v.level} risk=${v.risk} final=$final phone↔server=${v.roundTripMs}ms server=${v.serverMs}ms pip4=${v.pip4Ms}ms jev=${v.jevMs}ms trip=${v.tripwireRule}")
      _verdict.value = v
      Guard.note(
        Source.TRANSCRIPT,
        "Pip4: ${v.level.name.lowercase()}",
        "risk ${v.risk ?: "?"} · ${v.roundTripMs} ms round trip (server ${v.serverMs} ms, Jev ${v.jevMs} ms)${v.tripwireRule?.let { " · $it" } ?: ""}${v.reason?.let { " · $it" } ?: ""}",
      )
      Guard.applyServerVerdict(
        CallVerdict(
          callId = v.callId,
          level = v.level.name.lowercase(),
          action = s("action").orEmpty(),
          reason = v.reason.orEmpty(),
          scored = o["scored"]?.jsonPrimitive?.contentOrNull != "false",
          risk = v.risk,
          tripwireRule = v.tripwireRule,
          tripwireFiredNow = v.tripwireFiredNow,
        ),
      )
      act(v)
      } catch (e: Exception) {
        Log.w(TAG, "failed to apply pip-call verdict: ${e.message}", e)
      }
    }
  }

  /** Call over: forget the call and give Mom her microphone back. */
  fun endCall() {
    main.removeCallbacks(flushPartial)
    // Whatever was still being said goes out as a final line before the call is forgotten.
    main.removeCallbacks(settlePartial)
    settle()
    synchronized(callIds) {
      latestPartial = null
      settledText = ""
      callIds.clear()
      startedAt = 0L
      lastPartialText = ""
      pendingPartial = null
    }
    stopOutgoingWatch()
    context = emptyMap()
    callerPinned = false
    shownLevel = Level.CALM
    synchronized(acceptedIssues) { acceptedIssues.clear() }
    if (mutedByPip) unmute()
    _verdict.value = null
  }

  /**
   * The mic mute is system-wide and outlives the call, so Pip watches for the call to end itself rather than relying
   * on the accessibility service's listener (which isn't registered when phone permission came after it started).
   */
  private fun watchForCallEnd() {
    if (idleWatch != null || app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
    val tm = app.getSystemService(TelephonyManager::class.java)
    val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
      override fun onCallStateChanged(state: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) endCall()
      }
    }
    idleWatch = cb
    runCatching { tm.registerTelephonyCallback(app.mainExecutor, cb) }.onFailure { idleWatch = null }
  }

  private fun stopWatchingCallEnd() {
    idleWatch?.let { cb -> runCatching { app.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb) } }
    idleWatch = null
  }

  fun unmute() {
    mutedByPip = false
    stopWatchingCallEnd()
    app.getSystemService(AudioManager::class.java).isMicrophoneMute = false
    unmuteCaller()
  }

  /**
   * What a warning is about, so one she accepted isn't raised again on the same call: the gift-card tripwires
   * (an unknown caller asking, then her reading numbers out) are one issue; otherwise Jev's scam type.
   */
  fun issueOf(tripwireRule: String?, scamType: String?): String = when {
    tripwireRule?.startsWith("gift_card") == true -> "gift_card"
    !tripwireRule.isNullOrBlank() -> tripwireRule
    !scamType.isNullOrBlank() && scamType != "none" -> scamType
    else -> "scam"
  }

  /** She tapped Keep talking: no more warnings about [issue] on call [callId]. */
  fun accept(callId: String, issue: String) {
    synchronized(acceptedIssues) { acceptedIssues += "$callId $issue" }
    Log.i(TAG, "accepted issue $issue on $callId; won't warn about it again this call")
  }

  private fun act(v: Verdict) {
    if (v.level <= shownLevel) return // each level is acted on once per call, and never steps back down
    val issue = issueOf(v.tripwireRule, v.scamType)
    if (v.level >= Level.ALERT && synchronized(acceptedIssues) { "${v.callId} $issue" in acceptedIssues }) {
      Guard.note(Source.SYSTEM, "Warning skipped", "she already chose to keep talking about $issue")
      return
    }
    shownLevel = v.level
    when (v.level) {
      Level.CALM -> {}
      Level.WATCH -> Guard.onWarn?.invoke("Careful: don't share personal details or codes on this call.")
      Level.ALERT -> showWarning(v)
      Level.TAKEOVER -> {
        mute()
        showWarning(v)
        // After the warning's ping, which plays on the call stream, so she still hears it; not if she's unmuted since.
        main.postDelayed({ if (mutedByPip) muteCaller() }, 800)
      }
    }
  }

  private fun mute() {
    val am = app.getSystemService(AudioManager::class.java)
    am.isMicrophoneMute = true
    mutedByPip = true
    watchForCallEnd()
    // Some phones ignore a regular app's mic mute during a call; then press the dialer's own Mute button.
    if (!am.isMicrophoneMute) GuardAccessibilityService.instance?.pressMute()
    Guard.note(Source.TRANSCRIPT, "Pip muted the call", "microphone mute=${am.isMicrophoneMute}")
  }

  /**
   * Silences the caller for her too. Android won't let an app that isn't the dialer mute a cellular call's
   * downlink, so this is local: the voice-call stream is muted where the phone allows it (most refuse) and
   * otherwise turned down to its minimum, which some phones keep at 1. [unmuteCaller] puts her volume back.
   */
  private fun muteCaller() {
    if (callerVolumeBefore != null) return
    val am = app.getSystemService(AudioManager::class.java)
    val stream = AudioManager.STREAM_VOICE_CALL
    val before = am.getStreamVolume(stream)
    callerVolumeBefore = before
    runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
    if (!am.isStreamMute(stream)) runCatching { am.setStreamVolume(stream, am.getStreamMinVolume(stream), 0) }
    Guard.note(
      Source.TRANSCRIPT, "Pip muted the caller",
      "call volume $before -> ${am.getStreamVolume(stream)}${if (am.isStreamMute(stream)) " (muted)" else ""}",
    )
  }

  /** Brings the caller's voice back: undoes [muteCaller] and restores the call volume she had. */
  private fun unmuteCaller() {
    val before = callerVolumeBefore ?: return
    callerVolumeBefore = null
    val am = app.getSystemService(AudioManager::class.java)
    val stream = AudioManager.STREAM_VOICE_CALL
    runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
    runCatching { am.setStreamVolume(stream, before, 0) }
    Log.i(TAG, "caller unmuted: call volume back to ${am.getStreamVolume(stream)}")
  }

  private fun showWarning(v: Verdict) {
    val i = Intent(app, ScamWarningActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      .putExtra(ScamWarningActivity.EXTRA_LEVEL, v.level.name)
      .putExtra(ScamWarningActivity.EXTRA_REASON, v.reason)
      .putExtra(ScamWarningActivity.EXTRA_TRIPWIRE, v.tripwireRule)
      .putExtra(ScamWarningActivity.EXTRA_TYPE, v.scamType)
      .putExtra(ScamWarningActivity.EXTRA_RISK, v.risk ?: -1.0)
      .putExtra(ScamWarningActivity.EXTRA_CALL_ID, v.callId)
    // No notification: a short ping, then the warning screen itself. During a call the app is usually in the
    // background, where Android blocks startActivity; the bound accessibility service may start it, so it goes first.
    ping()
    ScreenGuard.dismissBanner()
    GuardAccessibilityService.instance?.parkPipInMiddle()
    val viaService = GuardAccessibilityService.instance?.let { svc ->
      runCatching { svc.startActivity(i); true }.onFailure { Log.w(TAG, "warning via accessibility blocked: ${it.message}") }.getOrDefault(false)
    } ?: false
    if (!viaService) {
      runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "warning screen blocked: ${it.message}") }
    }
  }

  /** A short two-beep ping on the call's audio, so she hears Pip even with the phone at her ear. Local only. */
  private fun ping() {
    runCatching {
      val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90)
      tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
      main.postDelayed({ tone.release() }, 800)
    }.onFailure { Log.w(TAG, "ping failed: ${it.message}") }
  }

  private fun request(method: String, url: String, body: String?): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 5000
    conn.readTimeout = 8000
    conn.setRequestProperty("x-pip-app-secret", BuildConfig.PIP_APP_SECRET)
    if (body != null) {
      conn.doOutput = true
      conn.setRequestProperty("content-type", "application/json")
      conn.outputStream.use { it.write(body.toByteArray()) }
    }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    // no disconnect(): reading the body to the end hands the socket back to the keep-alive pool
    if (code !in 200..299) error("HTTP $code ${text.take(200)}")
    return text
  }
}
