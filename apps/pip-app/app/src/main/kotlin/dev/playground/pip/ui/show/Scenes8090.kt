package dev.playground.pip.ui.show

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Ease
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.StopScope
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 1980s and 1990s: each decade is a quick-fire medley of three instantly recognisable moments, about three
 * seconds each, joined by whip pans, poofs and hops. All scenery is drawn here as CUSTOM actors.
 *
 * d80s: arcade maze chomp and the chase (0-2.9), the bike across the full moon (2.9-5.95), 88 mph with twin fire
 *       trails and "Great Scott!" (5.95-9).
 * d90s: hands-on-cheeks scream in the mirror (0-2.95), "I'm flying!" at the bow (2.95-5.6), bullet time (5.6-9).
 */
fun eightiesNineties(): List<Scene> = listOf(eighties(), nineties())

private const val HOME_X = 540f
private const val HOME_Y = 1350f
/** Panels are 1000 x 860 px, centred at (540, PANEL_Y): Pip's feet sit at local y 180. */
private const val PANEL_Y = 1170f

private val whipEase: Ease = Easing.cubicBezier(0.55f, 0f, 0.85f, 0.35f)
private val whipLand: Ease = Easing.cubicBezier(0.12f, 0.85f, 0.3f, 1f)
private val snap: Ease = Easing.cubicBezier(0.08f, 0.9f, 0.2f, 1f)

private fun SceneScope.home() = pip {
  x = HOME_X; y = HOME_Y; size = 300f
  right.x = 600f; right.y = 470f
  left.x = -60f; left.y = 470f
}

/** Sets Pip's values instantly at [at] (a cut, hidden by a fast move, a poof or being off screen). */
private fun SceneScope.setPip(at: Number, block: StopScope<PipState>.() -> Unit) =
  animate(at, 0.001, Easing.linear) { to(block) }

/** A blink that closes fast and opens slower (about 1:1.5). Mood swaps are hidden inside it. */
private fun SceneScope.blinkAt(at: Number, dur: Float = 0.17f) =
  animate(at, dur, Easing.linear, fill = Fill.NONE) {
    from(Easing.easeIn) { blink(0) }
    at(40, Easing.easeOut) { blink(1) }
    to { blink(0) }
  }

/** Follow-through on the hat tip: it trails a move by ~80 ms, overshoots, settles. Additive, so the idle sway stays. */
private fun SceneScope.hatLag(at: Number, amount: Float, dur: Float = 0.5f) =
  animate(at.toFloat() + 0.08f, dur, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatSway(0) }
    at(30, Easing.easeInOut) { hatSway(amount) }
    at(65, Easing.easeInOut) { hatSway(-amount * 0.35f) }
    to { hatSway(0) }
  }

/** A sine-like additive wobble that starts and ends at zero, so nothing is left over when it stops. */
private fun SceneScope.Actor.bob(at: Number, period: Float, cycles: Float, dy: Float) =
  animate(at, period, Easing.linear, iterations = cycles, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { y(0) }
    at(25, Easing.easeInOutSine) { y(-dy) }
    at(75, Easing.easeInSine) { y(dy) }
    to { y(0) }
  }

private fun SceneScope.pipBob(at: Number, period: Float, cycles: Float, dy: Float) =
  animate(at, period, Easing.linear, iterations = cycles, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { y(0) }
    at(25, Easing.easeInOutSine) { y(-dy) }
    at(75, Easing.easeInSine) { y(dy) }
    to { y(0) }
  }

private fun SceneScope.Actor.whipOut(at: Number, dur: Float = 0.26f) = animate(at, dur, whipEase) { to { x(-900) } }

private fun SceneScope.Actor.whipIn(at: Number, dur: Float = 0.34f) = animate(at, dur, Easing.linear) {
  from(whipLand) { x(1950) }
  at(78, Easing.easeInOutSine) { x(HOME_X - 16f) }
  to { x(HOME_X) }
}

/** Horizontal motion streaks across the panel area for a whip pan. */
private fun SceneScope.streaks(at: Number, dur: Float = 0.5f) =
  actor(ActorKind.CUSTOM, layer = 3) { x = HOME_X; y = PANEL_Y; alpha = 0f; paint = STREAKS }
    .animate(at, dur, Easing.linear, fill = Fill.NONE) {
      from { alpha(0); progress(0) }
      at(22) { alpha(1) }
      at(68) { alpha(1) }
      to { alpha(0); progress(1) }
    }

// ================================================================================================================
// 1980s
// ================================================================================================================

