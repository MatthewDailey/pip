package dev.playground.pip.twilio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import dev.playground.pip.BuildConfig
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mom's calls through Pip: a Twilio Voice SDK (VoIP) call that runs pip-call's TwiML App, so Pip3 hears both sides,
 * scores them with Pip4 and can take over the call. Flow:
 *   1. GET <pip-call>/token?user=<pip user> with x-pip-app-secret → a 1-hour access token
 *   2. Voice.connect with params To, pip_user, contact_name, in_contacts, direction, local_time
 *      (pip-call's /twiml/voice turns them into Pip4's call context)
 *   3. While connected, poll pip-call's GET /debug/calls/<CallSid> timeline for transcript lines, Pip4's
 *      verdicts and the takeover, and show them (and Pip's warning bubble).
 * Base URL, secret and identity come from BuildConfig (local.properties or env, see build.gradle.kts).
 */
object PipVoice {
  private const val TAG = "PipVoice"

  enum class Phase { IDLE, CONNECTING, RINGING, CONNECTED, RECONNECTING, ENDED, FAILED }

  data class Line(val speaker: String, val text: String)

  data class CallState(
    val phase: Phase = Phase.IDLE,
    val number: String = "",
    val contactName: String? = null,
    val callSid: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val level: String? = null,
    val reason: String? = null,
    val takenOver: Boolean = false,
    val lines: List<Line> = emptyList(),
    val error: String? = null,
  ) {
    val active get() = phase in setOf(Phase.CONNECTING, Phase.RINGING, Phase.CONNECTED, Phase.RECONNECTING)
  }

  private val _state = MutableStateFlow(CallState())
  val state: StateFlow<CallState> = _state

  val configured get() = BuildConfig.PIP_APP_SECRET.isNotBlank()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val json = Json { ignoreUnknownKeys = true }
  private var call: Call? = null
  private var poller: Job? = null
  private var focus: AudioFocusRequest? = null

  /** Places a call to [rawNumber] through Pip. Needs RECORD_AUDIO; READ_CONTACTS (optional) fills in the contact. */
  fun dial(context: Context, rawNumber: String) {
    if (_state.value.active) return
    val app = context.applicationContext
    val number = normalize(rawNumber)
    if (number == null) {
      _state.value = CallState(phase = Phase.FAILED, number = rawNumber, error = "That doesn't look like a phone number")
      return
    }
    if (!configured) {
      _state.value = CallState(phase = Phase.FAILED, number = number, error = "No PIP_APP_SECRET in this build: add pip.appSecret to local.properties")
      return
    }
    val contact = lookupContact(app, number)
    _state.value = CallState(phase = Phase.CONNECTING, number = number, contactName = contact)
    scope.launch {
      val token = runCatching { fetchToken() }.getOrElse {
        Log.w(TAG, "token failed", it)
        fail("Couldn't reach Pip: ${it.message}")
        return@launch
      }
      if (!_state.value.active) return@launch // hung up while fetching
      val params = buildMap {
        put("To", number)
        put("pip_user", BuildConfig.PIP_USER)
        put("in_contacts", (contact != null).toString())
        contact?.let { put("contact_name", it) }
        put("direction", "outbound")
        put("local_time", OffsetDateTime.now().toString())
      }
      Log.i(TAG, "connecting to $number params=$params")
      startAudio(app)
      call = Voice.connect(app, ConnectOptions.Builder(token).params(params).build(), listener(app))
    }
  }

  fun hangUp() {
    val c = call
    if (c != null) c.disconnect() else if (_state.value.active) _state.update { it.copy(phase = Phase.ENDED) }
  }

  fun toggleMute() {
    val muted = !_state.value.muted
    call?.mute(muted)
    _state.update { it.copy(muted = muted) }
  }

  fun toggleSpeaker(context: Context) {
    val on = !_state.value.speaker
    val am = context.getSystemService(AudioManager::class.java)
    if (on) {
      am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
    } else {
      am.clearCommunicationDevice()
    }
    _state.update { it.copy(speaker = on) }
  }

  /** Back to the dial pad after a call has ended. */
  fun reset() {
    if (!_state.value.active) _state.value = CallState()
  }

  private fun listener(app: Context) = object : Call.Listener {
    override fun onConnectFailure(c: Call, e: CallException) {
      Log.w(TAG, "connect failure ${e.errorCode} ${e.message}")
      ended(app, failed = "Call failed: ${e.message} (${e.errorCode})")
    }

    override fun onRinging(c: Call) {
      _state.update { it.copy(phase = Phase.RINGING, callSid = c.sid) }
      c.sid?.let { startPolling(it) }
    }

    override fun onConnected(c: Call) {
      Log.i(TAG, "connected ${c.sid}")
      _state.update { it.copy(phase = Phase.CONNECTED, callSid = c.sid) }
      c.sid?.let { startPolling(it) }
      Guard.note(Source.CALL_STATE, "Pip call connected", "${_state.value.contactName ?: _state.value.number} · ${c.sid}")
    }

    override fun onReconnecting(c: Call, e: CallException) = _state.update { it.copy(phase = Phase.RECONNECTING) }

    override fun onReconnected(c: Call) = _state.update { it.copy(phase = Phase.CONNECTED) }

    override fun onDisconnected(c: Call, e: CallException?) {
      Log.i(TAG, "disconnected ${c.sid} ${e?.message ?: ""}")
      ended(app, failed = e?.let { "Call dropped: ${it.message} (${it.errorCode})" })
    }
  }

  private fun fail(message: String) = _state.update { it.copy(phase = Phase.FAILED, error = message) }

  private fun ended(app: Context, failed: String?) {
    call = null
    stopAudio(app)
    // one last read so the screen shows the final verdict, then stop
    val sid = _state.value.callSid
    poller?.cancel()
    if (sid != null) scope.launch { runCatching { readTimeline(sid) } }
    _state.update { it.copy(phase = if (failed != null) Phase.FAILED else Phase.ENDED, error = failed, muted = false, speaker = false) }
  }

  private fun startPolling(callSid: String) {
    if (poller?.isActive == true) return
    poller = scope.launch {
      while (isActive) {
        runCatching { readTimeline(callSid) }.onFailure { Log.d(TAG, "timeline: ${it.message}") }
        delay(1500)
      }
    }
  }

  /** Reads pip-call's timeline for this call: transcript lines, Pip4's latest level and the takeover. */
  private suspend fun readTimeline(callSid: String) {
    val body = withContext(Dispatchers.IO) { get("${BuildConfig.PIP_CALL_URL}/debug/calls/$callSid", secret = false) } ?: return
    val events = json.parseToJsonElement(body).jsonObject["events"]?.jsonArray ?: return
    val lines = mutableListOf<Line>()
    var level: String? = null
    var reason: String? = null
    var takeover: String? = null
    for (e in events) {
      val o = e.jsonObject
      val data = o["data"] as? JsonObject
      fun str(k: String) = data?.get(k)?.jsonPrimitive?.contentOrNull
      when (o["kind"]?.jsonPrimitive?.contentOrNull) {
        "line" -> lines += Line(str("speaker") ?: "?", str("text") ?: "")
        "verdict" -> { level = str("level"); reason = str("reason") }
        "takeover" -> takeover = str("reason") ?: "scam"
      }
    }
    val before = _state.value
    if (before.callSid != callSid) return
    if (takeover != null && !before.takenOver) {
      Guard.note(Source.TRANSCRIPT, "Pip took over the call", takeover)
      Guard.onWarn?.invoke("Hold on! I've paused this call. It sounds like a scam: don't send money or codes.")
    }
    _state.update { it.copy(lines = lines.takeLast(30), level = level ?: it.level, reason = reason ?: it.reason, takenOver = it.takenOver || takeover != null) }
  }

  private suspend fun fetchToken(): String = withContext(Dispatchers.IO) {
    val user = URLEncoder.encode(BuildConfig.PIP_USER, "UTF-8")
    val body = get("${BuildConfig.PIP_CALL_URL}/token?user=$user", secret = true) ?: error("no token")
    json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull ?: error("no token in reply")
  }

  /** Plain GET; null on 404, throws on other errors. */
  private fun get(url: String, secret: Boolean): String? {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    if (secret) conn.setRequestProperty("x-pip-app-secret", BuildConfig.PIP_APP_SECRET)
    try {
      val code = conn.responseCode
      if (code == 404) return null
      if (code !in 200..299) error("HTTP $code ${conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""}")
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  private fun startAudio(app: Context) {
    val am = app.getSystemService(AudioManager::class.java)
    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
    focus = req
    am.requestAudioFocus(req)
    am.mode = AudioManager.MODE_IN_COMMUNICATION
  }

  private fun stopAudio(app: Context) {
    val am = app.getSystemService(AudioManager::class.java)
    am.clearCommunicationDevice()
    am.mode = AudioManager.MODE_NORMAL
    focus?.let { am.abandonAudioFocusRequest(it) }
    focus = null
  }

  /** E.164-ish: keeps digits and a leading +; a bare 10-digit number is taken as US. */
  internal fun normalize(raw: String): String? {
    val d = raw.filter { it.isDigit() }
    if (d.length !in 3..15) return null
    return when {
      raw.trim().startsWith("+") -> "+$d"
      d.length == 10 -> "+1$d"
      d.length == 11 && d.startsWith("1") -> "+$d"
      else -> "+$d"
    }
  }

  /** The contact's display name for this number, or null when it isn't a contact (or we may not read contacts). */
  private fun lookupContact(app: Context, number: String): String? {
    if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return runCatching {
      app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()
  }
}
