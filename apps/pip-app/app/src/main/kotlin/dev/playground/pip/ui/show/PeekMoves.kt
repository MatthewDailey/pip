package dev.playground.pip.ui.show

import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.KeyframesBuilder
import dev.playground.pip.ui.anim.StopScope
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Pip's two signature peeks, as reusable keyframe recipes: peeking in from a screen edge, and peeking out from
 * behind a home-screen icon. Each helper only declares keyframes (no per-frame code), so they stack and chain like
 * any other scene animation. Every helper returns when Pip spots the viewer and when he is gone again, so callers
 * can hang cues, bubbles or the next move off those moments.
 */

/** Which screen edge Pip peeks in from. TOP hangs him upside down from the top of the screen. */
enum class Edge { LEFT, RIGHT, TOP }

/** How Pip peeks from behind an icon: rising over its top, or leaning out past its left or right side. */
enum class IconPeekStyle { UP, SIDE_LEFT, SIDE_RIGHT }

/** Scene times (seconds from the scene start) of a peek's key moments. */
class PeekTimes(val spotted: Float, val gone: Float)

/** Seconds from the start of an [edgePeek] until Pip is gone again (or settled, with `exit = false`). */
fun edgePeekLength(creep: Float = 1f, hold: Float = 0.5f, beat: Float = 1f, exit: Boolean = true): Float {
  val spot = 1.58f * creep + 1.14f * beat
  return if (exit) spot + 0.42f + hold + 0.31f else spot + 0.6f
}

/** Seconds from the start of an [iconPeek] until Pip is hidden again (or settled, with `exit = false`). */
fun iconPeekLength(rise: Float = 1f, hold: Float = 0.3f, beat: Float = 1f, exit: Boolean = true): Float {
  val spot = 1.35f * rise + 0.38f + 1.44f * beat
  return if (exit) spot + 0.41f + hold else spot + 0.6f
}

// Eases picked by what the motion is (see the animation playbook, section 5).
private val sine = Easing.easeInOutSine
private val outSine = Easing.easeOutSine
private val dart = Easing.cubicBezier(0.2f, 0.9f, 0.3f, 1f)          // a crisp saccade
private val creepIn = Easing.cubicBezier(0.05f, 0.7f, 0.1f, 1f)      // emphasised decelerate: arriving
private val snapOut = Easing.cubicBezier(0.55f, 0f, 0.9f, 0.4f)      // ease-in snap: leaving fast
private val pop = Easing.cubicBezier(0.3f, 0.7f, 0.4f, 1.5f)         // snappy overshoot

/** Keyframe percent for [t] seconds into an animation lasting [d] seconds. */
private fun pc(t: Float, d: Float) = (t / d * 100f).coerceIn(0f, 100f)

/** One stop placed in seconds rather than percent. */
private fun KeyframesBuilder<PipState>.sec(t: Float, d: Float, ease: dev.playground.pip.ui.anim.Ease? = null, block: StopScope<PipState>.() -> Unit) =
  at(pc(t, d), ease, block)

/**
 * Converts a gaze direction on screen into Pip's own `look`, undoing his rotation and mirroring, so "look at the
 * middle of the screen" stays right however he is tilted. Returns (lookX, lookY) scaled to [mag].
 */
private fun gaze(dx: Float, dy: Float, rotation: Float, facing: Float, mag: Float = 1f): Pair<Float, Float> {
  val r = Math.toRadians(rotation.toDouble())
  val c = cos(r).toFloat(); val s = sin(r).toFloat()
  var bx = dx * c + dy * s
  val by = -dx * s + dy * c
  bx *= facing
  val n = hypot(bx, by).coerceAtLeast(1e-3f)
  return (bx / n * mag) to (by / n * mag)
}

/** Where a point of Pip's 540 x 660 box lands on screen for a given pose (used to place bubbles beside him). */
private fun boxToScreen(bx: Float, by: Float, x: Float, y: Float, size: Float, rotation: Float, facing: Float): Pair<Float, Float> {
  val k = size / 540f
  val px = (bx - 270f) * k * facing; val py = (by - 660f) * k
  val r = Math.toRadians(rotation.toDouble())
  val c = cos(r).toFloat(); val s = sin(r).toFloat()
  return (x + px * c - py * s) to (y + px * s + py * c)
}

/** A natural blink: snaps shut, holds a moment, opens more softly. [double] adds a second, slightly quicker one. */
fun SceneScope.peekBlink(at: Number, double: Boolean = false) {
  val t = at.toFloat()
  animate(t, 0.22, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.easeIn) { blink(0) }; at(30) { blink(1) }; at(42, Easing.easeOut) { blink(1) }; to { blink(0) }
  }
  if (double) animate(t + 0.29f, 0.18, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.easeIn) { blink(0) }; at(32) { blink(1) }; at(40, Easing.easeOut) { blink(1) }; to { blink(0) }
  }
}