private fun eighties() = scene("d80s", 9) {
  home()

  // ---------- Neon sign banner: flickers on, hums, fades. ----------
  actor(ActorKind.CUSTOM, layer = 3) { x = HOME_X; y = 380f; alpha = 0f; paint = NEON80 }
    .animate(0, 1.35, Easing.linear, fill = Fill.NONE) {
      from { alpha(0); progress(0); scale(0.95) }
      at(5) { alpha(1); progress(0.5) }
      at(8) { alpha(0.15); progress(0.1) }
      at(12) { alpha(1); progress(1) }
      at(15) { alpha(0.45) }
      at(18, Easing.easeOutSine) { alpha(1); scale(0.95) }
      at(45) { scale(1) }
      at(84, Easing.easeIn) { alpha(1); scale(1); y(380) }
      to { alpha(0); scale(1.05); y(362) }
    }

  // ================= Moment 1: the maze chomp and the chase (0 - 2.9) =================
  val maze = actor(ActorKind.CUSTOM, layer = -1) { x = HOME_X; y = PANEL_Y; progress = 595f; alpha = 0f; paint = MAZE }
  maze.animate(0, 0.45, Easing.outBack) { from { scale(0.88) }; to { scale(1) } }
  maze.animate(0, 0.25, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
  animate(0, 0.1, Easing.linear) { to { shadow(0.35) } }

  // Spots the dots: eyes first, then the face lights up.
  animate(0.1, 0.1, Easing.easeOut) { to { look(1, 0.35) } }
  blinkAt(0.2)
  animate(0.24, 0.26, Easing.outBack) { from { mouth(Mouth.O) }; to { eyeScale(1.25); brows(0.6, -0.15) } }
  // Anticipation: sink and lean back from the direction of travel.
  animate(0.3, 0.2, Easing.easeOut) { to { squash(1.12, 0.89); rotation(-5) } }

  fun chomp(t0: Float, x0: Float, x1: Float) {
    cue(t0 + 0.07f, "chomp")
    val lunge = Easing.cubicBezier(0.3f, 0.75f, 0.4f, 1f)
    animate(t0, 0.16, lunge) { from { x(x0) }; to { x(x1) } }
    maze.animate(t0, 0.16, lunge) { from { progress(x0 + 55) }; to { progress(x1 + 55) } }
    animate(t0, 0.25, Easing.linear) {
      from(Easing.easeOut) { mouth(Mouth.SHOUT); squash(0.9, 1.11); rotation(4) }
      at(40, Easing.easeOut) { mouth(Mouth.FLAT); squash(1.12, 0.89); rotation(-2) }
      to { squash(1, 1); rotation(0) }
    }
    hatLag(t0, -0.4f, 0.32f)
  }
  animate(0.5, 0.15, Easing.easeOut) { to { squint(0.45); eyeScale(1.05); brows(0.2, 0); blush(0.35) } }
  val xs = floatArrayOf(540f, 596f, 652f, 708f, 764f, 820f)
  for (j in 0 until 5) chomp(0.5f + 0.25f * j, xs[j], xs[j + 1])

  // Bliss: eyes shut on the last bite, the mood swaps behind the lids, a contented bounce.
  animate(1.76, 0.32, Easing.linear) {
    from(Easing.easeOut) { mood(Mood.HAPPY); mouth(Mouth.SMILE); squash(1.07, 0.94) }
    at(45, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  animate(1.76, 0.25, Easing.easeOut) { to { squint(0); blush(0.6); look(0.3, 0) } }

  // Something original and wobbly slides in behind him.
  val ghost = actor(ActorKind.CUSTOM, layer = -1) { x = -190f; y = 1215f; progress = 1f; paint = GHOST }
  ghost.animate(1.8, 0.42, Easing.linear) {
    from(Easing.cubicBezier(0.2f, 0.8f, 0.3f, 1f)) { x(-190) }
    at(80, Easing.easeInOutSine) { x(262) }
    to { x(250) }
  }
  ghost.bob(1.8, 0.5f, 3f, 9f)
  cue(1.95, "sting")

  // The realisation: eyes snap open (mood swaps inside a blink) and dart back before the head moves.
  blinkAt(1.98, 0.12f)
  animate(2.02, 0.07, Easing.easeOut) { from { mood(Mood.NEUTRAL); mouth(Mouth.FLAT) }; to { look(-1, 0.1); eyeScale(1.15); blush(0.2) } }
  // ...then the head whips round, 120 ms behind the eyes. Huge eyes, worried brows.
  animate(2.14, 0.32, Easing.linear) {
    from(Easing.easeIn) { facing(1); squash(1, 1) }
    at(32, Easing.outBack) { facing(-1); squash(0.88, 1.14) }
    to { squash(1, 1) }
  }
  hatLag(2.14, 0.6f, 0.45f)
  animate(2.2, 0.16, Easing.outBack) { from { mouth(Mouth.O) }; to { look(0.7, 0); eyeScale(1.55); brows(1, -0.8) } }
  // A locked stare through the gulp: no idle darts or blinks.
  animate(2.14, 0.36, Easing.linear, fill = Fill.NONE) { from { life(0) }; to { life(0) } }
  // Hold the gulp with a tremble.
  animate(2.3, 0.04, Easing.linear, iterations = 5f, fill = Fill.NONE, composite = Composite.ADD) {
    from { x(0) }; at(25) { x(-3) }; at(75) { x(3) }; to { x(0) }
  }

  // RUN: eyes look away first, turn back, crouch, and zip off right.
  animate(2.44, 0.06, Easing.easeOut) { to { look(-1, 0) } }
  animate(2.5, 0.12, Easing.linear) {
    from(Easing.easeInOutSine) { facing(-1); mouth(Mouth.SHOUT) }
    to { facing(1); squash(1.2, 0.83); rotation(-8) }
  }
  animate(2.5, 0.1, Easing.easeOut) { to { look(1, 0) } }
  cue(2.62, "whoosh")
  animate(2.62, 0.2, Easing.easeIn) { to { x(1400); squash(1.3, 0.77); rotation(10) } }
  hatLag(2.62, -0.8f, 0.3f)
  ghost.animate(2.6, 0.45, Easing.easeIn) { to { x(1420) } }

  // ---------- Whip pan to the moon ----------
  streaks(2.66)
  maze.whipOut(2.72)
  val moon = actor(ActorKind.CUSTOM, layer = -1) { x = 1950f; y = PANEL_Y; paint = MOON }
  moon.whipIn(2.84, 0.38f)

  // ================= Moment 2: the bike across the full moon (2.9 - 5.95) =================
  val rot = -14f
  val rad = Math.toRadians(rot.toDouble())
  val bx = 155f; val by = -26f
  val ox = (bx * cos(rad) - by * sin(rad)).toFloat()
  val oy = (bx * sin(rad) + by * cos(rad)).toFloat()
  val glide = Easing.cubicBezier(0.2f, 0.62f, 0.8f, 0.38f)
  val bikeT0 = 3.15f; val bikeDur = 2.7f
  val bike = actor(ActorKind.CUSTOM, layer = -1) { x = -500f; y = 1380f; rotation = rot; paint = BIKE }
  val basket = actor(ActorKind.CUSTOM, layer = 1) { x = -500f; y = 1380f; rotation = rot; paint = BASKET }
  for (b in listOf(bike, basket)) {
    b.animate(bikeT0, bikeDur, glide) { from { x(-260); y(1380) }; to { x(1360); y(900) } }
    b.bob(bikeT0, 0.54f, 5f, 7f)
  }
  cue(3.2, "whoosh")
  // Pip rides in the basket: a cut while he is off screen, then he follows the bike exactly.
  setPip(3.1) {
    size(120); shadow(0); rotation(rot); facing(1); squash(1, 1); x(-260 + ox); y(1380 + oy)
    mood(Mood.HAPPY); mouth(Mouth.SMILE); eyeScale(1); brows(0, 0, false); look(0, 0); blush(0.4)
  }
  animate(bikeT0, bikeDur, glide) { from { x(-260 + ox); y(1380 + oy) }; to { x(1360 + ox); y(900 + oy) } }
  pipBob(bikeT0, 0.54f, 5f, 7f)
  // The hat streams back in the wind and flutters.
  animate(bikeT0, bikeDur, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatSway(0) }; at(12) { hatSway(-0.85) }; at(90) { hatSway(-0.85) }; to { hatSway(0) }
  }
  animate(bikeT0, 0.19, Easing.easeInOutSine, iterations = 14f, direction = Direction.ALTERNATE, fill = Fill.NONE, composite = Composite.ADD) {
    from { hatSway(-0.12) }; to { hatSway(0.12) }
  }

  // Over the moon he opens his eyes to us (inside a double blink) and waves.
  blinkAt(4.0, 0.14f)
  animate(4.04, 0.2, Easing.outBack) { from { mood(Mood.NEUTRAL) }; to { look(-0.3, 0.3); eyeScale(1.2); brows(0.5, -0.2); blush(0.6) } }
  blinkAt(4.22, 0.14f)
  animate(4.2, 0.25, Easing.outBack) {
    from { rightHand(Hand.PALM, 560, 520, 0, 0.3) }
    to { rightHand(x = 640, y = 330, angle = 20, scale = 1) }
  }
  animate(4.45, 0.34, Easing.linear, iterations = 2f, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { rightHand(angle = 0) }
    at(25, Easing.easeInOutSine) { rightHand(angle = 22) }
    at(75, Easing.easeInSine) { rightHand(angle = -22) }
    to { rightHand(angle = 0) }
  }
  cue(4.3, "sparkle")
  actor(ActorKind.SPARKLE, layer = -1) { x = 800f; y = 870f; color = 0xFFFFF4C8.toInt(); visible = false }
    .animate(4.3, 0.75, Easing.linear, fill = Fill.NONE) {
      from(Easing.outBack) { visible(true); scale(0); rotation(0) }
      at(35, Easing.easeInOutSine) { scale(1.3); rotation(40) }
      to { scale(0); rotation(90) }
    }
  animate(5.15, 0.2, Easing.easeIn) { to { rightHand(x = 560, y = 520, scale = 0.3) } }
  setPip(5.36) { rightHand(Hand.NONE) }
  blinkAt(5.1)
  animate(5.14, 0.2, Easing.easeOut) { from { mood(Mood.HAPPY) }; to { look(0, 0); eyeScale(1) } }

  // Iris out on the moon.
  moon.animate(5.6, 0.36, Easing.easeIn) { from { progress(0) }; to { progress(1) } }

  // ================= Moment 3: 88 mph, twin fire trails, "Great Scott!" (5.95 - 9) =================
  setPip(5.96) {
    x(190); y(HOME_Y); size(300); rotation(0); shadow(1); squash(0.6, 1.5); alpha(0)
    mood(Mood.NEUTRAL); mouth(Mouth.FLAT); look(0.4, 0); blush(0); eyeScale(1)
  }
  cue(5.98, "poof")
  actor(ActorKind.SMOKE, layer = 2) { x = 190f; y = 1200f; scale = 1.6f; visible = false }
    .animate(5.96, 0.8, Easing.easeOut, fill = Fill.NONE) {
      from { visible(true); progress(0); scale(1.2) }
      to { progress(1); scale(2.0) }
    }
  animate(6.0, 0.42, Easing.linear) {
    from(Easing.easeOut) { alpha(1); squash(0.6, 1.5) }
    at(40, Easing.easeInOutSine) { squash(1.14, 0.88) }
    at(70, Easing.easeInOutSine) { squash(0.97, 1.03) }
    to { squash(1, 1) }
  }
  hatLag(6.0, 0.6f)
  // Determined: lids lower, brows knit, eyes lock on the road ahead.
  animate(6.12, 0.1, Easing.easeOut) { to { look(1, 0.05) } }
  animate(6.2, 0.25, Easing.easeOut) { to { lidTop(0.35); squint(0.25); brows(-0.2, 0.8) } }

  val speedo = actor(ActorKind.CUSTOM, layer = 2) { x = HOME_X; y = 760f; alpha = 0f; paint = SPEEDO }
  speedo.animate(6.15, 0.35, Easing.outBack) { from { alpha(0); scale(0.6) }; to { alpha(1); scale(1) } }
  speedo.animate(6.35, 1.05, Easing.cubicBezier(0.6f, 0f, 0.9f, 0.55f)) { from { progress(0) }; to { progress(1) } }
  speedo.animate(7.4, 0.06, Easing.linear, iterations = 4f, fill = Fill.NONE, composite = Composite.ADD) {
    from { rotation(0) }; at(25) { rotation(-3) }; at(75) { rotation(3) }; to { rotation(0) }
  }
  speedo.animate(7.7, 0.3, Easing.easeIn) { to { alpha(0); scale(1.1) } }

  // Revving: fists up, crouch, lean back, tremble, sparks.
  animate(6.4, 0.25, Easing.outBack) {
    from { leftHand(Hand.FIST, -20, 560, 0, 0.4); rightHand(Hand.FIST, 560, 560, 0, 0.4) }
    to { leftHand(x = -75, y = 520, scale = 1); rightHand(x = 615, y = 515, scale = 1) }
  }
  animate(6.4, 0.95, Easing.easeInOutSine) { to { squash(1.16, 0.86); rotation(-8) } }
  animate(6.7, 0.05, Easing.linear, iterations = 14f, fill = Fill.NONE, composite = Composite.ADD) {
    from { x(0) }; at(25) { x(-4) }; at(75) { x(4) }; to { x(0) }
  }
  animate(6.7, 0.07, Easing.linear, iterations = 10f, fill = Fill.NONE, composite = Composite.ADD) {
    from { hatSway(0) }; at(50) { hatSway(0.25) }; to { hatSway(0) }
  }
  val zap = actor(ActorKind.CUSTOM, layer = 2) { x = 190f; y = 1180f; paint = ZAP }
  zap.animate(6.7, 0.7, Easing.easeIn) { from { progress(0) }; to { progress(1) } }
  zap.animate(7.45, 0.06, Easing.linear) { to { progress(0) } }
  cue(6.85, "zap")
  cue(7.2, "zap")

  // BOOM: a flash, and he is gone, leaving two lines of fire.
  val trails = actor(ActorKind.CUSTOM, layer = -1) { alpha = 0f; paint = TRAILS }
  val flash = actor(ActorKind.CUSTOM, layer = 4) { alpha = 0f; paint = FLASH }
  cue(7.45, "boom")
  flash.animate(7.45, 0.35, Easing.linear, fill = Fill.NONE) {
    from { alpha(0) }; at(12, Easing.easeOut) { alpha(0.8) }; to { alpha(0) }
  }
  setPip(7.44) { leftHand(Hand.NONE); rightHand(Hand.NONE); mouth(Mouth.SHOUT) }
  animate(7.45, 0.18, Easing.easeIn) { to { x(1440); squash(1.3, 0.77); rotation(8) } }
  trails.animate(7.45, 0.001, Easing.linear) { to { alpha(1) } }
  trails.animate(7.45, 0.2, Easing.easeIn) { from { progress(0) }; to { progress(1) } }
  trails.animate(8.3, 0.65, Easing.easeIn) { from { progress(1) }; to { progress(2) } }

  // He skids back in from the right, leaning back, over his own fire tracks.
  setPip(7.9) {
    facing(-1); x(1320); squash(1, 1); rotation(14); mouth(Mouth.O)
    eyeScale(1.45); brows(1, -0.6); lidTop(0); squint(0); look(0.6, 0)
  }
  cue(8.0, "scratch")
  animate(7.95, 0.5, Easing.linear) { from(Easing.cubicBezier(0.1f, 0.75f, 0.3f, 1f)) { x(1320) }; to { x(HOME_X) } }
  animate(7.95, 0.78, Easing.linear) {
    from(Easing.easeOut) { rotation(16); squash(1.1, 0.91) }
    at(55, Easing.easeInOutSine) { rotation(-5); squash(0.94, 1.06) }
    at(80, Easing.easeInOutSine) { rotation(2); squash(1.02, 0.98) }
    to { rotation(0); squash(1, 1) }
  }
  animate(8.4, 0.55, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatDroop(0) }; at(25, Easing.easeInOutSine) { hatDroop(0.55) }
    at(60, Easing.easeInOutSine) { hatDroop(-0.12) }; to { hatDroop(0) }
  }
  for ((i, sx) in floatArrayOf(820f, 640f).withIndex()) {
    actor(ActorKind.SMOKE, layer = 1) { x = sx; y = 1320f; visible = false }
      .animate(8.12f + i * 0.16f, 0.7, Easing.easeOut, fill = Fill.NONE) {
        from { visible(true); progress(0); scale(0.7) }; to { progress(1); scale(1.2) }
      }
  }
  say("Great Scott!", 8.3, 0.7)
  // A quick hop to face front again: eyes first, blink on the turn.
  animate(8.6, 0.08, Easing.easeOut) { to { look(-0.6, 0) } }
  blinkAt(8.68, 0.14f)
  animate(8.7, 0.18, Easing.easeInOutSine) { from { facing(-1) }; to { facing(1) } }
  animate(8.7, 0.24, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { y(0) }; at(45, Easing.easeIn) { y(-26) }; to { y(0) }
  }
  animate(8.72, 0.22, Easing.easeOut) { from { mouth(Mouth.SMILE) }; to { eyeScale(1.1); brows(0.4, 0); look(0, 0) } }
}

// ================================================================================================================
// 1990s
// ================================================================================================================

private fun nineties() = scene("d90s", 9) {
  home()

  // ---------- Memphis-style banner: pops in, wiggles, pops out. ----------
  actor(ActorKind.CUSTOM, layer = 3) { x = HOME_X; y = 400f; alpha = 0f; paint = BANNER90 }
    .animate(0, 1.4, Easing.linear, fill = Fill.NONE) {
      from(Easing.outBack) { alpha(1); scale(0.3); rotation(-12) }
      at(22, Easing.easeInOutSine) { scale(1); rotation(3) }
      at(40, Easing.easeInOutSine) { rotation(-2) }
      at(60, Easing.easeInOutSine) { rotation(1) }
      at(80, Easing.inBack) { scale(1); rotation(0); alpha(1) }
      to { scale(0.4); alpha(0); rotation(8) }
    }

  // ================= Moment 1: hands-on-cheeks scream in the mirror (0 - 2.95) =================
  val mBack = actor(ActorKind.CUSTOM, layer = -1) { x = HOME_X; y = 1150f; alpha = 0f; paint = MIRROR_BACK }
  val mFront = actor(ActorKind.CUSTOM, layer = 1) { x = HOME_X; y = 1150f; alpha = 0f; paint = MIRROR_FRONT }
  for (m in listOf(mBack, mFront)) {
    m.animate(0, 0.45, Easing.outBack) { from { scale(0.86) }; to { scale(1) } }
    m.animate(0, 0.25, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
    m.animate(1.3, 0.08, Easing.linear, iterations = 9f, fill = Fill.NONE, composite = Composite.ADD) {
      from { rotation(0) }; at(25) { rotation(-1.4) }; at(75) { rotation(1.4) }; to { rotation(0) }
    }
    m.animate(2.42, 0.28, Easing.inBack) { to { scale(0.72) } }
    m.animate(2.42, 0.28, Easing.easeIn) { to { alpha(0) } }
  }
  mFront.animate(0.25, 0.9, Easing.easeInOutSine) { from { progress(0) }; to { progress(1) } }
  mFront.animate(1.3, 0.45, Easing.easeOut) { from { progress(0) }; to { progress(1) } }

  // Admires himself: sly lids, a pleased little head tilt.
  animate(0.1, 0.1, Easing.easeOut) { to { look(0.2, 0.15) } }
  animate(0.15, 0.35, Easing.easeOut) { from { mouth(Mouth.SMILE) }; to { lidTop(0.3); squint(0.35); brows(0.3, 0) } }
  animate(0.22, 0.55, Easing.linear) {
    from(Easing.easeInOutSine) { rotation(0) }; at(45, Easing.easeInOutSine) { rotation(5) }; to { rotation(-1) }
  }
  hatLag(0.22, 0.35f)
  // Anticipation: palms come up and out (left a frame ahead of right), he rises onto his toes.
  animate(0.58, 0.38, Easing.easeOut) {
    from { leftHand(Hand.PALM, -40, 560, -10, 0.4) }
    to { leftHand(x = -150, y = 380, angle = -35, scale = 1) }
  }
  animate(0.62, 0.36, Easing.easeOut) {
    from { rightHand(Hand.PALM, 580, 560, 10, 0.4) }
    to { rightHand(x = 690, y = 360, angle = 35, scale = 1) }
  }
  animate(0.6, 0.38, Easing.easeOut) { to { squash(0.95, 1.05); rotation(0); squint(0.6) } }
  // SLAP.
  cue(0.99, "pow")
  animate(0.96, 0.07, Easing.easeIn) { to { leftHand(x = 150, y = 495, angle = -12, scale = 0.95) } }
  animate(0.97, 0.07, Easing.easeIn) { to { rightHand(x = 490, y = 478, angle = 12, scale = 0.95) } }
  animate(0.96, 0.32, Easing.linear) {
    from(Easing.easeIn) { squash(0.95, 1.05) }
    at(25, Easing.easeOut) { squash(1.14, 0.88); eyesOpen(0.12); squint(0); lidTop(0); mouth(Mouth.FLAT) }
    to { squash(1.04, 0.96) }
  }
  hatLag(0.98, -0.4f, 0.4f)
  // The beat... then eyes POP and he screams.
  cue(1.3, "scream")
  animate(1.3, 0.32, Easing.linear) {
    from(Easing.outBack) { eyesOpen(0.12); eyeScale(1); mouth(Mouth.SHOUT) }
    at(30, Easing.easeInOutSine) { eyesOpen(1); eyeScale(1.7); squash(0.88, 1.14) }
    to { squash(0.96, 1.04) }
  }
  animate(1.3, 0.12, Easing.outBack) { to { brows(1, -0.7) } }
  animate(1.3, 0.7, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.outBack) { hatDroop(0) }; at(20, Easing.easeInOutSine) { hatDroop(-0.5) }; to { hatDroop(0) }
  }
  animate(1.34, 0.04, Easing.linear, iterations = 19f, fill = Fill.NONE, composite = Composite.ADD) {
    from { x(0) }; at(25) { x(-5) }; at(75) { x(5) }; to { x(0) }
  }
  actor(ActorKind.CUSTOM, layer = 2) { x = 548f; y = 1185f; alpha = 0f; paint = SCREAM }
    .animate(1.3, 0.85, Easing.linear, fill = Fill.NONE) {
      from(Easing.easeOut) { alpha(0); scale(0.7) }
      at(15) { alpha(1); scale(1) }
      at(80) { alpha(1) }
      to { alpha(0); scale(1.1) }
    }

  // Recover, look up, crouch for the hop.
  animate(2.1, 0.2, Easing.easeIn) { to { leftHand(x = -40, y = 600, scale = 0.3) } }
  animate(2.13, 0.2, Easing.easeIn) { to { rightHand(x = 580, y = 600, scale = 0.3) } }
  setPip(2.34) { leftHand(Hand.NONE); rightHand(Hand.NONE) }
  animate(2.05, 0.1, Easing.easeOut) { to { look(0.5, -0.5) } }
  blinkAt(2.1)
  animate(2.14, 0.2, Easing.easeOut) { from { mouth(Mouth.O) }; to { eyeScale(1.15); brows(0.5, -0.2) } }
  animate(2.18, 0.22, Easing.easeOut) { to { squash(1.2, 0.83) } }

  // Hop out of the bathroom and onto the bow, while the mirror poofs away.
  cue(2.42, "poof")
  actor(ActorKind.SMOKE, layer = 2) { x = HOME_X; y = 1150f; visible = false }
    .animate(2.42, 0.75, Easing.easeOut, fill = Fill.NONE) {
      from { visible(true); progress(0); scale(2.2) }; to { progress(1); scale(3.0) }
    }
  animate(2.4, 0.55, Easing.linear) {
    from(Easing.easeOut) { y(HOME_Y); squash(0.84, 1.19) }
    at(18, Easing.easeOut) { squash(1, 1) }
    at(48, Easing.easeIn) { y(1030); rotation(6); squash(1.04, 0.96) }
    to { y(1361); rotation(0); squash(0.9, 1.11) }
  }
  animate(2.4, 0.55, Easing.easeInOutSine) { to { x(560) } }
  animate(2.4, 0.55, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatDroop(0) }; at(45, Easing.easeInOutSine) { hatDroop(-0.3) }; at(85) { hatDroop(0.25) }; to { hatDroop(0) }
  }

  // ================= Moment 2: "I'm flying!" at the bow over a sunset sea (2.95 - 5.6) =================
  val sunset = actor(ActorKind.CUSTOM, layer = -1) { x = HOME_X; y = PANEL_Y; alpha = 0f; paint = SUNSET }
  val bow = actor(ActorKind.CUSTOM, layer = -1) { x = HOME_X; y = PANEL_Y; alpha = 0f; paint = BOW }
  val wind = actor(ActorKind.CUSTOM, layer = 2) { x = HOME_X; y = PANEL_Y; alpha = 0f; paint = WIND }
  for (p in listOf(sunset, bow)) {
    p.animate(2.55, 0.42, Easing.outBack) { from { scale(0.82) }; to { scale(1) } }
    p.animate(2.55, 0.25, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
    p.whipOut(5.5)
  }
  bow.bob(3.0, 1.25f, 2f, 9f)
  pipBob(3.0, 1.25f, 2f, 9f)
  wind.animate(2.9, 0.4, Easing.easeOut) { from { alpha(0) }; to { alpha(1) } }
  wind.animate(5.4, 0.15, Easing.easeIn) { to { alpha(0) } }

  cue(2.95, "land")
  cue(3.0, "splash")
  // The bow has its own deck, so no screen-floor shadow while he is on it.
  animate(2.85, 0.12, Easing.easeOut) { to { shadow(0) } }
  animate(5.5, 0.3, Easing.easeIn) { to { shadow(1) } }
  animate(2.95, 0.36, Easing.linear) {
    from(Easing.easeOut) { squash(1.22, 0.82) }
    at(40, Easing.easeInOutSine) { squash(0.95, 1.05) }
    to { squash(1, 1) }
  }
  hatLag(2.95, 0.5f)
  // Wonder: looks out to sea, a slow breath.
  animate(3.1, 0.12, Easing.easeOut) { to { look(1, -0.1) } }
  animate(3.2, 0.3, Easing.outBack) { to { eyeScale(1.2); brows(0.6, -0.1) } }
  blinkAt(3.45)
  blinkAt(3.68, 0.14f)
  // Anticipation: palms gather at the chest, he rises onto his toes and breathes in.
  animate(3.5, 0.35, Easing.easeOut) {
    from { leftHand(Hand.PALM, 100, 600, -20, 0.3); rightHand(Hand.PALM, 440, 600, 20, 0.3) }
    to { leftHand(x = 110, y = 560, scale = 0.8); rightHand(x = 430, y = 560, scale = 0.8) }
  }
  animate(3.5, 0.38, Easing.easeOut) { to { squash(0.95, 1.06) } }
  // Arms sweep wide (left leads by a frame), eyes shut in bliss: the mood swaps behind the lids.
  cue(3.9, "whoosh")
  animate(3.88, 0.38, Easing.outBack) { to { leftHand(x = -240, y = 390, angle = -85, scale = 1) } }
  animate(3.92, 0.38, Easing.outBack) { to { rightHand(x = 780, y = 370, angle = 85, scale = 1) } }
  blinkAt(3.88, 0.12f)
  animate(3.92, 0.3, Easing.easeOut) {
    from { mood(Mood.HAPPY); mouth(Mouth.SMILE) }
    to { squash(1, 1); blush(0.7); eyeScale(1); brows(0.4, -0.2); look(0.3, -0.2); rotation(-3) }
  }
  // Glide: hat streams back, arms ride the air on the beat, a gentle sway.
  animate(3.9, 1.55, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatSway(0) }; at(15) { hatSway(-0.8) }; at(85) { hatSway(-0.8) }; to { hatSway(0) }
  }
  animate(3.9, 0.21, Easing.easeInOutSine, iterations = 7f, direction = Direction.ALTERNATE, fill = Fill.NONE, composite = Composite.ADD) {
    from { hatSway(-0.1) }; to { hatSway(0.1) }
  }
  animate(4.3, 0.5, Easing.linear, iterations = 2f, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { leftHand(y = 0); rightHand(y = 0) }
    at(25, Easing.easeInOutSine) { leftHand(y = -16); rightHand(y = 12) }
    at(75, Easing.easeInSine) { leftHand(y = 16); rightHand(y = -12) }
    to { leftHand(y = 0); rightHand(y = 0) }
  }
  animate(4.3, 1.0, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { rotation(0) }; at(25, Easing.easeInOutSine) { rotation(2.5) }
    at(75, Easing.easeInSine) { rotation(-2.5) }; to { rotation(0) }
  }
  say("I'm flying!", 4.2, 1.15)

  // Arms fold away, eyes open, and the camera whips to bullet time.
  animate(5.3, 0.18, Easing.easeIn) { to { leftHand(x = 100, y = 580, angle = 0, scale = 0.3) } }
  animate(5.33, 0.18, Easing.easeIn) { to { rightHand(x = 440, y = 580, angle = 0, scale = 0.3) } }
  setPip(5.52) { leftHand(Hand.NONE); rightHand(Hand.NONE) }
  blinkAt(5.32)
  animate(5.36, 0.15, Easing.easeOut) { from { mood(Mood.NEUTRAL); mouth(Mouth.FLAT) }; to { look(1, 0); blush(0); brows(0.2, 0) } }
  streaks(5.45)
  animate(5.5, 0.35, Easing.linear) {
    from(Easing.easeOut) { x(560); rotation(-3) }
    at(50, Easing.easeInOutSine) { x(534); rotation(-6) }
    to { x(HOME_X); rotation(0) }
  }
  hatLag(5.5, 0.5f)

  // ================= Moment 3: bullet time (5.6 - 9) =================
  val mx = actor(ActorKind.CUSTOM, layer = -1) { x = 1950f; y = PANEL_Y; paint = MATRIX }
  val orbit = actor(ActorKind.CUSTOM, layer = -1) { x = 1950f; y = 1200f; progress = 1f; paint = ORBIT }
  val tint = actor(ActorKind.CUSTOM, layer = 2) { x = 1950f; y = PANEL_Y; paint = TINT }
  for (p in listOf(mx, orbit, tint)) {
    p.whipIn(5.6)
    p.animate(8.55, 0.45, Easing.easeIn) { to { alpha(0) } }
  }

  // Eyes dart: who's there?
  animate(5.92, 0.6, Easing.linear) {
    from(snap) { look(1, 0) }; at(18, snap) { look(-1, 0) }; at(55, snap) { look(-1, 0) }
    at(70, snap) { look(0.8, -0.1) }; to { look(0.8, -0.1) }
  }
  blinkAt(6.0, 0.14f)
  // Shades drop on.
  setPip(6.12) { sunglasses(0) }
  animate(6.12, 0.5, Easing.outBounce) { to { sunglasses(1) } }
  cue(6.3, "pop")
  animate(6.3, 0.2, Easing.easeOut) { to { brows(-0.1, 0.5); lidTop(0.3); eyeScale(1) } }

  // Bullet time is a frozen, locked stare: the idle layer sleeps from the shot until he snaps back.
  animate(6.55, 1.6, Easing.linear, fill = Fill.NONE) { from { life(0) }; to { life(0) } }
  animate(6.72, 0.5, Easing.easeOut) { to { shadow(0.4) } }
  animate(8.1, 0.3, Easing.easeOut) { to { shadow(1) } }
  // The shot. A real-speed flinch...
  cue(6.55, "pow")
  animate(6.56, 0.14, Easing.easeOut) { from { mouth(Mouth.O) }; to { squash(1.1, 0.91); eyeScale(1.25); brows(0.8, -0.4) } }
  // ...then the slow-motion bend back.
  animate(6.72, 0.9, Easing.cubicBezier(0.25f, 0.6f, 0.35f, 1f)) { to { rotation(-65); squash(1.04, 0.96) } }
  animate(6.72, 1.4, Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { hatSway(0) }; at(35, Easing.easeInOutSine) { hatSway(0.7) }; at(80) { hatSway(0.6) }; to { hatSway(0) }
  }
  animate(6.68, 0.35, Easing.outBack) {
    from { leftHand(Hand.PALM, -40, 520, 0, 0.4) }
    to { leftHand(x = -130, y = 360, angle = -55, scale = 1) }
  }
  animate(6.74, 0.35, Easing.outBack) {
    from { rightHand(Hand.PALM, 600, 520, 0, 0.4) }
    to { rightHand(x = 660, y = 250, angle = 45, scale = 1) }
  }
  // Arms windmill in slow motion.
  animate(7.0, 0.6, Easing.linear, iterations = 2f, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOutSine) { leftHand(angle = 0, y = 0); rightHand(angle = 0, y = 0) }
    at(25, Easing.easeInOutSine) { leftHand(angle = -28, y = -45); rightHand(angle = 30, y = 50) }
    at(75, Easing.easeInSine) { leftHand(angle = 20, y = 30); rightHand(angle = -20, y = -35) }
    to { leftHand(angle = 0, y = 0); rightHand(angle = 0, y = 0) }
  }

  fun shot(t0: Float, y0: Float) {
    actor(ActorKind.CUSTOM, layer = 3) { x = 1250f; y = y0; alpha = 0f; paint = BULLET }
      .animate(t0, 1.5, Easing.linear, fill = Fill.NONE) {
        from { x(1180); progress(0); alpha(1) }
        to { x(-150); progress(1330); alpha(1) }
      }
  }
  shot(6.6f, 1000f); cue(7.3, "whoosh")
  shot(6.8f, 950f); cue(7.52, "whoosh")
  shot(7.0f, 1030f); cue(7.72, "whoosh")

  // Snap back upright at full speed, smug.
  cue(8.1, "whoosh")
  animate(8.1, 0.4, Easing.linear) {
    from(Easing.easeIn) { rotation(-65); squash(1.04, 0.96) }
    at(50, Easing.easeOut) { rotation(8); squash(0.94, 1.06) }
    at(78, Easing.easeInOutSine) { rotation(-2); squash(1.02, 0.98) }
    to { rotation(0); squash(1, 1) }
  }
  hatLag(8.1, -0.7f)
  animate(8.08, 0.16, Easing.easeIn) { to { leftHand(x = -40, y = 560, angle = 0, scale = 0.3) } }
  animate(8.11, 0.16, Easing.easeIn) { to { rightHand(x = 580, y = 560, angle = 0, scale = 0.3) } }
  setPip(8.28) { leftHand(Hand.NONE); rightHand(Hand.NONE) }
  animate(8.3, 0.2, Easing.easeOut) { from { mouth(Mouth.SMILE) }; to { brows(0.3, 0); eyeScale(1); lidTop(0.25); look(0, 0.1) } }
  say("Whoa.", 8.25, 0.75)
  animate(8.55, 0.32, Easing.easeIn) { to { sunglasses(0) } }
  setPip(8.88) { sunglasses(-1); lidTop(0) }
}

