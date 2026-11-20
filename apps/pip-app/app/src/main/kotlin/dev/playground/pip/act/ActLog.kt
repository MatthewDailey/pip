package dev.playground.pip.act

import android.util.Log
import dev.playground.pip.core.EventLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One structured `PipEvents` line per step of Pip stepping in, shaped like the contracts' `PipEvent`:
 * `{"event":"Acted","at":…,"kind":"MUTE_MIC","result":"Done",…}`. The scenario runner greps `"flow":"scam-call"`.
 */
object ActLog {
  fun record(event: String, vararg fields: Pair<String, Any?>) {
    val line = buildJsonObject {
      put("event", JsonPrimitive(event))
      put("at", JsonPrimitive(System.currentTimeMillis()))
      put("flow", JsonPrimitive("scam-call"))
      for ((k, v) in fields) when (v) {
        null -> {}
        is Number -> put(k, JsonPrimitive(v))
        is Boolean -> put(k, JsonPrimitive(v))
        else -> put(k, JsonPrimitive(v.toString()))
      }
    }
    Log.i(EventLog.TAG, line.toString())
  }
}
