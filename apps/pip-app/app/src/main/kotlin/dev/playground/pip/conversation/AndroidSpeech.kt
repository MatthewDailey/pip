package dev.playground.pip.conversation

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.playground.pip.voice.PipVoice
import dev.playground.pip.voice.VoicePurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "PipTalk"

/**
 * The phone's own speech recognizer, on-device when the phone has the model installed ([SpeechModel], the same
 * recognizer `CallAudioCapture` uses during calls), otherwise the system's default one. It never asks for the model
 * download itself; that is a setup step. Main thread only, like [SpeechRecognizer] itself.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
  private var recognizer: SpeechRecognizer? = null

  override val onDevice: Boolean get() = SpeechModel.installed && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  override fun isAvailable(): Boolean = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

  override fun transcribe(locale: String): Flow<Transcript> = callbackFlow {
    val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
    recognizer = r
    var seq = 0
    r.setRecognitionListener(object : RecognitionListener {
      override fun onPartialResults(partial: Bundle?) {
        best(partial)?.let { trySend(Transcript(it, final = false, sequence = seq++)) }
      }
      override fun onResults(results: Bundle?) {
        best(results)?.let { trySend(Transcript(it, final = true, sequence = seq++)) }
        close()
      }
      override fun onError(error: Int) {
        // No match and silence are normal ends of an utterance; anything else is logged and ends it too.
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Log.w(TAG, "recognizer error $error")
        close()
      }
      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    r.startListening(intent(locale))
    awaitClose {
      runCatching { r.cancel() }
      r.destroy()
      if (recognizer === r) recognizer = null
    }
  }

  override fun finish() { recognizer?.stopListening() }

  private fun intent(locale: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
  }

  private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/** The phone's text-to-speech in Pip's voice ([PipVoice]). With no engine installed, [speak] returns false at once and Pip stays silent. */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
  private val ready = CompletableDeferred<Boolean>()
  private val pending = mutableMapOf<String, (Boolean) -> Unit>()
  private var next = 0

  private val tts = PipVoice(context.applicationContext, VoicePurpose.CHAT) { ok ->
    if (!ok) Log.w(TAG, "no text-to-speech engine; Pip's replies are text only")
    ready.complete(ok)
  }.apply {
    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String) {}
      override fun onDone(utteranceId: String) { finish(utteranceId, true) }
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String) { finish(utteranceId, false) }
      override fun onStop(utteranceId: String, interrupted: Boolean) { finish(utteranceId, false) }
    })
  }

  override suspend fun speak(text: String, usage: AudioUsage): Boolean {
    if (!ready.await()) return false
    tts.setAudioAttributes(AudioAttributes.Builder().setUsage(usage.toAndroid()).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    val id = "pip-${next++}"
    return suspendCancellableCoroutine { cont ->
      synchronized(pending) { pending[id] = { ok -> if (cont.isActive) cont.resume(ok) } }
      cont.invokeOnCancellation { tts.stop() }
      if (!tts.speak(text, TextToSpeech.QUEUE_FLUSH, id)) finish(id, false)
    }
  }

  override fun stop() { tts.stop() }

  fun shutdown() = tts.shutdown()

  private fun finish(id: String, ok: Boolean) {
    synchronized(pending) { pending.remove(id) }?.invoke(ok)
  }

  private fun AudioUsage.toAndroid() = when (this) {
    AudioUsage.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
    AudioUsage.ALARM -> AudioAttributes.USAGE_ALARM
    AudioUsage.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
    AudioUsage.VOICE_COMMUNICATION -> AudioAttributes.USAGE_VOICE_COMMUNICATION
  }
}