// ================================================================================================================
// Drawing. Every painter is a pure function of the actor's values and the time; paints are reused.
// ================================================================================================================

private typealias Painter = (Canvas, ActorState, Float) -> Unit

private const val HW = 500f
private const val HH = 430f
private const val INK_C = 0xFF3A2A26.toInt()
private const val SIL = 0xFF0A0E22.toInt()
private const val GHOST_C = 0xFFFF4FA3.toInt()
private const val DOT_Y = 94f
private const val MOON_Y = -60f
private const val MOON_R = 320f
private const val HORIZON = 60f

private val pf = Paint(Paint.ANTI_ALIAS_FLAG)
private val ps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
}
private val pt = TextPaint(Paint.ANTI_ALIAS_FLAG)
private val pp = Path()
private val pp2 = Path()
private val rr = RectF()
private val blur8 = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
private val blur16 = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
private val blur30 = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)

private fun solid(color: Int, blur: BlurMaskFilter? = null): Paint {
  pf.shader = null; pf.maskFilter = blur; pf.color = color; return pf
}
private fun shaded(s: Shader): Paint { pf.maskFilter = null; pf.color = Color.WHITE; pf.shader = s; return pf }
private fun pen(color: Int, w: Float, blur: BlurMaskFilter? = null): Paint {
  ps.shader = null; ps.maskFilter = blur; ps.color = color; ps.strokeWidth = w; return ps
}
private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg colors: Long) =
  LinearGradient(x0, y0, x1, y1, IntArray(colors.size) { colors[it].toInt() }, null, Shader.TileMode.CLAMP)