/** A speech bubble pinned to a screen point, for when Pip is half off screen and a head-anchored one would be too. */
fun SceneScope.peekBubble(text: String, x: Float, y: Float, at: Number, duration: Number) {
  val t0 = at.toFloat(); val d = duration.toFloat()
  actor(ActorKind.BUBBLE, text, anchorToPip = false) { this.x = x; this.y = y; alpha = 0f }
    .animate(t0, d, ease = Easing.linear, fill = Fill.NONE) {
      at(0, pop) { scale(0.3); alpha(1); rotation(-6) }
      at(pc(0.22f, d), sine) { scale(1); rotation(0) }
      at(pc(d - 0.2f, d), Easing.easeIn) { alpha(1); scale(1) }
      to { alpha(0); scale(0.85) }
    }
}

/**
 * Pip peeks in from a screen edge.
 *
 * He leans in from off screen at a slant, so the hat leads: its point slides in first while the floppy tip trails and
 * then whips, then one eye clears the edge, a beat later the second. His eyes dart around the screen ahead of his
 * head (curious lids, a sly squint), land on the viewer, double-blink, widen with a little brow lift, and after a
 * held beat he ducks back out with a quick ease-in snap and a whoosh. The creep is slow and the retreat is fast.
 *
 * @param at scene time the peek starts (Pip is cut to an off-screen pose then, so whatever came before must be
 *   off screen or a cut must be acceptable).
 * @param side which edge. LEFT and RIGHT lean him in sideways; TOP hangs him upside down from the top edge, swinging.
 * @param along screen y of his feet for LEFT/RIGHT, or screen x for TOP.
 * @param depth extra px he comes in beyond the default two-eyes-showing pose (negative keeps him shyer).
 * @param creep stretches (>1) or quickens (<1) the creep in; vary it so repeated peeks never share a rhythm.
 * @param hold how long he stares at the viewer before ducking out.
 * @param beat tempo of the eye-dart phase (<1 is a quicker, more nervous scan).
 * @param exit false leaves him at the peek pose (eyes on the viewer) so the caller can take over.
 * @param facing for TOP only: which way his face turns while hanging.
 */
