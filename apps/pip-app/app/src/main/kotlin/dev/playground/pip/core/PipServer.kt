package dev.playground.pip.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import dev.playground.pip.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Posts live call transcripts to jev-playground (`POST {PIP_SERVER_URL}/api/calls/:callId`) and
 * exposes the verdict for TJ's intervention layer via [Guard.onCallVerdict] / [Guard.callVerdict].
 *
 * Body:
 * ```
 * { "chunks": [{ "speaker": "user"|"caller", "text", "t": seconds, "final": boolean }],
 *   "context": { caller_number, caller_name, caller_in_contacts, direction, verification,
 *                call_duration_s, local_time },
 *   "listen": "user", "approach": "jev" }
 * ```
 * Partials (`final: false`) are throttled to once per 500 ms and only when the text changes, so the
 * server's gift-card + reading-digits tripwire can fire mid-sentence. Finals always go out.
 *
 * Local [Guard.reportCallLine] stays the offline fallback; this client is best-effort (5s timeout, one retry).
 *
 * The live call path used by the app is [dev.playground.pip.live.PipCallVerdicts] → pip-call → Pip4.
 * This direct client is kept as a fallback implementation but is not wired to capture or sim receivers.
 */
data class CallMeta(
  val callerNumber: String? = null,
  val callerName: String? = null,
  val callerInContacts: Boolean? = null,
  val direction: String? = null,
  val verification: String? = null,
)

data class CallVerdict(
  val callId: String,
  val level: String,
  val action: String,
  val reason: String,
  val scored: Boolean = false,
  val risk: Double? = null,
  val tripwireRule: String? = null,
  val tripwireFiredNow: Boolean = false,
)

object PipServer {
  const val TAG = "PipServer"
  private const val TIMEOUT_MS = 5_000
  private const val PARTIAL_MIN_MS = 500L

  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pip-server").apply { isDaemon = true } }
  private val main = Handler(Looper.getMainLooper())
  private val lock = Any()

  @Volatile private var callId: String? = null
  @Volatile private var startedAt = 0L
  @Volatile private var meta = CallMeta()
  /** Screening can land before the call goes off-hook; kept until [endCall]. */
  @Volatile private var pendingMeta = CallMeta()

  private var lastPartialText = ""
  private var lastPartialAt = 0L
  private var pendingPartial: Pending? = null
  private val flushPartial = Runnable { flushPendingPartial() }

  private var loggedSkip = false

  private data class Pending(val callId: String, val speaker: String, val text: String, val t: Double)

  fun currentCallId(): String? = callId
  fun currentMeta(): CallMeta = meta
  fun elapsedSeconds(): Double = if (startedAt == 0L) 0.0 else (System.currentTimeMillis() - startedAt) / 1000.0

  /** Incoming/outgoing screening result, recorded even before the call is answered. */
  fun setScreeningMeta(next: CallMeta) {
    synchronized(lock) {
      pendingMeta = merge(pendingMeta, next)
      if (callId != null) meta = merge(meta, next)
    }
  }

  /**
   * Opens a live call session. [context] is used to fill missing number/direction from the call log
   * (outgoing calls that screening didn't already describe). Pass null for simulated calls.
   */
  fun beginCall(id: String, context: Context? = null, seed: CallMeta? = null) {
    val trimmed = id.trim().ifEmpty { "call-${System.currentTimeMillis()}" }
    synchronized(lock) {
      if (callId == trimmed && startedAt != 0L) {
        if (seed != null) meta = merge(meta, seed)
        return
      }
      callId = trimmed
      startedAt = System.currentTimeMillis()
      lastPartialText = ""
      lastPartialAt = 0L
      pendingPartial = null
      meta = merge(pendingMeta, seed ?: CallMeta())
    }
    main.removeCallbacks(flushPartial)
    if (context != null) fillMissingFromDevice(context)
    Log.i(TAG, "begin call=$trimmed meta=$meta")
  }

  fun endCall() {
    main.removeCallbacks(flushPartial)
    synchronized(lock) {
      Log.i(TAG, "end call=$callId")
      callId = null
      startedAt = 0L
      meta = CallMeta()
      pendingMeta = CallMeta()
      lastPartialText = ""
      pendingPartial = null
    }
  }