private fun rad(cx: Float, cy: Float, r: Float, vararg colors: Long) =
  RadialGradient(cx, cy, r, IntArray(colors.size) { colors[it].toInt() }, null, Shader.TileMode.CLAMP)

private fun panelShadow(c: Canvas) {
  rr.set(-HW + 10f, -HH + 26f, HW - 10f, HH + 26f)
  c.drawRoundRect(rr, 56f, 56f, solid(0x553A2A26, blur30))
}
private fun clipPanel(c: Canvas) {
  pp.reset(); rr.set(-HW, -HH, HW, HH); pp.addRoundRect(rr, 56f, 56f, Path.Direction.CW); c.clipPath(pp)
}
private fun panelBorder(c: Canvas) {
  rr.set(-HW, -HH, HW, HH)
  c.drawRoundRect(rr, 56f, 56f, pen(INK_C, 6f))
  rr.inset(7f, 7f)
  c.drawRoundRect(rr, 50f, 50f, pen(0x26FFFFFF, 3f))
}

// ---------------------------------------------------------------- shared

private val STREAKS: Painter = { c, a, _ ->
  for (i in 0 until 22) {
    val y = -420f + 840f * hash01(i * 3 + 1)
    val len = 220f + 420f * hash01(i * 3 + 2)
    val sp = 0.8f + 0.6f * hash01(i * 3 + 3)
    val x = 900f - fract(a.progress * sp + hash01(i * 7)) * 1800f
    val th = 3f + 7f * hash01(i * 5)
    val dark = i % 3 == 0
    c.drawLine(x, y, x + len, y, pen(if (dark) 0x553A2A26 else 0xD9FFFFFF.toInt(), th))
  }
}