fun SceneScope.edgePeek(
  at: Number,
  side: Edge = Edge.LEFT,
  along: Float,
  size: Float = 300f,
  depth: Float = 0f,
  creep: Float = 1f,
  hold: Float = 0.5f,
  beat: Float = 1f,
  exit: Boolean = true,
  facing: Float = 1f,
  sounds: Boolean = true,
): PeekTimes {
  val t0 = at.toFloat()
  val k = size / 540f
  val top = side == Edge.TOP
  val face = when (side) { Edge.LEFT -> 1f; Edge.RIGHT -> -1f; Edge.TOP -> facing }

  // The pose in a frame local to the edge: u is how far his feet sit inside the edge (px, negative = outside),
  // theta is his lean towards the screen. LEFT/RIGHT lean ~50 degrees so the hat point leads; TOP hangs at 180.
  fun px(u: Float) = when (side) { Edge.LEFT -> u; Edge.RIGHT -> W - u; Edge.TOP -> along }
  fun py(u: Float) = when (side) { Edge.TOP -> u; else -> along }
  fun rot(theta: Float) = when (side) { Edge.LEFT -> theta; Edge.RIGHT -> -theta; Edge.TOP -> 180f + theta * facing }

  // Depths in box units (x k for px), measured from Pip's outline: hidden, hat point in, first eye in, both eyes in.
  val uHidden = (if (top) -720f else -600f) * k
  val uHat = (if (top) -540f else -400f) * k
  val uEye1 = (if (top) -215f else -200f) * k
  val uFinal = (if (top) -125f else -95f) * k + depth
  // Lean: starts slanted (hat leads), lifts as he arrives; TOP swings in like a pendulum from the other side.
  val th0 = if (top) -18f else 60f
  val th1 = if (top) 7f else 47f

  // Timeline, seconds from t0. Creep is slow and uneven; the exit is quick.
  val a1 = 0.42f * creep          // hat point in
  val a2 = a1 + 0.34f * creep     // a suspicious pause, the hat tip still swinging
  val a3 = a2 + 0.62f * creep     // first eye clears the edge
  val a4 = a3 + 0.2f * creep      // a beat
  val a5 = a4 + 0.36f * beat      // second eye in: full peek
  val spot = a5 + 0.78f * beat    // eyes find the viewer
  val s1 = spot + 0.42f + hold    // anticipation before ducking out
  val s2 = s1 + 0.11f             // exit starts
  val s3 = s2 + 0.2f              // gone
  val end = if (exit) s3 else spot + 0.6f

  val inX = if (side == Edge.LEFT) 1f else if (side == Edge.RIGHT) -1f else 0f
  val inY = if (top) 1f else 0f
  val r1 = rot(th1)
  // Gaze targets, in screen directions, converted to his tilted frame.
  val ahead = gaze(inX, inY - 0.25f, rot(th0 + (th1 - th0) * 0.5f), face, 0.9f)
  val scanA = if (top) gaze(-1f, 0.5f, r1, face, 1f) else gaze(inX, -0.75f, r1, face, 1f)
  val scanB = if (top) gaze(1f, 0.55f, r1, face, 1f) else gaze(inX, 0.6f, r1, face, 1f)
  val far = gaze(inX * 1f, inY * 1f, r1, face, 1f)

  // Cut to the hidden pose.
  animate(t0, 0.001, ease = Easing.linear) {
    to {
      pos(px(uHidden), py(uHidden)); size(size); facing(face); rotation(rot(th0)); squash(1, 1)
      look(ahead.first, ahead.second); lidTop(0.38); squint(0.2); eyeScale(1); brows(0, 0, false)
      mood(Mood.NEUTRAL); mouth(Mouth.SMILE); hideBehind(null); hatDroop(0); shadow(0)
    }
  }

  // Travel: in to the hat, a pause, a slow creep to one eye, a beat, then the second eye. Optional snap out.
  val dPos = end
  animate(t0, dPos, ease = Easing.linear) {
    sec(0f, dPos, creepIn) { pos(px(uHidden), py(uHidden)) }
    sec(a1, dPos, sine) { pos(px(uHat), py(uHat)) }
    sec(a2, dPos, sine) { pos(px(uHat - 6f * k), py(uHat - 6f * k)) }       // a tiny shy pull-back
    sec(a3, dPos, sine) { pos(px(uEye1), py(uEye1)) }
    sec(a4, dPos, outSine) { pos(px(uEye1 + 4f * k), py(uEye1 + 4f * k)) }
    sec(a5, dPos, sine) { pos(px(uFinal), py(uFinal)) }
    sec(spot, dPos, Easing.easeOut) { pos(px(uFinal), py(uFinal)) }
    sec(spot + 0.08f, dPos, sine) { pos(px(uFinal - 14f * k), py(uFinal - 14f * k)) } // startle: a flinch back
    sec(spot + 0.4f, dPos, sine) { pos(px(uFinal), py(uFinal)) }
    if (exit) {
      sec(s1, dPos, Easing.easeOut) { pos(px(uFinal), py(uFinal)) }
      sec(s2, dPos, snapOut) { pos(px(uFinal + 22f * k), py(uFinal + 22f * k)) } // anticipation: in a hair
      sec(s3, dPos) { pos(px(uHidden), py(uHidden)) }
    }
    to { if (exit) pos(px(uHidden), py(uHidden)) else pos(px(uFinal), py(uFinal)) }
  }

  // Lean: overlaps the travel, lifting as he settles, with a small overshoot.
  animate(t0, dPos, ease = Easing.linear) {
    sec(0f, dPos, sine) { rotation(rot(th0)) }
    sec(a2, dPos, sine) { rotation(rot(th0 + (th1 - th0) * 0.25f)) }
    sec(a5 + 0.05f * beat, dPos, sine) { rotation(rot(th1 + (th1 - th0) * 0.12f)) }
    sec(a5 + 0.4f * beat, dPos, sine) { rotation(rot(th1)) }
    sec(spot, dPos, Easing.easeOut) { rotation(rot(th1)) }
    sec(spot + 0.09f, dPos, sine) { rotation(rot(th1 - (th1 - th0) * 0.1f)) }
    sec(spot + 0.42f, dPos, sine) { rotation(rot(th1)) }
    if (exit) {
      sec(s1, dPos, sine) { rotation(rot(th1)) }
      sec(s2, dPos, snapOut) { rotation(rot(th1 + (th1 - th0) * 0.08f)) }
      sec(s3, dPos) { rotation(rot(th0 + (th0 - th1) * 0.15f)) }
    }
    to { rotation(if (exit) rot(th0 + (th0 - th1) * 0.15f) else rot(th1)) }
  }

  // Squash: stays soft and round on the creep; a stretch along the exit.
  animate(t0, dPos, ease = Easing.linear) {
    sec(0f, dPos, sine) { squash(0.94, 1.07) }
    sec(a1 + 0.1f, dPos, sine) { squash(1.03, 0.97) }
    sec(a2, dPos, sine) { squash(1, 1) }
    sec(spot, dPos, Easing.easeOut) { squash(1, 1) }
    sec(spot + 0.07f, dPos, sine) { squash(0.93, 1.08) }
    sec(spot + 0.25f, dPos, sine) { squash(1.03, 0.97) }
    sec(spot + 0.42f, dPos, sine) { squash(1, 1) }
    if (exit) {
      sec(s1, dPos, Easing.easeOut) { squash(1, 1) }
      sec(s2, dPos, Easing.easeIn) { squash(1.1, 0.9) }
      sec(s3, dPos) { squash(0.86, 1.16) }
    }
    to { squash(if (exit) 0.86f else 1f, if (exit) 1.16f else 1f) }
  }

  // The floppy tip: trails on the way in, whips when he stops, rings out; trails again on the exit.
  val sw = if (top) 1.25f else 1f
  animate(t0, end, ease = Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    sec(0f, end, sine) { hatSway(0) }
    sec(a1 * 0.7f, end, sine) { hatSway(-0.55f * sw) }
    sec(a1 + 0.09f, end, sine) { hatSway(0.75f * sw) }
    sec(a1 + 0.27f, end, sine) { hatSway(-0.36f * sw) }
    sec(a1 + 0.45f, end, sine) { hatSway(0.16f * sw) }
    sec(a3 - 0.1f, end, sine) { hatSway(-0.28f * sw) }
    sec(a5 + 0.12f * beat, end, sine) { hatSway(0.42f * sw) }
    sec(a5 + 0.32f * beat, end, sine) { hatSway(-0.18f * sw) }
    sec(a5 + 0.52f * beat, end, sine) { hatSway(0.06f) }
    sec(spot + 0.06f, end, sine) { hatSway(0) }
    sec(spot + 0.16f, end, sine) { hatSway(-0.35f * sw) }  // the flinch flicks it
    sec(spot + 0.36f, end, sine) { hatSway(0.15f * sw) }
    sec(spot + 0.55f, end, sine) { hatSway(0) }
    if (exit) { sec(s2, end, Easing.easeIn) { hatSway(0) }; sec(s3, end) { hatSway(0.9f) } }
    to { hatSway(0) }
  }

  // Hanging upside down: he swings on his "feet" like a pendulum and the hat flops down under gravity.
  if (top) {
    animate(t0 + a5 - 0.1f * beat, 2.0, ease = Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
      at(0, outSine) { rotation(0) }; at(10, sine) { rotation(9f * facing) }; at(30, sine) { rotation(-5.5f * facing) }
      at(50, sine) { rotation(3f * facing) }; at(70, sine) { rotation(-1.4f * facing) }; at(88, sine) { rotation(0.5f * facing) }
      to { rotation(0) }
    }
    animate(t0, end, ease = Easing.linear) {
      sec(0f, end, sine) { hatDroop(0) }
      sec(a1 + 0.15f, end, outSine) { hatDroop(0.85) }
      sec(a1 + 0.4f, end, sine) { hatDroop(0.5) }
      sec(a1 + 0.65f, end, sine) { hatDroop(0.72) }
      sec(spot + 0.1f, end, sine) { hatDroop(0.62) }
      sec(spot + 0.3f, end, sine) { hatDroop(0.9) }
      to { hatDroop(if (exit) 0.2f else 0.65f) }
    }
  }

  // Eyes lead the head: ahead while creeping, then quick darts around the screen, then straight at the viewer.
  animate(t0, end, ease = Easing.linear) {
    sec(0f, end) { look(ahead.first, ahead.second) }
    sec(a3 + 0.04f, end, dart) { look(ahead.first, ahead.second) }
    sec(a3 + 0.15f, end) { look(scanA.first, scanA.second) }
    sec(a4 + 0.22f * beat, end, dart) { look(scanA.first, scanA.second) }
    sec(a4 + 0.33f * beat, end) { look(scanB.first, scanB.second) }
    sec(a5 + 0.3f * beat, end, dart) { look(scanB.first, scanB.second) }
    sec(a5 + 0.41f * beat, end) { look(far.first, far.second) }
    sec(spot - 0.13f * beat, end, dart) { look(far.first, far.second) }
    sec(spot, end) { look(0, 0.05) }
    to { look(0, 0.05) }
  }
  // Lids: curious and sly on the way in, wide open with brows up when he sees you.
  animate(t0, end, ease = Easing.linear) {
    sec(0f, end, sine) { lidTop(0.38); squint(0.22); eyeScale(1); brows(0, 0, false); life(1) }
    sec(spot - 0.15f * beat, end, sine) { life(1) }
    sec(spot, end, sine) { life(0.1) }
    sec(a5, end, sine) { lidTop(0.3); squint(0.28) }
    sec(spot + 0.1f, end, pop) { lidTop(0.3); squint(0.28); eyeScale(1); brows(-0.2, 0, true) }
    sec(spot + 0.22f, end, sine) { lidTop(0); squint(0); eyeScale(1.2); brows(0.75, -0.25, true) }
    sec(spot + 0.5f, end, sine) { eyeScale(1.12); brows(0.55, -0.2, true) }
    if (exit) {
      sec(s1, end, Easing.easeIn) { lidTop(0); eyeScale(1.12); brows(0.55, -0.2, true); life(0.1) }
      sec(s1 + 0.05f, end) { life(1) }
      sec(s2, end) { lidTop(0.15); eyeScale(0.95); brows(0.2, -0.4, true) }   // a wince as he goes
      to { lidTop(0); squint(0); eyeScale(1); brows(0, 0, false); life(1) }
    } else to { lidTop(0); squint(0); eyeScale(1.12); brows(0.55, -0.2, true); life(0.4) }
  }
  // The "oh!" is swapped behind the double blink.
  peekBlink(t0 + spot + 0.02f, double = true)
  animate(t0 + spot + 0.08f, 0.001, ease = Easing.linear) { to { mouth(Mouth.O) } }
  if (exit) animate(t0 + s3, 0.001, ease = Easing.linear) { to { mouth(Mouth.SMILE) } }

  if (sounds) {
    cue(t0 + a1 * 0.35f, "peek")
    if (exit) cue(t0 + s2, "whoosh")
  }
  return PeekTimes(t0 + spot, t0 + end)
}

