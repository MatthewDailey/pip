package dev.playground.pip.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

enum class Source { CALL_SCREEN, CALL_STATE, CALL_AUDIO, TRANSCRIPT, SMS, NOTIFICATION, SCREEN, MANUAL, SYSTEM, SERVER }

@Serializable
data class GuardEvent(
  val time: Long,
  val source: Source,
  val app: String? = null,
  val title: String,
  val detail: String = "",
  val score: Int = 0,
  val rules: List<String> = emptyList(),
)

/**
 * Every signal the app sees lands here: an in-memory feed for the UI, a JSON-lines file for later analysis
 * (`adb shell run-as dev.playground.pip cat files/events.jsonl`), and logcat under tag PipEvents.
 */
object EventLog {
  const val TAG = "PipEvents"
  private const val MAX_IN_MEMORY = 500
  private val json = Json { encodeDefaults = false }
  private val writer = Executors.newSingleThreadExecutor()
  private val _events = MutableStateFlow<List<GuardEvent>>(emptyList())
  val events: StateFlow<List<GuardEvent>> = _events
  private var file: File? = null

  fun init(context: Context) {
    if (file != null) return
    val f = File(context.filesDir, "events.jsonl")
    file = f
    if (f.exists()) {
      val restored = f.readLines().takeLast(MAX_IN_MEMORY).mapNotNull { runCatching { json.decodeFromString(GuardEvent.serializer(), it) }.getOrNull() }
      _events.value = restored.reversed()
    }
  }

  fun add(event: GuardEvent) {
    _events.update { (listOf(event) + it).take(MAX_IN_MEMORY) }
    val line = json.encodeToString(GuardEvent.serializer(), event)
    Log.i(TAG, line)
    val f = file ?: return
    writer.execute { f.appendText(line + "\n") }
  }

  fun clear() {
    _events.value = emptyList()
    val f = file ?: return
    writer.execute { f.writeText("") }
  }
}