  /**
   * Send one utterance. Partials are coalesced; finals flush any pending partial and go out immediately.
   * If no call is open, [callId] starts one (used by the adb sim receiver).
   */
  fun sendLine(callId: String, speaker: String, text: String, t: Double? = null, final: Boolean = true) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    val id = callId.trim().ifEmpty { currentCallId() ?: "call-${System.currentTimeMillis()}" }
    if (this.callId == null || this.callId != id) beginCall(id, seed = if (this.callId == null) pendingMeta else null)
    val whenS = t ?: elapsedSeconds()
    if (final) {
      main.removeCallbacks(flushPartial)
      synchronized(lock) {
        lastPartialText = ""
        pendingPartial = null
      }
      enqueue(id, speaker, clean, whenS, final = true)
      return
    }
    val now = System.currentTimeMillis()
    var sendNow: Pending? = null
    var delay = 0L
    synchronized(lock) {
      if (clean == lastPartialText) return
      val wait = PARTIAL_MIN_MS - (now - lastPartialAt)
      if (wait <= 0) {
        lastPartialText = clean
        lastPartialAt = now
        sendNow = Pending(id, speaker, clean, whenS)
      } else {
        pendingPartial = Pending(id, speaker, clean, whenS)
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

  private fun flushPendingPartial() {
    val p = synchronized(lock) {
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

  private fun enqueue(id: String, speaker: String, text: String, t: Double, final: Boolean) {
    if (serverUrl().isEmpty()) {
      if (!loggedSkip) {
        loggedSkip = true
        Log.w(TAG, "PIP_SERVER_URL is empty; not posting transcripts")
      }
      return
    }
    val snapshot = meta
    val duration = t.roundToInt()
    io.execute { postWithRetry(id, speaker, text, t, final, snapshot, duration) }
  }

  private fun postWithRetry(
    id: String,
    speaker: String,
    text: String,
    t: Double,
    final: Boolean,
    snapshot: CallMeta,
    duration: Int,
  ) {
    var lastError: Exception? = null
    repeat(2) { attempt ->
      try {
        val verdict = postOnce(id, speaker, text, t, final, snapshot, duration)
        if (verdict != null) {
          main.post { Guard.applyServerVerdict(verdict) }
        }
        return
      } catch (e: Exception) {
        lastError = e
        if (e is HttpException && e.code in 400..499) { Log.w(TAG, "POST ${e.code} for $id: ${e.message}"); return }
        if (attempt == 0) {
          Log.w(TAG, "POST attempt ${attempt + 1} failed for $id (${e.javaClass.simpleName}); retrying")
          try { Thread.sleep(300) } catch (_: InterruptedException) { return }
        }
      }
    }
    Log.w(TAG, "POST failed for $id: ${lastError?.message}")
  }

  private class HttpException(val code: Int, message: String) : IOException(message)

  private fun postOnce(
    id: String,
    speaker: String,
    text: String,
    t: Double,
    final: Boolean,
    snapshot: CallMeta,
    duration: Int,
  ): CallVerdict? {
    val url = URL(endpoint(id))
    val body = requestBody(speaker, text, t, final, snapshot, duration)
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
      val bypass = BuildConfig.PIP_SERVER_BYPASS
      if (bypass.isNotEmpty()) setRequestProperty("x-vercel-protection-bypass", bypass)
    }
    try {
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw HttpException(code, raw.take(200))
      return parseVerdict(id, raw)
    } finally {
      conn.disconnect()
    }
  }

  internal fun requestBody(
    speaker: String,
    text: String,
    t: Double,
    final: Boolean,
    snapshot: CallMeta = meta,
    duration: Int = t.roundToInt(),
  ): String {
    val chunk = JSONObject()
      .put("speaker", speaker)
      .put("text", text)
      .put("t", t)
      .put("final", final)
    val ctx = JSONObject()
    snapshot.callerNumber?.let { ctx.put("caller_number", it) }
    snapshot.callerName?.let { ctx.put("caller_name", it) }
    snapshot.callerInContacts?.let { ctx.put("caller_in_contacts", it) }
    snapshot.direction?.let { ctx.put("direction", it) }
    snapshot.verification?.let { ctx.put("verification", it) }
    ctx.put("call_duration_s", duration)
    ctx.put("local_time", OffsetDateTime.now().toString())
    return JSONObject()
      .put("chunks", JSONArray().put(chunk))
      .put("context", ctx)
      .put("listen", "user")
      .put("approach", "jev")
      .toString()
  }

  private fun parseVerdict(id: String, raw: String): CallVerdict? {
    if (raw.isBlank()) return null
    val obj = JSONObject(raw)
    if (!obj.has("level")) return null
    val latest = obj.optJSONObject("latest")
    val trip = obj.optJSONObject("tripwire")
    val fired = obj.optJSONObject("tripwireFiredNow")
    return CallVerdict(
      callId = obj.optString("callId", id),
      level = obj.getString("level"),
      action = obj.optString("action", ""),
      reason = obj.optString("reason", ""),
      scored = obj.optBoolean("scored", false),
      risk = latest?.optDouble("risk")?.takeIf { !it.isNaN() },
      tripwireRule = fired?.optString("rule")?.ifBlank { null } ?: trip?.optString("rule")?.ifBlank { null },
      tripwireFiredNow = fired != null,
    )
  }

  private fun serverUrl(): String = BuildConfig.PIP_SERVER_URL.trim()

  private fun endpoint(id: String): String = "${serverUrl().trimEnd('/')}/api/calls/${Uri.encode(id)}"

  private fun merge(base: CallMeta, extra: CallMeta) = CallMeta(
    callerNumber = extra.callerNumber ?: base.callerNumber,
    callerName = extra.callerName ?: base.callerName,
    callerInContacts = extra.callerInContacts ?: base.callerInContacts,
    direction = extra.direction ?: base.direction,
    verification = extra.verification ?: base.verification,
  )

  private fun fillMissingFromDevice(context: Context) {
    var current = meta
    if (current.callerNumber == null) current = merge(current, fromCallLog(context))
    if (current.callerInContacts == null && !current.callerNumber.isNullOrBlank()) {
      val name = contactName(context, current.callerNumber!!)
      current = current.copy(
        callerInContacts = name != null,
        callerName = current.callerName ?: name,
      )
    }
    synchronized(lock) { meta = merge(meta, current) }
  }

  private fun fromCallLog(context: Context): CallMeta {
    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
      return CallMeta()
    }
    return try {
      context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE),
        null,
        null,
        "${CallLog.Calls.DATE} DESC",
      )?.use { c ->
        if (!c.moveToFirst()) return CallMeta()
        val number = c.getString(0).orEmpty()
        val cached = c.getString(1)
        val type = c.getInt(2)
        CallMeta(
          callerNumber = number.ifBlank { "anonymous" },
          callerName = cached?.ifBlank { null },
          direction = if (type == CallLog.Calls.OUTGOING_TYPE) "outbound" else "inbound",
        )
      } ?: CallMeta()
    } catch (e: SecurityException) {
      Log.w(TAG, "call log unavailable: ${e.message}")
      CallMeta()
    }
  }

  private fun contactName(context: Context, number: String): String? {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
      context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    } catch (_: SecurityException) {
      null
    }
  }
}
