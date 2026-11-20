package dev.playground.pip.conversation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Whether the on-device speech model for the phone's language is installed.
 *
 * The on-device recognizer answers a missing model with ERROR_LANGUAGE_UNAVAILABLE, and asking it to fetch the model
 * ([SpeechRecognizer.triggerModelDownload]) may show the system's download prompt over whatever is on screen. Pip
 * only asks for the download from setup, where the person tapped "Yes" to it; during a call or a conversation he
 * checks [state] first and skips on-device transcription quietly when the model isn't there.
 */
object SpeechModel {
  const val TAG = "PipSpeech"

  enum class State { UNKNOWN, INSTALLED, PENDING, DOWNLOADABLE, UNSUPPORTED }

  @Volatile var state: State = State.UNKNOWN
    private set

  val installed: Boolean get() = state == State.INSTALLED

  private val main = Handler(Looper.getMainLooper())
  private val listeners = mutableListOf<(State) -> Unit>()
  private var checking: SpeechRecognizer? = null
  private var downloading: SpeechRecognizer? = null

  fun language(): String = Locale.getDefault().toLanguageTag()

  fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())

  /** Asks the on-device recognizer what it has for [language]; [done] runs on the main thread. Any thread. */
  fun check(context: Context, done: (State) -> Unit = {}) = main.post {
    listeners += done
    if (checking != null) return@post
    val ctx = context.applicationContext
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return@post settle(State.UNSUPPORTED)
    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { checking = it }
    // Some recognizers never answer; don't leave callers waiting.
    val timeout = Runnable { if (checking === r) settle(State.UNKNOWN) }
    main.postDelayed(timeout, 5_000)
    r.checkRecognitionSupport(intent(), ctx.mainExecutor, object : RecognitionSupportCallback {
      override fun onSupportResult(support: RecognitionSupport) {
        main.removeCallbacks(timeout)
        if (checking === r) settle(classify(support))
      }
      override fun onError(error: Int) {
        main.removeCallbacks(timeout)
        Log.w(TAG, "checkRecognitionSupport error $error")
        if (checking === r) settle(State.UNKNOWN)
      }
    })
  }

  private fun settle(s: State) {
    checking?.destroy()
    checking = null
    state = s
    Log.i(TAG, "model ${language()}: $s")
    val waiting = listeners.toList()
    listeners.clear()
    waiting.forEach { it(s) }
  }

  private fun classify(s: RecognitionSupport): State {
    fun List<String>.has() = any { it.equals(language(), ignoreCase = true) || it.substringBefore('-').equals(language().substringBefore('-'), ignoreCase = true) }
    return when {
      s.installedOnDeviceLanguages.has() -> State.INSTALLED
      s.pendingOnDeviceLanguages.has() -> State.PENDING
      s.supportedOnDeviceLanguages.has() -> State.DOWNLOADABLE
      else -> State.UNSUPPORTED
    }
  }

  /**
   * Asks the system to fetch the model. The recognizer may show its own download prompt first, so call this only
   * when the person asked for it (setup) or from adb on a dev emulator. [done] gets the state after the request.
   */
  fun download(context: Context, done: (State) -> Unit = {}) = main.post {
    val ctx = context.applicationContext
    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return@post done(State.UNSUPPORTED)
    downloading?.destroy()
    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { downloading = it }
    fun finish() {
      if (downloading === r) { r.destroy(); downloading = null }
      check(ctx, done)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      r.triggerModelDownload(intent(), ctx.mainExecutor, object : ModelDownloadListener {
        override fun onProgress(completedPercent: Int) { Log.i(TAG, "download $completedPercent%") }
        override fun onSuccess() { Log.i(TAG, "download done"); finish() }
        override fun onScheduled() { Log.i(TAG, "download scheduled"); finish() }
        override fun onError(error: Int) { Log.w(TAG, "download error $error"); finish() }
      })
    } else {
      r.triggerModelDownload(intent())
      main.postDelayed({ finish() }, 1_000)
    }
  }
}
