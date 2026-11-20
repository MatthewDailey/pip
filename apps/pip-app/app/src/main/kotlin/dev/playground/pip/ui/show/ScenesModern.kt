package dev.playground.pip.ui.show

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Ease
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The last three decades, each a quick-fire medley of three instantly recognisable moments joined by whip pans,
 * poofs and hops:
 *
 * - d00s: the wizard on the bridge, the battle-cry charge, and the "can you hear me now?" signal hunt.
 * - d10s: the calm coffee in the burning room, the horse-riding dance, and "deal with it".
 * - dToday: holding the door (the showstopper), then the red light / green light doll game.
 *
 * Every set piece is an original flat-vector drawing painted below as a CUSTOM actor. Music runs at 120 BPM and every
 * decade starts on a beat, so whole and half seconds land on the beat.
 */
fun modernDecades(): List<Scene> = listOf(noughties(), tenners(), today())

private const val HX = 540f
private const val HY = 1350f
/** Pip's box-unit to screen-pixel factor at size 300. */
private const val K = 300f / 540f
private const val PI_F = 3.1415927f

private fun boxX(v: Float) = HX + (v - 270f) * K
private fun boxY(v: Float) = HY - (660f - v) * K

/** A whip-fast move: nearly all the travel in the first frames, then a soft settle. */
private val snap: Ease = Easing.cubicBezier(0.1f, 0.85f, 0.2f, 1f)
/** Accelerating charge: slow off the mark, flat out at the end. */
private val charge: Ease = Easing.cubicBezier(0.5f, 0f, 0.8f, 0.6f)
private val overshoot: Ease = Easing.cubicBezier(0.25f, 1.35f, 0.45f, 1f)

// ============================================================================================ scene helpers

private fun SceneScope.home() = pip {
  x = HX; y = HY; size = 300f; groundY = HY
  right.x = 600f; right.y = 470f; left.x = -60f; left.y = 470f
}

private fun SceneScope.custom(
  layer: Int, px: Float, py: Float, anchor: Boolean = false, text: String = "",
  style: ActorState.() -> Unit = {}, painter: (Canvas, ActorState, Float) -> Unit,
): SceneScope.Actor = actor(ActorKind.CUSTOM, text, anchorToPip = anchor, layer = layer) {
  x = px; y = py; paint = painter; style()
}

/** The decade title, punched in at the top for the first ~1.2 s. */
private fun SceneScope.banner(title: String) {
  cue(0.05, "whoosh")
  actor(ActorKind.BANNER, title, anchorToPip = false, layer = 6) { x = W / 2; y = 330f; alpha = 0f }
    .animate(0.05, 1.25, ease = Easing.linear, fill = Fill.NONE) {
      at(0, overshoot) { scale(0.55); alpha(0); y(290); rotation(-4) }
      at(14, Easing.easeOutSine) { alpha(1) }
      at(24, Easing.easeInOutSine) { scale(1.06); y(330); rotation(1) }
      at(36, Easing.easeInOutSine) { scale(1); rotation(0) }
      at(82, Easing.easeInSine) { scale(1); alpha(1); y(330) }
      to { scale(0.9); alpha(0); y(300) }
    }
}

/** A speech bubble that sits [dy] px below the top of Pip's head (negative is higher). */
private fun SceneScope.bubble(text: String, at0: Float, dur: Float, dy: Float = 0f, dx: Float = 0f) {
  actor(ActorKind.BUBBLE, text) { alpha = 0f; y = dy; x = dx }.animate(at0, dur, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.outBack) { scale(0.4); alpha(1) }
    at(100f * 0.22f / dur) { scale(1) }
    at(100f * (dur - 0.2f) / dur, Easing.easeInSine) { alpha(1); scale(1) }
    to { alpha(0); scale(0.9) }
  }
}

/** A blink that snaps shut and opens more softly; it hides any mood or mouth swap placed under it. */
private fun SceneScope.blinkAt(at0: Float) = animate(at0, 0.17, ease = Easing.linear, fill = Fill.NONE) {
  from(Easing.easeInSine) { blink(0) }
  at(38, Easing.easeOutSine) { blink(1) }
  to { blink(0) }
}

/** The hat tip trails a move in direction [dir] (+1 = right) and overshoots when the head stops. */
private fun SceneScope.hatKick(at0: Float, dir: Float, amt: Float = 0.8f) {
  if (dir == 0f) return
  animate(at0 + 0.07f, 0.7, ease = Easing.linear, composite = Composite.ADD) {
    from(Easing.easeOutSine) { hatSway(0) }
    at(18, Easing.easeInOutSine) { hatSway(-dir * amt) }
    at(45, Easing.easeInOutSine) { hatSway(dir * amt * 0.55f) }
    at(72, Easing.easeInOutSine) { hatSway(-dir * amt * 0.2f) }
    to { hatSway(0) }
  }
}

