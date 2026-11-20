package dev.playground.pip.ui.show

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.PlayableInstance
import app.rive.runtime.kotlin.core.RendererType
import app.rive.runtime.kotlin.core.RiveEvent
import app.rive.runtime.kotlin.core.ViewModel.PropertyDataType
import java.io.File

/**
 * Plays a compiled showreel `.riv` full screen in the accessibility overlay, as an alternative engine to [ShowRunner].
 *
 * It loads `/sdcard/Download/<riv>.riv` when that is readable (push a new build there to iterate without
 * reinstalling), else the bundled `assets/showreel/<riv>.riv`. The first artboard plays through its `Main` state
 * machine with `Fit.FILL`: the overlay measures 1080x2424, so artboard units are screen pixels.
 *
 * Runtime layout reaches the file through its default view model. For each launcher icon on the home screen it sets
 * (the label in camel case, so "YouTube" is `youTubeX`; matched ignoring case) `<icon>X`, `<icon>Y` and `<icon>D`, the
 * centre and diameter of the icon circle that [StageInfo.icon] uses, and `<icon>LabelW`, `<icon>LabelH`, the label
 * under it: the cell's width, from the circle's centre to 40 px below the cell. The hide masks size from these.
 * [overrides] then sets any number by name. Names the file lacks are skipped.
 *
 * It logs the same lines as the Kotlin engine, under the same tag, so `tools/pip-show/record.sh` works unchanged:
 * `PIPSHOW start`, one `PIPCUE <name> <t>` per Rive event (t is animation seconds since playback began), and
 * `PIPSHOW end` on a `showEnd` event or when the first animation's duration has played.
 *
 * Events need the legacy [RiveAnimationView]: the Compose `app.rive.Rive` API in rive-android 11.16.3 has no event
 * callback.
 */