/** Where to pin a bubble beside a pose from [edgePeek] (a point above his forehead, kept on screen). */
fun SceneScope.edgeBubbleSpot(side: Edge, along: Float, size: Float = 300f): Pair<Float, Float> {
  val k = size / 540f
  return when (side) {
    Edge.LEFT -> boxToScreen(300f, 120f, -95f * k, along, size, 47f, 1f).let { (it.first + 140f).coerceIn(150f, W - 150f) to it.second - 30f }
    Edge.RIGHT -> boxToScreen(300f, 120f, W + 95f * k, along, size, -47f, -1f).let { (it.first - 140f).coerceIn(150f, W - 150f) to it.second - 30f }
    Edge.TOP -> (along + 260f).coerceIn(150f, W - 150f) to 330f
  }
}

/**
 * Pip peeks out from behind a home-screen icon. The stage masks him wherever the icon's circle and label are
 * ([hideBehind]), so at about 170 px he fits completely behind a launcher icon.
 *
 * UP: he rises slowly so the hat point shows first, holds, rises until his eyes clear the icon's top edge, and his
 * eyes dart left and right (head following a beat late). On a beat they find the viewer: they widen, he does a tiny
 * jump, and ducks back down behind the icon with a stretch on the drop and a squash at the bottom.
 * SIDE_LEFT / SIDE_RIGHT: the same acting, but he leans out past the icon's side at an angle, hat first.
 *
 * @param at scene time; Pip is cut to his hidden pose behind [icon] then.
 * @param rise stretches (>1) or quickens (<1) the slow rise.
 * @param hold how long he stares at the viewer before ducking.
 * @param beat tempo of the eye-dart phase (<1 is a quicker scan).
 * @param exit false leaves him peeking (eyes on the viewer) for the caller to take over.
 */
