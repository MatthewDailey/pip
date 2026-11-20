package dev.playground.pip.ui.show

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.setViewTreeLifecycleOwner
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.RiveEvent
import app.rive.runtime.kotlin.core.ViewModelInstance
import java.io.File
import kotlin.concurrent.withLock

/**
 * Pip roams the home screen: plays `roam.riv` (packages/pip-rive/roam) full screen in the accessibility overlay and
 * directs it, one move at a time, from what is really on screen.
 *
 * The loop: read the page ([readStage]), let [RoamPlanner] pick a move, write its view-model numbers, fire its
 * trigger, and wait for the clip's `moveEnd` event (or its length plus a margin). Then rebase (Pip's feet into
 * anchorX/anchorY, every other number to rest), fire `settle` a frame later, idle a moment and go again. Scrolls and
 * page changes in the launcher mark the stage stale ([onScreenChanged]); it is re-read before every pick anyway.
 *
 * Logs, under the show tag: `ROAM start seed=<n>`, `ROAM stage ...` whenever the layout changes, one
 * `ROAM move #<i> <trigger> from=(x,y) to=(x,y) target=<what>` per move, and `PIPCUE <event> <t>` for its sound cues,
 * so `tools/pip-show/record.sh` can lay a soundtrack under a recording. Replay a run with the same seed and layout.
 * It roams until [stop], or for [seconds] when set (then logs `PIPSHOW end`, which is what record.sh waits for).
 */