// ---------------------------------------------------------------- 1980s

private val neonPlate by lazy { lin(0f, -120f, 0f, 120f, 0xFF2A1458, 0xFF0B0620) }
private val neonFace: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

private val NEON80: Painter = { c, a, t ->
  val power = clamp01(a.progress)
  val hum = 0.9f + 0.1f * sin(t * 57f)
  rr.set(-340f, -100f, 340f, 132f); c.drawRoundRect(rr, 40f, 40f, solid(0x663A2A26, blur16))
  rr.set(-340f, -120f, 340f, 112f)
  c.drawRoundRect(rr, 40f, 40f, shaded(neonPlate))
  c.drawRoundRect(rr, 40f, 40f, pen(INK_C, 6f))
  // Bolts in the corners.
  for (sx in floatArrayOf(-312f, 312f)) for (sy in floatArrayOf(-92f, 84f)) c.drawCircle(sx, sy, 6f, solid(0xFF4A3A70.toInt()))
  // Thin tube frame.
  rr.inset(22f, 22f)
  c.drawRoundRect(rr, 24f, 24f, pen(withAlpha(0xFF2EF3FF.toInt(), 0.35f * power * hum), 12f, blur8))
  c.drawRoundRect(rr, 24f, 24f, pen(withAlpha(0xFF8FF8FF.toInt(), power), 3f))
  // The lettering is an outlined neon tube: wide soft glow, a coloured tube, a hot white core.
  pt.typeface = neonFace; pt.textSize = 150f; pt.textAlign = Paint.Align.CENTER; pt.textSkewX = 0f
  pt.style = Paint.Style.STROKE; pt.strokeJoin = Paint.Join.ROUND; pt.shader = null
  pt.maskFilter = blur16; pt.strokeWidth = 24f; pt.color = withAlpha(0xFFFF2FB4.toInt(), 0.55f * power * hum)
  c.drawText("1980s", 0f, 46f, pt)
  pt.maskFilter = null; pt.strokeWidth = 10f; pt.color = withAlpha(0xFFFF4FC4.toInt(), 0.25f + 0.75f * power)
  c.drawText("1980s", 0f, 46f, pt)
  pt.strokeWidth = 3.5f; pt.color = withAlpha(0xFFFFE6F8.toInt(), power)
  c.drawText("1980s", 0f, 46f, pt)
  pt.style = Paint.Style.FILL
  // Cyan swoosh underline.
  pp.reset(); pp.moveTo(-230f, 82f); pp.cubicTo(-80f, 62f, 120f, 98f, 250f, 70f)
  c.drawPath(pp, pen(withAlpha(0xFF2EF3FF.toInt(), 0.5f * power * hum), 18f, blur16))
  c.drawPath(pp, pen(withAlpha(0xFF2EF3FF.toInt(), 0.3f + 0.7f * power), 7f))
  c.drawPath(pp, pen(withAlpha(0xFFE0FFFF.toInt(), power), 2.5f))
}

private val mazeBg by lazy { lin(0f, -HH, 0f, HH, 0xFF0A0D2E, 0xFF140B38, 0xFF07081E) }
private val mazeVignette by lazy { rad(0f, 0f, 720f, 0x00000000, 0x00000000, 0x66000000) }
private val mazeWalls = arrayOf(
  floatArrayOf(-420f, -330f, -170f, -262f), floatArrayOf(-110f, -330f, 110f, -262f), floatArrayOf(170f, -330f, 420f, -262f),
  floatArrayOf(-420f, 236f, -170f, 300f), floatArrayOf(-110f, 236f, 110f, 300f), floatArrayOf(170f, 236f, 420f, 300f),
  floatArrayOf(-300f, 334f, 300f, 384f),
)
private val pixelFace: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

private val MAZE: Painter = { c, a, t ->
  panelShadow(c)
  c.save(); clipPanel(c)
  c.drawRect(-HW, -HH, HW, HH, shaded(mazeBg))
  // Walls: a soft blue bloom under a crisp double outline.
  rr.set(-HW + 26f, -HH + 66f, HW - 26f, HH - 26f)
  c.drawRoundRect(rr, 34f, 34f, pen(0x662E6BFF, 16f, blur8))
  c.drawRoundRect(rr, 34f, 34f, pen(0xFF3D7BFF.toInt(), 5f))
  rr.inset(11f, 11f); c.drawRoundRect(rr, 24f, 24f, pen(0xFF1C3BC8.toInt(), 3f))
  for (w in mazeWalls) {
    rr.set(w[0], w[1], w[2], w[3])
    c.drawRoundRect(rr, 22f, 22f, pen(0x662E6BFF, 16f, blur8))
    c.drawRoundRect(rr, 22f, 22f, pen(0xFF3D7BFF.toInt(), 5f))
    rr.inset(11f, 11f); c.drawRoundRect(rr, 12f, 12f, pen(0xFF1C3BC8.toInt(), 3f))
  }
  // Dots: eaten once the chomper's mouth (progress, screen x) has passed them.
  val eatenTo = a.progress - HOME_X
  var eaten = 0
  for (k in 0..12) {
    val dx = 147f + 56f * k - HOME_X
    if (dx <= eatenTo) { eaten++; continue }
    rr.set(dx - 9f, DOT_Y - 9f, dx + 9f, DOT_Y + 9f)
    c.drawRoundRect(rr, 4f, 4f, solid(0xFFFFD9B8.toInt()))
  }
  val pulse = 0.5f + 0.5f * sin(t * 10f)
  c.drawCircle(448f, DOT_Y, 30f, solid(withAlpha(0xFFFFD9B8.toInt(), 0.35f * pulse), blur8))
  c.drawCircle(448f, DOT_Y, 16f + 3f * pulse, solid(0xFFFFE6CC.toInt()))
  // Score line.
  pt.typeface = pixelFace; pt.textSize = 34f; pt.style = Paint.Style.FILL; pt.maskFilter = null; pt.shader = null; pt.textSkewX = 0f
  pt.textAlign = Paint.Align.LEFT; pt.color = 0xFFFF4A4A.toInt(); c.drawText("1UP", -440f, -384f, pt)
  pt.color = Color.WHITE; c.drawText((1000 + eaten * 10).toString().padStart(6, '0'), -360f, -384f, pt)
  pt.textAlign = Paint.Align.RIGHT; pt.color = 0xFF8FB4FF.toInt(); c.drawText("HI 025000", 440f, -384f, pt)
  pt.textAlign = Paint.Align.LEFT
  // CRT scanlines and vignette.
  var y = -HH
  val scan = pen(0x18000000, 2f)
  while (y < HH) { c.drawLine(-HW, y, HW, y, scan); y += 6f }
  c.drawRect(-HW, -HH, HW, HH, shaded(mazeVignette))
  c.restore()
  panelBorder(c)
}

private val ghostBody by lazy { rad(-40f, -80f, 240f, 0xFFFF9BCB, GHOST_C.toLong() and 0xFFFFFFFFL, 0xFFB8226E) }

private val GHOST: Painter = { c, a, t ->
  c.drawCircle(0f, 10f, 140f, solid(withAlpha(GHOST_C, 0.3f), blur30))
  pp.reset()
  pp.moveTo(-100f, 110f); pp.lineTo(-100f, -20f)
  rr.set(-100f, -120f, 100f, 80f); pp.arcTo(rr, 180f, 180f, false)
  pp.lineTo(100f, 110f)
  for (i in 0 until 4) {
    val x0 = 100f - 50f * i; val x1 = x0 - 50f
    val wob = 8f * sin(t * 9f + i * 1.7f)
    pp.quadTo((x0 + x1) / 2f, 110f + (if (i % 2 == 0) 36f else -8f) + wob, x1, 110f)
  }
  pp.close()
  c.drawPath(pp, shaded(ghostBody))
  c.drawPath(pp, pen(INK_C, 5f))
  rr.set(-70f, -100f, -20f, -66f); c.drawOval(rr, solid(0x59FFFFFF))
  val look = a.progress * 11f
  for (s in floatArrayOf(-1f, 1f)) {
    rr.set(s * 38f - 26f, -67f, s * 38f + 26f, -1f)
    c.drawOval(rr, solid(Color.WHITE)); c.drawOval(rr, pen(INK_C, 3f))
    c.drawCircle(s * 38f + look, -28f, 13f, solid(0xFF1D2263.toInt()))
    c.drawCircle(s * 38f + look - 4f, -32f, 4f, solid(Color.WHITE))
  }
  // A tiny wobbly grin.
  pp.reset(); pp.moveTo(-30f, 40f)
  for (i in 1..6) pp.lineTo(-30f + i * 10f, 40f + (if (i % 2 == 0) 0f else 7f))
  c.drawPath(pp, pen(0xFF7A1446.toInt(), 4f))
}

