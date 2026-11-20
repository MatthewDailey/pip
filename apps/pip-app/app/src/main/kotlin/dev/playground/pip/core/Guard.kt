package dev.playground.pip.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.playground.pip.MainActivity
import dev.playground.pip.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Single entry point every source reports through: score the text, log it, warn when it crosses the threshold. */
object Guard {
  private const val ALERT_CHANNEL = "scam-alerts"
  private const val STATUS_CHANNEL = "status"
  private lateinit var rules: ScamRules
  private lateinit var app: Context
  private val lastAlertAt = mutableMapOf<String, Long>()

  fun init(context: Context) {
    app = context.applicationContext
    EventLog.init(app)
    rules = context.assets.open("scam_rules.json").bufferedReader().use { ScamRules.parse(it.readText()) }
    val nm = app.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Scam warnings", NotificationManager.IMPORTANCE_HIGH))
    nm.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Monitoring status", NotificationManager.IMPORTANCE_LOW))
  }

  fun assess(text: String): Assessment = rules.assess(text)

  private val callHits = mutableMapOf<String, MutableList<RuleSpec>>()

  /** Logs a signal; when the text looks like a scam, also posts a warning. Returns the assessment. */
  fun report(source: Source, title: String, text: String, app: String? = null, alertKey: String = "$source:$app"): Assessment {
    val a = rules.assess("$title\n$text")
    EventLog.add(GuardEvent(System.currentTimeMillis(), source, app, title, text.take(2000), a.score, a.labels))
    if (a.alert) warn(source, a, alertKey)
    return a
  }

  /**
   * A call is judged as a whole: scammers spread the pressure across many lines, each harmless on its own.
   * Each line is logged with its own score; the warning fires when the call's combined rule hits cross the threshold.
   */
  @Synchronized
  fun reportCallLine(callKey: String, title: String, text: String): Assessment {
    val line = rules.assess(text)
    val acc = callHits.getOrPut(callKey) { mutableListOf() }.apply { addAll(line.hits) }
    val call = rules.combine(acc)
    val detail = if (call.score > line.score) "$text\n[call so far: risk ${call.score}]" else text
    EventLog.add(GuardEvent(System.currentTimeMillis(), Source.TRANSCRIPT, null, title, detail, line.score, line.labels))
    if (call.alert) warn(Source.TRANSCRIPT, call, callKey)
    return call
  }

  @Synchronized
  fun endCalls() {
    callHits.clear()
    _callVerdict.value = null
  }

  fun note(source: Source, title: String, detail: String = "", app: String? = null) {
    EventLog.add(GuardEvent(System.currentTimeMillis(), source, app, title, detail))
  }

  /** At most one warning per source key per minute, so a live transcript doesn't spam the person. */
  /** Set by the on-screen mascot so warnings also appear as a speech bubble. */
  @Volatile var onWarn: ((String) -> Unit)? = null

  /** Latest jev-playground verdict for this call. TJ's intervention layer should collect this. */
  @Volatile var onCallVerdict: ((CallVerdict) -> Unit)? = null
  private val _callVerdict = MutableStateFlow<CallVerdict?>(null)
  val callVerdict: StateFlow<CallVerdict?> = _callVerdict


  /** Apply a live server verdict: log it, notify TJ, and surface alert/takeover through the existing warn path. */
  fun applyServerVerdict(v: CallVerdict) {
    val riskBit = v.risk?.let { " risk=${"%.2f".format(it)}" } ?: ""
    val tripBit = v.tripwireRule?.let { " tripwire=$it" } ?: ""
    EventLog.add(
      GuardEvent(
        System.currentTimeMillis(),
        Source.SERVER,
        null,
        "Server verdict: ${v.level}",
        "${v.reason}$riskBit$tripBit action=${v.action}",
        score = ((v.risk ?: 0.0) * 100).toInt(),
        rules = listOfNotNull(v.level, v.tripwireRule),
      ),
    )
    _callVerdict.value = v
    onCallVerdict?.invoke(v)
    if (v.level == "takeover" || v.level == "alert") warnFromServer(v)
  }

  private fun warn(source: Source, a: Assessment, key: String) {
    val now = System.currentTimeMillis()
    if ((lastAlertAt[key] ?: 0) > now - 60_000) return
    lastAlertAt[key] = now
    // Generic warnings are an experiment: shipping builds step in only through act-mute's intervention.
    if (!PipFlags.exploratory) {
      EventLog.add(GuardEvent(now, Source.SYSTEM, null, "Warning held (exploratory off)", a.labels.joinToString(", ")))
      return
    }
    onWarn?.invoke("Hold on! This sounds like a scam: ${a.labels.first().lowercase()}. Don't send money or codes.")
    if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val where = when (source) {
      Source.TRANSCRIPT, Source.CALL_AUDIO -> "this call"
      Source.SMS -> "a text message"
      Source.CALL_SCREEN -> "an incoming call"
      else -> "something on your phone"
    }
    val n = NotificationCompat.Builder(app, ALERT_CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_pip)
      .setContentTitle("This may be a scam")
      .setContentText("Be careful with $where: ${a.labels.joinToString(", ")}")
      .setStyle(NotificationCompat.BigTextStyle().bigText(
        "Be careful with $where.\n\n${a.labels.joinToString("\n") { "• $it" }}\n\nDon't send money, gift cards or codes. Hang up and call a family member.",
      ))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(app).notify(key.hashCode(), n)
  }


  private fun warnFromServer(v: CallVerdict) {
    val key = "server:${v.callId}:${v.level}"
    val now = System.currentTimeMillis()
    if ((lastAlertAt[key] ?: 0) > now - 60_000) return
    lastAlertAt[key] = now
    // Generic overlay bubbles and notifications are exploratory; scam calls get act-mute's card instead.
    if (!PipFlags.exploratory) {
      EventLog.add(GuardEvent(now, Source.SYSTEM, null, "Server warning held (exploratory off)", "${v.level}: ${v.reason}"))
      return
    }
    val summary = WarningCopy.forCall(v.tripwireRule, v.reason)
    onWarn?.invoke(summary)
    if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val title = if (v.level == "takeover") "Pip is stepping in" else "This may be a scam"
    val n = NotificationCompat.Builder(app, ALERT_CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_pip)
      .setContentTitle(title)
      .setContentText(summary)
      .setStyle(NotificationCompat.BigTextStyle().bigText(
        "$summary\n\nDon't send money, gift cards or codes. Hang up and call a family member.",
      ))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(app).notify(key.hashCode(), n)
  }

  fun statusChannel() = STATUS_CHANNEL
}
