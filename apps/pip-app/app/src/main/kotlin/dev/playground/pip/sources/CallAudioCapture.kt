package dev.playground.pip.sources

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.playground.pip.conversation.SpeechModel
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import dev.playground.pip.live.PipCallVerdicts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Records the microphone during a call and streams the same PCM into the on-device speech recognizer.
 *
 * Android only lets an app record the mic while a call is active when it runs inside an enabled accessibility
 * service, so this must be driven from [GuardAccessibilityService]. It hears the caller only when the call is
 * on speaker. The recognizer reads from a pipe we write to (EXTRA_AUDIO_SOURCE, API 33+), because a recognizer
 * opening the mic itself runs in another process that doesn't share the accessibility exemption.
 */
class CallAudioCapture(private val context: Context) {
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var running = false
  /** Loudest RMS of the last half second of capture, so Pip can check the mic is live again after an unmute. */
  @Volatile var recentPeak = 0
    private set
  @Volatile private var sink: ParcelFileDescriptor.AutoCloseOutputStream? = null
  private var recognizer: SpeechRecognizer? = null
  private var thread: Thread? = null
  private var wavFile: File? = null
  private var callTag = ""
  private var startedAt = 0L

  @SuppressLint("MissingPermission")
  fun start(tag: String) {
    if (running) return
    callTag = tag
    startedAt = System.currentTimeMillis()
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val record = try {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
    } catch (e: SecurityException) {
      Guard.note(Source.CALL_AUDIO, "Mic permission missing", e.message ?: ""); return
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      Guard.note(Source.CALL_AUDIO, "AudioRecord failed to initialise"); record.release(); return
    }
    running = true
    failures = 0
    modelMissing = false
    val dir = File(context.filesDir, "calls").apply { mkdirs() }
    val wav = File(dir, "$tag.wav").also { wavFile = it }
    main.post { startRecognizer() }
    thread = Thread({ loop(record, wav) }, "call-audio").apply { start() }
    Guard.note(Source.CALL_AUDIO, "Recording started", "source=VOICE_RECOGNITION file=${wav.name}")
  }