private val nightSky by lazy { lin(0f, -HH, 0f, HH, 0xFF0B1433, 0xFF1A2756, 0xFF2B3A6B) }
private val moonGlow by lazy { rad(0f, MOON_Y, 560f, 0x88FFF1CC, 0x22FFE7B0, 0x00FFE7B0) }
private val moonFace by lazy { rad(-90f, MOON_Y - 110f, 440f, 0xFFFFFDF2, 0xFFF8EAC6, 0xFFE4D09C) }
private val craters = floatArrayOf(-120f, -120f, 48f, 90f, -160f, 30f, 140f, 40f, 62f, -60f, 90f, 38f, -180f, 40f, 24f, 40f, -40f, 20f, 210f, -70f, 22f)
private val treesFar by lazy { treeLine(300f, 0.75f, 11) }
private val treesNear by lazy { treeLine(345f, 1f, 3) }

private fun treeLine(base: Float, k: Float, seed: Int): Path = Path().apply {
  moveTo(-HW - 20f, HH + 20f); lineTo(-HW - 20f, base)
  var x = -HW - 20f; var i = 0
  while (x < HW + 40f) {
    val b = base + 22f * sin(x / 170f + seed)
    val h = (70f + 110f * hash01(i * 7 + seed)) * k
    val w = (22f + 14f * hash01(i * 3 + seed)) * k
    lineTo(x - w, b); lineTo(x - w * 0.55f, b - h * 0.45f); lineTo(x - w * 0.8f, b - h * 0.45f)
    lineTo(x, b - h); lineTo(x + w * 0.8f, b - h * 0.45f); lineTo(x + w * 0.55f, b - h * 0.45f); lineTo(x + w, b)
    x += w * 2f + 6f * hash01(i + seed); i++
  }
  lineTo(HW + 40f, HH + 20f); close()
}

private val MOON: Painter = { c, a, t ->
  val iris = clamp01(a.progress)
  c.save()
  if (iris > 0f) { pp2.reset(); pp2.addCircle(0f, MOON_Y, (1f - iris) * 1000f, Path.Direction.CW); c.clipPath(pp2) }
  panelShadow(c)
  c.save(); clipPanel(c)
  c.drawRect(-HW, -HH, HW, HH, shaded(nightSky))
  for (i in 0 until 46) {
    val tw = 0.35f + 0.65f * max(0f, sin(t * (1.3f + hash01(i + 9)) + i * 2.1f))
    c.drawCircle(-HW + 1000f * hash01(i + 100), -HH + 700f * hash01(i + 200), 1.5f + 2.5f * hash01(i + 300), solid(withAlpha(Color.WHITE, tw)))
  }
  c.drawCircle(0f, MOON_Y, 560f, shaded(moonGlow))
  c.drawCircle(0f, MOON_Y, MOON_R, shaded(moonFace))
  c.save(); pp.reset(); pp.addCircle(0f, MOON_Y, MOON_R, Path.Direction.CW); c.clipPath(pp)
  var j = 0
  while (j < craters.size) {
    val cx = craters[j]; val cy = MOON_Y + craters[j + 1]; val r = craters[j + 2]
    c.drawCircle(cx, cy, r, solid(0x40C2A672))
    c.drawCircle(cx + r * 0.12f, cy + r * 0.12f, r * 0.82f, solid(0x30FFFFFF))
    j += 3
  }
  c.restore()
  c.drawPath(treesFar, solid(0xFF141C3E.toInt()))
  c.drawPath(treesNear, solid(0xFF070B1C.toInt()))
  c.restore()
  panelBorder(c)
  c.restore()
  if (iris > 0f && iris < 1f) c.drawCircle(0f, MOON_Y, (1f - iris) * 1000f, pen(INK_C, 8f))
}

/** Two-bone leg: hip to foot with equal bones, knee bent forward (+x). Writes the knee into [out]. */
private fun knee(hx: Float, hy: Float, fx: Float, fy: Float, len: Float, out: FloatArray) {
  val dx = fx - hx; val dy = fy - hy
  val d = min(sqrt(dx * dx + dy * dy), len * 2f - 0.01f).coerceAtLeast(0.01f)
  val h = sqrt((len * len - d * d / 4f).coerceAtLeast(0f))
  val mx = hx + dx / 2f; val my = hy + dy / 2f
  val px = -dy / d; val py = dx / d
  val s = if (px >= 0f) 1f else -1f
  out[0] = mx + px * h * s; out[1] = my + py * h * s
}
private val kneeOut = FloatArray(2)

private val BIKE: Painter = { c, _, t ->
  val spin = t * 11f
  for (wx in floatArrayOf(-115f, 125f)) {
    c.drawCircle(wx, 40f, 66f, pen(SIL, 11f))
    for (s in 0 until 8) {
      val ang = spin + s * TAU / 8f
      c.drawLine(wx, 40f, wx + cos(ang) * 62f, 40f + sin(ang) * 62f, pen(SIL, 2.5f))
    }
    c.drawCircle(wx, 40f, 8f, solid(SIL))
  }
  val frame = pen(SIL, 9f)
  c.drawLine(-115f, 40f, -40f, -58f, frame); c.drawLine(-40f, -58f, 5f, 40f, frame); c.drawLine(5f, 40f, -115f, 40f, frame)
  c.drawLine(-40f, -58f, 92f, -50f, frame); c.drawLine(5f, 40f, 92f, -50f, frame); c.drawLine(92f, -50f, 125f, 40f, frame)
  c.drawLine(92f, -50f, 100f, -86f, frame); c.drawLine(100f, -86f, 74f, -94f, pen(SIL, 8f))
  rr.set(-64f, -74f, -16f, -60f); c.drawRoundRect(rr, 7f, 7f, solid(SIL))
  // Rider: a hooded kid, pedalling.
  val crank = spin * 0.9f
  for (side in 0..1) {
    val ang = crank + side * Math.PI.toFloat()
    val fx = 5f + cos(ang) * 24f; val fy = 40f + sin(ang) * 24f
    knee(-38f, -74f, fx, fy, 80f, kneeOut)
    val leg = pen(if (side == 0) 0xFF141A36.toInt() else SIL, 20f)
    c.drawLine(-38f, -74f, kneeOut[0], kneeOut[1], leg); c.drawLine(kneeOut[0], kneeOut[1], fx, fy, leg)
    c.drawLine(fx - 10f, fy, fx + 16f, fy, pen(SIL, 12f))
  }
  c.drawLine(-40f, -80f, 4f, -150f, pen(SIL, 48f))
  c.drawLine(8f, -142f, 76f, -94f, pen(SIL, 16f))
  c.drawCircle(26f, -188f, 31f, solid(SIL))
  pp.reset(); pp.moveTo(-8f, -170f); pp.quadTo(-6f, -230f, 34f, -222f); pp.quadTo(14f, -200f, 10f, -168f); pp.close()
  c.drawPath(pp, solid(SIL))
}

private val BASKET: Painter = { c, _, _ ->
  c.drawLine(104f, -60f, 92f, -52f, pen(SIL, 6f))
  pp.reset(); pp.moveTo(104f, -68f); pp.lineTo(206f, -68f); pp.lineTo(196f, -22f); pp.lineTo(114f, -22f); pp.close()
  c.drawPath(pp, solid(SIL))
  val weave = pen(0xFF1E2750.toInt(), 2.5f)
  for (yy in floatArrayOf(-56f, -44f, -32f)) {
    val inset = (yy + 68f) / 46f * 10f
    c.drawLine(104f + inset + 3f, yy, 206f - inset - 3f, yy, weave)
  }
  for (xx in floatArrayOf(126f, 146f, 166f, 186f)) c.drawLine(xx, -64f, xx - (xx - 155f) * 0.08f, -26f, weave)
  c.drawLine(100f, -68f, 210f, -68f, pen(SIL, 8f))
}

private val speedoBg by lazy { lin(0f, -100f, 0f, 100f, 0xFF2A1512, 0xFF0E0605) }
private val SPEEDO: Painter = { c, a, t ->
  rr.set(-250f, -86f, 250f, 116f); c.drawRoundRect(rr, 28f, 28f, solid(0x663A2A26, blur16))
  rr.set(-250f, -100f, 250f, 100f)
  c.drawRoundRect(rr, 28f, 28f, shaded(speedoBg))
  c.drawRoundRect(rr, 28f, 28f, pen(INK_C, 6f))
  rr.inset(12f, 12f); c.drawRoundRect(rr, 18f, 18f, pen(0xFF5A4A4A.toInt(), 3f))
  pt.typeface = pixelFace; pt.textSkewX = -0.08f; pt.shader = null; pt.style = Paint.Style.FILL
  pt.textSize = 150f; pt.textAlign = Paint.Align.RIGHT
  pt.maskFilter = null; pt.color = 0xFF2E0C0A.toInt(); c.drawText("88", 92f, 52f, pt)
  val v = (clamp01(a.progress) * 88f).roundToInt().toString().padStart(2, ' ')
  val lit = if (a.progress >= 1f) (if (sin(t * 28f) > 0f) 1f else 0.6f) else 1f
  pt.maskFilter = blur16; pt.color = withAlpha(0xFFFF3B2F.toInt(), 0.7f * lit); c.drawText(v, 92f, 52f, pt)
  pt.maskFilter = null; pt.color = withAlpha(0xFFFF7058.toInt(), lit); c.drawText(v, 92f, 52f, pt)
  pt.textAlign = Paint.Align.LEFT; pt.textSize = 44f; pt.color = 0xFFFFB347.toInt(); c.drawText("MPH", 112f, 50f, pt)
  pt.textSize = 24f; pt.color = 0x99FFB347.toInt(); c.drawText("SPEED", 114f, -30f, pt)
  pt.textSkewX = 0f
}