class RiveShowRunner(
  private val service: AccessibilityService,
  private val riv: String,
  private val overrides: Map<String, Float> = emptyMap(),
  private val durationOverride: Float? = null,
  private val renderer: RendererType = RendererType.Rive,
  /** Called with each Rive event's name, on Rive's render thread (the greeting speaks `say:` lines from it). */
  private val onEvent: (String) -> Unit = {},
  private val onDone: () -> Unit,
) : StagePlayer {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private var view: RiveAnimationView? = null
  private var owner: ViewOwner? = null

  override fun start(fromScene: String?) {
    if (view != null) return
    if (fromScene != null) Log.w(TAG, "RIVE engine: --es from is not supported; playing $riv from the start")
    app.rive.runtime.kotlin.core.Rive.init(service.applicationContext)
    val (bytes, label) = load() ?: run { onDone(); return }

    val clock = Clock()
    val v = RiveAnimationView(RiveAnimationView.Builder(service).setRendererType(renderer))
    v.addEventListener(clock)
    v.registerListener(clock)
    v.setRiveBytes(bytes, stateMachineName = STATE_MACHINE, autoplay = true, autoBind = true, fit = Fit.FILL)

    val artboard = v.controller.activeArtboard
    val duration = durationOverride ?: runCatching { artboard?.firstAnimation?.effectiveDurationInSeconds }.getOrNull()
    if (duration == null || duration <= 0f) Log.w(TAG, "RIVE $label: no animation duration; ending only on showEnd")
    clock.duration = duration
    applyLayout(v, label)

    val o = ViewOwner().also { it.start() }
    v.setViewTreeLifecycleOwner(o)
    owner = o
    view = v
    wm.addView(v, overlayLayoutParams())
    Log.i(TAG, "RIVE playing $label (${bytes.size} bytes), duration=$duration, renderer=$renderer")
    // A settled state machine stops advancing, which would leave the duration check unreached.
    if (duration != null && duration > 0f) main.postDelayed({ if (view === v) finish("watchdog") }, ((duration + WATCHDOG_SLACK) * 1000).toLong())
  }

  override fun stop() {
    val v = view ?: return
    view = null
    main.removeCallbacksAndMessages(null)
    runCatching { v.stop() }
    runCatching { wm.removeView(v) }
    owner?.destroy()
    owner = null
    Log.i(TAG, "PIPSHOW end")
    onDone()
  }

  private fun finish(why: String) {
    if (view == null) return
    Log.i(TAG, "RIVE finished ($why)")
    stop()
  }

  private fun load(): Pair<ByteArray, String>? {
    val download = File("$DOWNLOAD_DIR/$riv.riv")
    runCatching { download.readBytes() }.onSuccess { return it to download.path }
    val asset = "showreel/$riv.riv"
    return runCatching { service.assets.open(asset).use { it.readBytes() } }
      .map { it to "assets/$asset" }
      .onFailure { Log.e(TAG, "RIVE no $riv.riv in $DOWNLOAD_DIR or assets/showreel", it) }
      .getOrNull()
  }

  /** Sets icon positions and [overrides] on the artboard's bound view-model instance. */
  private fun applyLayout(v: RiveAnimationView, label: String) {
    val file = v.controller.file
    val artboard = v.controller.activeArtboard
    val vmi = artboard?.viewModelInstance
    if (file == null || artboard == null || vmi == null) {
      Log.i(TAG, "RIVE $label has no view model; layout not applied")
      return
    }
    val numbers = runCatching { file.defaultViewModelForArtboard(artboard).properties }.getOrDefault(emptyList())
      .filter { it.type == PropertyDataType.NUMBER }.map { it.name }.toSet()
    val bounds = wm.currentWindowMetrics.bounds
    val stage = StageInfo(bounds.width().toFloat(), bounds.height().toFloat(), findLauncherIcons(service))
    val values = linkedMapOf<String, Float>()
    for (name in stage.icons.keys) {
      val spot = stage.icon(name)
      val key = camel(name)
      values["${key}X"] = spot.cx
      values["${key}Y"] = spot.cy
      values["${key}D"] = spot.radius * 2
      values["${key}LabelW"] = spot.cell.width()
      values["${key}LabelH"] = spot.cell.bottom + LABEL_BELOW_CELL - spot.cy
    }
    values.putAll(overrides)
    // Matched ignoring case: the converter may spell an icon "youTube" or "youtube".
    val byLower = numbers.associateBy { it.lowercase() }
    val applied = mutableListOf<String>()
    for ((key, value) in values) {
      val name = byLower[key.lowercase()] ?: continue
      runCatching { vmi.getNumberProperty(name).value = value }
        .onSuccess { applied += "$name=${"%.1f".format(value)}" }
        .onFailure { Log.e(TAG, "RIVE set $name failed", it) }
    }
    Log.i(TAG, "RIVE view model ${vmi.name}: set ${applied.joinToString()}; numbers=$numbers; icons=${stage.icons.keys}")
    val missing = overrides.keys.filter { it.lowercase() !in byLower }
    if (missing.isNotEmpty()) Log.w(TAG, "RIVE $label has no number properties $missing")
  }

  /**
   * Turns events into PIPCUE lines and ends the show. Both callbacks run on Rive's render thread. The controller
   * reports an advance's events at the start of the next advance, before [notifyAdvance], and an event's `delay` is
   * measured back from the end of the advance that fired it, so `time - delay` is its animation time.
   */
  private inner class Clock : RiveFileController.RiveEventListener, RiveFileController.Listener {
    @Volatile var duration: Float? = null
    private var time = 0.0
    private var started = false
    private var reachedEnd = false
    private var ended = false
    private val frames = mutableListOf<Float>()

    override fun notifyEvent(event: RiveEvent) {
      val t = (time - event.delay).coerceAtLeast(0.0)
      Log.i(TAG, "PIPCUE ${event.name} ${"%.3f".format(t)}")
      runCatching { onEvent(event.name) }.onFailure { Log.e(TAG, "RIVE event handler failed for ${event.name}", it) }
      if (event.name == END_EVENT) end("showEnd event")
    }

    override fun notifyAdvance(elapsed: Float) {
      if (!started) {
        started = true
        Log.i(TAG, "PIPSHOW start offset=0.0 duration=$duration engine=rive riv=$riv")
      }
      // The last frame's events arrive with the following advance, so end one advance after the duration.
      if (reachedEnd) end("duration ${"%.3f".format(time)}s")
      time += elapsed
      frames += elapsed
      val d = duration
      if (d != null && d > 0f && time >= d) reachedEnd = true
    }

    private fun end(why: String) {
      if (ended) return
      ended = true
      val sorted = frames.sorted()
      fun ms(q: Double) = sorted.getOrNull((q * (sorted.size - 1)).toInt())?.let { "%.0f".format(it * 1000) }
      Log.i(TAG, "RIVE frames ${frames.size} in ${"%.2f".format(time)}s: median ${ms(0.5)} ms, p95 ${ms(0.95)} ms, " +
        "max ${ms(1.0)} ms, over 50 ms ${frames.count { it > 0.05f }}")
      main.post { finish(why) }
    }

    override fun notifyPlay(animation: PlayableInstance) {}
    override fun notifyPause(animation: PlayableInstance) {}
    override fun notifyStop(animation: PlayableInstance) {}
    @Deprecated("Linear animations are deprecated.")
    override fun notifyLoop(animation: PlayableInstance) {}
    override fun notifyStateChanged(stateMachineName: String, stateName: String) {}
  }

  /** The overlay window has no Activity; the view releases its Rive resources when this owner is destroyed. */
  internal class ViewOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
  }

  companion object {
    const val TAG = ShowRunner.TAG
    const val STATE_MACHINE = "Main"
    const val END_EVENT = "showEnd"
    const val DOWNLOAD_DIR = "/sdcard/Download"
    private const val WATCHDOG_SLACK = 3f
    /** How far below its launcher cell an icon's hide mask reaches, as the Kotlin show hides Pip, in px. */
    private const val LABEL_BELOW_CELL = 40f

    /** "YouTube" -> "youTube", "Play Store" -> "playStore". */
    fun camel(label: String): String {
      val words = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
      if (words.isEmpty()) return ""
      return words.first().replaceFirstChar { it.lowercaseChar() } +
        words.drop(1).joinToString("") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
  }
}

/** A full-screen, untouchable accessibility overlay window that draws under the status bar and cutout. */
internal fun overlayLayoutParams() = WindowManager.LayoutParams(
  WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
  WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
  android.graphics.PixelFormat.TRANSLUCENT,
).apply {
  layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
  fitInsetsTypes = 0
}