  fun stop() {
    if (!running) return
    running = false
    thread?.join(2000)
    thread = null
    main.post {
      runCatching { sink?.close() }
      sink = null
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun loop(record: AudioRecord, wav: File) {
    val buf = ShortArray(RATE / 10)
    val bytes = ByteArray(buf.size * 2)
    var total = 0L
    var peakSinceReport = 0.0
    var silentSamples = 0L
    var lastReport = System.currentTimeMillis()
    var windowPeak = 0.0
    var windowStart = lastReport
    FileOutputStream(wav).use { out ->
      out.write(ByteArray(44))
      record.startRecording()
      if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        Guard.note(Source.CALL_AUDIO, "Recording blocked by the system", "Android refused concurrent capture; is the accessibility service enabled?")
      }
      while (running) {
        val n = record.read(buf, 0, buf.size)
        if (n <= 0) continue
        var sum = 0.0
        for (i in 0 until n) {
          val s = buf[i].toInt()
          sum += (s * s).toDouble()
          bytes[2 * i] = (s and 0xff).toByte()
          bytes[2 * i + 1] = (s shr 8 and 0xff).toByte()
        }
        val rms = sqrt(sum / n)
        if (rms < 30) silentSamples += n
        peakSinceReport = maxOf(peakSinceReport, rms)
        windowPeak = maxOf(windowPeak, rms)
        if (System.currentTimeMillis() - windowStart >= 500) { recentPeak = windowPeak.toInt(); windowPeak = 0.0; windowStart = System.currentTimeMillis() }
        out.write(bytes, 0, n * 2)
        try { sink?.write(bytes, 0, n * 2) } catch (_: IOException) { /* recognizer session ended; next one picks up */ }
        total += n
        val now = System.currentTimeMillis()
        if (now - lastReport > 5000) {
          Guard.note(Source.CALL_AUDIO, "Audio level", "peak RMS ${peakSinceReport.toInt()} over 5s, ${total / RATE}s recorded")
          peakSinceReport = 0.0
          lastReport = now
        }
      }
      record.stop()
      record.release()
    }
    writeWavHeader(wav, total)
    val silentPct = if (total == 0L) 100 else (silentSamples * 100 / total)
    Guard.note(Source.CALL_AUDIO, "Recording saved", "${wav.name}: ${total / RATE}s, ${silentPct}% near-silent")
  }

  /**
   * Starts the on-device recognizer only once [SpeechModel] says the model is installed: a recognizer started without
   * it fails with ERROR_LANGUAGE_UNAVAILABLE, and fetching the model may put the system's download prompt over the
   * call. Without the model the call is still recorded and transcription is skipped.
   */
  private fun startRecognizer() {
    if (!running) return
    if (!SpeechModel.installed) {
      SpeechModel.check(context) { state ->
        if (!running) return@check
        if (state == SpeechModel.State.INSTALLED) startRecognizer() else skipTranscription(state)
      }
      return
    }
    val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it; it.setRecognitionListener(listener) }
    val (read, write) = ParcelFileDescriptor.createPipe()
    sink = ParcelFileDescriptor.AutoCloseOutputStream(write)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
      putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
      putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
      putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
      putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
    }
    r.startListening(intent)
  }

  private var failures = 0
  private var modelMissing = false

  /** A recognizer session ends after each utterance or error; close its pipe and open a fresh one, backing off on repeated errors. */
  private fun restart(failed: Boolean = false) {
    runCatching { sink?.close() }
    sink = null
    failures = if (failed) failures + 1 else 0
    val delay = if (failures == 0) 200L else minOf(30_000L, 1000L shl minOf(failures, 5))
    if (running) main.postDelayed({ startRecognizer() }, delay)
  }

  private fun skipTranscription(state: SpeechModel.State) {
    if (modelMissing) return
    modelMissing = true
    Guard.note(Source.TRANSCRIPT, "Speech model not installed", "state=$state; audio is still recorded, transcription is skipped")
  }

  private fun elapsedSeconds(): Double = if (startedAt == 0L) 0.0 else (System.currentTimeMillis() - startedAt) / 1000.0

  private fun handleText(results: Bundle?, final: Boolean) {
    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return
    // Local keyword rules stay the offline fallback; pip-call → Pip4 is the live verdict.
    if (final) Guard.reportCallLine("call:$callTag", "Call speech", text)
    PipCallVerdicts.send("call:$callTag", speaker = "user", text = text, t = elapsedSeconds(), final = final)
  }

  private val listener = object : RecognitionListener {
    override fun onResults(results: Bundle?) { handleText(results, final = true); restart() }
    override fun onSegmentResults(segmentResults: Bundle) = handleText(segmentResults, final = true)
    override fun onEndOfSegmentedSession() = restart()
    override fun onError(error: Int) {
      val benign = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
      if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
        // The model went missing after the check: stop for this call rather than retry into the same error.
        runCatching { sink?.close() }
        sink = null
        return skipTranscription(SpeechModel.State.DOWNLOADABLE)
      }
      if (!benign && failures == 0) Guard.note(Source.TRANSCRIPT, "Recognizer error $error")
      restart(failed = !benign)
    }
    override fun onPartialResults(partialResults: Bundle?) { handleText(partialResults, final = false) }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }

  private fun writeWavHeader(f: File, samples: Long) {
    val dataLen = samples * 2
    RandomAccessFile(f, "rw").use { raf ->
      fun le32(v: Long) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())
      fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
      raf.seek(0)
      raf.write("RIFF".toByteArray()); raf.write(le32(36 + dataLen)); raf.write("WAVE".toByteArray())
      raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1))
      raf.write(le32(RATE.toLong())); raf.write(le32(RATE * 2L)); raf.write(le16(2)); raf.write(le16(16))
      raf.write("data".toByteArray()); raf.write(le32(dataLen))
    }
  }

  companion object { const val RATE = 16_000 }
}