private val ZAP: Painter = { c, a, t ->
  val k = a.progress
  if (k > 0f) {
    val seed = floor(t * 22f).toInt()
    for (i in 0 until 6) {
      if (hash01(seed * 13 + i) > k) continue
      val ang = TAU * hash01(seed * 7 + i * 3)
      var r = 150f
      pp.reset(); pp.moveTo(cos(ang) * r, sin(ang) * r * 0.9f)
      for (s in 1..6) {
        r += 24f
        val j = (hash01(seed * 31 + i * 11 + s) - 0.5f) * 0.5f
        pp.lineTo(cos(ang + j) * r, sin(ang + j) * r * 0.9f)
      }
      c.drawPath(pp, pen(withAlpha(0xFF6FD8FF.toInt(), 0.55f), 16f, blur8))
      c.drawPath(pp, pen(0xFFEFFBFF.toInt(), 4f))
    }
  }
}

private val flameBack by lazy { lin(0f, 1338f - 74f, 0f, 1338f, 0xFFFFF2B0, 0xFFFFB23F, 0xFFFF4A1C) }
private val flameFront by lazy { lin(0f, 1376f - 74f, 0f, 1376f, 0xFFFFF2B0, 0xFFFFB23F, 0xFFFF4A1C) }

/** Two fire tracks in screen coordinates. progress 0..1 lays them down left to right, 1..2 burns them out. */
private val TRAILS: Painter = { c, a, t ->
  val p = a.progress
  val reveal = clamp01(p)
  val burn = 1f - clamp01(p - 1f)
  val x0 = 150f
  val xf = lerp(x0, 1180f, reveal)
  if (xf > x0 + 1f) {
    val f = floor(t * 16f).toInt()
    for (ri in 0..1) {
      val y = if (ri == 0) 1338f else 1376f
      val w = if (ri == 0) 0.8f else 1f
      c.drawLine(x0, y, xf, y, pen(0x553A2A26, 12f * w))
      c.drawLine(x0, y, xf, y, pen(withAlpha(0xFFFF6A1F.toInt(), 0.5f * burn), 28f * w, blur16))
      var x = x0; var i = 0
      val sh = if (ri == 0) flameBack else flameFront
      while (x < xf) {
        val h = (30f + 36f * hash01(i * 17 + f * 3 + ri * 101)) * w * burn
        if (h > 2f) {
          val lean = h * 0.22f
          pp.reset(); pp.moveTo(x - 11f * w, y)
          pp.quadTo(x - 12f * w, y - h * 0.55f, x - lean, y - h)
          pp.quadTo(x + 10f * w, y - h * 0.45f, x + 11f * w, y); pp.close()
          c.drawPath(pp, shaded(sh))
        }
        x += 20f; i++
      }
      c.drawLine(x0, y, xf, y, pen(withAlpha(0xFFFFE08A.toInt(), burn), 5f * w))
      for (e in 0 until 10) {
        val life = fract(t * 1.3f + hash01(e * 5 + ri))
        val ex = x0 + (xf - x0) * hash01(e * 9 + ri * 3)
        c.drawCircle(ex + 10f * sin(t * 4f + e), y - life * 130f, 3.5f, solid(withAlpha(0xFFFFD27A.toInt(), burn * (1f - life))))
      }
    }
  }
}

private val FLASH: Painter = { c, _, _ -> c.drawRect(0f, 0f, 1080f, 2424f, solid(Color.WHITE)) }

// ---------------------------------------------------------------- 1990s

private val banner90Fill by lazy { lin(0f, -80f, 0f, 50f, 0xFFFFF27A, 0xFFFFC21F) }
private val chunky: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

private val BANNER90: Painter = { c, _, t ->
  // Memphis shapes behind the type.
  c.drawCircle(-215f, -20f, 96f, solid(0xFF19C3B4.toInt()))
  c.drawCircle(-215f, -20f, 96f, pen(INK_C, 5f))
  c.save(); c.translate(262f, -78f); c.rotate(t * 40f)
  pp.reset(); pp.moveTo(0f, -46f); pp.lineTo(40f, 24f); pp.lineTo(-40f, 24f); pp.close()
  c.drawPath(pp, solid(0xFF8E5CF7.toInt())); c.drawPath(pp, pen(INK_C, 5f))
  c.restore()
  pp.reset(); pp.moveTo(-340f, 84f)
  for (i in 1..7) pp.lineTo(-340f + i * 26f, 84f + (if (i % 2 == 0) 0f else -22f))
  c.drawPath(pp, pen(0xFFFF3E8E.toInt(), 11f))
  for (gx in 0..3) for (gy in 0..2) c.drawCircle(196f + gx * 22f, 50f + gy * 20f, 4.5f, solid(INK_C))
  // Chunky italic type: magenta drop block, ink outline, yellow fill.
  pt.typeface = chunky; pt.textSize = 160f; pt.textSkewX = -0.22f; pt.textAlign = Paint.Align.CENTER; pt.maskFilter = null
  pt.shader = null; pt.style = Paint.Style.FILL; pt.color = 0xFFFF3E8E.toInt(); c.drawText("1990s", 12f, 62f, pt)
  pt.style = Paint.Style.STROKE; pt.strokeWidth = 16f; pt.strokeJoin = Paint.Join.ROUND; pt.color = INK_C
  c.drawText("1990s", 0f, 50f, pt)
  pt.style = Paint.Style.FILL; pt.shader = banner90Fill; pt.color = Color.WHITE
  c.drawText("1990s", 0f, 50f, pt)
  pt.shader = null; pt.textSkewX = 0f
  // Teal squiggle underline.
  pp.reset(); pp.moveTo(-150f, 106f)
  for (i in 1..6) pp.quadTo(-150f + i * 52f - 26f, 106f + (if (i % 2 == 0) 16f else -16f), -150f + i * 52f, 106f)
  c.drawPath(pp, pen(0xFF19C3B4.toInt(), 9f))
}

private val glass by lazy { lin(0f, -330f, 0f, 212f, 0xFFEAF3F6, 0xFFC9DCE3, 0xFFB2C8D1) }
private val chrome by lazy { lin(-262f, -332f, 262f, 212f, 0xFFF4F6F8, 0xFF9AA6AE, 0xFFE6ECF0, 0xFF8C98A0) }
private val marble by lazy { lin(0f, 196f, 0f, 262f, 0xFFFBF8F4, 0xFFE6DDD4) }

private val MIRROR_BACK: Painter = { c, _, _ ->
  rr.set(-290f, -360f, 290f, 250f); c.drawRoundRect(rr, 40f, 40f, solid(0x4D3A2A26, blur30))
  rr.set(-262f, -332f, 262f, 212f)
  c.drawRoundRect(rr, 30f, 30f, shaded(glass))
  c.save(); pp.reset(); pp.addRoundRect(rr, 30f, 30f, Path.Direction.CW); c.clipPath(pp)
  // Reflected wall tiles, faint.
  val tile = pen(0x1F6E8A96, 3f)
  var g = -262f
  while (g < 262f) { c.drawLine(g, -332f, g, 212f, tile); g += 88f }
  g = -332f
  while (g < 212f) { c.drawLine(-262f, g, 262f, g, tile); g += 88f }
  c.drawCircle(0f, -360f, 300f, shaded(bulbWash))
  c.restore()
}
private val bulbWash by lazy { rad(0f, -360f, 300f, 0x66FFF1C4, 0x00FFF1C4) }

private val MIRROR_FRONT: Painter = { c, a, _ ->
  rr.set(-262f, -332f, 262f, 212f)
  c.save(); pp.reset(); pp.addRoundRect(rr, 30f, 30f, Path.Direction.CW); c.clipPath(pp)
  val sx = lerp(-760f, 760f, a.progress)
  pp2.reset(); pp2.moveTo(sx - 40f, 240f); pp2.lineTo(sx + 40f, 240f); pp2.lineTo(sx + 220f, -360f); pp2.lineTo(sx + 140f, -360f); pp2.close()
  c.drawPath(pp2, solid(0x47FFFFFF))
  pp2.reset(); pp2.moveTo(sx + 70f, 240f); pp2.lineTo(sx + 96f, 240f); pp2.lineTo(sx + 276f, -360f); pp2.lineTo(sx + 250f, -360f); pp2.close()
  c.drawPath(pp2, solid(0x2EFFFFFF))
  c.restore()
  // Chrome frame.
  ps.maskFilter = null; ps.shader = chrome; ps.color = Color.WHITE; ps.strokeWidth = 26f
  c.drawRoundRect(rr, 30f, 30f, ps); ps.shader = null
  rr.set(-275f, -345f, 275f, 225f); c.drawRoundRect(rr, 42f, 42f, pen(INK_C, 5f))
  rr.set(-249f, -319f, 249f, 199f); c.drawRoundRect(rr, 18f, 18f, pen(0x803A2A26.toInt(), 3f))
  // Vanity bulbs.
  rr.set(-240f, -378f, 240f, -340f); c.drawRoundRect(rr, 19f, 19f, shaded(chrome))
  c.drawRoundRect(rr, 19f, 19f, pen(INK_C, 4f))
  for (i in 0 until 5) {
    val bxp = -180f + i * 90f
    c.drawCircle(bxp, -362f, 36f, solid(0x66FFE9A8, blur16))
    c.drawCircle(bxp, -362f, 19f, solid(0xFFFFF6D6.toInt()))
    c.drawCircle(bxp, -362f, 19f, pen(0xFFE2B85C.toInt(), 3f))
    c.drawCircle(bxp - 6f, -368f, 5f, solid(Color.WHITE))
  }
  // Marble counter with a little faucet.
  rr.set(-340f, 204f, 340f, 276f); c.drawRoundRect(rr, 16f, 16f, solid(0x403A2A26, blur16))
  pp.reset(); pp.moveTo(-200f, 196f); pp.lineTo(-200f, 152f); pp.quadTo(-200f, 136f, -182f, 136f); pp.lineTo(-150f, 136f); pp.lineTo(-150f, 156f)
  c.drawPath(pp, pen(0xFF9AA6AE.toInt(), 14f)); c.drawPath(pp, pen(0xFFE6ECF0.toInt(), 5f))
  rr.set(-340f, 196f, 340f, 262f)
  c.drawRoundRect(rr, 14f, 14f, shaded(marble))
  c.drawLine(-326f, 200f, 326f, 200f, pen(0xCCFFFFFF.toInt(), 3f))
  c.drawLine(-260f, 228f, -150f, 246f, pen(0x26A0968C, 2f)); c.drawLine(80f, 214f, 220f, 240f, pen(0x26A0968C, 2f))
  c.drawRoundRect(rr, 14f, 14f, pen(INK_C, 5f))
}