fun SceneScope.iconPeek(
  at: Number,
  icon: String = "YouTube",
  style: IconPeekStyle = IconPeekStyle.UP,
  size: Float = 170f,
  rise: Float = 1f,
  hold: Float = 0.3f,
  beat: Float = 1f,
  exit: Boolean = true,
  sounds: Boolean = true,
): PeekTimes {
  val t0 = at.toFloat()
  val spotI = stage.icon(icon)
  val k = size / 540f
  val s = when (style) { IconPeekStyle.SIDE_LEFT -> -1f; else -> 1f }
  val side = style != IconPeekStyle.UP
  val face = if (style == IconPeekStyle.SIDE_LEFT) -1f else 1f

  // Hidden: his feet just inside the bottom of the label mask, hat point inside the icon circle.
  val yHidden = spotI.cell.bottom + 34f
  val xHidden = spotI.cx + if (side) s * 6f else 0f
  // UP: hat point ~26 px over the rim, then eyes (box y ~411, 249 units over his feet) clear it by ~18 px.
  val yHat = spotI.top + 655f * k - 26f
  val yEyes = spotI.top + 249f * k - 18f
  // SIDE: feet rise just above the icon's centre line and he leans out past its edge. Above the centre line only the
  // circle masks him, so his cheek beside the icon is never cut by the straight top edge of the label mask.
  val ySide = spotI.cy - 24f
  val xSide = spotI.cx + s * 26f
  val lean = s * 40f
  // SIDE first shows the hat point over the icon's shoulder (tall and narrow so nothing pokes out below), then leans.
  val xPeek = spotI.cx + s * 12f
  val yPeek = spotI.cy + 127f * k / (170f / 540f)

  val b1 = 0.55f * rise           // hat point appears
  val b2 = b1 + 0.38f             // hold, the tip wiggling
  val b3 = b2 + 0.8f * rise       // slow rise until the eyes clear
  val d1 = b3 + 0.12f * beat     // dart one way
  val d2 = d1 + 0.5f * beat      // dart the other way
  val d3 = d2 + 0.46f * beat     // eyes to the viewer
  val spot = d3 + 0.36f * beat    // the beat lands: spotted!
  val j = spot + 0.13f            // top of the tiny jump
  val dk = j + 0.08f + hold       // duck
  val gone = dk + 0.2f
  val end = if (exit) gone + 0.3f else spot + 0.6f

  animate(t0, 0.001, ease = Easing.linear) {
    to {
      hideBehind(icon); size(size); facing(face); pos(xHidden, yHidden); rotation(0); squash(1, 1)
      look(0, -0.4); lidTop(0.32); squint(0.15); eyeScale(1); brows(0, 0, false); mood(Mood.NEUTRAL); mouth(Mouth.SMILE)
      hatDroop(0); shadow(0)
    }
  }

  if (!side) {
    animate(t0, end, ease = Easing.linear) {
      sec(0f, end, outSine) { y(yHidden) }
      sec(b1, end, sine) { y(yHat) }
      sec(b2, end, sine) { y(yHat + 3f) }
      sec(b3, end, sine) { y(yEyes) }
      sec(spot, end, outSine) { y(yEyes) }
      sec(j, end, sine) { y(yEyes - 20f) }
      sec(j + 0.08f, end, sine) { y(yEyes - 17f) }
      if (exit) { sec(dk, end, snapOut) { y(yEyes - 15f) }; sec(gone, end) { y(yHidden) } }
      to { y(if (exit) yHidden else yEyes) }
    }
    // Head follows the eye darts a beat late, so the eyes visibly lead.
    animate(t0, end, ease = Easing.linear) {
      sec(0f, end) { rotation(0) }
      sec(b1 - 0.1f, end, sine) { rotation(0) }
      sec(b2 - 0.05f, end, sine) { rotation(-3) }      // the hat point "sniffs" left and right
      sec(b2 + 0.25f, end, sine) { rotation(2) }
      sec(b3, end, sine) { rotation(0) }
      sec(d1 + 0.1f * beat, end, sine) { rotation(0) }
      sec(d1 + 0.34f * beat, end, sine) { rotation(-6) }
      sec(d2 + 0.1f * beat, end, sine) { rotation(-6) }
      sec(d2 + 0.36f * beat, end, sine) { rotation(5.5) }
      sec(d3 + 0.08f * beat, end, sine) { rotation(5.5) }
      sec(d3 + 0.3f * beat, end, sine) { rotation(0) }
      sec(j, end, sine) { rotation(-2) }
      to { rotation(0) }
    }
  } else {
    // Lean first so the hat swings out past the edge, then rise so the eyes follow.
    animate(t0, end, ease = Easing.linear) {
      sec(0f, end, sine) { rotation(0); pos(xHidden, yHidden) }
      sec(b1, end, sine) { rotation(s * 4f); pos(xPeek, yPeek) }
      sec(b2, end, sine) { rotation(s * 8f); pos(xPeek, yPeek + 4f) }
      sec(b3, end, sine) { rotation(lean); pos(xSide, ySide) }
      sec(d1 + 0.1f * beat, end, sine) { rotation(lean) }
      sec(d1 + 0.34f * beat, end, sine) { rotation(lean + s * 4f) }
      sec(d2 + 0.1f * beat, end, sine) { rotation(lean + s * 4f) }
      sec(d2 + 0.36f * beat, end, sine) { rotation(lean - s * 5f) }
      sec(spot, end, outSine) { rotation(lean - s * 5f); pos(xSide, ySide) }
      sec(j, end, sine) { rotation(lean - s * 9f); pos(xSide, ySide - 16f) }
      sec(j + 0.08f, end, sine) { rotation(lean - s * 8f); pos(xSide, ySide - 13f) }
      if (exit) {
        sec(dk, end, snapOut) { rotation(lean - s * 6f); pos(xSide, ySide - 12f) }
        sec(gone, end) { rotation(s * 4f); pos(xHidden, yHidden) }
      }
      to { if (exit) { rotation(0); pos(xHidden, yHidden) } else { rotation(lean - s * 5f); pos(xSide, ySide) } }
    }
  }

  // Squash: tall and narrow as he rises (fits behind the icon, reads as reaching), a pop on the jump, a stretch on
  // the drop so the hat lingers above the rim, and a squash once he's down.
  animate(t0, end, ease = Easing.linear) {
    sec(0f, end, sine) { squash(0.92, 1.09) }
    sec(b1, end, sine) { if (side) squash(0.9, 1.1) else squash(0.95, 1.06) }
    sec(b2, end, sine) { if (side) squash(0.91, 1.09) else squash(0.97, 1.03) }
    sec(b3, end, sine) { squash(0.93, 1.07) }
    sec(b3 + 0.3f, end, sine) { squash(0.96, 1.04) }
    sec(spot, end, Easing.easeOut) { squash(1.06, 0.94) }
    sec(j, end, sine) { squash(0.9, 1.12) }
    sec(j + 0.08f, end, sine) { squash(0.97, 1.03) }
    if (exit) {
      sec(dk, end, Easing.easeIn) { squash(0.95, 1.05) }
      sec(dk + 0.1f, end, Easing.easeIn) { squash(0.84, 1.2) }
      sec(gone, end, Easing.easeOut) { squash(1.26, 0.8) }
      sec(gone + 0.15f, end, sine) { squash(0.95, 1.05) }
    }
    to { squash(1, 1) }
  }

  // The floppy tip lags every move and rings out at each stop.
  animate(t0, end, ease = Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    sec(0f, end, sine) { hatSway(0) }
    sec(b1 * 0.6f, end, sine) { hatSway(-0.4f * s) }
    sec(b1 + 0.08f, end, sine) { hatSway(0.6f * s) }
    sec(b1 + 0.24f, end, sine) { hatSway(-0.3f * s) }
    sec(b2 + 0.1f, end, sine) { hatSway(0.15f * s) }
    sec(b3 + 0.1f, end, sine) { hatSway(-0.3f * s) }
    sec(d1 + 0.42f * beat, end, sine) { hatSway(0.35) }
    sec(d2 + 0.42f * beat, end, sine) { hatSway(-0.4) }
    sec(d3 + 0.4f * beat, end, sine) { hatSway(0.15) }
    sec(spot, end, sine) { hatSway(0) }
    sec(j + 0.04f, end, sine) { hatSway(-0.6f * s) }
    if (exit) { sec(dk + 0.04f, end, sine) { hatSway(0.2f * s) }; sec(gone, end, sine) { hatSway(0.9f * s) }; sec(gone + 0.2f, end) { hatSway(-0.3f * s) } }
    to { hatSway(0) }
  }

  // Eyes: up at the rim while rising, darts left and right ahead of the head, then straight at the viewer.
  val outA = if (side) (s * 1f) to -0.25f else -1f to -0.15f
  val outB = if (side) (s * 0.2f) to -0.9f else 1f to -0.2f
  animate(t0, end, ease = Easing.linear) {
    sec(0f, end) { look(0, -0.4) }
    sec(b3 - 0.25f, end, sine) { look(0, -0.4) }
    sec(b3, end) { look(0, -0.1) }
    sec(d1, end, dart) { look(0, -0.1) }
    sec(d1 + 0.11f * beat, end) { look(outA.first, outA.second) }
    sec(d2, end, dart) { look(outA.first, outA.second) }
    sec(d2 + 0.12f * beat, end) { look(outB.first, outB.second) }
    sec(d3, end, dart) { look(outB.first, outB.second) }
    sec(d3 + 0.1f * beat, end) { look(0, 0.1) }
    to { look(0, 0.1) }
  }
  animate(t0, end, ease = Easing.linear) {
    sec(0f, end, sine) { lidTop(0.32); squint(0.15); eyeScale(1); life(1) }
    sec(b3, end, sine) { lidTop(0.25); squint(0.25) }
    sec(d3, end, sine) { lidTop(0.25); squint(0.25) }
    sec(d3 + 0.15f * beat, end, sine) { lidTop(0.12); squint(0.1); life(1) }
    sec(spot, end, pop) { lidTop(0.12); squint(0.1); eyeScale(1); brows(0, 0, true); life(0.1) }
    sec(spot + 0.1f, end, sine) { lidTop(0); squint(0); eyeScale(1.32); brows(0.85, -0.3, true) }
    sec(spot + 0.32f, end, sine) { eyeScale(1.24); brows(0.7, -0.25, true) }
    if (exit) {
      sec(dk, end) { eyeScale(1.24); life(0.1) }
      sec(gone, end) { lidTop(0); squint(0); eyeScale(1); brows(0, 0, false); life(1) }
      to { lidTop(0); squint(0); eyeScale(1); brows(0, 0, false); life(1) }
    } else to { lidTop(0); squint(0); eyeScale(1.24); brows(0.7, -0.25, true); life(0.4) }
  }
  // A thoughtful blink when the darting stops, then the "oh!" mouth swaps in on the spot.
  peekBlink(t0 + d3 + 0.12f * beat)
  animate(t0 + spot, 0.001, ease = Easing.linear) { to { mouth(Mouth.O) } }
  if (exit) animate(t0 + gone, 0.001, ease = Easing.linear) { to { mouth(Mouth.SMILE) } }

  if (sounds) {
    cue(t0 + b1 * 0.4f, "peek")
    cue(t0 + spot, "pop")
    if (exit) cue(t0 + dk, "whoosh")
  }
  return PeekTimes(t0 + spot, t0 + (if (exit) gone else end))
}

