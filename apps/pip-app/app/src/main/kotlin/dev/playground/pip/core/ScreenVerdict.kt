package dev.playground.pip.core

import org.json.JSONObject

/**
 * Jev screen-fraud verdict, as returned by `POST /api/screens/classify`.
 * We decide WHETHER Pip intervenes; TJ's layer decides HOW.
 */
data class ScreenVerdict(
  val id: String,
  val intervene: Boolean,
  val risk: Double,
  val fraud: Double,
  val fraudType: String,
  val reason: String,
  val suggestedLine: String?,
  val mode: String,
  val latencyMs: Long,
  val model: String,
  val appPackage: String?,
  val url: String?,
  val signals: Signals = Signals(),
) {
  data class Signals(
    val fakeLogin: Double = 0.0,
    val paymentRequest: Double = 0.0,
    val urgency: Double = 0.0,
    val remoteAccess: Double = 0.0,
  )

  companion object {
    fun fromJson(raw: String): ScreenVerdict {
      val o = JSONObject(raw)
      if (o.has("error") && !o.has("verdict")) {
        throw IllegalArgumentException(o.optString("error", "server error"))
      }
      val v = o.optJSONObject("verdict") ?: JSONObject()
      val signals = v.optJSONObject("signals") ?: JSONObject()
      val source = o.optJSONObject("source") ?: JSONObject()
      val fraudType = v.optString("fraud_type", "benign")
      val suggested = o.optString("suggested_pip_line").ifBlank { null }
      return ScreenVerdict(
        id = o.optString("id"),
        intervene = o.optBoolean("intervene", false),
        risk = v.optDouble("risk", 0.0),
        fraud = v.optDouble("fraud", 0.0),
        fraudType = fraudType,
        reason = suggested ?: fraudType,
        suggestedLine = suggested,
        mode = o.optString("mode", "text"),
        latencyMs = o.optLong("latency_ms", 0L),
        model = o.optString("model"),
        appPackage = source.optString("app_package").ifBlank { null },
        url = source.optString("url").ifBlank { null },
        signals = Signals(
          fakeLogin = signals.optDouble("fake_login", 0.0),
          paymentRequest = signals.optDouble("payment_request", 0.0),
          urgency = signals.optDouble("urgency", 0.0),
          remoteAccess = signals.optDouble("remote_access", 0.0),
        ),
      )
    }
  }
}