/** A hop on an arc from [fromX] to [toX]: anticipation, stretch, apex, impact, settle. Lands at at0 + 0.82 dur. */
private fun SceneScope.hop(at0: Float, fromX: Float, toX: Float, height: Float = 70f, dur: Float = 0.42f, cues: Boolean = false) {
  val lift = at0 + dur * 0.2f
  val air = dur * 0.62f
  animate(at0, dur, ease = Easing.linear) {
    at(0, Easing.easeOutSine) { squash(1, 1) }
    at(20, Easing.easeOutSine) { squash(1.15, 0.87) }
    at(32, Easing.easeInOutSine) { squash(0.89, 1.12) }
    at(56, Easing.easeInOutSine) { squash(1, 1) }
    at(78, Easing.easeOut) { squash(0.95, 1.05) }
    at(84, Easing.easeOutSine) { squash(1.18, 0.85) }
    at(93, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  animate(lift, air, ease = Easing.easeInOutSine) { from { x(fromX) }; to { x(toX) } }
  animate(lift, air, ease = Easing.linear) {
    from(Easing.easeOutSine) { y(HY) }
    at(50, Easing.easeInSine) { y(HY - height) }
    to { y(HY) }
  }
  hatKick(lift, if (toX > fromX) 1f else if (toX < fromX) -1f else 0f, 0.55f)
  if (cues) { cue(lift, "hop"); cue(lift + air, "land") }
}

/**
 * Camera shake: decaying pseudo-random offsets layered (ADD) on Pip and on every actor in [targets]. Pip-anchored
 * actors follow Pip on their own, so they don't belong in [targets].
 */
private fun SceneScope.shake(at0: Float, dur: Float, amp: Float, seed: Int, targets: List<SceneScope.Actor>, pipAmp: Float = 1f) {
  val n = (dur / 0.034f).toInt().coerceAtLeast(4)
  fun off(i: Int, axis: Int): Float {
    val d = 1f - i.toFloat() / n
    return amp * d * d * (mHash(seed * 131 + i * 7 + axis) * 2f - 1f)
  }
  if (pipAmp > 0f) animate(at0, dur, ease = Easing.linear, composite = Composite.ADD) {
    for (i in 0..n) at(100f * i / n) { x(off(i, 0) * pipAmp); y(off(i, 1) * 0.5f * pipAmp) }
  }
  for (target in targets) target.animate(at0, dur, ease = Easing.linear, composite = Composite.ADD) {
    for (i in 0..n) at(100f * i / n) { x(off(i, 0)); y(off(i, 1) * 0.7f) }
  }
}

/** Full-screen motion streaks for a whip pan; the camera swings right, so streaks rush left. */
private fun SceneScope.whip(at0: Float) {
  val w = W; val h = H
  cue(at0, "whoosh")
  custom(7, w / 2, h / 2, style = { visible = false }) { c, a, _ -> paintWhip(c, a, w, h) }
    .animate(at0, 0.42, ease = Easing.linear, fill = Fill.NONE) { from { visible(true); progress(0) }; to { progress(1) } }
}

private fun SceneScope.poofAt(at0: Float, px: Float, py: Float, size: Float = 1.4f) {
  cue(at0, "poof")
  actor(ActorKind.SMOKE, anchorToPip = false, layer = 5) { x = px; y = py; visible = false }
    .animate(at0, 0.8, ease = Easing.easeOut, fill = Fill.NONE) {
      from { visible(true); progress(0); scale(size * 0.7f) }
      to { progress(1); scale(size) }
    }
}

// ============================================================================================ 2000s

private fun noughties() = scene("d00s", 9) {
  home()
  val w = W; val h = H; val t0 = start

  // ---------------------------------------------------------------- 1. "You shall not pass!" (0 - 3.25 s)
  val cavern = custom(-3, w / 2, h / 2, style = { alpha = 0f }) { c, _, t -> paintCavern(c, t, w, h) }
  cavern.animate(0, 0.4, ease = Easing.easeOutSine) { to { alpha(1) } }
  banner("2000s")

  // Glance around the cavern, eyes first.
  animate(0.1, 0.14, ease = Easing.easeOutSine) { to { look(-0.7, -0.15) } }
  animate(0.22, 0.4, ease = Easing.easeInOutSine) { to { rotation(-3) } }
  animate(0.3, 0.12, ease = Easing.easeOutSine) { to { look(0.1, -1) } }

  // The grey wizard hat drops out of a puff and lands on top of his red one.
  val hat = custom(2, -14f, 60f, anchor = true, style = { visible = false }) { c, a, t -> paintWizardHat(c, a, t) }
  poofAt(0.36f, HX - 10f, 830f, 0.9f)
  hat.animate(0.4, 0.26, ease = Easing.linear) {
    from(Easing.easeInSine) { visible(true); y(-260); rotation(-10); scale(0.9) }
    at(86, Easing.easeOutSine) { y(68); rotation(4); scale(1.04) }
    to { y(60); rotation(0); scale(1) }
  }
  hat.animate(0.66, 0.5, ease = Easing.linear) {
    from(Easing.easeOutSine) { rotation(0) }
    at(25, Easing.easeInOutSine) { rotation(-6) }
    at(55, Easing.easeInOutSine) { rotation(3) }
    to { rotation(0) }
  }
  cue(0.64, "pop")
  animate(0.64, 0.45, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1, 1); rotation(-3) }
    at(14, Easing.easeInOutSine) { squash(1.16, 0.86); rotation(0) }
    at(40, Easing.easeInOutSine) { squash(0.95, 1.05) }
    at(70, Easing.easeInOutSine) { squash(1.02, 0.98) }
    to { squash(1, 1) }
  }
  // Thought change under a blink: from curious to stern.
  blinkAt(0.74f)
  animate(0.8, 0.2, ease = Easing.easeOutSine) {
    to { look(0, 0); brows(-0.35, 0.75); lidTop(0.3); squint(0.2); mouth(Mouth.FLAT) }
  }

  // The staff spins into his hand (the fist is part of the prop, so it stays glued to the shaft).
  val staffX = boxX(610f); val staffY = boxY(450f)
  val staff = custom(2, staffX, staffY, style = { visible = false }) { c, a, t -> paintStaff(c, a, t) }
  cue(0.86, "whoosh")
  staff.animate(0.86, 0.42, ease = Easing.linear) {
    from(Easing.easeOutSine) { visible(true); scale(0.1); rotation(-620); y(staffY - 120); progress(0.2) }
    at(70, Easing.easeInOutSine) { scale(1.06); rotation(12); y(staffY + 6) }
    to { scale(1); rotation(0); y(staffY); progress(0.3) }
  }
  animate(1.22, 0.3, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1, 1) }
    at(30, Easing.easeInOutSine) { squash(1.07, 0.94) }
    to { squash(1, 1) }
  }

  // Anticipation: eyes up, then the staff rises and he grows tall, leaning back.
  animate(1.26, 0.12, ease = Easing.easeOutSine) { to { look(0.35, -0.8); squint(0); eyeScale(1.1) } }
  staff.animate(1.36, 0.36, ease = Easing.easeOutSine) { to { y(staffY - 150); rotation(-9); progress(0.7) } }
  animate(1.36, 0.36, ease = Easing.easeOutSine) { to { squash(0.9, 1.11); rotation(-5) } }
  hatKick(1.36f, -1f, 0.5f)
  hat.animate(1.4, 0.36, ease = Easing.easeOutSine) { to { rotation(-5) } }
  // Moving hold at the top: a tremble of gathered power.
  staff.animate(1.72, 0.06, ease = Easing.easeInOutSine, iterations = 2f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { x(0) }; to { x(3) }
  }

  // SLAM.
  val slam = 1.8f
  staff.animate(slam - 0.08f, 0.08, ease = Easing.easeInSine) { to { y(staffY); rotation(2); progress(1) } }
  staff.animate(slam, 0.5, ease = Easing.easeOutSine) { to { rotation(0); progress(0.45) } }
  animate(slam - 0.08f, 0.6, ease = Easing.linear) {
    from(Easing.easeInSine) { squash(0.9, 1.11); rotation(-5) }
    at(14, Easing.easeOutSine) { squash(1.22, 0.82); rotation(3) }
    at(36, Easing.easeInOutSine) { squash(0.95, 1.06); rotation(1) }
    at(60, Easing.easeInOutSine) { squash(1.03, 0.97) }
    to { squash(1, 1); rotation(0) }
  }
  hat.animate(slam, 0.45, ease = Easing.linear) {
    from(Easing.easeOutSine) { rotation(-5); y(60) }
    at(15, Easing.easeOutSine) { rotation(5); y(48) }
    at(45, Easing.easeInOutSine) { rotation(-2); y(62) }
    to { rotation(0); y(60) }
  }
  hatKick(slam, 1f, 0.9f)
  animate(slam - 0.02f, 0.1, ease = Easing.easeOutSine) {
    to { look(0, 0.1); squint(0.65); eyeScale(1); brows(-0.7, 1); mouth(Mouth.SHOUT) }
  }
  cue(slam, "boom")
  custom(3, staffX, HY - 6f, style = { visible = false }) { c, a, _ -> paintBurst(c, a) }
    .animate(slam, 0.75, ease = Easing.linear, fill = Fill.NONE) { from { visible(true); progress(0) }; to { progress(1) } }
  shake(slam, 0.5f, 24f, 1, listOf(cavern, staff))
  bubble("You shall not pass!", slam + 0.06f, 1.15f, dy = -250f)
  // The glare: eyes widen into the stare and hold.
  animate(2.05, 0.25, ease = Easing.easeOutSine) { to { squint(0.25); eyeScale(1.18) } }
  animate(2.3, 0.6, ease = Easing.easeInOutSine) { to { rotation(-1.5) } }
  // A smug half-lid as he lets it land.
  blinkAt(2.7f)
  animate(2.76, 0.18, ease = Easing.easeOutSine) { to { lidTop(0.45); eyeScale(1); mouth(Mouth.SMILE); brows(-0.2, 0.4) } }

  // ---------------------------------------------------------------- whip pan out (3.0 - 3.35 s)
  animate(2.96, 0.12, ease = Easing.easeOutSine) { to { look(1, 0) } }
  animate(3.0, 0.1, ease = Easing.easeOutSine) { to { squash(1.14, 0.88); rotation(-8) } }
  animate(3.1, 0.16, ease = Easing.easeInSine) { to { x(1460); squash(1.35, 0.76); rotation(8) } }
  hatKick(3.1f, 1f, 1f)
  staff.animate(3.1, 0.16, ease = Easing.easeInSine) { to { x(staffX + 920) } }
  cavern.animate(3.04, 0.32, ease = Easing.easeInSine) { to { x(w / 2 - w * 1.2f) } }
  whip(3.02f)
  hat.animate(3.27, 0.01) { to { visible(false) } }
  staff.animate(3.27, 0.01) { to { visible(false) } }

  // ---------------------------------------------------------------- 2. The battle-cry charge (3.27 - 5.8 s)
  val caveX = 870f
  val field = custom(-3, w / 2 + w * 1.2f, h / 2) { c, a, t -> paintField(c, a, t, w, h, caveX - w / 2) }
  field.animate(3.06, 0.3, ease = Easing.easeOutSine) { to { x(w / 2) } }

  // Off the right edge, then in again from the left: the camera has panned. Fresh face for a fresh bit.
  animate(3.27, 0.01) {
    to { x(-260); squash(1, 1); rotation(-12); look(1, 0); lidTop(0); squint(0); eyeScale(1.15); brows(0.6, 0.3); mouth(Mouth.SMILE) }
  }
  animate(3.3, 0.3, ease = overshoot) { to { x(230) } }
  animate(3.3, 0.5, ease = Easing.linear) {
    from(Easing.easeOutSine) { rotation(-12); squash(1, 1) }
    at(55, Easing.easeOutSine) { rotation(-14); squash(1.2, 0.84) }
    at(75, Easing.easeInOutSine) { rotation(3); squash(0.96, 1.04) }
    to { rotation(0); squash(1, 1) }
  }
  hatKick(3.3f, 1f, 0.9f)
  cue(3.56, "land")
  actor(ActorKind.SMOKE, anchorToPip = false, layer = 2) { x = 260f; y = HY - 30f; visible = false }
    .animate(3.5, 0.6, ease = Easing.easeOut, fill = Fill.NONE) { from { visible(true); progress(0); scale(0.35) }; to { progress(1); scale(0.6) } }

  // Wind-up: eyes to the cave, a blink, a huge breath, fist up.
  animate(3.62, 0.1, ease = Easing.easeOutSine) { to { look(1, -0.2) } }
  animate(3.72, 0.2, ease = Easing.easeOutSine) { to { rotation(4) } }
  blinkAt(3.74f)
  animate(3.8, 0.16, ease = Easing.easeOutSine) { to { brows(-0.4, 0.9); squint(0.35); mouth(Mouth.FLAT) } }
  animate(3.74, 0.22, ease = Easing.easeOutSine) {
    from { rightHand(Hand.FIST, x = 600, y = 470, angle = 0, scale = 0.2); leftHand(Hand.FIST, x = -40, y = 520, scale = 0.2) }
    to { rightHand(x = 560, y = 40, scale = 1.15); leftHand(x = -70, y = 540, scale = 1) }
  }
  animate(3.76, 0.24, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1, 1) }
    at(55, Easing.easeInOutSine) { squash(0.9, 1.12); rotation(-2) }
    to { squash(1.16, 0.87); rotation(-9) }
  }

  // CHARGE.
  val runAt = 4.0f; val runDur = 0.72f; val runFrom = 230f; val runTo = caveX
  cue(runAt - 0.02f, "crowd")
  animate(runAt - 0.04f, 0.1, ease = Easing.easeOutSine) { to { eyesOpen(0.8); mouth(Mouth.SHOUT); squint(0.55) } }
  animate(runAt, runDur, ease = charge) { from { x(runFrom) }; to { x(runTo) } }
  animate(runAt, 0.2, ease = Easing.easeOutSine) { to { rotation(14) } }
  animate(runAt, 0.15, ease = Easing.linear, iterations = 5f) {
    from(Easing.easeOutSine) { squash(1.07, 0.93) }
    at(45, Easing.easeInOutSine) { squash(0.94, 1.06) }
    to { squash(1.07, 0.93) }
  }
  animate(runAt, 0.15, ease = Easing.linear, iterations = 5f, composite = Composite.ADD) {
    from(Easing.easeOutSine) { y(0) }
    at(50, Easing.easeInSine) { y(-26) }
    to { y(0) }
  }
  animate(runAt, 0.15, ease = Easing.easeInOutSine, iterations = 5f, direction = Direction.ALTERNATE) {
    from { rightHand(y = 40, angle = -10) }; to { rightHand(y = 0, angle = 12) }
  }
  animate(runAt, 0.3, ease = Easing.linear, composite = Composite.ADD) {
    from(Easing.easeOutSine) { hatSway(0) }
    to { hatSway(-1.1) }
  }
  custom(-1, 0f, 0f) { c, _, t -> paintDustTrail(c, t, t0 + runAt, runDur, runFrom, runTo) }
  val leeroy = custom(4, w / 2, 560f, text = "LEEEEEROY!", style = { alpha = 0f }) { c, a, t -> paintShout(c, a, t, SHOUT_GOLD, 170f, 1000f, 4f) }
  leeroy.animate(runAt, 0.7, ease = Easing.linear) { from { alpha(1); progress(0) }; to { alpha(1); progress(1) } }
  leeroy.animate(runAt, 0.06, ease = Easing.easeInOutSine, iterations = 12f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { y(-4) }; to { y(4) }
  }
  leeroy.animate(4.95, 0.3, ease = Easing.easeInSine) { to { alpha(0); scale(1.15) } }
  // The cave notices him coming.
  field.animate(4.45, 0.2, ease = Easing.outBack) { to { progress(1) } }

  // Into the dark.
  animate(4.66, 0.14, ease = Easing.easeInSine) { to { size(200); alpha(0); y(HY - 40) } }
  animate(4.8, 0.01) { to { rightHand(Hand.NONE); leftHand(Hand.NONE) } }

  // The brawl, heard more than seen.
  val brawl = custom(1, caveX, HY - 110f, style = { scale = 0f }) { c, a, t -> paintBrawl(c, a, t) }
  brawl.animate(4.84, 0.18, ease = Easing.outBack) { to { scale(1) } }
  brawl.animate(4.84, 0.6, ease = Easing.linear) { to { progress(1) } }
  brawl.animate(5.32, 0.16, ease = Easing.easeInSine) { to { scale(0.4); alpha(0) } }
  cue(4.9, "pow"); cue(5.07, "pow"); cue(5.24, "pow")
  shake(4.9f, 0.45f, 9f, 2, listOf(field, brawl), pipAmp = 0f)

  // Launched back out, tumbling, and landing dazed on the home screen.
  val outAt = 5.3f
  cue(outAt + 0.02f, "whoosh")
  animate(outAt, 0.01) {
    to {
      alpha(1); size(300); x(caveX); y(HY - 60); eyesOpen(0.12); mouth(Mouth.O); brows(show = false)
      squint(0); lidTop(0); eyeScale(1); hatSway(0)
    }
  }
  animate(outAt, 0.44, ease = Easing.easeOutSine) { to { x(380) } }
  animate(outAt, 0.44, ease = Easing.linear) {
    from(Easing.easeOutSine) { y(HY - 60) }
    at(45, Easing.easeInSine) { y(880) }
    to { y(HY) }
  }
  animate(outAt, 0.44, ease = Easing.easeOutSine) { from { rotation(0) }; to { rotation(-720) } }
  animate(outAt, 0.5, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(0.86, 1.16) }
    at(45, Easing.easeInOutSine) { squash(1, 1) }
    at(84, Easing.easeOutSine) { squash(0.92, 1.08) }
    at(88, Easing.easeOutSine) { squash(1.25, 0.8) }
    to { squash(1.1, 0.91) }
  }
  field.animate(5.5, 0.4, ease = Easing.easeInOutSine) { to { alpha(0) } }
  val land = outAt + 0.44f
  cue(land, "land")
  actor(ActorKind.SMOKE, anchorToPip = false, layer = 2) { x = 380f; y = HY - 20f; visible = false }
    .animate(land, 0.6, ease = Easing.easeOut, fill = Fill.NONE) { from { visible(true); progress(0); scale(0.45) }; to { progress(1); scale(0.75) } }
  hatKick(land - 0.05f, -1f, 1f)

  // ---------------------------------------------------------------- 3. "Can you hear me now? ... Good." (5.75 - 9 s)
  animate(land, 0.45, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1.1, 0.91); rotation(-720) }
    at(30, Easing.easeInOutSine) { squash(0.95, 1.05); rotation(-714) }
    at(55, Easing.easeInOutSine) { squash(1.03, 0.97); rotation(-724) }
    at(80, Easing.easeInOutSine) { rotation(-718) }
    to { squash(1, 1); rotation(-720) }
  }
  animate(land + 0.12f, 0.18, ease = Easing.easeOutSine) { to { eyesOpen(1) } }
  blinkAt(land + 0.32f); blinkAt(land + 0.5f)
  animate(land + 0.36f, 0.1) { to { mouth(Mouth.SMILE) } }

  val phoneAt = 6.06f
  cue(phoneAt, "pop")
  animate(phoneAt, 0.24, ease = Easing.outBack) {
    from { rightHand(Hand.PHONE, x = 600, y = 470, angle = 20, scale = 0) }
    to { rightHand(x = 590, y = 440, angle = -12, scale = 1) }
  }
  val bars = custom(2, 210f, 70f, anchor = true, style = { alpha = 0f; scale = 0.6f; progress = 1f }) { c, a, t -> paintSignal(c, a, t) }
  bars.animate(6.12, 0.25, ease = Easing.outBack) { to { alpha(1); scale(1) } }
  bars.animate(6.12, 1.4, ease = Easing.linear) {
    from { progress(1) }
    at(36) { progress(1) }
    at(42, Easing.easeOutSine) { progress(2) }
    at(61) { progress(2) }
    at(66, Easing.easeOutSine) { progress(3.1) }
    at(70, Easing.easeInSine) { progress(3.1) }
    at(76) { progress(1.1) }
    at(88) { progress(1.1) }
    at(94, Easing.easeOutSine) { progress(5.2) }
    to { progress(5) }
  }
  bubble("Can you hear me now?", 6.2f, 1.05f, dx = -40f)
  animate(6.16, 0.14, ease = Easing.easeOutSine) { to { look(0.6, -0.7); brows(0.7, -0.3); mouth(Mouth.O); eyeScale(1.08) } }
  animate(6.26, 0.3, ease = Easing.easeOutSine) { to { rotation(-724) } }
  hop(6.42f, 380f, 520f, height = 46f, dur = 0.38f)
  hop(6.82f, 520f, 680f, height = 52f, dur = 0.38f)
  // Bars collapse: worry.
  animate(7.02, 0.1, ease = Easing.easeOutSine) { to { look(1, -0.6); brows(0.5, -1); mouth(Mouth.FLAT) } }
  animate(7.05, 0.25, ease = Easing.easeInOutSine) { to { rotation(-716) } }
  hop(7.22f, 680f, HX, height = 66f, dur = 0.42f)
  cue(7.57, "ding")
  actor(ActorKind.SPARKLE, anchorToPip = true, layer = 3) { x = 290f; y = 20f; scale = 0f }
    .animate(7.55, 0.5, ease = Easing.linear, fill = Fill.NONE) {
      at(0, Easing.outBack) { scale(0) }
      at(30, Easing.easeInSine) { scale(0.7) }
      to { scale(0) }
    }
  blinkAt(7.6f)
  animate(7.66, 0.16, ease = Easing.easeOutSine) {
    to { look(0, 0); squint(0.6); lidTop(0.12); brows(0.3, 0); mouth(Mouth.SMILE); eyeScale(1) }
  }
  bubble("Good.", 7.72f, 0.85f)
  animate(7.74, 0.6, ease = Easing.linear) {
    from(Easing.easeInOutSine) { rotation(-720) }
    at(25, Easing.easeInOutSine) { rotation(-714) }
    at(50, Easing.easeInOutSine) { rotation(-720) }
    at(75, Easing.easeInOutSine) { rotation(-716) }
    to { rotation(-720) }
  }
  animate(8.48, 0.2, ease = Easing.inBack) { to { rightHand(scale = 0) } }
  animate(8.69, 0.01) { to { rightHand(Hand.NONE) } }
  bars.animate(8.45, 0.25, ease = Easing.easeInSine) { to { alpha(0); scale(0.7) } }
  blinkAt(8.72f)
  animate(8.76, 0.2, ease = Easing.easeOutSine) { to { squint(0.15); lidTop(0); brows(show = false) } }
  // Unwind the tumble's two full turns so the next scene starts upright at 0 degrees.
  animate(8.98, 0.01) { to { rotation(0) } }
}

// ============================================================================================ 2010s

