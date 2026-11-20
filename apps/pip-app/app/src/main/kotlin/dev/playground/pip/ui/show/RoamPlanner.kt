package dev.playground.pip.ui.show

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The roam director's brain, free of Android so it can be unit tested: what is on the home screen ([RoamStage]),
 * and which move Pip makes next ([RoamPlanner.next]). The Rive side is packages/pip-rive/roam/build-roam.mjs; its
 * header is the contract (view-model numbers, triggers, where each move leaves his feet), and the constants in
 * [Roam] must match the ones it names.
 */

/**
 * A launcher icon as Pip sees it: the centre of its drawn circle and that circle's diameter plus [Roam.MARGIN] on
 * each side ([d]), so the mask covers the circle's anti-aliased rim; its label, a box from the circle's bottom to the
 * text's ([labelW] by [labelH], 0 for the dock's, which draw no text); and its launcher cell's width. Only an icon
 * with a label ([canHide]) has the room under it to hide him; the dock's are spots and anchors only.
 */
data class RoamIcon(
  val label: String, val cx: Float, val cy: Float, val d: Float, val labelW: Float, val labelH: Float,
  val canHide: Boolean = true, val cellW: Float = d,
) {
  /** Where his feet go when he stands on top of it: on the masked rim. */
  val top get() = Pt(cx, cy + Roam.PEEK_END * d)

  companion object {
    /**
     * The icon in a launcher cell (screen px), from Pixel Launcher's layout as measured on the emulator: every icon
     * is the same size, 0.741 of the widest (labelled) cell; in a labelled cell the circle starts 0.065 of the cell's
     * width below its top and the text ends 8 px above its bottom; a dock cell has no text and centres its circle.
     * The label's width is estimated from its length (16.5 px a character at the launcher's text size).
     */
    fun fromCell(label: String, left: Float, top: Float, right: Float, bottom: Float, widestCell: Float): RoamIcon {
      val w = right - left
      val drawn = minOf(0.741f * widestCell, w)
      val labelledCy = top + 0.065f * w + drawn / 2
      val labelled = bottom - (labelledCy + drawn / 2) > 40f
      val cy = if (labelled) labelledCy else top + (bottom - top) / 2
      val d = drawn + 2 * Roam.MARGIN
      val labelTop = cy + d / 2
      return if (labelled) {
        RoamIcon(label, (left + right) / 2, cy, d, minOf(w, 16.5f * label.length + 16f), bottom - 8f - labelTop, true, w)
      } else {
        RoamIcon(label, (left + right) / 2, cy, d, 0f, 0f, false, w)
      }
    }
  }
}

