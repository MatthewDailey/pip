package dev.playground.pip.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * The one way Pip speaks: the phone's text-to-speech in Pip's voice ([PipVoicePicker]) with its pitch and rate.
 * Every speaker (the first-run greeting, the act card, chat replies) makes its own instance for its [purpose].
 *
 * With no engine, or none that starts, [speak] returns false and Pip stays silent; callers show text either way.
 * The voice override from `turbo run pip:voice -- set <name>` is read again before each line, so a long-lived
 * speaker follows it without a restart.
 */
class PipVoice(
  context: Context,
  private val purpose: VoicePurpose,
  private val onReady: (Boolean) -> Unit = {},
) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  @Volatile var ready = false
    private set
  @Volatile private var applied: String? = null
  private lateinit var tts: TextToSpeech

  init {
    tts = TextToSpeech(context.applicationContext) { status ->
      ready = status == TextToSpeech.SUCCESS
      if (ready) apply() else Log.w(TAG, "VOICE $purpose: no text-to-speech engine (status $status); Pip stays silent")
      onReady(ready)
    }
  }

  /** Every voice the engine offers, as the picker sees them. Empty until [ready]. */
  fun voices(): List<VoiceSpec> = runCatching { tts.voices.orEmpty().map(::spec) }.getOrDefault(emptyList())

  fun pick(): VoicePick = PipVoicePicker.pick(voices(), purpose, override(prefs))

  fun speak(text: String, queue: Int = TextToSpeech.QUEUE_FLUSH, id: String = "pip-${text.hashCode()}"): Boolean {
    if (!ready) return false
    if (applied != override(prefs)) apply()
    return tts.speak(text, queue, null, id) == TextToSpeech.SUCCESS
  }

  /** Renders [text] in Pip's voice to a WAV file; [UtteranceProgressListener.onDone] fires with [id] when it's written. */
  fun synthesizeToFile(text: String, file: File, id: String): Boolean {
    if (!ready) return false
    if (applied != override(prefs)) apply()
    return tts.synthesizeToFile(text, null, file, id) == TextToSpeech.SUCCESS
  }

  fun setAudioAttributes(attributes: AudioAttributes) { tts.setAudioAttributes(attributes) }

  fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) { tts.setOnUtteranceProgressListener(listener) }

  fun stop() { if (ready) tts.stop() }

  fun shutdown() {
    ready = false
    tts.shutdown()
  }

  private fun apply() {
    val override = override(prefs)
    applied = override
    val pick = pick()
    val voice = pick.voice?.let { want -> tts.voices.orEmpty().firstOrNull { it.name == want.name } }
    val set = voice?.let { tts.setVoice(it) == TextToSpeech.SUCCESS } ?: false
    if (!set) tts.setLanguage(Locale.US)
    tts.setPitch(pick.tuning.pitch)
    tts.setSpeechRate(pick.tuning.rate)
    Log.i(TAG, "VOICE $purpose: ${if (set) voice!!.name else "engine default (${tts.defaultEngine})"} " +
      "pitch=${pick.tuning.pitch} rate=${pick.tuning.rate} (${pick.why})")
  }

  companion object {
    const val TAG = "PipVoice"
    private const val PREFS = "pip-voice"
    private const val KEY_OVERRIDE = "override"

    fun override(context: Context): String? = override(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private fun override(prefs: android.content.SharedPreferences): String? = prefs.getString(KEY_OVERRIDE, null)

    fun setOverride(context: Context, name: String?) {
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .apply { if (name.isNullOrBlank()) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, name) }.commit()
    }

    fun spec(v: Voice) = VoiceSpec(
      name = v.name,
      locale = v.locale,
      quality = v.quality,
      latency = v.latency,
      network = v.isNetworkConnectionRequired,
      installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
    )
  }
}