class RoamDirector(
  private val service: AccessibilityService,
  private val seed: Long,
  private val seconds: Float? = null,
  private val onDone: () -> Unit,
) : StagePlayer {
  private val wm = service.getSystemService(WindowManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private val planner = RoamPlanner(seed)
  private var view: RiveAnimationView? = null
  private var owner: RiveShowRunner.ViewOwner? = null
  private var vmi: ViewModelInstance? = null
  private var stage: RoamStage? = null
  private var current: RoamMove? = null
  private var moves = 0
  private var startedAt = 0L

  override fun start(fromScene: String?) {
    if (view != null) return
    app.rive.runtime.kotlin.core.Rive.init(service.applicationContext)
    val (bytes, label) = load() ?: run { onDone(); return }
    val v = RiveAnimationView(RiveAnimationView.Builder(service))
    v.addEventListener(events)
    v.setRiveBytes(bytes, stateMachineName = RiveShowRunner.STATE_MACHINE, autoplay = true, autoBind = true, fit = Fit.FILL)
    vmi = v.controller.activeArtboard?.viewModelInstance
    if (vmi == null) {
      Log.e(TAG, "ROAM $label has no view model; stopping")
      onDone()
      return
    }
    val o = RiveShowRunner.ViewOwner().also { it.start() }
    v.setViewTreeLifecycleOwner(o)
    owner = o
    view = v
    wm.addView(v, overlayLayoutParams())
    startedAt = System.nanoTime()
    Log.i(TAG, "ROAM start seed=$seed riv=$label (${bytes.size} bytes)" + (seconds?.let { " for ${it}s" } ?: ""))
    Log.i(TAG, "PIPSHOW start offset=0.0 duration=0 engine=roam riv=roam")
    val s = refreshStage()
    val feet = planner.start(s)
    write(Roam.rest(feet, 1f), null)
    Log.i(TAG, "ROAM appear at=(${feet.x.toInt()},${feet.y.toInt()})")
    main.postDelayed(::nextMove, 600)
    seconds?.let { main.postDelayed({ stop() }, (it * 1000).toLong()) }
  }

  override fun stop() {
    val v = view ?: return
    view = null
    main.removeCallbacksAndMessages(null)
    runCatching { v.stop() }
    runCatching { wm.removeView(v) }
    owner?.destroy()
    owner = null
    vmi = null
    Log.i(TAG, "ROAM stop after $moves moves")
    Log.i(TAG, "PIPSHOW end")
    onDone()
  }

  /** A launcher scroll or page change: re-read now so the log shows when the layout changed. */
  fun onScreenChanged() {
    if (view == null) return
    main.removeCallbacks(rereadStage)
    main.postDelayed(rereadStage, 400)
  }

  private val rereadStage = Runnable { if (view != null) refreshStage() }

  private fun refreshStage(): RoamStage {
    val s = readStage()
    if (s.signature != stage?.signature) {
      Log.i(TAG, "ROAM stage ${s.describe()}")
      if (s.icons.size > Roam.SLOTS) Log.w(TAG, "ROAM ${s.icons.size} icons; only the first ${Roam.SLOTS} are drawn over Pip")
      // The icon mask follows the page at once, even mid-move, so he is never drawn over an icon that moved.
      write(Roam.iconSlots(s), null)
    }
    stage = s
    return s
  }

  private fun nextMove() {
    if (view == null) return
    val s = refreshStage()
    val from = planner.feet
    val move = planner.next(s)
    current = move
    moves++
    Log.i(TAG, "ROAM move #$moves ${move.trigger} from=(${from?.x?.toInt()},${from?.y?.toInt()}) " +
      "to=(${move.end.x.toInt()},${move.end.y.toInt()}) target=${move.target}" +
      (move.hole?.let { " hole=(${it.cx.toInt()},${it.cy.toInt()} d${it.d.toInt()})" } ?: ""))
    write(move.values, move.trigger)
    main.postDelayed(timeout, ((move.seconds + 1.5f) * 1000).toLong())
  }

  private val timeout = Runnable { current?.let { Log.w(TAG, "ROAM no moveEnd for ${it.trigger}; going on"); finished(it) } }

  private fun finished(move: RoamMove) {
    if (view == null || current !== move) return
    current = null
    main.removeCallbacks(timeout)
    planner.landed(move)
    write(Roam.rest(move.end, planner.face), null)
    // Settle once the rebase has been drawn: every held value is zero by then, so the switch to rest is invisible.
    main.postDelayed({ if (view != null) write(emptyMap(), "settle") }, 50)
    main.postDelayed(::nextMove, 250L + (Math.random() * 900).toLong())
  }

  /** Writes [values] and fires [trigger] under the file lock, so the next advance sees them all at once. */
  private fun write(values: Map<String, Float>, trigger: String?) {
    val v = view ?: return
    val model = vmi ?: return
    val lock = v.controller.file?.lock
    val apply = {
      for ((name, value) in values) runCatching { model.getNumberProperty(name).value = value }
        .onFailure { Log.e(TAG, "ROAM set $name failed", it) }
      if (trigger != null) runCatching { model.getTriggerProperty(trigger).trigger() }
        .onFailure { Log.e(TAG, "ROAM fire $trigger failed", it) }
    }
    if (lock != null) lock.withLock { apply() } else apply()
  }

  private val events = object : RiveFileController.RiveEventListener {
    override fun notifyEvent(event: RiveEvent) {
      val t = (System.nanoTime() - startedAt) / 1e9
      Log.i(TAG, "PIPCUE ${event.name} ${"%.3f".format(t)}")
      if (event.name == MOVE_END) main.post { current?.let(::finished) }
    }
  }

  private fun load(): Pair<ByteArray, String>? {
    val download = File("${RiveShowRunner.DOWNLOAD_DIR}/roam.riv")
    runCatching { download.readBytes() }.onSuccess { return it to download.path }
    return runCatching { service.assets.open(ASSET).use { it.readBytes() } }
      .map { it to "assets/$ASSET" }
      .onFailure { Log.e(TAG, "ROAM no roam.riv in ${RiveShowRunner.DOWNLOAD_DIR} or assets/$ASSET", it) }
      .getOrNull()
  }

  /**
   * The home-screen page as the accessibility tree reports it. Labelled nodes 100-400 px square are launcher icon
   * cells. Nodes at least 400 px wide and under 900 px tall are bars (widgets, the search
   * bar); icons inside a bar (its buttons) and bars inside a bar are dropped.
   */
  private fun readStage(): RoamStage {
    val bounds = wm.currentWindowMetrics.bounds
    val status = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top.toFloat()
    data class Found(val label: String, val r: Rect, val hasText: Boolean)
    val found = mutableListOf<Found>()
    fun walk(n: AccessibilityNodeInfo?, depth: Int) {
      if (n == null || depth > 30) return
      val text = n.text?.toString()
      val label = text ?: n.contentDescription?.toString()
      if (!label.isNullOrBlank() && label.length < 40 && n.isVisibleToUser) {
        val r = Rect(); n.getBoundsInScreen(r)
        found += Found(label, r, text != null)
      }
      for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
    }
    runCatching { walk(service.rootInActiveWindow, 0) }
    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "ROAM nodes ${found.joinToString { "${it.label}${if (it.hasText) "" else "*"}${it.r.toShortString()}" }}")
    val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
    val wide = found.filter { it.r.width() >= 400 && it.r.height() in 80..900 }
    val bars = wide.filter { b -> wide.none { o -> o !== b && o.r.contains(b.r) && o.r != b.r } }
    val cells = found.filter { f -> f.r.width() in 100..400 && f.r.height() in 100..400 && bars.none { it.r.contains(f.r) } }
    val widest = (cells.maxOfOrNull { it.r.width() } ?: 0).toFloat()
    val icons = cells.map { f -> RoamIcon.fromCell(f.label, f.r.left.toFloat(), f.r.top.toFloat(), f.r.right.toFloat(), f.r.bottom.toFloat(), widest) }
    return RoamStage(w, h, status, icons, bars.map { RoamBar(it.label, it.r.left.toFloat(), it.r.top.toFloat(), it.r.right.toFloat(), it.r.bottom.toFloat()) })
  }

  companion object {
    const val TAG = ShowRunner.TAG
    const val ASSET = "roam/roam.riv"
    const val MOVE_END = "moveEnd"
  }
}