private fun tenners() = scene("d10s", 9) {
  home()
  val w = W; val h = H; val t0 = start

  // ---------------------------------------------------------------- 1. "This is fine." (0 - 3.25 s)
  val room = custom(-3, w / 2, h / 2, style = { alpha = 0f }) { c, a, t -> paintRoom(c, a, t, w, h) }
  val fireBack = custom(-2, w / 2, h / 2) { c, a, t -> paintFire(c, a, t, w, h, front = false) }
  val fireFront = custom(3, w / 2, h / 2) { c, a, t -> paintFire(c, a, t, w, h, front = true) }
  room.animate(0, 0.3, ease = Easing.easeOutSine) { to { alpha(1) } }
  for (fire in listOf(room, fireBack, fireFront)) fire.animate(0, 3.1, ease = Easing.linear) {
    from(Easing.easeInOutSine) { progress(0.05) }
    at(58, Easing.easeOutSine) { progress(0.5) }
    at(64, Easing.easeOutSine) { progress(0.8) }
    at(72, Easing.easeInOutSine) { progress(0.72) }
    to { progress(1) }
  }
  banner("2010s")

  // Settle in, content, mug in hand.
  val mugX = boxX(560f); val mugY = boxY(520f)
  val sipX = boxX(470f); val sipY = boxY(500f)
  pip { right.kind = Hand.FIST; right.x = 560f; right.y = 520f }
  val mug = custom(2, mugX, mugY) { c, a, t -> paintMug(c, a, t) }
  blinkAt(0.08f)
  animate(0.12, 0.3, ease = Easing.easeOutSine) { to { lidTop(0.35); squint(0.25); mouth(Mouth.SMILE); squash(1.04, 0.96) } }
  // A slow sip: eyes close as it reaches the lips.
  animate(0.55, 0.32, ease = Easing.easeInOutSine) { to { rightHand(x = 470, y = 500) } }
  mug.animate(0.55, 0.32, ease = Easing.easeInOutSine) { to { x(sipX); y(sipY); rotation(-26) } }
  animate(0.72, 0.16, ease = Easing.easeInSine) { to { eyesOpen(0.08); rotation(-3) } }
  animate(1.12, 0.3, ease = Easing.easeInOutSine) { to { rightHand(x = 560, y = 520) } }
  mug.animate(1.12, 0.3, ease = Easing.easeInOutSine) { to { x(mugX); y(mugY); rotation(0) } }
  animate(1.22, 0.24, ease = Easing.easeOutSine) { to { eyesOpen(1); rotation(0) } }
  animate(1.22, 0.4, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1.04, 0.96) }
    at(40, Easing.easeInOutSine) { squash(1.08, 0.93) }
    to { squash(1.04, 0.96) }
  }

  // The line, delivered with a serene squint.
  cue(1.45, "pop")
  bubble("This is fine.", 1.45f, 1.35f)
  animate(1.45, 0.2, ease = Easing.easeOutSine) { to { squint(0.5); lidTop(0.28) } }

  // A flare to his right: eyes dart, then snap back to us as if nothing happened.
  cue(1.95, "zap")
  animate(2.0, 0.06, ease = Easing.easeOutSine) { to { look(0.95, 0.25) } }
  animate(2.24, 0.07, ease = Easing.easeOutSine) { to { look(0, 0) } }
  // The smile starts to crack.
  animate(2.3, 0.6, ease = Easing.linear) {
    from { lidTop(0.28); mouth(Mouth.SMILE) }
    at(10) { lidTop(0.05); mouth(Mouth.FLAT) }
    at(18) { lidTop(0.4); mouth(Mouth.SMILE) }
    at(30) { lidTop(0.15); mouth(Mouth.FLAT) }
    at(40) { mouth(Mouth.SMILE) }
    at(52) { lidTop(0.08); mouth(Mouth.FLAT) }
    at(62) { mouth(Mouth.SMILE) }
    at(76) { mouth(Mouth.FROWN) }
    to { lidTop(0.1); mouth(Mouth.SMILE) }
  }
  animate(2.34, 0.25, ease = Easing.easeOutSine) { to { brows(0.5, -0.85); squint(0.35) } }
  mug.animate(2.3, 0.05, ease = Easing.easeInOutSine, iterations = 12f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { rotation(-3); x(-1) }; to { rotation(3); x(1) }
  }
  actor(ActorKind.SWEAT, anchorToPip = true, layer = 3) { x = 128f; y = 150f; alpha = 0f }
    .animate(2.4, 0.75, ease = Easing.linear) {
      from(Easing.outBack) { alpha(1); scale(0.3); y(150) }
      at(20, Easing.easeInSine) { scale(1) }
      to { y(215); alpha(0.9) }
    }
  // His hat tip catches.
  val tipFlame = custom(3, -138f, 98f, anchor = true, style = { scale = 0f }) { c, _, t -> paintTipFlame(c, t) }
  tipFlame.animate(2.6, 0.2, ease = Easing.outBack) { to { scale(1) } }
  tipFlame.animate(2.9, 0.2, ease = Easing.outBack) { to { scale(1.5) } }
  // Realisation: eyes lead up to the hat, the head follows, then the pop.
  animate(2.7, 0.1, ease = Easing.easeOutSine) { to { look(-0.8, -1) } }
  animate(2.8, 0.18, ease = Easing.easeOutSine) { to { rotation(-5) } }
  animate(2.92, 0.08, ease = Easing.easeOutSine) {
    to { eyeScale(1.5); lidTop(0); squint(0); brows(1, -0.5); mouth(Mouth.SHOUT); squash(0.9, 1.12) }
  }
  cue(2.95, "scream")

  // ---------------------------------------------------------------- whip pan (3.0 - 3.35 s)
  animate(3.02, 0.08, ease = Easing.easeOutSine) { to { squash(1.14, 0.88); rotation(-8) } }
  animate(3.1, 0.16, ease = Easing.easeInSine) { to { x(1460); squash(1.35, 0.76); rotation(8) } }
  animate(3.1, 0.16, ease = Easing.easeInSine) { to { rightHand(x = 400) } }
  mug.animate(3.1, 0.16, ease = Easing.easeInSine) { to { x(mugX + 920) } }
  hatKick(3.1f, 1f, 1f)
  for (part in listOf(room, fireBack, fireFront)) part.animate(3.04, 0.32, ease = Easing.easeInSine) { to { x(w / 2 - w * 1.2f) } }
  whip(3.02f)
  mug.animate(3.27, 0.01) { to { visible(false) } }
  tipFlame.animate(3.27, 0.01) { to { scale(0) } }

  // ---------------------------------------------------------------- 2. The horse-riding dance (3.27 - 6.05 s)
  val party = custom(-3, w / 2 + w * 1.2f, h / 2) { c, a, t -> paintParty(c, a, t, w, h, t0) }
  party.animate(3.06, 0.3, ease = Easing.easeOutSine) { to { x(w / 2) } }
  animate(3.27, 0.01) {
    to {
      x(-260); rotation(-10); squash(1, 1); eyeScale(1); lidTop(0.3); squint(0.3); brows(0.35, 0); mouth(Mouth.SMILE)
      rightHand(Hand.NONE); look(0.4, 0)
    }
  }
  animate(3.3, 0.2, ease = overshoot) { to { x(HX) } }
  animate(3.3, 0.22, ease = Easing.easeOutSine) { to { rotation(0) } }
  hatKick(3.3f, 1f, 0.9f)
  cue(3.5, "crowd")

  // Gallop: two contacts per beat ("ba-DUM"), landing hard on the beat.
  val danceAt = 3.5f; val beats = 5f
  animate(danceAt, 0.5, ease = Easing.linear, iterations = beats) {
    at(0, Easing.easeOutSine) { squash(1.13, 0.88) }
    at(14, Easing.easeOutSine) { squash(0.92, 1.09) }
    at(32, Easing.easeInSine) { squash(1, 1) }
    at(46, Easing.easeOutSine) { squash(1.07, 0.94) }
    at(58, Easing.easeOutSine) { squash(0.95, 1.05) }
    at(80, Easing.easeInSine) { squash(1, 1) }
    to { squash(1.13, 0.88) }
  }
  animate(danceAt, 0.5, ease = Easing.linear, iterations = beats, composite = Composite.ADD) {
    at(0, Easing.easeOutSine) { y(0) }
    at(30, Easing.easeInSine) { y(-48) }
    at(46, Easing.easeOutSine) { y(-6) }
    at(70, Easing.easeInSine) { y(-22) }
    to { y(0) }
  }
  // Side to side over two beats, the body leaning into each side.
  animate(danceAt, 1.0, ease = Easing.linear, iterations = 2.5f, composite = Composite.ADD) {
    at(0, Easing.easeInOutSine) { x(0); rotation(0) }
    at(25, Easing.easeInOutSine) { x(46); rotation(5) }
    at(50, Easing.easeInOutSine) { x(0); rotation(0) }
    at(75, Easing.easeInOutSine) { x(-46); rotation(-5) }
    to { x(0); rotation(0) }
  }
  // The eyes lead the sway by a tenth of a second.
  animate(danceAt - 0.1f, 1.0, ease = Easing.linear, iterations = 2.5f) {
    at(0, Easing.easeInOutSine) { look(0, 0) }
    at(25, Easing.easeInOutSine) { look(0.6, -0.1) }
    at(50, Easing.easeInOutSine) { look(0, 0) }
    at(75, Easing.easeInOutSine) { look(-0.6, -0.1) }
    to { look(0, 0) }
  }
  // Lasso hand circling overhead, one loop per beat; reins hand bouncing in front.
  animate(danceAt - 0.1f, 0.14, ease = Easing.outBack) {
    from { rightHand(Hand.FIST, x = 460, y = -60, scale = 0); leftHand(Hand.FIST, x = 130, y = 600, scale = 0) }
    to { rightHand(scale = 1); leftHand(scale = 1) }
  }
  animate(danceAt, 0.5, ease = Easing.linear, iterations = beats) {
    for (i in 0..16) {
      val th = i / 16f * 2f * PI_F
      at(i * 6.25f) { rightHand(x = 400f + 60f * cos(th), y = -60f + 18f * sin(th)) }
    }
  }
  animate(danceAt, 0.25, ease = Easing.easeInOutSine, iterations = beats * 2f, direction = Direction.ALTERNATE) {
    from { leftHand(y = 600, angle = -8) }; to { leftHand(y = 572, angle = 8) }
  }
  val rope = custom(2, 0f, 0f, anchor = true, style = { alpha = 0f }) { c, a, _ -> paintLasso(c, a) }
  rope.animate(danceAt - 0.05f, 0.12, ease = Easing.easeOutSine) { to { alpha(1) } }
  rope.animate(danceAt, 0.5, ease = Easing.linear, iterations = beats) { from { progress(0) }; to { progress(1) } }
  blinkAt(4.3f)
  bubble("Giddy-up!", 4.55f, 0.95f, dy = -60f)
  for ((i, nx) in listOf(170f, 900f, 300f, 820f).withIndex()) {
    actor(ActorKind.MUSIC_NOTE, if (i % 2 == 0) "♪" else "♫", anchorToPip = false, layer = 1) {
      x = nx; y = 1150f; alpha = 0f; color = if (i % 2 == 0) 0xFFFF4FA3.toInt() else 0xFF3FE0FF.toInt()
    }.animate(3.6f + i * 0.5f, 1.6, ease = Easing.linear, fill = Fill.NONE) {
      at(0, Easing.easeOutSine) { alpha(0); y(1150); scale(0.6) }
      at(15) { alpha(1); scale(1) }
      at(70, Easing.easeInSine) { alpha(1) }
      to { alpha(0); y(760) }
    }
  }

  // ---------------------------------------------------------------- 3. "Deal with it." (6.0 - 9 s)
  animate(6.0, 0.15, ease = Easing.inBack) { to { rightHand(scale = 0); leftHand(scale = 0) } }
  animate(6.16, 0.01) { to { rightHand(Hand.NONE); leftHand(Hand.NONE) } }
  rope.animate(6.0, 0.1, ease = Easing.easeInSine) { to { alpha(0) } }
  party.animate(6.0, 0.35, ease = Easing.easeInOutSine) { to { alpha(0) } }
  animate(6.0, 0.35, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1.13, 0.88) }
    at(40, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  blinkAt(6.06f)
  animate(6.12, 0.1) { to { mouth(Mouth.FLAT); brows(show = false); lidTop(0.2); squint(0) } }
  animate(6.2, 0.1, ease = Easing.easeOutSine) { to { look(0, -1) } }
  animate(6.28, 0.25, ease = Easing.easeOutSine) { to { rotation(-4) } }
  // The shades fall like a stone, land, and bounce once.
  val shadesLand = 6.62f
  animate(6.3, 0.32, ease = Easing.easeInSine) { from { sunglasses(0) }; to { sunglasses(1) } }
  animate(shadesLand, 0.24, ease = Easing.linear) {
    from(Easing.easeOutSine) { sunglasses(1) }
    at(30, Easing.easeInOutSine) { sunglasses(1.05) }
    at(65, Easing.easeInOutSine) { sunglasses(0.985) }
    to { sunglasses(1) }
  }
  cue(shadesLand, "pop")
  animate(shadesLand, 0.4, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1, 1); rotation(-4) }
    at(18, Easing.easeInOutSine) { squash(1.12, 0.9); rotation(1) }
    at(50, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1); rotation(0) }
  }
  animate(shadesLand, 0.1, ease = Easing.easeOutSine) { to { look(0, 0) } }
  // A slow, cool nod; the smirk arrives on the way down.
  animate(6.92, 0.3, ease = Easing.linear) {
    from(Easing.easeInOutSine) { rotation(0) }
    at(45, Easing.easeInOutSine) { rotation(4) }
    to { rotation(0) }
  }
  animate(7.0, 0.01) { to { mouth(Mouth.SMILE) } }
  // Wind up (lean the other way, hands come in low) ... then the DAB.
  val dab = 7.36f
  animate(7.12, 0.2, ease = Easing.easeOutSine) {
    from { leftHand(Hand.FIST, x = 120, y = 580, angle = 0, scale = 0); rightHand(Hand.POINT, x = 520, y = 580, angle = 20, scale = 0) }
    to { leftHand(scale = 0.8); rightHand(scale = 0.8); squash(1.12, 0.89); rotation(6) }
  }
  cue(dab - 0.06f, "whoosh")
  cue(dab + 0.02f, "pow")
  animate(dab, 0.12, ease = snap) {
    to { leftHand(x = 140, y = 360, angle = -10, scale = 1.1); rightHand(x = 760, y = 160, angle = 55, scale = 1.15); rotation(-17) }
  }
  animate(dab, 0.4, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1.12, 0.89) }
    at(25, Easing.easeInOutSine) { squash(0.92, 1.09) }
    at(55, Easing.easeInOutSine) { squash(1.04, 0.97) }
    to { squash(1, 1) }
  }
  hatKick(dab, -1f, 1f)
  animate(dab + 0.12f, 0.9, ease = Easing.easeInOutSine) { to { rotation(-15.5); rightHand(angle = 52, y = 170) } }
  cue(7.56, "sparkle")
  actor(ActorKind.SPARKLE, anchorToPip = true, layer = 3) { x = 100f; y = 200f; scale = 0f }
    .animate(7.52, 0.55, ease = Easing.linear, fill = Fill.NONE) {
      at(0, Easing.outBack) { scale(0) }
      at(30, Easing.easeInSine) { scale(0.55) }
      to { scale(0) }
    }
  bubble("Deal with it.", 7.5f, 0.95f, dx = 40f)
  // Release, shades back up into the sky, and a wink to camera.
  animate(8.4, 0.22, ease = Easing.inBack) { to { leftHand(scale = 0); rightHand(scale = 0) } }
  animate(8.63, 0.01) { to { leftHand(Hand.NONE); rightHand(Hand.NONE) } }
  animate(8.4, 0.3, ease = Easing.easeInOutSine) { to { rotation(0) } }
  hatKick(8.4f, 1f, 0.5f)
  cue(8.52, "whoosh")
  animate(8.52, 0.26, ease = Easing.easeInSine) { from { sunglasses(1) }; to { sunglasses(0) } }
  animate(8.79, 0.01) { to { sunglasses(-1) } }
  animate(8.66, 0.3, ease = Easing.linear) {
    from(Easing.easeOutSine) { squint(0); rotation(0) }
    at(35, Easing.easeInOutSine) { squint(0.85); rotation(5); lidTop(0.3) }
    to { squint(0.2); rotation(0); lidTop(0) }
  }
}

// ============================================================================================ today

/** Door impacts in seconds from the start of `dToday`, with their strength (1 = a big one). */
private val THUDS = floatArrayOf(
  0.5f, 0.55f, 1.5f, 0.6f, 2.0f, 0.7f, 2.5f, 0.8f, 2.75f, 0.75f,
  3.0f, 1.0f, 3.25f, 0.8f, 3.5f, 1.0f, 3.75f, 0.85f, 4.0f, 1.15f,
)

/** How hard the door is ringing at [lt] (scene seconds): each impact spikes and decays fast. */
private fun doorImpulse(lt: Float): Float {
  var v = 0f
  var i = 0
  while (i < THUDS.size) {
    val age = lt - THUDS[i]
    if (age >= 0f) v += THUDS[i + 1] * exp(-age * 9f)
    i += 2
  }
  return v
}

/** Light leaking through the cracks: it builds as the door gives, flares with each hit and dies in the silence. */
private fun doorLight(lt: Float): Float =
  (0.12f + 0.8f * mSmooth(0.8f, 3.6f, lt) - 0.85f * mSmooth(4.1f, 4.5f, lt) + 0.45f * doorImpulse(lt)).coerceIn(0f, 1.3f)

