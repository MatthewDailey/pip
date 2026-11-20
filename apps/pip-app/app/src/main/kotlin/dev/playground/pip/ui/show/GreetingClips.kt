package dev.playground.pip.ui.show

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject

/**
 * Pre-rendered voice clips for the first-run greeting's lines, bundled in `assets/greeting-voice/` with a
 * `lines.json` that maps each line to its WAV. They are rendered once with a GPT-Live voice by
 * `turbo run voice:greeting` (tools/pip-voice/render_greeting.py), so the phone needs no key or network.
 *
 * [play] queues a line's clip so a long line delays the next rather than cutting it, like the text-to-speech queue it
 * stands in for; it returns false when the line has no clip, and the caller speaks it with [dev.playground.pip.voice.PipVoice].
 * Like speakerphone, the clips play on the phone's loudspeaker (even with headphones or Bluetooth connected) at a
 * high media volume: [loud] (called before the show starts, and by the first clip if it wasn't) raises the media
 * volume to 80% of its maximum (if lower) and [release] puts it back. Call from the main thread.
 */
class GreetingClips(context: Context) {
  private val assets = context.applicationContext.assets
  private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
  private var savedVolume: Int? = null
  private val clips: Map<String, String> = load()
  private val queue = ArrayDeque<String>()
  private var player: MediaPlayer? = null

  val voice: String? = runCatching { manifest()?.optString("voice") }.getOrNull()

  fun has(line: String) = line in clips

  fun play(line: String): Boolean {
    val file = clips[line] ?: return false
    queue.addLast(file)
    loud()
    if (player == null) next()
    return true
  }

  fun release() {
    queue.clear()
    player?.release()
    player = null
    savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
    savedVolume = null
  }

  fun loud() {
    if (savedVolume != null) return
    runCatching {
      val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
      // 80% of the maximum: loud, without the crackle a speaker gives at full volume.
      val loud = (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * LOUD).toInt()
      savedVolume = now
      if (now < loud) audio.setStreamVolume(AudioManager.STREAM_MUSIC, loud, 0)
      Log.i(TAG, "GREET media volume $now -> ${maxOf(now, loud)}")
    }.onFailure { Log.w(TAG, "GREET media volume failed: ${it.message}") }
  }

  private fun next() {
    val file = queue.removeFirstOrNull() ?: return
    val p = MediaPlayer()
    player = p
    try {
      assets.openFd("$DIR/$file").use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
      p.setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
      )
      speaker()?.let { p.setPreferredDevice(it) }
      p.setOnCompletionListener { done(it) }
      p.setOnErrorListener { mp, what, extra -> Log.w(TAG, "GREET clip $file failed ($what/$extra)"); done(mp); true }
      p.prepare()
      p.start()
      Log.i(TAG, "GREET clip start $file (preferred ${p.preferredDevice?.type}, routed ${p.routedDevice?.type})")
    } catch (e: Exception) {
      Log.w(TAG, "GREET clip $file failed: $e")
      done(p)
    }
  }

  private fun speaker(): AudioDeviceInfo? =
    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

  private fun done(mp: MediaPlayer) {
    mp.release()
    if (player === mp) player = null
    next()
  }

  private fun manifest(): JSONObject? =
    runCatching { assets.open("$DIR/lines.json").bufferedReader().use { JSONObject(it.readText()) } }.getOrNull()

  private fun load(): Map<String, String> {
    val lines = manifest()?.optJSONObject("lines") ?: return emptyMap()
    return lines.keys().asSequence().associateWith { lines.getString(it) }
  }

  companion object {
    const val TAG = ShowRunner.TAG
    private const val DIR = "greeting-voice"
    private const val LOUD = 0.8f
  }
}