private val SCREAM: Painter = { c, _, t ->
  for (i in 0..12) {
    val ang = (-200f + 220f * i / 12f + 2f * sin(t * 50f + i)) * DEG
    val r0 = 236f + 10f * sin(t * 40f + i * 1.3f)
    val r1 = r0 + 54f + 26f * hash01(i)
    c.drawLine(cos(ang) * r0, sin(ang) * r0, cos(ang) * r1, sin(ang) * r1, pen(INK_C, 9f))
    if (i % 3 == 1) c.drawCircle(cos(ang) * (r1 + 22f), sin(ang) * (r1 + 22f), 6f, solid(0xFFFFC21F.toInt()))
  }
}

private val sky90 by lazy { lin(0f, -HH, 0f, HORIZON, 0xFF3A2B6E, 0xFF8E4C8A, 0xFFF2677A, 0xFFFFB060) }
private val sea90 by lazy { lin(0f, HORIZON, 0f, HH, 0xFFF08A5D, 0xFF7A3F7A, 0xFF2B1F4E) }
private val sunGlow by lazy { rad(-170f, HORIZON, 340f, 0xAAFFE29A, 0x33FFB060, 0x00FFB060) }
private val sunDisc by lazy { lin(0f, HORIZON - 150f, 0f, HORIZON, 0xFFFFF3B0, 0xFFFF8A4C) }
private val clouds = floatArrayOf(-430f, -250f, -110f, -230f, 40f, -312f, 420f, -292f, -70f, -150f, 250f, -136f)

private val SUNSET: Painter = { c, _, t ->
  panelShadow(c)
  c.save(); clipPanel(c)
  c.drawRect(-HW, -HH, HW, HORIZON, shaded(sky90))
  c.drawCircle(-170f, HORIZON, 340f, shaded(sunGlow))
  c.save(); c.clipRect(-HW, -HH, HW, HORIZON)
  c.drawCircle(-170f, HORIZON + 10f, 150f, shaded(sunDisc))
  c.restore()
  var i = 0
  while (i < clouds.size) {
    val drift = 10f * sin(t * 0.4f + i)
    rr.set(clouds[i] + drift, clouds[i + 1], clouds[i + 2] + drift, clouds[i + 3])
    c.drawRoundRect(rr, 12f, 12f, solid(0xB3F2A9CF.toInt()))
    rr.offset(18f, 8f); rr.bottom = rr.top + 6f
    c.drawRoundRect(rr, 4f, 4f, solid(0x668E4C8A))
    i += 4
  }
  c.drawRect(-HW, HORIZON, HW, HH, shaded(sea90))
  for (k in 0 until 26) {
    val y = HORIZON + 8f + k * k * 0.55f
    val len = (150f - k * 3.5f) * (0.75f + 0.25f * sin(t * 3f + k * 1.7f))
    val cx = -170f + 30f * sin(t * 1.3f + k)
    c.drawLine(cx - len / 2f, y, cx + len / 2f, y, pen(withAlpha(0xFFFFE6A8.toInt(), 0.85f * (1f - k / 28f)), 4f + k * 0.2f))
  }
  c.drawLine(-HW, HORIZON, HW, HORIZON, pen(0x66FFE0B0, 6f))
  // Two far gulls.
  for (g in 0..1) {
    val gx = 150f + g * 60f; val gy = -190f - g * 22f
    val flap = 10f * sin(t * 6f + g * 2f)
    pp.reset(); pp.moveTo(gx - 18f, gy - flap); pp.quadTo(gx - 8f, gy - 8f, gx, gy); pp.quadTo(gx + 8f, gy - 8f, gx + 18f, gy - flap)
    c.drawPath(pp, pen(0xB33A2A26.toInt(), 4f))
  }
  c.restore()
  panelBorder(c)
}

private val hull by lazy { lin(0f, 180f, 0f, 460f, 0xFF3A2B4A, 0xFF1B1428) }
private val deck by lazy { lin(-520f, 0f, 330f, 0f, 0xFF8E5A33, 0xFFC98E58, 0xFFE2AE74) }

private val BOW: Painter = { c, _, _ ->
  c.save(); clipPanel(c)
  pp.reset(); pp.moveTo(-520f, 250f); pp.lineTo(330f, 186f); pp.lineTo(250f, 310f); pp.lineTo(110f, 470f); pp.lineTo(-520f, 470f); pp.close()
  c.drawPath(pp, shaded(hull))
  c.drawLine(-520f, 288f, 292f, 214f, pen(0xFFF4EEE8.toInt(), 9f))
  c.drawLine(-520f, 250f, 330f, 186f, pen(0xFFFFB27A.toInt(), 5f))
  pp.reset(); pp.moveTo(-520f, 150f); pp.lineTo(330f, 186f); pp.lineTo(-520f, 250f); pp.close()
  c.drawPath(pp, shaded(deck))
  for (k in 1..4) c.drawLine(-520f, 150f + k * 20f, 330f, 186f, pen(0x40603818, 2.5f))
  c.drawPath(pp, pen(INK_C, 4f))
  // Back rail.
  val post = pen(0xFF2A2036.toInt(), 7f)
  var x = -500f
  while (x < 320f) {
    val base = 150f + (x + 520f) / 850f * 36f
    c.drawLine(x, base, x, base - 64f, post); x += 110f
  }
  c.drawLine(-520f, 86f, 320f, 140f, pen(0xFF2A2036.toInt(), 8f))
  c.drawLine(-520f, 82f, 320f, 136f, pen(0x99FFC08A.toInt(), 3f))
  c.drawLine(320f, 140f, 330f, 186f, post)
  c.restore()
}

private val WIND: Painter = { c, _, t ->
  c.save(); clipPanel(c)
  for (i in 0 until 14) {
    val y = -380f + 600f * hash01(i + 40)
    val sp = 1.1f + hash01(i + 50)
    val x = 520f - fract(t * sp * 0.9f + hash01(i + 70)) * 1100f
    val len = 90f + 160f * hash01(i + 60)
    c.drawLine(x, y, x + len, y, pen(0x73FFFFFF, 3.5f))
  }
  c.restore()
}

private val matrixBg by lazy { lin(0f, -HH, 0f, HH, 0xFF020D06, 0xFF062614) }
private val matrixFloor by lazy { rad(0f, 185f, 320f, 0x5530FF7A, 0x0030FF7A) }
private val glyphs = arrayOf("0", "1", "<", ">", "+", "=", "*", "/", "[", "]", "#", "%")

private val MATRIX: Painter = { c, _, t ->
  panelShadow(c)
  c.save(); clipPanel(c)
  c.drawRect(-HW, -HH, HW, HH, shaded(matrixBg))
  pt.typeface = pixelFace; pt.textSize = 30f; pt.textAlign = Paint.Align.CENTER; pt.style = Paint.Style.FILL
  pt.maskFilter = null; pt.shader = null; pt.textSkewX = 0f
  for (col in 0 until 19) {
    val x = -486f + col * 54f
    val speed = 160f + 140f * hash01(col)
    val head = -HH - 100f + (t * speed + hash01(col + 5) * 1100f) % 1300f
    for (k in 0 until 12) {
      val y = head - k * 34f
      if (y < -HH - 20f || y > HH + 30f) continue
      val g = glyphs[(hash01(col * 31 + k + floor(t * 6f + k).toInt()) * glyphs.size).toInt().coerceIn(0, glyphs.size - 1)]
      pt.color = if (k == 0) 0xFFD8FFE4.toInt() else withAlpha(0xFF2BE36F.toInt(), (1f - k / 12f) * 0.7f)
      c.drawText(g, x, y, pt)
    }
  }
  pt.textAlign = Paint.Align.LEFT
  rr.set(-320f, 150f, 320f, 220f); c.drawOval(rr, shaded(matrixFloor))
  c.restore()
  panelBorder(c)
}

private val ORBIT: Painter = { c, a, t ->
  for (j in 0..2) {
    val rx = 330f + j * 60f; val ry = 70f + j * 16f; val cy = j * 40f - 40f
    rr.set(-rx, cy - ry, rx, cy + ry)
    val start = t * 70f * (if (j % 2 == 0) 1f else -1f) + j * 120f
    c.drawArc(rr, start, 110f, false, pen(withAlpha(0xFF7CFFB0.toInt(), 0.25f * a.progress), 12f, blur8))
    c.drawArc(rr, start, 110f, false, pen(withAlpha(0xFF7CFFB0.toInt(), 0.5f * a.progress), 4f))
  }
}

private val TINT: Painter = { c, _, _ ->
  rr.set(-HW, -HH, HW, HH); c.drawRoundRect(rr, 56f, 56f, solid(0x1C1FD86A))
}

private val brass by lazy { lin(0f, -11f, 0f, 11f, 0xFFFFE3A1, 0xFFC98B2E, 0xFF8A5A1A) }
private val copper by lazy { lin(0f, -11f, 0f, 11f, 0xFFFFC09A, 0xFFB8653A, 0xFF7A3A1E) }

/** A bullet flying left. progress is how far it has travelled; its ripple rings stay where they were shed. */
private val BULLET: Painter = { c, a, _ ->
  val dist = a.progress
  c.drawLine(0f, 0f, min(dist, 420f), 0f, pen(0x33CFFFE0, 6f))
  var k = 0
  while (k * 80f <= dist) {
    val age = dist - k * 80f
    if (age < 600f) {
      val r = 14f + age * 0.09f
      val al = 1f - age / 600f
      rr.set(age - r * 0.32f, -r, age + r * 0.32f, r)
      c.drawOval(rr, pen(withAlpha(0xFFCFFFE0.toInt(), al * 0.7f), 3f))
    }
    k++
  }
  rr.set(-6f, -11f, 46f, 11f); c.drawRoundRect(rr, 3f, 3f, shaded(brass))
  pp.reset(); pp.moveTo(-6f, -11f); pp.quadTo(-30f, -9f, -32f, 0f); pp.quadTo(-30f, 9f, -6f, 11f); pp.close()
  c.drawPath(pp, shaded(copper))
  c.drawLine(-20f, -5f, 40f, -5f, pen(0xAAFFFFFF.toInt(), 3f))
  c.drawLine(46f, -11f, 46f, 11f, pen(INK_C, 3f))
}