private fun today() = scene("dToday", 9) {
  home()
  val w = W; val h = H; val t0 = start

  // ---------------------------------------------------------------- 1. HODOR (0 - 4.85 s)
  val doorCx = HX; val doorCy = 970f
  val corridor = custom(-3, w / 2, h / 2, style = { alpha = 0f }) { c, _, t -> paintCorridor(c, t, w, h, t0, doorCy + 380f - h / 2) }
  val door = custom(-2, doorCx, doorCy, style = { alpha = 0f }) { c, _, t -> paintDoor(c, t, t0) }
  val dust = custom(3, 0f, 0f) { c, _, t -> paintDust(c, t, t0, doorCx, doorCy - 400f) }
  corridor.animate(0, 0.35, ease = Easing.easeOutSine) { to { alpha(1) } }
  door.animate(0.05, 0.35, ease = Easing.easeOutSine) { to { alpha(1) } }
  banner("Today")

  // Innocent: whistling, eyes wandering. Then the first BANG.
  animate(0.05, 0.2, ease = Easing.easeOutSine) { to { mouth(Mouth.O); lidTop(0.15) } }
  animate(0.1, 0.1, ease = Easing.easeOutSine) { to { look(-0.75, -0.2) } }
  animate(0.18, 0.3, ease = Easing.easeInOutSine) { to { rotation(-2.5) } }
  animate(0.3, 0.1, ease = Easing.easeOutSine) { to { look(0.7, -0.3) } }
  animate(0.38, 0.12, ease = Easing.easeInOutSine) { to { rotation(1.5) } }

  // Thud 1: the jump scare.
  for (i in 0 until THUDS.size / 2) cue(THUDS[i * 2], "door_thud")
  animate(0.5, 0.06, ease = Easing.easeOutSine) {
    to { eyeScale(1.5); lidTop(0); brows(1, -0.6); look(0, -0.2) }
  }
  animate(0.5, 0.42, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(0.86, 1.17); y(HY); rotation(0) }
    at(28, Easing.easeInSine) { squash(0.97, 1.03); y(HY - 72) }
    at(62, Easing.easeOutSine) { squash(1.16, 0.86); y(HY) }
    at(80, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1); y(HY) }
  }
  hatKick(0.5f, -1f, 0.7f)
  animate(0.52, 0.14, ease = Easing.outBack) {
    from { leftHand(Hand.PALM, x = 0, y = 520, angle = -20, scale = 0); rightHand(Hand.PALM, x = 540, y = 520, angle = 20, scale = 0) }
    to { leftHand(x = -90, y = 380, angle = -25, scale = 0.9); rightHand(x = 630, y = 380, angle = 25, scale = 0.9) }
  }
  animate(0.55, 0.01) { to { mouth(Mouth.O) } }
  shake(0.5f, 0.3f, 12f, 11, listOf(corridor, door), pipAmp = 0.5f)
  // Double-take back at the door, a blink, and resolve sets in.
  animate(0.8, 0.1, ease = Easing.easeOutSine) { to { look(0.5, -1) } }
  animate(0.88, 0.2, ease = Easing.easeOutSine) { to { rotation(-4) } }
  blinkAt(0.98f)
  animate(1.02, 0.1) { to { eyeScale(1); brows(-0.4, 0.8); mouth(Mouth.FLAT); lidTop(0.12); look(0, 0) } }
  // Brace: a dip, then back flat against the door, palms slapped out wide.
  animate(1.0, 0.36, ease = Easing.linear) {
    from(Easing.easeInOutSine) { squash(1, 1); rotation(-4) }
    at(22, Easing.easeOutSine) { squash(1.08, 0.93); rotation(-1) }
    at(50, Easing.easeOutSine) { squash(1.15, 0.88); rotation(0) }
    to { squash(1.06, 0.95) }
  }
  animate(1.08, 0.14, ease = overshoot) { to { leftHand(x = -170, y = 300, angle = -35, scale = 1.1) } }
  animate(1.11, 0.14, ease = overshoot) { to { rightHand(x = 710, y = 300, angle = 35, scale = 1.1) } }
  cue(1.18, "land")

  // He loses ground with each blow: pushed toward us (bigger, lower), then heaves it shut in the silence.
  val pushKeys = floatArrayOf(
    1.5f, 0f, 1.6f, 9f, 1.95f, 6f, 2.08f, 14f, 2.45f, 11f, 2.58f, 19f, 2.72f, 17f, 2.84f, 24f,
    2.98f, 22f, 3.1f, 33f, 3.22f, 30f, 3.35f, 37f, 3.47f, 35f, 3.6f, 43f, 3.72f, 41f, 3.85f, 47f,
    3.98f, 45f, 4.12f, 58f, 4.45f, 58f, 4.8f, 0f,
  )
  animate(1.5, 3.3, ease = Easing.linear, composite = Composite.ADD) {
    var i = 0
    while (i < pushKeys.size) {
      val pct = (pushKeys[i] - 1.5f) / 3.3f * 100f
      val v = pushKeys[i + 1]
      val prevUp = i >= 2 && v > pushKeys[i - 1]
      at(pct, if (prevUp) Easing.easeInOutSine else snap) { size(v); y(v * 0.45f) }
      i += 2
    }
  }

  // Thud 2 onward: strain. The whole body trembles with effort, more as it escalates.
  shake(1.5f, 0.3f, 14f, 12, listOf(corridor, door))
  animate(1.5, 0.08, ease = Easing.easeOutSine) { to { eyesOpen(0.3); brows(-0.6, 1) } }
  animate(1.58, 0.12, ease = Easing.easeOutSine) { to { eyesOpen(1) } }
  bubble("Hold the door!", 1.55f, 0.9f, dy = -20f)
  animate(1.55, 0.01) { to { mouth(Mouth.SHOUT) } }
  animate(2.0, 0.08, ease = Easing.easeInOutSine, iterations = 26f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { rotation(-1.2); x(-2) }; to { rotation(1.2); x(2) }
  }
  animate(2.0, 0.2, ease = Easing.easeOutSine) { to { squint(0.7); eyesOpen(0.85); lidTop(0.25); brows(-0.65, 1) } }
  animate(2.42, 0.01) { to { mouth(Mouth.FLAT) } }
  shake(2.0f, 0.28f, 16f, 13, listOf(corridor, door))
  shake(2.5f, 0.25f, 18f, 14, listOf(corridor, door))
  shake(2.75f, 0.25f, 18f, 15, listOf(corridor, door))
  actor(ActorKind.SWEAT, anchorToPip = true, layer = 3) { x = -110f; y = 160f; alpha = 0f }
    .animate(2.15, 1.9, ease = Easing.linear) {
      from(Easing.outBack) { alpha(1); scale(0.3); y(160) }
      at(10, Easing.easeInSine) { scale(1) }
      at(90, Easing.easeInSine) { y(250); alpha(1) }
      to { y(262); alpha(0) }
    }

  // The words get shorter as the hits get harder.
  val shoutY = 520f
  val holdText = custom(4, w / 2, shoutY, text = "HOLD THE DOOR!", style = { alpha = 0f }) { c, a, t -> paintShout(c, a, t, SHOUT_WARM, 128f, 980f, 2.5f) }
  holdText.animate(2.45, 0.25, ease = Easing.linear) { from { alpha(1); progress(0) }; to { alpha(1); progress(1) } }
  holdText.animate(2.75, 0.01) { to { text("HOLD DOOR!") } }
  holdText.animate(2.75, 0.2, ease = Easing.linear) {
    from(Easing.easeOutSine) { scale(1.18) }
    to { scale(1) }
  }
  holdText.animate(2.99, 0.01) { to { alpha(0) } }
  val hodor = custom(4, w / 2, shoutY, text = "HODOR!", style = { alpha = 0f; progress = 1f }) { c, a, t -> paintShout(c, a, t, SHOUT_HOT, 240f, 1000f, 5f) }
  hodor.animate(3.0, 1.15, ease = Easing.linear) {
    at(0, Easing.outBack) { alpha(1); scale(0.35) }
    at(13, Easing.easeOutSine) { scale(1.12) }
    at(21.7, Easing.easeOutSine) { scale(1.0) }
    at(43.5, Easing.easeOutSine) { scale(1.22) }
    at(56, Easing.easeOutSine) { scale(1.04) }
    at(65, Easing.easeOutSine) { scale(1.12) }
    at(78, Easing.easeOutSine) { scale(1.02) }
    at(87, Easing.easeOutSine) { scale(1.32) }
    to { alpha(1); scale(1.18) }
  }
  hodor.animate(4.15, 0.25, ease = Easing.easeInSine) { to { alpha(0); scale(1.3) } }
  cue(3.0, "boom")
  val shaken = listOf(corridor, door, hodor)
  shake(3.0f, 0.3f, 28f, 16, shaken)
  shake(3.25f, 0.25f, 20f, 17, shaken)
  shake(3.5f, 0.25f, 28f, 18, shaken)
  shake(3.75f, 0.25f, 22f, 19, shaken)
  shake(4.0f, 0.45f, 34f, 20, shaken)
  animate(3.0, 0.08, ease = Easing.easeInOutSine, iterations = 14f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { rotation(-1.6) }; to { rotation(1.6) }
  }

  // HODOR: eyes screwed shut, head thrown up in a full-body shout; alternating with beady, strained glances.
  fun bellow(at0: Float, big: Float) = animate(at0, 0.24, ease = Easing.linear) {
    from(Easing.easeOutSine) { squash(1.06, 0.95) }
    at(25, Easing.easeInOutSine) { squash(0.88 - 0.03f * big, 1.13 + 0.04f * big) }
    to { squash(0.94, 1.07) }
  }
  animate(2.97, 0.05, ease = Easing.easeInSine) { to { eyesOpen(0.08); mouth(Mouth.SHOUT); brows(-0.9, 1); squint(0.3) } }
  bellow(3.0f, 1f)
  hatKick(3.0f, -1f, 1f)
  animate(3.25, 0.06, ease = Easing.easeOutSine) { to { eyesOpen(0.75); eyeScale(0.8); squint(0.75) } }
  animate(3.47, 0.05, ease = Easing.easeInSine) { to { eyesOpen(0.08); squint(0.3) } }
  bellow(3.5f, 1f)
  hatKick(3.5f, 1f, 1f)
  animate(3.75, 0.06, ease = Easing.easeOutSine) { to { eyesOpen(0.75); squint(0.75) } }
  animate(3.97, 0.05, ease = Easing.easeInSine) { to { eyesOpen(0.08); squint(0.3) } }
  bellow(4.0f, 1.6f)
  hatKick(4.0f, -1f, 1.1f)

  // Silence. Eyes creep open, dart, and only then the long exhale.
  animate(4.18, 0.16, ease = Easing.easeOutSine) { to { eyesOpen(1); eyeScale(1.15); squint(0); mouth(Mouth.FLAT); brows(0.4, -0.7) } }
  animate(4.24, 0.3, ease = Easing.easeInOutSine) { to { squash(1.04, 0.97) } }
  animate(4.3, 0.05, ease = Easing.easeOutSine) { to { look(-0.9, 0) } }
  animate(4.42, 0.05, ease = Easing.easeOutSine) { to { look(0.9, 0) } }
  animate(4.52, 0.08, ease = Easing.easeOutSine) { to { look(0, 0) } }
  cue(4.56, "sigh")
  animate(4.56, 0.12, ease = Easing.easeInSine) { to { eyesOpen(0.1) } }
  animate(4.6, 0.01) { to { brows(show = false); eyeScale(1); mouth(Mouth.O) } }
  animate(4.56, 0.3, ease = Easing.easeOutSine) { to { squash(1.12, 0.89); hatDroop(0.35) } }
  animate(4.56, 0.22, ease = Easing.easeInSine) { to { leftHand(y = 470, scale = 0); rightHand(y = 470, scale = 0) } }

  // ---------------------------------------------------------------- poof to the playground (4.8 - 5.05 s)
  poofAt(4.8f, HX, HY - 190f, 1.9f)
  for (a in listOf(corridor, door, dust)) a.animate(4.84, 0.16, ease = Easing.easeInSine) { to { alpha(0) } }
  val yard = custom(-3, w / 2, h / 2, style = { alpha = 0f }) { c, a, t -> paintYard(c, a, t, w, h) }
  val dollY = 640f
  val doll = custom(-2, HX, dollY, style = { alpha = 0f }) { c, a, t -> paintDoll(c, a, t) }
  yard.animate(4.86, 0.18, ease = Easing.easeOutSine) { to { alpha(1) } }
  doll.animate(4.9, 0.2, ease = Easing.easeOutSine) { to { alpha(1) } }
  animate(4.9, 0.01) {
    to {
      x(300); y(HY); squash(1, 1); rotation(0); hatDroop(0); eyesOpen(1); eyeScale(1); squint(0)
      leftHand(Hand.NONE); rightHand(Hand.NONE); mouth(Mouth.NONE); brows(show = false)
    }
  }

  // ---------------------------------------------------------------- 2. Red light, green light (4.9 - 9 s)
  cue(5.0, "ding")
  val green = custom(4, w / 2, 470f, text = "Green light!", style = { alpha = 0f }) { c, a, t -> paintShout(c, a, t, SHOUT_GREEN, 120f, 900f, 0f) }
  green.animate(5.0, 0.3, ease = Easing.linear) { from { alpha(1); progress(0) }; to { alpha(1); progress(1) } }
  green.animate(5.72, 0.18, ease = Easing.easeInSine) { to { alpha(0) } }
  // Sly sneak: low lids, eyes on the doll, hands up on tiptoe.
  animate(5.0, 0.2, ease = Easing.easeOutSine) {
    to { lidTop(0.45); look(0.35, -0.75); brows(-0.25, 0.4); mouth(Mouth.SMILE) }
  }
  animate(5.02, 0.2, ease = Easing.outBack) {
    from { leftHand(Hand.PALM, x = 40, y = 520, angle = -20, scale = 0); rightHand(Hand.PALM, x = 520, y = 520, angle = 20, scale = 0) }
    to { leftHand(x = -40, y = 420, angle = -30, scale = 0.85); rightHand(x = 600, y = 420, angle = 30, scale = 0.85) }
  }
  fun tiptoe(at0: Float, fromX: Float, toX: Float) {
    animate(at0, 0.28, ease = Easing.linear) {
      from(Easing.easeOutSine) { squash(1, 1); rotation(0); x(fromX); y(HY) }
      at(45, Easing.easeInSine) { squash(0.92, 1.09); rotation(6); y(HY - 30) }
      at(85, Easing.easeOutSine) { squash(1.05, 0.96); rotation(1); x(toX); y(HY) }
      to { squash(1, 1); rotation(0); x(toX); y(HY) }
    }
    animate(at0 + 0.04f, 0.28, ease = Easing.easeInOutSine, direction = Direction.NORMAL) {
      from { leftHand(y = 420) }; at(45) { leftHand(y = 400); rightHand(y = 400) }; to { leftHand(y = 420); rightHand(y = 420) }
    }
  }
  tiptoe(5.1f, 300f, 340f)
  tiptoe(5.4f, 340f, 380f)
  tiptoe(5.68f, 380f, 420f)

  // Caught mid-step. FREEZE.
  val turn = 5.95f
  animate(turn - 0.03f, 0.13, ease = Easing.easeOutSine) { to { squash(0.93, 1.08); rotation(8); y(HY - 32); x(445) } }
  doll.animate(turn, 0.3, ease = Easing.linear) {
    from(overshoot) { progress(0) }
    to { progress(1) }
  }
  yard.animate(turn, 0.16, ease = Easing.easeOutSine) { to { progress(1) } }
  cue(turn, "sting")
  val red = custom(4, w / 2, 470f, text = "Red light!", style = { alpha = 0f }) { c, a, t -> paintShout(c, a, t, SHOUT_RED, 130f, 900f, 1.5f) }
  red.animate(turn + 0.03f, 0.22, ease = Easing.linear) { from { alpha(1); progress(0) }; to { alpha(1); progress(1) } }
  red.animate(6.85, 0.2, ease = Easing.easeInSine) { to { alpha(0) } }
  animate(turn + 0.08f, 0.05, ease = Easing.easeOutSine) {
    to { eyeScale(1.32); lidTop(0); brows(0.6, -1); mouth(Mouth.FLAT); life(0); look(0.3, -0.7) }
  }
  animate(turn + 0.15f, 0.11, ease = Easing.easeInOutSine, iterations = 11f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { rotation(-1.1) }; to { rotation(1.1) }
  }
  animate(6.35, 0.05, ease = Easing.easeOutSine) { to { look(-0.95, 0.1) } }
  animate(6.6, 0.05, ease = Easing.easeOutSine) { to { look(0.95, 0.1) } }
  animate(6.86, 0.06, ease = Easing.easeOutSine) { to { look(0.3, -0.75) } }
  cue(6.4, "dundun")
  actor(ActorKind.SWEAT, anchorToPip = true, layer = 3) { x = 125f; y = 140f; alpha = 0f }
    .animate(6.45, 0.95, ease = Easing.linear) {
      from(Easing.outBack) { alpha(1); scale(0.3); y(140) }
      at(18, Easing.easeInSine) { scale(1) }
      at(85, Easing.easeInSine) { y(220); alpha(1) }
      to { y(230); alpha(0) }
    }

  // The doll turns away. Release: foot down, a long relieved exhale with eyes shut.
  doll.animate(7.3, 0.3, ease = Easing.easeInOutSine) { to { progress(0) } }
  yard.animate(7.3, 0.3, ease = Easing.easeInOutSine) { to { progress(0) } }
  cue(7.3, "whoosh")
  cue(7.48, "sigh")
  animate(7.45, 0.08, ease = Easing.easeInSine) { to { eyesOpen(0.1); life(1) } }
  animate(7.5, 0.01) { to { brows(show = false); eyeScale(1); mouth(Mouth.O) } }
  animate(7.45, 0.7, ease = Easing.linear) {
    from(Easing.easeInSine) { squash(0.93, 1.08); rotation(8); y(HY - 32); x(445) }
    at(20, Easing.easeOutSine) { squash(1.18, 0.86); rotation(0); y(HY); x(430) }
    at(55, Easing.easeInOutSine) { squash(1.07, 0.94); x(430) }
    to { squash(1.02, 0.98); rotation(0); y(HY); x(430) }
  }
  animate(7.5, 0.25, ease = Easing.easeOutSine) { to { hatDroop(0.4) } }
  animate(7.5, 0.18, ease = Easing.inBack) { to { leftHand(scale = 0); rightHand(scale = 0) } }
  animate(7.7, 0.01) { to { leftHand(Hand.NONE); rightHand(Hand.NONE) } }
  bubble("Phew…", 7.6f, 0.8f)
  animate(7.88, 0.14, ease = Easing.easeOutSine) { to { eyesOpen(1); mouth(Mouth.SMILE) } }
  animate(7.95, 0.35, ease = Easing.easeInOutSine) { to { hatDroop(0) } }
  // A cheeky look to camera, then a hop home.
  animate(8.02, 0.12, ease = Easing.easeOutSine) { to { look(0, 0); squint(0.5); lidTop(0.2) } }
  hop(8.2f, 430f, HX, height = 64f, dur = 0.42f, cues = true)
  yard.animate(8.45, 0.45, ease = Easing.easeInOutSine) { to { alpha(0) } }
  doll.animate(8.4, 0.4, ease = Easing.easeInOutSine) { to { alpha(0) } }
  animate(8.7, 0.25, ease = Easing.easeOutSine) { to { squint(0.3); lidTop(0) } }
}