/** A wide element (a widget, the search bar): he can stand on its top edge and hang a web from its bottom edge. */
data class RoamBar(val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

data class Pt(val x: Float, val y: Float) {
  operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
  fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/** What the current home-screen page shows, in screen pixels. [statusBottom] is the status bar's lower edge. */
data class RoamStage(val width: Float, val height: Float, val statusBottom: Float, val icons: List<RoamIcon>, val bars: List<RoamBar>) {
  /** A short fingerprint of the layout: it changes when an icon moves or the page changes. */
  val signature: String get() = icons.joinToString(";") { "${it.label}@${it.cx.toInt()},${it.cy.toInt()}" } +
    "|" + bars.joinToString(";") { "${it.label}@${it.top.toInt()}" }

  fun describe() = "icons=[${icons.joinToString { "${it.label}(${it.cx.toInt()},${it.cy.toInt()} d${it.d.toInt()})" }}] " +
    "bars=[${bars.joinToString { "${it.label}(${it.left.toInt()},${it.top.toInt()}-${it.right.toInt()},${it.bottom.toInt()})" }}] " +
    "status=${statusBottom.toInt()}"
}

/** Somewhere Pip can stand. */
data class Spot(val at: Pt, val name: String, val icon: RoamIcon? = null)

enum class RoamKind { HOP, PEEK, LURK, SWING, LOOK }

/**
 * One move: the [trigger] to fire after writing [values] (view-model numbers, by name), where it leaves his feet,
 * and how long the clip runs. [hole] is the icon he hides behind, if any.
 */
data class RoamMove(
  val kind: RoamKind,
  val trigger: String,
  val values: Map<String, Float>,
  val end: Pt,
  val seconds: Float,
  val target: String,
  val hole: RoamIcon? = null,
)

object Roam {
  /** px from his hanging hand to his feet; build-roam.mjs GRIP. */
  const val GRIP = 207.9f
  /** Feet offset from an icon's centre when standing on it, in (masked) diameters; build-roam.mjs PEEK_END. */
  const val PEEK_END = -0.5f
  /** px the icon mask reaches past each drawn circle, over its anti-aliased rim and small layout errors. */
  const val MARGIN = 3f
  /** Icon slots in roam.riv's always-on icon mask; build-roam.mjs SLOTS. */
  const val SLOTS = 40
  /** Blend ranges for signed numbers; build-roam.mjs K.px and K.rad. */
  const val BLEND_PX = 4000f
  const val BLEND_RAD = 7f

  /** Clip lengths in seconds (frames / 60), for the timeout if `moveEnd` never arrives. */
  val SECONDS = mapOf("hop" to 45 / 60f, "peekLeft" to 264 / 60f, "peekRight" to 264 / 60f, "peekTop" to 264 / 60f,
    "lurk" to 496 / 60f, "swing" to 138 / 60f, "look" to 150 / 60f)

  /**
   * What to write for a signed blend number [v] so the move plays v. Rive mixes a blend's lower clip first, so
   * below zero the output is -k * (1 - t)^2 rather than linear; this undoes it (see `pair` in build-roam.mjs).
   */
  fun blendValue(v: Float, k: Float): Float = if (v >= 0f) v else -sqrt(-v * k)

  /** Every view-model number back at rest with his feet at [feet]: what the director writes after each move. */
  fun rest(feet: Pt, face: Float): Map<String, Float> = linkedMapOf(
    "anchorX" to feet.x, "anchorY" to feet.y, "armAngle" to 0f, "bodyAngle" to 0f, "armLength" to 0f, "webScale" to 0f,
    "grip" to 0f, "sweep" to 0f, "reel" to 0f, "dx" to 0f, "dy" to 0f, "lift" to 0f, "iconD" to 0f, "face" to face,
  ) + noHole()

  fun noHole(): Map<String, Float> = mapOf("holeX" to 540f, "holeY" to 1212f, "holeW" to 0f, "holeH" to 0f)

  /**
   * The box under [i] that hides the rest of Pip while he crouches behind it: from the icon's centre down 1.3
   * diameters (his hidden feet are at 1.22, build-roam.mjs HID), as wide as its cell.
   */
  fun hole(i: RoamIcon): Map<String, Float> =
    mapOf("holeX" to i.cx, "holeY" to i.cy + 0.65f * i.d, "holeW" to maxOf(i.cellW, i.d), "holeH" to 1.3f * i.d)

  /** Every icon on [stage] into the icon mask's slots, so Pip is always drawn behind icons and labels; spare slots 0. */
  fun iconSlots(stage: RoamStage): Map<String, Float> {
    val out = linkedMapOf<String, Float>()
    for (n in 0 until SLOTS) {
      val i = stage.icons.getOrNull(n)
      out["icon${n}X"] = i?.cx ?: 0f
      out["icon${n}Y"] = i?.cy ?: 0f
      out["icon${n}D"] = i?.d ?: 0f
      out["label${n}Y"] = i?.let { it.cy + it.d / 2 } ?: 0f
      out["label${n}W"] = i?.labelW ?: 0f
      out["label${n}H"] = i?.labelH ?: 0f
    }
    return out
  }
}

/**
 * Picks Pip's moves at random from what is on screen, weighted so it reads well: never the same kind of move twice
 * running, swings for long distances and hops for short ones, and now and then a long lurk behind an icon. All
 * randomness comes from [seed], so a run with the same seed and layout replays exactly.
 */
class RoamPlanner(val seed: Long) {
  private val rnd = Random(seed)
  private var last: RoamKind? = null
  var feet: Pt? = null
    private set
  var face = 1f
    private set

  /** Where he appears: a random spot on [stage] (or mid-screen when there is none). */
  fun start(stage: RoamStage): Pt {
    val s = spots(stage)
    val p = if (s.isEmpty()) Pt(stage.width / 2, stage.height * 0.6f) else s[rnd.nextInt(s.size)].at
    feet = p
    return p
  }

  /** Records that the last move finished with his feet at [end]. */
  fun landed(move: RoamMove) {
    feet = move.end
    face = move.values["face"] ?: face
  }

  /** The next move on [stage]. */
  fun next(stage: RoamStage): RoamMove {
    val from = feet ?: start(stage)
    val spots = spots(stage)
    // Standing on nothing (the page changed under him): get to something first.
    val stranded = spots.isNotEmpty() && spots.none { it.at.dist(from) < 40f }
    val weights = linkedMapOf(RoamKind.HOP to 3f, RoamKind.PEEK to 2.5f, RoamKind.SWING to 2.5f, RoamKind.LURK to 0.8f, RoamKind.LOOK to 1f)
    if (stranded) weights.keys.retainAll(setOf(RoamKind.HOP, RoamKind.SWING))
    else last?.let { weights.remove(it) }
    while (weights.isNotEmpty()) {
      val kind = pick(weights)
      val move = when (kind) {
        RoamKind.HOP -> hop(stage, from, spots, stranded)
        RoamKind.PEEK -> peek(stage, from)
        RoamKind.LURK -> lurk(stage, from)
        RoamKind.SWING -> swing(stage, from, spots)
        RoamKind.LOOK -> look(from)
      }
      if (move != null) {
        last = kind
        return move
      }
      weights.remove(kind)
    }
    last = RoamKind.LOOK
    return look(from)
  }

  private fun <T> pick(weights: Map<T, Float>): T {
    var x = rnd.nextFloat() * weights.values.sum()
    for ((k, w) in weights) { x -= w; if (x <= 0f) return k }
    return weights.keys.last()
  }

  /** Icon tops and points along each bar's top edge. */
  fun spots(stage: RoamStage): List<Spot> =
    stage.icons.map { Spot(it.top, "icon:${it.label}", it) } +
      stage.bars.flatMap { b ->
        val n = max(1, ((b.right - b.left) / 300f).toInt())
        (0 until n).map { i -> Spot(Pt(b.left + (b.right - b.left) * (i + 0.5f) / n, b.top), "bar:${b.label}#$i") }
      }

  private fun hop(stage: RoamStage, from: Pt, spots: List<Spot>, stranded: Boolean): RoamMove? {
    // A short hop, or a drop to anything below: falls read fine at any height, climbs need a web.
    val near = spots.filter { s -> s.at.dist(from) in 100f..520f || (s.at.y - from.y > 200f && abs(s.at.x - from.x) < 600f) }
    val target = when {
      near.isNotEmpty() -> near[rnd.nextInt(near.size)]
      stranded -> spots.minByOrNull { it.at.dist(from) } ?: return null
      else -> return null
    }
    val d = target.at - from
    val face = if (abs(d.x) < 20f) face else sign(d.x)
    val lift = (60f + 0.3f * hypot(d.x, d.y) + max(0f, -d.y) * 0.4f).coerceIn(60f, 320f)
    return RoamMove(RoamKind.HOP, "hop", Roam.rest(from, face) + mapOf(
      "dx" to Roam.blendValue(d.x, Roam.BLEND_PX), "dy" to Roam.blendValue(d.y, Roam.BLEND_PX), "lift" to lift,
    ), target.at, Roam.SECONDS.getValue("hop"), target.name)
  }

  private fun hideIn(kind: RoamKind, trigger: String, from: Pt, icon: RoamIcon, face: Float): RoamMove {
    val d = Pt(icon.cx, icon.cy) - from
    val lift = (70f + 0.25f * hypot(d.x, d.y)).coerceIn(70f, 260f)
    return RoamMove(kind, trigger, Roam.rest(from, face) + Roam.hole(icon) + mapOf(
      "dx" to Roam.blendValue(d.x, Roam.BLEND_PX), "dy" to Roam.blendValue(d.y, Roam.BLEND_PX), "lift" to lift, "iconD" to icon.d,
    ), icon.top, Roam.SECONDS.getValue(trigger), "icon:${icon.label}", icon)
  }

  private fun peek(stage: RoamStage, from: Pt): RoamMove? {
    val near = stage.icons.filter { it.canHide && it.top.dist(from) < 650f }
    if (near.isEmpty()) return null
    val icon = near[rnd.nextInt(near.size)]
    return when (rnd.nextInt(3)) {
      0 -> hideIn(RoamKind.PEEK, "peekLeft", from, icon, -1f)
      1 -> hideIn(RoamKind.PEEK, "peekRight", from, icon, 1f)
      else -> hideIn(RoamKind.PEEK, "peekTop", from, icon, if (rnd.nextBoolean()) 1f else -1f)
    }
  }

  /** A long idle peek behind the icon he stands on. */
  private fun lurk(stage: RoamStage, from: Pt): RoamMove? {
    val icon = stage.icons.firstOrNull { it.canHide && it.top.dist(from) < 40f } ?: return null
    return hideIn(RoamKind.LURK, "lurk", from, icon, 1f)
  }

  private fun look(from: Pt): RoamMove {
    val face = if (rnd.nextBoolean()) 1f else -1f
    return RoamMove(RoamKind.LOOK, "look", Roam.rest(from, face), from, Roam.SECONDS.getValue("look"), "here")
  }

  /** A web anchor: a point, or the best point on a horizontal segment from [x0] to [x1] at [y]. */
  private class Anchor(val name: String, val y: Float, val x0: Float, val x1: Float)

  private fun anchors(stage: RoamStage): List<Anchor> =
    listOf(Anchor("statusBar", stage.statusBottom, 40f, stage.width - 40f)) +
      stage.bars.map { Anchor("under:${it.label}", it.bottom, it.left + 30f, it.right - 30f) } +
      stage.icons.map { Anchor("icon:${it.label}", it.cy, it.cx, it.cx) }

  private fun swing(stage: RoamStage, from: Pt, spots: List<Spot>): RoamMove? {
    val h0 = Pt(from.x, from.y - Roam.GRIP)
    data class Plan(val spot: Spot, val a: Pt, val name: String, val score: Float)
    val plans = mutableListOf<Plan>()
    for (spot in spots) {
      val span = spot.at.dist(from)
      if (span < 380f) continue
      val h1 = Pt(spot.at.x, spot.at.y - Roam.GRIP)
      for (an in anchors(stage)) {
        if (an.y > min(h0.y, h1.y) - 250f) continue
        // The point on the segment as nearly equidistant from both hands as it can be, so the web barely reels.
        val x = if (abs(h1.x - h0.x) < 1f) (h0.x + h1.x) / 2 else
          ((h1.x * h1.x - h0.x * h0.x) + (an.y - h1.y) * (an.y - h1.y) - (an.y - h0.y) * (an.y - h0.y)) / (2 * (h1.x - h0.x))
        val a = Pt(x.coerceIn(an.x0, an.x1), an.y)
        val l0 = a.dist(h0); val l1 = a.dist(h1)
        if (l0 !in 250f..2100f) continue
        if (abs(l1 - l0) > 0.35f * l0) continue
        // Through the bottom of the arc he must stay on screen.
        val between = a.x in min(h0.x, h1.x)..max(h0.x, h1.x)
        if (between && a.y + max(l0, l1) + Roam.GRIP > stage.height - 60f) continue
        val sweep = abs(angle(h1 - a) - angle(h0 - a))
        if (sweep < 0.35f || sweep > 2.4f) continue
        plans += Plan(spot, a, an.name, abs(l1 - l0) / l0 + (if (between) 0f else 0.6f) + rnd.nextFloat() * 0.25f)
      }
    }
    if (plans.isEmpty()) return null
    // Prefer long swings: draw among the candidates weighted by span, then the best anchor for that target.
    val bySpot = plans.groupBy { it.spot }
    val spot = pick(bySpot.keys.associateWith { it.at.dist(from) })
    val plan = bySpot.getValue(spot).minBy { it.score }
    val a = plan.a
    val h1 = Pt(spot.at.x, spot.at.y - Roam.GRIP)
    val v0 = h0 - a; val v1 = h1 - a
    val l0 = hypot(v0.x, v0.y); val l1 = hypot(v1.x, v1.y)
    val t0 = angle(v0)
    var sweep = angle(v1) - t0
    if (sweep > Math.PI) sweep -= (2 * Math.PI).toFloat()
    if (sweep < -Math.PI) sweep += (2 * Math.PI).toFloat()
    val face = if (spot.at.x >= from.x) 1f else -1f
    return RoamMove(RoamKind.SWING, "swing", Roam.rest(from, face) + mapOf(
      "anchorX" to a.x, "anchorY" to a.y, "armAngle" to t0, "bodyAngle" to -t0, "armLength" to l0, "webScale" to l0 / 1000f,
      "grip" to Roam.GRIP, "sweep" to Roam.blendValue(sweep, Roam.BLEND_RAD), "reel" to Roam.blendValue(l1 - l0, Roam.BLEND_PX),
      "lift" to 40f,
    ), spot.at, Roam.SECONDS.getValue("swing"), "${spot.name} web=${plan.name}@(${a.x.toInt()},${a.y.toInt()})")
  }

  /** Rive's rotation for an arm pointing along [v] from its pivot: 0 points down, positive turns clockwise. */
  private fun angle(v: Pt) = atan2(-v.x, v.y)
}