/** The pin-point above an icon for a bubble while Pip peeks from behind it. */
fun SceneScope.iconBubbleSpot(icon: String, style: IconPeekStyle = IconPeekStyle.UP): Pair<Float, Float> {
  val s = stage.icon(icon)
  val dx = when (style) { IconPeekStyle.UP -> 0f; IconPeekStyle.SIDE_LEFT -> -70f; IconPeekStyle.SIDE_RIGHT -> 70f }
  return (s.cx + dx).coerceIn(150f, W - 150f) to s.top - 70f
}

/**
 * The peek reel (~25 s), played with `--es show peeks`: left edge, right edge, an upside-down hang from the top, then
 * peeks from behind YouTube, Photos and Gmail. Each variant is its own scene (sized to the peek plus a short empty
 * beat) so `--es from <name>` jumps straight to it. Creep and rise speeds alternate slow and quick so no two peeks
 * share a rhythm.
 */
fun peekShow(): Show {
  val pad = 0.22f
  fun edgeScene(name: String, at: Float, creep: Float, hold: Float, block: SceneScope.() -> Unit) =
    scene(name, at + edgePeekLength(creep, hold) + pad, block)
  fun iconScene(name: String, at: Float, rise: Float, hold: Float, block: SceneScope.() -> Unit) =
    scene(name, at + iconPeekLength(rise, hold) + pad, block)
  return Show(
    listOf(
      edgeScene("left", 0.15f, 1.1f, 0.4f) {
        pip { x = -400f; y = 1350f; size = 300f; mouth = Mouth.SMILE }
        edgePeek(0.15, Edge.LEFT, along = 1350f, creep = 1.1f, hold = 0.4f)
      },
      edgeScene("right", 0.1f, 0.75f, 0.55f) {
        pip { x = W + 400f; y = 980f; size = 300f; mouth = Mouth.SMILE }
        val p = edgePeek(0.1, Edge.RIGHT, along = 980f, creep = 0.75f, hold = 0.55f, depth = 20f)
        val (bx, by) = edgeBubbleSpot(Edge.RIGHT, 980f)
        peekBubble("Psst!", bx, by, p.spotted + 0.1f, 0.85f)
      },
      edgeScene("top", 0.1f, 1f, 0.6f) {
        pip { x = 700f; y = -500f; size = 300f; mouth = Mouth.SMILE }
        val p = edgePeek(0.1, Edge.TOP, along = 700f, creep = 1f, hold = 0.6f, facing = -1f)
        peekBubble("Peekaboo!", 520f, 400f, p.spotted + 0.12f, 0.85f)
      },
      iconScene("youtube", 0.1f, 1f, 0.3f) {
        pip { x = 922f; y = H + 400f; size = 170f; mouth = Mouth.SMILE }
        iconPeek(0.1, "YouTube", IconPeekStyle.UP, rise = 1f, hold = 0.3f)
      },
      iconScene("photos", 0.1f, 0.8f, 0.3f) {
        pip { x = 668f; y = H + 400f; size = 170f; mouth = Mouth.SMILE }
        iconPeek(0.1, "Photos", IconPeekStyle.SIDE_RIGHT, rise = 0.8f, hold = 0.3f)
      },
      iconScene("gmail", 0.1f, 0.9f, 0.55f) {
        pip { x = 413f; y = H + 400f; size = 170f; mouth = Mouth.SMILE }
        val p = iconPeek(0.1, "Gmail", IconPeekStyle.SIDE_LEFT, rise = 0.9f, hold = 0.55f)
        val (bx, by) = iconBubbleSpot("Gmail", IconPeekStyle.SIDE_LEFT)
        peekBubble("Psst!", bx, by, p.spotted + 0.05f, 0.75f)
      },
    ),
  ) {
    // Always-on life under every scene, as in the main show (see PipLife.kt).
    pipLife()
  }
}