// ============================================================================================ painting kit

private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
private val shoutFace: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
private val pathA = Path()
private val pathB = Path()
private val rectA = RectF()
private val shaderCache = HashMap<String, Shader>()
private val glowCache = HashMap<Int, Shader>()

private const val INK_C = 0xFF3A2A26.toInt()
private const val SKIN_C = 0xFFFDE3D2.toInt()
private const val SKIN_LINE = 0xFFE7B9A3.toInt()
private const val HAT_C = 0xFFF05248.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val SHOUT_GOLD = 0xFFFFC93C.toInt()
private const val SHOUT_WARM = 0xFFFFE3A3.toInt()
private const val SHOUT_HOT = 0xFFFF8A3D.toInt()
private const val SHOUT_GREEN = 0xFF5BD873.toInt()
private const val SHOUT_RED = 0xFFFF5147.toInt()

private fun m01(v: Float) = v.coerceIn(0f, 1f)
private fun mLerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun mFract(v: Float) = v - floor(v)
private fun mHash(i: Int): Float { val v = sin(i * 12.9898f + 78.233f) * 43758.547f; return v - floor(v) }
private fun mSmooth(e0: Float, e1: Float, x: Float): Float { val t = m01((x - e0) / (e1 - e0)); return t * t * (3f - 2f * t) }
private fun mBack(t: Float): Float { val x = m01(t) - 1f; return 1f + x * x * (2.70158f * x + 1.70158f) }
private fun mAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or (((color ushr 24) * m01(a)).toInt() shl 24)
private fun mMix(a: Int, b: Int, t: Float): Int {
  val u = m01(t)
  fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u).toInt() shl s
  return ch(24) or ch(16) or ch(8) or ch(0)
}

private fun solid(color: Int, alpha: Float = 1f): Paint { fillP.shader = null; fillP.color = mAlpha(color, alpha); return fillP }
private fun line(color: Int, width: Float, alpha: Float = 1f): Paint {
  strokeP.shader = null; strokeP.color = mAlpha(color, alpha); strokeP.strokeWidth = width; return strokeP
}
private fun shaded(key: String, alpha: Float = 1f, make: () -> Shader): Paint {
  fillP.shader = shaderCache.getOrPut(key, make); fillP.color = mAlpha(WHITE, alpha); return fillP
}
private fun vGrad(y0: Float, y1: Float, colors: IntArray, stops: FloatArray? = null): Shader =
  LinearGradient(0f, y0, 0f, y1, colors, stops, Shader.TileMode.CLAMP)
private fun hGrad(x0: Float, x1: Float, colors: IntArray, stops: FloatArray? = null): Shader =
  LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)

