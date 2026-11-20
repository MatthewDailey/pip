package dev.playground.pip.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug control of Pip's voice, guarded by DUMP in the manifest so only the shell can send it. Prefer
 * `turbo run pip:voice -- list|set <name>|reset|render [line...]`, which wraps:
 * ```
 * adb shell am broadcast -n dev.playground.pip/.voice.PipVoiceReceiver --es voice list
 * adb shell am broadcast -n dev.playground.pip/.voice.PipVoiceReceiver --es voice set --es name en-us-x-iom-local
 * adb shell am broadcast -n dev.playground.pip/.voice.PipVoiceReceiver --es voice reset
 * adb shell am broadcast -n dev.playground.pip/.voice.PipVoiceReceiver --es voice render --es lines "'Hi! I'm Pip.|Tap me anytime.'"
 * ```
 * Logs under [PipVoice.TAG]: `VOICE <name> ...` for each voice, `PICK <purpose> ...`, `RENDERED <file>`, `END`.
 * `render` writes `<slug>.wav` files to the app's external files dir, `voice/`, for demo soundtracks.
 */
class PipVoiceReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext
    when (intent.getStringExtra("voice")) {
      "set" -> PipVoice.setOverride(app, intent.getStringExtra("name"))
      "reset" -> PipVoice.setOverride(app, null)
      "list", "render", null -> Unit
      else -> { Log.w(TAG, "unknown voice command ${intent.getStringExtra("voice")}"); return }
    }
    val render = intent.getStringExtra("voice") == "render"
    val lines = intent.getStringExtra("lines")?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val pending = goAsync()
    lateinit var voice: PipVoice
    voice = PipVoice(app, VoicePurpose.GREETING) { ok ->
      Thread {
        try {
          if (ok) report(app, voice)
          if (ok && render) render(app, voice, lines)
        } finally {
          Log.i(TAG, "END")
          voice.shutdown()
          pending.finish()
        }
      }.start()
    }
  }

  private fun report(app: Context, voice: PipVoice) {
    val voices = voice.voices().sortedBy { it.name }
    val english = voices.filter { it.locale.language == "en" }
    Log.i(TAG, "ENGINE ${voices.size} voices, ${english.size} English; override=${PipVoice.override(app) ?: "none"}")
    english.forEach { v ->
      Log.i(TAG, "VOICE ${v.name} locale=${v.locale.toLanguageTag()} quality=${v.quality} latency=${v.latency} " +
        "network=${v.network} installed=${v.installed}")
    }
    VoicePurpose.entries.forEach { p ->
      val pick = PipVoicePicker.pick(voices, p, PipVoice.override(app))
      Log.i(TAG, "PICK $p ${pick.voice?.name ?: "engine default"} pitch=${pick.tuning.pitch} rate=${pick.tuning.rate} (${pick.why})")
    }
  }

  private fun render(app: Context, voice: PipVoice, lines: List<String>) {
    val dir = File(app.getExternalFilesDir(null), "voice").apply { mkdirs() }
    lines.forEachIndexed { i, line ->
      val done = CountDownLatch(1)
      voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String) {}
        override fun onDone(id: String) = done.countDown()
        @Deprecated("Deprecated in Java") override fun onError(id: String) = done.countDown()
        override fun onError(id: String, code: Int) = done.countDown()
      })
      val file = File(dir, PipVoicePicker.slug(line) + ".wav")
      val ok = voice.synthesizeToFile(line, file, "render-$i") && done.await(RENDER_TIMEOUT_S, TimeUnit.SECONDS)
      Log.i(TAG, if (ok) "RENDERED ${file.name} ${file.length()}" else "RENDER FAILED $line")
    }
  }

  companion object {
    const val TAG = PipVoice.TAG
    private const val RENDER_TIMEOUT_S = 10L
  }
}