/** A soft radial glow of radius [r]; one cached unit gradient per colour, scaled into place. */
private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
  if (alpha <= 0.004f || r <= 0.5f) return
  fillP.shader = glowCache.getOrPut(color) {
    RadialGradient(0f, 0f, 1f, intArrayOf(color, mAlpha(color, 0.4f), mAlpha(color, 0f)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
  }
  fillP.color = mAlpha(WHITE, alpha)
  c.save(); c.translate(x, y); c.scale(r, r); c.drawCircle(0f, 0f, 1f, fillP); c.restore()
  fillP.shader = null
}

/** A four-point twinkle star. */
private fun star(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float = 1f) {
  if (r <= 0.5f) return
  val n = r * 0.2f
  pathB.reset()
  pathB.moveTo(x, y - r); pathB.quadTo(x + n, y - n, x + r, y); pathB.quadTo(x + n, y + n, x, y + r)
  pathB.quadTo(x - n, y + n, x - r, y); pathB.quadTo(x - n, y - n, x, y - r); pathB.close()
  c.drawPath(pathB, solid(color, alpha))
}

// ============================================================================================ shared painters

private fun paintWhip(c: Canvas, a: ActorState, w: Float, h: Float) {
  val p = a.progress
  if (p <= 0f || p >= 1f) return
  val env = sin(p * PI_F)
  val hw = w / 2; val hh = h / 2
  c.drawRect(-hw, -hh, hw, hh, solid(0xFFF6EFE8.toInt(), 0.22f * env))
  for (i in 0 until 38) {
    val y = -hh + h * mHash(i + 3)
    val len = 260f + 640f * mHash(i + 11)
    val speed = 1.2f + mHash(i + 19)
    val x = hw + 500f - (w + 1400f) * mFract(p * speed + mHash(i))
    val width = 4f + 18f * mHash(i + 5)
    val col = if (i % 3 == 0) INK_C else WHITE
    val al = if (i % 3 == 0) 0.22f else 0.6f
    c.drawLine(x, y, x + len, y, line(col, width, al * env))
  }
}

/**
 * Big comic-book shout: chunky italic letters with an ink outline and drop shadow. [ActorState.progress] reveals the
 * letters one by one with a pop; at 1 the whole word shows. [jitter] shakes each letter on its own.
 */
private fun paintShout(c: Canvas, a: ActorState, t: Float, fill: Int, baseSize: Float, maxWidth: Float, jitter: Float) {
  val s = a.text
  if (s.isEmpty()) return
  textP.shader = null
  textP.typeface = shoutFace; textP.textAlign = Paint.Align.LEFT; textP.textSize = baseSize
  var total = textP.measureText(s)
  if (total > maxWidth) { textP.textSize = baseSize * maxWidth / total; total = maxWidth }
  val size = textP.textSize
  val reveal = m01(a.progress) * s.length
  val base = size * 0.36f
  val hi = mMix(fill, WHITE, 0.55f)
  var x = -total / 2
  for (i in s.indices) {
    val adv = textP.measureText(s, i, i + 1)
    val vis = if (a.progress >= 1f) 9f else reveal - i
    if (vis > 0f && s[i] != ' ') {
      val sc = 0.25f + 0.75f * mBack(vis / 1.4f)
      val jx = jitter * sin(t * 43f + i * 2.1f)
      val jy = jitter * cos(t * 37f + i * 1.3f)
      c.save()
      c.translate(x + adv / 2 + jx, jy)
      c.rotate(sin(i * 1.7f) * 4f)
      c.scale(sc, sc)
      textP.style = Paint.Style.FILL; textP.color = 0x66000000
      c.drawText(s, i, i + 1, -adv / 2 + size * 0.04f, base + size * 0.08f, textP)
      textP.style = Paint.Style.STROKE; textP.strokeWidth = size * 0.15f; textP.color = INK_C
      c.drawText(s, i, i + 1, -adv / 2, base, textP)
      textP.style = Paint.Style.FILL; textP.color = fill
      c.drawText(s, i, i + 1, -adv / 2, base, textP)
      // A lighter top half, clipped, for a glossy print look.
      c.save(); c.clipRect(-adv, -size, adv, -size * 0.05f)
      textP.color = hi
      c.drawText(s, i, i + 1, -adv / 2, base, textP)
      c.restore()
      c.restore()
    }
    x += adv
  }
}

/** A cartoon flame rising from (x, base): red outside, orange, then a yellow core, flickering on its own clock. */
private fun flame(c: Canvas, x: Float, base: Float, height: Float, width: Float, t: Float, seed: Int, glowIt: Boolean = true) {
  if (height < 4f) return
  val fl = 0.86f + 0.1f * sin(t * 11f + seed * 2.3f) + 0.06f * sin(t * 23f + seed * 0.7f)
  val hgt = height * fl
  val sway = sin(t * 6.5f + seed * 1.7f) * width * 0.22f
  if (glowIt) glow(c, x, base - hgt * 0.4f, hgt * 0.95f, 0xFFFF7A1E.toInt(), 0.3f)
  flameShape(c, x, base, hgt, width, sway, 0xFFE8432A.toInt())
  flameShape(c, x, base, hgt * 0.72f, width * 0.68f, sway * 0.8f, 0xFFFF8F2B.toInt())
  flameShape(c, x, base, hgt * 0.44f, width * 0.4f, sway * 0.6f, 0xFFFFE066.toInt())
}

private fun flameShape(c: Canvas, x: Float, base: Float, h: Float, w: Float, sway: Float, color: Int) {
  pathA.reset()
  pathA.moveTo(x - w / 2, base)
  pathA.cubicTo(x - w * 0.62f, base - h * 0.45f, x - w * 0.1f + sway * 0.6f, base - h * 0.7f, x + sway, base - h)
  pathA.cubicTo(x + w * 0.12f + sway * 0.5f, base - h * 0.62f, x + w * 0.62f, base - h * 0.42f, x + w / 2, base)
  pathA.quadTo(x, base + w * 0.3f, x - w / 2, base)
  pathA.close()
  c.drawPath(pathA, solid(color))
}

// ============================================================================================ 2000s painters

private fun paintCavern(c: Canvas, t: Float, w: Float, h: Float) {
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  val deck = HY - h / 2
  c.drawRect(-hw, -hh, hw, hh, shaded("cav.sky") {
    vGrad(-h / 2, h / 2, intArrayOf(0xFF0B0910.toInt(), 0xFF191320.toInt(), 0xFF2B1411.toInt(), 0xFF6A250C.toInt()), floatArrayOf(0f, 0.42f, 0.66f, 1f))
  })
  val fl = 0.85f + 0.1f * sin(t * 5.3f) + 0.05f * sin(t * 11.7f)
  // Distant pillars fading into the dark.
  for (i in 0 until 5) {
    val px = -w / 2 + w * (0.1f + 0.2f * i) + (mHash(i) - 0.5f) * 60f
    val pw = 56f + 40f * mHash(i + 7)
    rectA.set(px - pw / 2, -hh, px + pw / 2, deck + 500f)
    c.drawRect(rectA, solid(0xFF231A26.toInt(), 0.5f + 0.3f * mHash(i + 3)))
    rectA.set(px - pw / 2 - 10f, deck - 760f - 200f * mHash(i + 2), px + pw / 2 + 10f, deck - 730f - 200f * mHash(i + 2))
    c.drawRect(rectA, solid(0xFF2A202D.toInt(), 0.6f))
  }
  // The fiery abyss below.
  glow(c, 0f, deck + 560f, w * 0.95f, 0xFFFF6A1A.toInt(), 0.6f * fl)
  glow(c, -w * 0.22f, deck + 800f, w * 0.55f, 0xFFFFB347.toInt(), 0.4f * fl)
  glow(c, w * 0.3f, deck + 720f, w * 0.45f, 0xFFFF3D12.toInt(), 0.35f * fl)
  // Jagged cliff walls on both sides.
  for (side in intArrayOf(-1, 1)) {
    pathA.reset()
    val edge = side * (hw + 10f)
    pathA.moveTo(edge, -h * 0.18f)
    for (k in 0..12) {
      val y = mLerp(-h * 0.18f, hh, k / 12f)
      val ledge = if (y > deck - 30f && y < deck + 120f) 70f else 0f
      pathA.lineTo(side * (w / 2 - 50f - 70f * mHash(k * 3 + side + 20) - ledge), y)
    }
    pathA.lineTo(edge, hh); pathA.close()
    c.drawPath(pathA, solid(0xFF120D12.toInt()))
  }
  // Embers drifting up out of the abyss.
  for (i in 0 until 28) {
    val sp = 0.07f + 0.08f * mHash(i + 50)
    val life = mFract(t * sp + mHash(i + 9))
    val ex = -w / 2 + w * mHash(i) + sin(t * 1.7f + i) * 18f
    val ey = h / 2 - life * h * 0.95f
    val ea = sin(life * PI_F) * (0.5f + 0.5f * mHash(i + 77))
    val r = 2.5f + 3.5f * mHash(i + 31)
    glow(c, ex, ey, r * 5f, 0xFFFF8A2A.toInt(), ea * 0.55f)
    c.drawCircle(ex, ey, r, solid(0xFFFFD27A.toInt(), ea))
  }
  // The narrow stone bridge: thick at the ends, a thin arch in the middle, lit from below.
  val th = 44f
  pathA.reset()
  pathA.moveTo(-hw, deck); pathA.lineTo(hw, deck); pathA.lineTo(hw, deck + th + 80f)
  pathA.quadTo(0f, deck + th - 80f, -hw, deck + th + 80f); pathA.close()
  c.drawPath(pathA, shaded("cav.bridge") {
    vGrad(deck, deck + th + 80f, intArrayOf(0xFF8E8996.toInt(), 0xFF5A5564.toInt(), 0xFF2E2A33.toInt()), floatArrayOf(0f, 0.35f, 1f))
  })
  pathB.reset(); pathB.moveTo(hw, deck + th + 80f); pathB.quadTo(0f, deck + th - 80f, -hw, deck + th + 80f)
  c.drawPath(pathB, line(0xFFFF8A3D.toInt(), 7f, 0.55f * fl))
  var bx = -hw + 20f
  var k = 0
  while (bx < hw) {
    val u = bx / hw
    val under = deck + th + 80f * u * u
    c.drawLine(bx, deck + 4f, bx + 3f, under - 4f, line(0xFF2F2B36.toInt(), 3f, 0.7f))
    if (k % 2 == 0) c.drawLine(bx + 6f, deck + 22f, bx + 76f, deck + 22f, line(0xFF2F2B36.toInt(), 2f, 0.4f))
    bx += 72f + 22f * mHash(k + 40); k++
  }
  c.drawLine(-hw, deck + 2f, hw, deck + 2f, line(0xFFC9C4D2.toInt(), 4f, 0.7f))
}

/** Grey pointed wizard hat, origin at the centre of its brim. */
private fun paintWizardHat(c: Canvas, a: ActorState, t: Float) {
  val wob = sin(t * 3.3f) * 5f + a.rotation * 1.5f
  rectA.set(-168f, -4f, 168f, 40f); c.drawOval(rectA, solid(0xFF000000.toInt(), 0.16f))
  rectA.set(-182f, -22f, 182f, 30f); c.drawOval(rectA, solid(0xFF5C6169.toInt()))
  rectA.set(-182f, -30f, 182f, 18f)
  c.drawOval(rectA, shaded("wiz.brim") { hGrad(-182f, 182f, intArrayOf(0xFFB6BAC1.toInt(), 0xFF969BA3.toInt(), 0xFF6F747C.toInt()), floatArrayOf(0f, 0.5f, 1f)) })
  pathA.reset()
  pathA.moveTo(-108f, -6f)
  pathA.cubicTo(-86f, -90f, -40f, -190f, 8f + wob * 0.5f, -262f)
  pathA.quadTo(40f + wob, -306f, 88f + wob * 1.6f, -292f)
  pathA.quadTo(52f + wob, -266f, 44f + wob * 0.3f, -204f)
  pathA.cubicTo(58f, -124f, 88f, -62f, 110f, -6f)
  pathA.quadTo(0f, 12f, -108f, -6f)
  pathA.close()
  c.drawPath(pathA, shaded("wiz.cone") { hGrad(-110f, 110f, intArrayOf(0xFFC2C6CD.toInt(), 0xFF9DA2AA.toInt(), 0xFF6C717A.toInt()), floatArrayOf(0f, 0.45f, 1f)) })
  pathB.reset(); pathB.moveTo(-24f, -34f); pathB.quadTo(-12f, -150f, 16f + wob * 0.4f, -236f)
  c.drawPath(pathB, line(0xFF5E636B.toInt(), 4f, 0.35f))
  pathB.reset(); pathB.moveTo(-104f, -12f); pathB.quadTo(0f, 8f, 106f, -12f); pathB.lineTo(99f, -44f)
  pathB.quadTo(0f, -26f, -97f, -44f); pathB.close()
  c.drawPath(pathB, solid(0xFF4B4656.toInt()))
  pathB.reset(); pathB.moveTo(-97f, -44f); pathB.quadTo(0f, -26f, 99f, -44f)
  c.drawPath(pathB, line(0xFF8A8597.toInt(), 3f, 0.6f))
}

/** A gnarled wooden staff with a glowing crystal; origin at the fist that holds it, foot 117 px below. */
private fun paintStaff(c: Canvas, a: ActorState, t: Float) {
  val g = a.progress
  pathA.reset()
  pathA.moveTo(-8f, 117f); pathA.cubicTo(-13f, 20f, -3f, -150f, -10f, -300f); pathA.lineTo(8f, -300f)
  pathA.cubicTo(13f, -150f, 4f, 20f, 8f, 117f); pathA.close()
  c.drawPath(pathA, shaded("staff.wood") { hGrad(-12f, 12f, intArrayOf(0xFFA87648.toInt(), 0xFF7A4E2C.toInt(), 0xFF573519.toInt())) })
  rectA.set(-9f, -92f, 3f, -70f); c.drawOval(rectA, solid(0xFF4A2D16.toInt(), 0.7f))
  rectA.set(-2f, -222f, 8f, -204f); c.drawOval(rectA, solid(0xFF4A2D16.toInt(), 0.7f))
  // The crook curling around the crystal.
  pathB.reset(); pathB.moveTo(0f, -298f); pathB.cubicTo(-40f, -330f, -36f, -404f, 10f, -404f)
  pathB.quadTo(46f, -400f, 40f, -362f)
  c.drawPath(pathB, line(0xFF6B4324.toInt(), 17f))
  c.drawPath(pathB, line(0xFFB4834F.toInt(), 5f, 0.6f))
  val pulse = 0.85f + 0.15f * sin(t * 9f)
  glow(c, 6f, -360f, 60f + 120f * g, 0xFFBFE8FF.toInt(), (0.35f + 0.6f * g) * pulse)
  pathB.reset(); pathB.moveTo(6f, -392f); pathB.lineTo(22f, -362f); pathB.lineTo(6f, -334f); pathB.lineTo(-10f, -362f); pathB.close()
  c.drawPath(pathB, shaded("staff.gem") { vGrad(-392f, -334f, intArrayOf(WHITE, 0xFFA8DCFF.toInt(), 0xFF5AA7E6.toInt())) })
  // The fist.
  c.drawCircle(0f, 0f, 21f, solid(SKIN_C)); c.drawCircle(0f, 0f, 21f, line(SKIN_LINE, 3.5f))
  c.drawLine(-9f, -8f, -9f, 6f, line(SKIN_LINE, 2.5f)); c.drawLine(1f, -10f, 1f, 6f, line(SKIN_LINE, 2.5f))
}

/** The staff's impact: a white-blue flash, rays, and a shock ring running along the bridge. */
private fun paintBurst(c: Canvas, a: ActorState) {
  val p = a.progress
  if (p <= 0f || p >= 1f) return
  val e = 1f - (1f - p) * (1f - p) * (1f - p)
  val fade = 1f - p
  c.drawRect(-2600f, -2600f, 2600f, 2600f, solid(0xFFD8ECFF.toInt(), 0.5f * fade * fade * fade))
  glow(c, 0f, -30f, 160f + 900f * e, 0xFFE6F3FF.toInt(), 0.95f * fade * fade)
  for (i in 0 until 15) {
    val ang = (-170f + i * (160f / 14f) + p * 10f) * PI_F / 180f
    val len = (140f + 720f * e) * (0.55f + 0.45f * mHash(i + 3))
    val wd = 9f + 8f * mHash(i)
    val ca = cos(ang); val sa = sin(ang)
    pathA.reset(); pathA.moveTo(-sa * wd, ca * wd); pathA.lineTo(ca * len, sa * len); pathA.lineTo(sa * wd, -ca * wd); pathA.close()
    c.drawPath(pathA, solid(WHITE, 0.6f * fade))
  }
  val rx = 60f + 820f * e
  rectA.set(-rx, -rx * 0.14f, rx, rx * 0.14f)
  c.drawOval(rectA, line(0xFFCFE6FF.toInt(), 3f + 14f * fade, fade))
  c.drawCircle(0f, -10f, 54f * fade, solid(WHITE, fade))
}

/** Dusk plain with a rocky cave on the right full of glowing eyes. [ActorState.progress] is the cave's alarm. */
private fun paintField(c: Canvas, a: ActorState, t: Float, w: Float, h: Float, cx: Float) {
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  val deck = HY - h / 2
  c.drawRect(-hw, -hh, hw, deck, shaded("field.sky") {
    vGrad(-h / 2, deck, intArrayOf(0xFF2E2350.toInt(), 0xFF7A4B78.toInt(), 0xFFE58B63.toInt(), 0xFFF8C77E.toInt()), floatArrayOf(0f, 0.38f, 0.78f, 1f))
  })
  glow(c, -220f, deck - 300f, 560f, 0xFFFFD9A0.toInt(), 0.75f)
  c.drawCircle(-220f, deck - 300f, 105f, solid(0xFFFFEBC6.toInt()))
  for (layer in 0..1) {
    pathA.reset(); pathA.moveTo(-hw, deck)
    for (k in 0..9) {
      val x = -hw + (2f * hw) * k / 9f
      val y = deck - (if (layer == 0) 300f else 140f) - (if (layer == 0) 230f else 150f) * mHash(k + 40 + layer * 17)
      pathA.lineTo(x, y)
    }
    pathA.lineTo(hw, deck); pathA.close()
    c.drawPath(pathA, solid(if (layer == 0) 0xFF8A5A86.toInt() else 0xFF5B3A63.toInt(), if (layer == 0) 0.8f else 1f))
  }
  c.drawRect(-hw, deck, hw, hh, shaded("field.ground") { vGrad(deck, h / 2, intArrayOf(0xFF7C9A45.toInt(), 0xFF3F5A26.toInt())) })
  c.drawRect(-hw, deck - 6f, hw, deck + 40f, solid(0xFFC9A46E.toInt()))
  c.drawLine(-hw, deck - 5f, hw, deck - 5f, line(0xFFE6C690.toInt(), 4f))
  for (i in 0 until 26) {
    val gx = -w / 2 + w * mHash(i + 200); val gy = deck + 70f + (h / 2 - deck - 90f) * mHash(i + 230)
    c.drawLine(gx, gy, gx - 6f, gy - 18f, line(0xFF9DBB5C.toInt(), 3f, 0.7f)); c.drawLine(gx, gy, gx + 7f, gy - 15f, line(0xFF9DBB5C.toInt(), 3f, 0.7f))
  }
  // Rock mound.
  pathA.reset()
  pathA.moveTo(cx - 280f, deck + 10f)
  pathA.cubicTo(cx - 270f, deck - 260f, cx - 130f, deck - 440f, cx + 20f, deck - 450f)
  pathA.cubicTo(cx + 170f, deck - 460f, hw + 40f, deck - 380f, hw + 40f, deck - 200f)
  pathA.lineTo(hw + 40f, deck + 10f); pathA.close()
  c.drawPath(pathA, shaded("field.rock") { vGrad(deck - 460f, deck, intArrayOf(0xFF7A6E72.toInt(), 0xFF4A4045.toInt())) })
  pathB.reset(); pathB.moveTo(cx - 262f, deck - 140f); pathB.cubicTo(cx - 230f, deck - 330f, cx - 100f, deck - 430f, cx + 20f, deck - 438f)
  c.drawPath(pathB, line(0xFF9C8F90.toInt(), 6f, 0.6f))
  c.drawLine(cx + 160f, deck - 380f, cx + 120f, deck - 250f, line(0xFF3A3236.toInt(), 4f, 0.6f))
  c.drawLine(cx - 200f, deck - 160f, cx - 160f, deck - 60f, line(0xFF3A3236.toInt(), 4f, 0.6f))
  // The mouth, and what waits inside.
  pathB.reset(); pathB.moveTo(cx - 155f, deck + 8f); pathB.cubicTo(cx - 155f, deck - 220f, cx + 155f, deck - 220f, cx + 155f, deck + 8f); pathB.close()
  c.drawPath(pathB, solid(0xFF0E090C.toInt()))
  glow(c, cx, deck - 40f, 140f, 0xFF4A2340.toInt(), 0.5f)
  val alarm = m01(a.progress)
  for (j in 0 until 7) {
    if (mFract(t * 0.6f + mHash(j)) < 0.05f) continue
    val ex = cx + (mHash(j + 60) - 0.5f) * 190f
    val ey = deck - 30f - 100f * mHash(j + 70)
    val r = 6f + 5f * alarm
    val col = mMix(0xFFFFD54A.toInt(), 0xFFFF4A3A.toInt(), alarm)
    glow(c, ex, ey, 30f, col, 0.35f)
    rectA.set(ex - 10f - r * 0.6f, ey - r, ex - 10f + r * 0.6f, ey + r); c.drawOval(rectA, solid(col))
    rectA.set(ex + 10f - r * 0.6f, ey - r, ex + 10f + r * 0.6f, ey + r); c.drawOval(rectA, solid(col))
  }
}

/** Puffs kicked up behind the charge; each appears where Pip was when it spawned and fades on its own clock. */
private fun paintDustTrail(c: Canvas, t: Float, runStart: Float, runDur: Float, fromX: Float, toX: Float) {
  val n = 16
  for (i in 0 until n) {
    val u = i / (n - 1f)
    val age = t - (runStart + runDur * u)
    if (age < 0f || age > 0.75f) continue
    val k = age / 0.75f
    val px = mLerp(fromX, toX, charge(u)) - 40f - age * 60f
    val py = HY - 14f - age * 70f
    val r = 16f + 60f * k + 14f * mHash(i)
    val al = (1f - k) * 0.8f
    c.drawCircle(px, py + 4f, r, solid(0xFFA8916E.toInt(), al * 0.5f))
    c.drawCircle(px, py, r, solid(0xFFE3CFAE.toInt(), al))
    c.drawCircle(px - r * 0.3f, py - r * 0.3f, r * 0.45f, solid(0xFFF6EAD4.toInt(), al * 0.8f))
  }
}

/** A tumbling cartoon fight cloud with stars and stray limbs. */
private fun paintBrawl(c: Canvas, a: ActorState, t: Float) {
  val spin = t * 420f
  for (i in 0 until 10) {
    val ang = (i * 36f + spin * 0.3f) * PI_F / 180f
    val d = 70f + 22f * sin(t * 13f + i)
    val r = 62f + 16f * sin(t * 17f + i * 2f)
    c.drawCircle(cos(ang) * d, sin(ang) * d * 0.75f + 5f, r, solid(0xFFB59C7A.toInt()))
  }
  for (i in 0 until 10) {
    val ang = (i * 36f + spin * 0.3f) * PI_F / 180f
    val d = 64f + 22f * sin(t * 13f + i)
    val r = 54f + 14f * sin(t * 17f + i * 2f)
    c.drawCircle(cos(ang) * d - 6f, sin(ang) * d * 0.75f - 6f, r, solid(0xFFE8D6B8.toInt()))
  }
  val frame = floor(t * 12f).toInt()
  for (j in 0 until 3) {
    val ang = mHash(frame * 3 + j) * 2f * PI_F
    val lx = cos(ang) * 130f; val ly = sin(ang) * 95f
    if (j == 2) {
      pathA.reset(); pathA.moveTo(lx - 22f, ly + 10f); pathA.lineTo(lx + 4f, ly - 40f); pathA.lineTo(lx + 22f, ly + 10f); pathA.close()
      c.drawPath(pathA, solid(HAT_C))
    } else {
      c.drawCircle(lx, ly, 20f, solid(SKIN_C)); c.drawCircle(lx, ly, 20f, line(SKIN_LINE, 3f))
    }
  }
  for (j in 0 until 4) {
    val ang = (j * 90f + t * 300f) * PI_F / 180f
    star(c, cos(ang) * 150f, sin(ang) * 110f - 40f, 22f + 8f * sin(t * 20f + j), 0xFFFFD447.toInt())
  }
}

/** Phone signal card: [ActorState.progress] is the number of bars (0..5, fractional bars fill partway). */
private fun paintSignal(c: Canvas, a: ActorState, t: Float) {
  val v = a.progress.coerceIn(0f, 5f)
  rectA.set(-92f, -64f, 96f, 70f); c.drawRoundRect(rectA, 26f, 26f, solid(0xFF3A2A26.toInt(), 0.16f))
  rectA.set(-96f, -70f, 92f, 64f); c.drawRoundRect(rectA, 26f, 26f, solid(WHITE))
  c.drawRoundRect(rectA, 26f, 26f, line(HAT_C, 4f))
  val col = when { v < 1.6f -> 0xFFF05248.toInt(); v < 3.6f -> 0xFFFFB020.toInt(); else -> 0xFF34C759.toInt() }
  for (i in 0 until 5) {
    val fill = m01(v - i)
    val bx = -66f + i * 30f; val bh = 22f + i * 17f
    rectA.set(bx, 44f - bh, bx + 20f, 44f); c.drawRoundRect(rectA, 6f, 6f, solid(0xFFEDE5E0.toInt()))
    if (fill > 0f) {
      val pop = 1f + 0.25f * (1f - mBack(fill)) * (if (fill < 1f) 1f else 0f)
      rectA.set(bx - (pop - 1f) * 10f, 44f - bh * fill * pop, bx + 20f + (pop - 1f) * 10f, 44f)
      c.drawRoundRect(rectA, 6f, 6f, solid(col))
    }
  }
  if (v < 1.6f) {
    val blinkOn = sin(t * 18f) > -0.2f
    if (blinkOn) {
      c.drawLine(68f, -48f, 80f, -36f, line(HAT_C, 5f)); c.drawLine(80f, -48f, 68f, -36f, line(HAT_C, 5f))
    }
  }
}

// ============================================================================================ 2010s painters

/** A cosy room: striped wallpaper, a framed picture, a side table with a plant; smoke and heat build with progress. */
private fun paintRoom(c: Canvas, a: ActorState, t: Float, w: Float, h: Float) {
  val p = m01(a.progress)
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  val deck = HY - h / 2
  c.drawRect(-hw, -hh, hw, deck - 110f, shaded("room.wall") { vGrad(-h / 2, deck, intArrayOf(0xFFF3DDAE.toInt(), 0xFFE2BC80.toInt())) })
  var sx = -hw
  while (sx < hw) {
    rectA.set(sx, -hh, sx + 30f, deck - 110f); c.drawRect(rectA, solid(0xFFC99A5C.toInt(), 0.13f))
    var dy = -hh + 60f
    while (dy < deck - 140f) { c.drawCircle(sx + 51f, dy, 5f, solid(0xFFC99A5C.toInt(), 0.18f)); dy += 120f }
    sx += 72f
  }
  // Picture frame, slightly crooked.
  c.save(); c.translate(-290f, -430f); c.rotate(-4f + 2f * p * sin(t * 3f))
  rectA.set(-118f, -92f, 118f, 92f); c.drawRect(rectA, solid(0xFF000000.toInt(), 0.12f))
  rectA.set(-114f, -96f, 114f, 86f); c.drawRect(rectA, solid(0xFF6B4226.toInt()))
  rectA.set(-96f, -78f, 96f, 68f); c.drawRect(rectA, solid(0xFFF6EBD5.toInt()))
  rectA.set(-80f, -62f, 80f, 52f); c.drawRect(rectA, solid(0xFF9BD3E8.toInt()))
  c.drawCircle(36f, -26f, 18f, solid(0xFFFFD166.toInt()))
  pathA.reset(); pathA.moveTo(-80f, 52f); pathA.quadTo(-30f, -10f, 20f, 30f); pathA.quadTo(50f, 0f, 80f, 20f); pathA.lineTo(80f, 52f); pathA.close()
  c.drawPath(pathA, solid(0xFF7FB069.toInt()))
  c.restore()
  // Wainscot and floor.
  c.drawRect(-hw, deck - 110f, hw, deck, shaded("room.wains") { vGrad(deck - 110f, deck, intArrayOf(0xFFB0703F.toInt(), 0xFF8E5530.toInt())) })
  c.drawRect(-hw, deck - 116f, hw, deck - 98f, solid(0xFF7E4A2A.toInt()))
  var px = -hw + 30f
  while (px < hw) { rectA.set(px, deck - 84f, px + 150f, deck - 18f); c.drawRect(rectA, line(0xFF7A4526.toInt(), 3f, 0.5f)); px += 180f }
  c.drawRect(-hw, deck, hw, hh, shaded("room.floor") { vGrad(deck, h / 2, intArrayOf(0xFF8A5A38.toInt(), 0xFF5E3B23.toInt())) })
  var fy = deck + 40f; var gap = 40f
  while (fy < hh) { c.drawLine(-hw, fy, hw, fy, line(0xFF4E301C.toInt(), 3f, 0.45f)); gap *= 1.35f; fy += gap }
  // Side table and a plant that wilts in the heat.
  rectA.set(230f, deck - 160f, 410f, deck - 144f); c.drawRect(rectA, solid(0xFF6E4426.toInt()))
  c.drawLine(250f, deck - 144f, 250f, deck + 10f, line(0xFF5A361E.toInt(), 10f)); c.drawLine(390f, deck - 144f, 390f, deck + 10f, line(0xFF5A361E.toInt(), 10f))
  pathA.reset(); pathA.moveTo(290f, deck - 160f); pathA.lineTo(282f, deck - 224f); pathA.lineTo(358f, deck - 224f); pathA.lineTo(350f, deck - 160f); pathA.close()
  c.drawPath(pathA, solid(0xFFC8643C.toInt()))
  for (l in 0 until 5) {
    c.save(); c.translate(320f, deck - 226f); c.rotate(-60f + l * 30f + p * (if (l < 2) -40f else 40f))
    rectA.set(-12f, -86f, 12f, 0f); c.drawOval(rectA, solid(mMix(0xFF5FA85A.toInt(), 0xFF8A8A40.toInt(), p)))
    c.restore()
  }
  // Smoke gathering under the ceiling, and the room warming.
  if (p > 0f) {
    c.save(); c.translate(0f, -hh); c.scale(1f, 80f + h * 0.55f * p)
    c.drawRect(-hw, 0f, hw, 1f, shaded("room.smoke") { vGrad(0f, 1f, intArrayOf(0xF0221A18.toInt(), 0x00221A18)) })
    c.restore()
    c.drawRect(-hw, -hh, hw, hh, solid(0xFFFF7A2A.toInt(), 0.14f * p))
  }
}

/** Flames creeping in: along the back wall behind Pip, or in the foreground corners (kept clear of him). */
private fun paintFire(c: Canvas, a: ActorState, t: Float, w: Float, h: Float, front: Boolean) {
  val p = a.progress
  if (p <= 0.01f) return
  val hw = w / 2; val hh = h / 2
  val deck = HY - h / 2
  if (!front) {
    for (i in 0 until 12) {
      val fx = -hw + 40f + (w - 80f) * i / 11f + (mHash(i + 3) - 0.5f) * 40f
      val grow = m01(p * 1.7f - mHash(i + 90) * 0.7f)
      val fh = (150f + 200f * mHash(i)) * grow
      flame(c, fx, deck + 6f, fh, fh * 0.55f + 24f, t, i)
    }
    for (side in intArrayOf(-1, 1)) for (j in 0 until 4) {
      val grow = m01(p * 1.6f - j * 0.25f)
      val fh = (120f + 80f * mHash(j + side * 7 + 30)) * grow
      flame(c, side * (hw - 50f - 26f * j), deck - 110f - j * 210f * p, fh, fh * 0.55f + 20f, t, j + 40 + side * 5)
    }
  } else {
    for (i in 0 until 9) {
      val u = i / 8f
      val corner = abs(u - 0.5f) * 2f
      if (corner < 0.45f) continue
      val grow = m01(p * 1.5f - (1f - corner) * 0.5f)
      val fh = (240f + 260f * mHash(i + 40)) * grow * corner
      flame(c, -hw + w * u, hh + 30f, fh, fh * 0.5f + 40f, t, i + 20)
    }
  }
}

/** A white coffee mug with a little red heart, held by the fist at the origin, steaming. */
private fun paintMug(c: Canvas, a: ActorState, t: Float) {
  rectA.set(-34f, -50f, 2f, -4f); c.drawOval(rectA, line(0xFFE6DDD3.toInt(), 10f))
  rectA.set(-4f, -64f, 60f, 10f)
  c.drawRoundRect(rectA, 13f, 13f, shaded("mug.body") { hGrad(-4f, 60f, intArrayOf(WHITE, 0xFFF1E9E0.toInt(), 0xFFD5C9BF.toInt())) })
  rectA.set(-4f, -72f, 60f, -56f); c.drawOval(rectA, solid(0xFFE8DED4.toInt()))
  rectA.set(2f, -69f, 54f, -59f); c.drawOval(rectA, solid(0xFF5A3524.toInt()))
  pathA.reset()
  pathA.moveTo(28f, -16f); pathA.cubicTo(10f, -28f, 14f, -44f, 28f, -36f); pathA.cubicTo(42f, -44f, 46f, -28f, 28f, -16f); pathA.close()
  c.drawPath(pathA, solid(HAT_C))
  for (k in 0 until 3) {
    val ph = mFract(t * 0.55f + k / 3f)
    val al = sin(ph * PI_F) * 0.55f
    val sx = 12f + 16f * k; val sy = -80f - ph * 50f
    pathB.reset(); pathB.moveTo(sx, sy); pathB.cubicTo(sx - 12f, sy - 18f, sx + 12f, sy - 30f, sx, sy - 50f)
    c.drawPath(pathB, line(WHITE, 5f, al))
  }
}

private fun paintTipFlame(c: Canvas, t: Float) {
  glow(c, 0f, -30f, 90f, 0xFFFF8A2A.toInt(), 0.5f)
  flame(c, 0f, 12f, 82f, 44f, t, 77, glowIt = false)
}

/** Club backdrop: purple sunburst turning slowly, swinging spotlights and bokeh, the floor glow thumping on the beat. */
private fun paintParty(c: Canvas, a: ActorState, t: Float, w: Float, h: Float, t0: Float) {
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  val deck = HY - h / 2
  val beat = mFract((t - t0) * 2f)
  val pulse = (1f - beat) * (1f - beat) * (1f - beat)
  c.drawRect(-hw, -hh, hw, hh, shaded("party.bg") {
    RadialGradient(0f, -200f, h * 0.75f, intArrayOf(0xFF8A33B0.toInt(), 0xFF3A1260.toInt(), 0xFF14061F.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  })
  c.save(); c.translate(0f, -200f); c.rotate(t * 14f)
  for (i in 0 until 18 step 2) {
    val a1 = i * 20f * PI_F / 180f; val a2 = (i + 1) * 20f * PI_F / 180f
    pathA.reset(); pathA.moveTo(0f, 0f); pathA.lineTo(cos(a1) * h, sin(a1) * h); pathA.lineTo(cos(a2) * h, sin(a2) * h); pathA.close()
    c.drawPath(pathA, solid(WHITE, 0.05f + 0.04f * pulse))
  }
  c.restore()
  val bokeh = intArrayOf(0xFFFF4FA3.toInt(), 0xFF3FE0FF.toInt(), 0xFFFFE14F.toInt())
  for (i in 0 until 22) {
    val bx = -w / 2 + w * mHash(i + 300); val by = -h / 2 + (deck + h / 2 - 100f) * mHash(i + 330)
    val r = 10f + 26f * mHash(i + 360)
    c.drawCircle(bx, by, r, solid(bokeh[i % 3], (0.12f + 0.18f * (0.5f + 0.5f * sin(t * 2f + i))) * (0.7f + 0.3f * pulse)))
  }
  for (i in 0 until 3) {
    val ox = (-0.36f + 0.36f * i) * w
    c.save(); c.translate(ox, -h / 2); c.rotate(sin(t * 1.4f + i * 2.1f) * 26f)
    pathA.reset(); pathA.moveTo(-18f, 0f); pathA.lineTo(18f, 0f); pathA.lineTo(250f, h * 0.85f); pathA.lineTo(-250f, h * 0.85f); pathA.close()
    val key = "party.spot$i"
    c.drawPath(pathA, shaded(key, 0.8f + 0.2f * pulse) { vGrad(0f, h * 0.85f, intArrayOf(mAlpha(bokeh[i], 0.42f), mAlpha(bokeh[i], 0f))) })
    c.restore()
  }
  c.drawRect(-hw, deck, hw, hh, shaded("party.floor") { vGrad(deck, h / 2, intArrayOf(0xFF2A0B3D.toInt(), 0xFF12051C.toInt())) })
  c.drawLine(-hw, deck, hw, deck, line(0xFFFF7BC4.toInt(), 4f, 0.5f + 0.4f * pulse))
  c.save(); c.translate(0f, deck + 8f); c.scale(1f, 0.22f)
  glow(c, 0f, 0f, 430f, 0xFFFF4FA3.toInt(), 0.35f + 0.4f * pulse)
  c.restore()
}

/** The lasso: a loop spinning flat above Pip's head and the rope back to his circling fist. progress = turns. */
private fun paintLasso(c: Canvas, a: ActorState) {
  val th = a.progress * 2f * PI_F
  val hx = 72f + 33f * cos(th); val hy = -33f + 10f * sin(th)
  val cx = 60f; val cy = -78f; val rx = 160f; val ry = 34f
  val lx = cx + rx * cos(th + 0.5f); val ly = cy + ry * sin(th + 0.5f)
  rectA.set(cx - rx, cy - ry, cx + rx, cy + ry)
  c.drawOval(rectA, line(0xFF8C6236.toInt(), 10f))
  c.drawOval(rectA, line(0xFFD8AA6A.toInt(), 5f))
  // Motion smear on the loop's leading edge.
  for (k in 1..3) {
    val ang = th + 0.5f - k * 0.35f
    c.drawCircle(cx + rx * cos(ang), cy + ry * sin(ang), 5f, solid(0xFFD8AA6A.toInt(), 0.4f / k))
  }
  pathA.reset(); pathA.moveTo(hx, hy); pathA.quadTo((hx + lx) / 2f, (hy + ly) / 2f + 18f, lx, ly)
  c.drawPath(pathA, line(0xFF8C6236.toInt(), 9f))
  c.drawPath(pathA, line(0xFFD8AA6A.toInt(), 4f))
}

// ============================================================================================ today painters

/**
 * A dark stone corridor lit by two wall torches. The light leaking from the door pools on the floor and everything
 * flickers warm with each impact. [floorY] is the floor line in local coordinates.
 */
private fun paintCorridor(c: Canvas, t: Float, w: Float, h: Float, t0: Float, floorY: Float) {
  val lt = t - t0
  val imp = doorImpulse(lt); val light = doorLight(lt)
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  c.drawRect(-hw, -hh, hw, hh, solid(0xFF141012.toInt()))
  var r = 0
  var y0 = -hh
  while (y0 < floorY) {
    var x = -hw - mHash(r * 5) * 140f
    var k = 0
    while (x < hw) {
      val bw = 150f + 110f * mHash(r * 13 + k)
      val col = mMix(0xFF2B2421.toInt(), 0xFF3E342F.toInt(), mHash(r * 31 + k * 3))
      rectA.set(x + 4f, y0 + 4f, x + bw - 4f, y0 + 92f)
      c.drawRoundRect(rectA, 10f, 10f, solid(col))
      c.drawLine(x + 12f, y0 + 8f, x + bw - 14f, y0 + 8f, line(0xFF5C4D45.toInt(), 3f, 0.45f))
      c.drawLine(x + 12f, y0 + 90f, x + bw - 14f, y0 + 90f, line(0xFF0A0708.toInt(), 3f, 0.5f))
      x += bw; k++
    }
    y0 += 96f; r++
  }
  c.drawRect(-hw, floorY, hw, hh, shaded("cor.floor") { vGrad(floorY, h / 2, intArrayOf(0xFF2E2420.toInt(), 0xFF0F0C0B.toInt())) })
  var fy = floorY + 36f; var gap = 36f
  while (fy < hh) { c.drawLine(-hw, fy, hw, fy, line(0xFF0A0708.toInt(), 3f, 0.55f)); gap *= 1.4f; fy += gap }
  for (k in -6..6) c.drawLine(k * 90f, floorY, k * 320f, hh, line(0xFF0A0708.toInt(), 3f, 0.45f))
  // Light spilling under and around the door onto the floor.
  pathA.reset(); pathA.moveTo(-250f, floorY); pathA.lineTo(250f, floorY); pathA.lineTo(460f, floorY + 420f); pathA.lineTo(-460f, floorY + 420f); pathA.close()
  c.drawPath(pathA, shaded("cor.spill", 0.55f * m01(light)) { vGrad(floorY, floorY + 420f, intArrayOf(0xCCFFD68A.toInt(), 0x00FFD68A)) })
  // Torches.
  val fl = 0.82f + 0.12f * sin(t * 7.3f) + 0.06f * sin(t * 17f)
  for (side in intArrayOf(-1, 1)) {
    val tx = side * 400f; val ty = floorY - 700f
    glow(c, tx, ty - 30f, 560f, 0xFFFF9A3C.toInt(), 0.32f * fl)
    c.drawLine(tx, ty + 80f, tx, ty + 12f, line(0xFF2A2523.toInt(), 14f))
    c.drawLine(tx - side * 30f, ty + 84f, tx, ty + 60f, line(0xFF2A2523.toInt(), 10f))
    pathA.reset(); pathA.moveTo(tx - 26f, ty - 6f); pathA.lineTo(tx + 26f, ty - 6f); pathA.lineTo(tx + 14f, ty + 20f); pathA.lineTo(tx - 14f, ty + 20f); pathA.close()
    c.drawPath(pathA, solid(0xFF3A3330.toInt()))
    flame(c, tx, ty - 4f, 100f, 50f, t, 5 + side, glowIt = false)
  }
  c.drawRect(-hw, -hh, hw, hh, shaded("cor.vig") {
    RadialGradient(0f, floorY - 380f, h * 0.62f, intArrayOf(0x00000000, 0x00000000, 0xD8000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
  })
  if (imp > 0.01f) c.drawRect(-hw, -hh, hw, hh, solid(0xFFFFB060.toInt(), 0.1f * m01(imp)))
}

/** The arched outline of the door, inflated by [g] px; origin at the door's centre, floor at y = 380. */
private fun doorShape(p: Path, g: Float) {
  p.reset()
  p.moveTo(-260f - g, 380f)
  p.lineTo(-260f - g, -120f)
  rectA.set(-260f - g, -380f - g, 260f + g, 140f + g)
  p.arcTo(rectA, 180f, 180f, false)
  p.lineTo(260f + g, 380f)
  p.close()
}

/**
 * The heavy wooden door being battered from the other side: planks rattle on every impact, and a hot light blazes
 * through the cracks between them. Pure function of the scene clock.
 */
private fun paintDoor(c: Canvas, t: Float, t0: Float) {
  val lt = t - t0
  val imp = doorImpulse(lt); val light = doorLight(lt)
  c.save()
  c.translate(imp * sin(t * 61f) * 5f, -imp * 3f)
  c.scale(1f + imp * 0.012f, 1f + imp * 0.012f, 0f, 380f)
  glow(c, 0f, 0f, 560f + 160f * imp, 0xFFFFD08A.toInt(), 0.28f * light)
  // Stone frame with voussoirs.
  doorShape(pathA, 50f)
  c.drawPath(pathA, shaded("door.frame") { vGrad(-430f, 380f, intArrayOf(0xFF5A4F48.toInt(), 0xFF3A322D.toInt())) })
  for (k in 0..8) {
    val ang = (180f + k * 22.5f) * PI_F / 180f
    c.drawLine(cos(ang) * 262f, -120f + sin(ang) * 262f, cos(ang) * 310f, -120f + sin(ang) * 310f, line(0xFF221C19.toInt(), 4f, 0.8f))
  }
  for (yy in floatArrayOf(-40f, 90f, 220f, 330f)) {
    c.drawLine(-310f, yy, -262f, yy, line(0xFF221C19.toInt(), 4f, 0.8f)); c.drawLine(262f, yy, 310f, yy, line(0xFF221C19.toInt(), 4f, 0.8f))
  }
  doorShape(pathB, 50f)
  c.drawPath(pathB, line(0xFF7A6C62.toInt(), 3f, 0.5f))
  // The light behind the door shows through every crack.
  doorShape(pathB, 0f)
  c.drawPath(pathB, solid(mMix(0xFF3A2414.toInt(), 0xFFFFE6B0.toInt(), m01(light))))
  c.save(); c.clipPath(pathB)
  val n = 7; val pw = 520f / n; val gap = 4f + 4f * m01(light)
  for (i in 0 until n) {
    val px = -260f + i * pw
    val dx = imp * sin(t * 71f + i * 1.9f) * 7f
    val dy = imp * cos(t * 53f + i * 2.7f) * 6f
    rectA.set(px + gap / 2 + dx, -440f + dy, px + pw - gap / 2 + dx, 400f + dy)
    c.drawRect(rectA, shaded(if (i % 2 == 0) "door.plankA" else "door.plankB") {
      if (i % 2 == 0) vGrad(-380f, 380f, intArrayOf(0xFF8E5D36.toInt(), 0xFF6A4224.toInt(), 0xFF4A2D17.toInt()))
      else vGrad(-380f, 380f, intArrayOf(0xFF835532.toInt(), 0xFF623C20.toInt(), 0xFF432814.toInt()))
    })
    c.drawLine(rectA.left + 2f, rectA.top, rectA.left + 2f, rectA.bottom, line(0xFFB98A5E.toInt(), 3f, 0.3f))
    c.drawLine(rectA.right - 2f, rectA.top, rectA.right - 2f, rectA.bottom, line(0xFF2A190C.toInt(), 3f, 0.4f))
    for (g in 0 until 3) {
      val gx = rectA.left + 14f + g * 18f + mHash(i * 3 + g) * 6f
      val gy = -330f + mHash(i * 7 + g) * 300f
      pathA.reset(); pathA.moveTo(gx, gy + dy); pathA.cubicTo(gx + 6f, gy + 90f + dy, gx - 6f, gy + 180f + dy, gx + 2f, gy + 300f + dy)
      c.drawPath(pathA, line(0xFF3A2312.toInt(), 2.5f, 0.35f))
    }
    if (mHash(i + 11) > 0.45f) {
      val ky = -250f + 480f * mHash(i + 5)
      rectA.set(px + pw / 2 - 9f + dx, ky - 14f + dy, px + pw / 2 + 9f + dx, ky + 14f + dy)
      c.drawOval(rectA, solid(0xFF3A2312.toInt(), 0.6f))
    }
  }
  // Light glinting through each crack.
  for (i in 1 until n) {
    val gx = -260f + i * pw + imp * sin(t * 71f + i * 1.9f) * 3.5f
    c.drawLine(gx, -400f, gx, 400f, line(0xFFFFE6A6.toInt(), 2f + 7f * m01(light), 0.2f + 0.6f * m01(light)))
    c.drawLine(gx, -400f, gx, 400f, line(WHITE, 1.5f, 0.6f * m01(light)))
  }
  // Iron straps and rivets.
  for ((s, sy) in floatArrayOf(-170f, 60f, 290f).withIndex()) {
    val jy = imp * sin(t * 47f + s * 2f) * 4f
    rectA.set(-262f, sy - 16f + jy, 262f, sy + 16f + jy); c.drawRect(rectA, solid(0xFF3B3634.toInt()))
    c.drawLine(-262f, sy - 13f + jy, 262f, sy - 13f + jy, line(0xFF77706B.toInt(), 3f, 0.6f))
    for (i in 0 until n) {
      val rx = -260f + pw * (i + 0.5f)
      c.drawCircle(rx, sy + jy, 7f, solid(0xFF5A5450.toInt())); c.drawCircle(rx - 2f, sy - 2f + jy, 2.5f, solid(0xFFB0A8A2.toInt()))
    }
  }
  c.restore()
  // A hot rim along the bottom gap.
  c.drawLine(-250f, 378f, 250f, 378f, line(0xFFFFE2A0.toInt(), 3f + 6f * m01(light), 0.3f + 0.6f * m01(light)))
  c.restore()
}

/** Dust and grit shaken loose from above the door frame by each impact (screen coordinates). */
private fun paintDust(c: Canvas, t: Float, t0: Float, cx: Float, topY: Float) {
  val lt = t - t0
  var i = 0
  while (i < THUDS.size) {
    val ti = THUDS[i]; val s = THUDS[i + 1]
    i += 2
    val age = lt - ti
    if (age < 0f || age > 1.6f) continue
    val seed = (ti * 100f).toInt()
    glow(c, cx + (mHash(seed) - 0.5f) * 300f, topY + 30f + age * 80f, 60f + 160f * age, 0xFFB8A68E.toInt(), 0.35f * s * m01(1f - age / 0.9f))
    val n = (10 + 16 * s).toInt()
    for (k in 0 until n) {
      val h1 = mHash(seed * 7 + k); val h2 = mHash(seed * 11 + k * 3); val h3 = mHash(seed * 13 + k * 5)
      val chunk = k % 4 == 0 && s > 0.7f
      val g = if (chunk) 1500f + 600f * h3 else 220f + 380f * h3
      val x = cx - 270f + 540f * h1 + (h2 - 0.5f) * 70f * age
      val y = topY - 20f + 60f * h2 + 0.5f * g * age * age
      val al = m01(1f - age / 1.6f)
      if (chunk) {
        c.save(); c.translate(x, y); c.rotate(age * 400f * (h2 - 0.5f))
        rectA.set(-5f - 4f * h3, -5f - 3f * h1, 5f + 4f * h3, 5f + 3f * h1)
        c.drawRect(rectA, solid(0xFF6A5D55.toInt(), al)); c.restore()
      } else {
        c.drawCircle(x, y, 2f + 4f * h3, solid(0xFFD8C8B0.toInt(), al * 0.85f))
      }
    }
  }
}

/** The playground: a mint wall painted with clouds, a sandy field, a lone tree and a finish line. progress = red tint. */
private fun paintYard(c: Canvas, a: ActorState, t: Float, w: Float, h: Float) {
  val hw = w / 2 + 60f; val hh = h / 2 + 60f
  val deck = HY - h / 2
  val wallY = deck - 560f
  c.drawRect(-hw, -hh, hw, wallY, shaded("yard.wall") { vGrad(-h / 2, wallY, intArrayOf(0xFFB8EADF.toInt(), 0xFF7CCBBB.toInt())) })
  for (i in 0 until 5) {
    val cx = -w / 2 + w * (0.1f + 0.2f * i) + (mHash(i + 500) - 0.5f) * 80f + sin(t * 0.2f + i) * 6f
    val cy = -h / 2 + 260f + 420f * mHash(i + 510)
    for (b in 0 until 4) c.drawCircle(cx + (b - 1.5f) * 46f, cy - (if (b == 1 || b == 2) 24f else 0f), 44f + 10f * mHash(i * 4 + b), solid(WHITE, 0.92f))
  }
  rectA.set(-hw, wallY - 10f, hw, wallY + 8f); c.drawRect(rectA, solid(0xFF5FA898.toInt()))
  c.drawRect(-hw, wallY + 8f, hw, hh, shaded("yard.sand") {
    vGrad(wallY, h / 2, intArrayOf(0xFFF1DDB4.toInt(), 0xFFE2C48F.toInt(), 0xFFD1AE76.toInt()), floatArrayOf(0f, 0.5f, 1f))
  })
  for (i in 0 until 60) {
    c.drawCircle(-w / 2 + w * mHash(i + 600), wallY + 40f + (h / 2 - wallY) * mHash(i + 660), 2f + 3f * mHash(i + 720), solid(0xFFB8955E.toInt(), 0.35f))
  }
  // The tree.
  val tx = -330f; val ty = wallY + 190f
  rectA.set(tx - 16f, ty - 260f, tx + 16f, ty); c.drawRoundRect(rectA, 10f, 10f, solid(0xFF6E4A30.toInt()))
  rectA.set(tx - 90f, ty + 6f, tx + 90f, ty + 26f); c.drawOval(rectA, solid(0xFF000000.toInt(), 0.12f))
  for (b in 0 until 5) {
    val ang = b * 72f * PI_F / 180f
    c.drawCircle(tx + cos(ang) * 56f, ty - 300f + sin(ang) * 40f, 66f, solid(if (b % 2 == 0) 0xFF4E8A4A.toInt() else 0xFF5FA05A.toInt()))
  }
  c.drawCircle(tx - 20f, ty - 330f, 40f, solid(0xFF79B872.toInt(), 0.7f))
  // The line you must not be caught short of.
  val ly = deck - 330f
  c.drawRect(-hw, ly - 7f, hw, ly + 7f, solid(0xFFE0453A.toInt(), 0.85f))
  if (a.progress > 0f) c.drawRect(-hw, -hh, hw, hh, solid(0xFFFF3B30.toInt(), 0.13f * m01(a.progress)))
}

/**
 * An original big doll with a bob and two buns, in an orange pinafore. progress 0 = back turned, 1 = facing us;
 * the face slides round from the side as she turns, and her eyes glow and scan once she's looking.
 */
private fun paintDoll(c: Canvas, a: ActorState, t: Float) {
  val p = a.progress
  val q = m01(p)
  val r = 120f
  val hair = 0xFF3A2418.toInt()
  // Body, drawn first.
  rectA.set(-120f, 405f, 120f, 440f); c.drawOval(rectA, solid(0xFF000000.toInt(), 0.14f))
  c.drawRoundRect(-44f, 320f, -14f, 425f, 12f, 12f, solid(SKIN_C)); c.drawRoundRect(14f, 320f, 44f, 425f, 12f, 12f, solid(SKIN_C))
  rectA.set(-46f, 380f, -12f, 396f); c.drawRect(rectA, solid(WHITE)); rectA.set(12f, 380f, 46f, 396f); c.drawRect(rectA, solid(WHITE))
  rectA.set(-50f, 410f, -10f, 432f); c.drawRoundRect(rectA, 10f, 10f, solid(0xFF2A1A14.toInt()))
  rectA.set(10f, 410f, 50f, 432f); c.drawRoundRect(rectA, 10f, 10f, solid(0xFF2A1A14.toInt()))
  c.drawRoundRect(-112f, 130f, -82f, 270f, 14f, 14f, solid(SKIN_C)); c.drawRoundRect(82f, 130f, 112f, 270f, 14f, 14f, solid(SKIN_C))
  pathA.reset(); pathA.moveTo(-72f, 104f); pathA.lineTo(72f, 104f); pathA.lineTo(132f, 336f); pathA.quadTo(0f, 356f, -132f, 336f); pathA.close()
  c.drawPath(pathA, shaded("doll.dress") { vGrad(104f, 350f, intArrayOf(0xFFFFA24A.toInt(), 0xFFE07A22.toInt())) })
  rectA.set(-58f, 96f, 58f, 128f); c.drawRoundRect(rectA, 12f, 12f, solid(0xFFFFE07A.toInt()))
  val collar = mSmooth(0.4f, 0.8f, q)
  if (collar > 0f) {
    rectA.set(-46f, 100f, 0f, 136f); c.drawOval(rectA, solid(WHITE, collar))
    rectA.set(0f, 100f, 46f, 136f); c.drawOval(rectA, solid(WHITE, collar))
  }
  // Head.
  c.save()
  c.rotate((1f - q) * sin(t * 1.3f) * 3f + (p - q) * 30f, 0f, 100f)
  rectA.set(-r * 0.9f, r * 0.7f, r * 0.9f, r * 1.0f); c.drawOval(rectA, solid(0xFF000000.toInt(), 0.12f))
  for (side in intArrayOf(-1, 1)) {
    c.drawCircle(side * 74f, -r + 8f, 42f, solid(hair))
    c.drawCircle(side * 74f - 10f, -r - 4f, 14f, solid(0xFF5A3A28.toInt(), 0.6f))
    pathA.reset()
    val bx = side * 42f; val by = -r + 30f
    pathA.moveTo(bx, by); pathA.lineTo(bx + side * 26f, by - 18f); pathA.lineTo(bx + side * 26f, by + 18f); pathA.close()
    c.drawPath(pathA, solid(HAT_C))
  }
  pathB.reset(); pathB.addCircle(0f, 0f, r, Path.Direction.CW)
  c.save(); c.clipPath(pathB)
  c.drawCircle(0f, 0f, r, shaded("doll.hair") { RadialGradient(-30f, -40f, r * 1.3f, intArrayOf(0xFF5A3A28.toInt(), hair), null, Shader.TileMode.CLAMP) })
  val showFace = q > 0.02f
  if (showFace) {
    val fx = r * 0.98f * (1f - q)
    val frx = r * 0.86f * (0.2f + 0.8f * sin(q * PI_F / 2f))
    rectA.set(fx - frx, -r * 0.62f, fx + frx, r * 0.98f); c.drawOval(rectA, solid(0xFFFCE1CC.toInt()))
    val feat = mSmooth(0.35f, 0.8f, q)
    if (feat > 0f) {
      val sc = frx / (r * 0.86f)
      val scan = mSmooth(0.85f, 1f, q)
      val look = sin(t * 2.6f) * 5f * scan
      for (side in intArrayOf(-1, 1)) {
        val ex = fx + side * 0.38f * frx; val ey = 16f
        glow(c, ex, ey, 46f, 0xFFFF3B30.toInt(), 0.55f * scan * feat)
        rectA.set(ex - 11f * sc, ey - 14f, ex + 11f * sc, ey + 14f); c.drawOval(rectA, solid(0xFF231510.toInt(), feat))
        c.drawCircle(ex + look * sc, ey - 2f, 4.5f * sc, solid(mMix(WHITE, 0xFFFF6A5E.toInt(), scan), feat))
        rectA.set(ex - 18f * sc, ey + 22f, ex + 18f * sc, ey + 36f); c.drawOval(rectA, solid(0xFFF59A9A.toInt(), 0.6f * feat))
      }
      pathA.reset(); pathA.moveTo(fx - 12f * sc, 62f); pathA.quadTo(fx, 70f, fx + 12f * sc, 62f)
      c.drawPath(pathA, line(0xFFB2504A.toInt(), 4f, feat))
    }
  }
  // Bangs over the forehead (front) or the crown (back), with a scalloped edge.
  pathA.reset(); pathA.moveTo(-r, -r * 1.1f); pathA.lineTo(r, -r * 1.1f)
  val edge = mLerp(r * 0.2f, -r * 0.3f, q)
  pathA.lineTo(r, edge)
  for (s in 0 until 5) {
    val x1 = r - (s + 1) * (2f * r / 5f)
    pathA.quadTo(x1 + r / 5f, edge + 22f, x1, edge)
  }
  pathA.close()
  c.drawPath(pathA, solid(hair))
  if (q < 0.6f) {
    val al = 1f - q / 0.6f
    for (s in 0 until 4) {
      pathA.reset(); pathA.moveTo(-60f + s * 40f, -r * 0.9f); pathA.quadTo(-50f + s * 40f, 0f, -70f + s * 44f, r * 0.9f)
      c.drawPath(pathA, line(0xFF6A4630.toInt(), 3f, 0.5f * al))
    }
  }
  c.restore()
  c.restore()
}
