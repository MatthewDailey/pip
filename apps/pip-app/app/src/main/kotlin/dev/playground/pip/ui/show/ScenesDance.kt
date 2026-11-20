package dev.playground.pip.ui.show

import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.INFINITE
import dev.playground.pip.ui.anim.StopScope
import kotlin.math.floor

/*
 * Pip's Jam. The soundtrack is 120 BPM from the start of this scene, so scene-local beats land on multiples of
 * 0.5 s; body moves hit the beats while the eyes act on their own, looser timing. Pip starts and ends at
 * (540, 1350), size 300, fists resting at his sides.
 *
 * Mood stays NEUTRAL for most of it so lids, squint and glances read; HAPPY (closed, arched eyes) is kept for the
 * moments he's lost in the music.
 */

private const val HOME_X = 540f
private const val HOME_Y = 1350f
private const val BEAT = 0.5f

fun danceScenes(): List<Scene> = listOf(danceScene())

// ---------------------------------------------------------------------------------------------------------------
// Moves and props

private fun PipState.home() {
  x = HOME_X; y = HOME_Y; size = 300f
  left.kind = Hand.FIST; left.x = -60f; left.y = 470f
  right.kind = Hand.FIST; right.x = 600f; right.y = 470f
}

private fun StopScope<PipState>.restHands() {
  leftHand(Hand.FIST, -60, 470, 0, 1); rightHand(Hand.FIST, 600, 470, 0, 1)
}

/** Percent of an animation that starts at [t0] and lasts [d], for writing stops in scene seconds. */
private fun pc(t: Number, t0: Number, d: Number) = 100f * (t.toFloat() - t0.toFloat()) / d.toFloat()

/**
 * One bounce per beat, layered on whatever else is moving: squash on the beat, stretch on the way up, hang at the
 * top half way through, then fall back into the next beat.
 */
private fun SceneScope.bounce(t0: Number, beats: Int, depth: Float = 1f) =
  animate(t0, BEAT, ease = Easing.linear, iterations = beats.toFloat(), fill = Fill.NONE, composite = Composite.ADD) {
    at(0, Easing.easeOut) { squash(0.12f * depth, -0.12f * depth); y(0) }
    at(30) { squash(-0.04f * depth, 0.06f * depth) }
    at(50, Easing.easeIn) { y(-44f * depth) }
    at(58) { squash(0, 0) }
    at(88, Easing.easeIn) { squash(-0.03f * depth, 0.05f * depth) }
    to { squash(0.12f * depth, -0.12f * depth); y(0) }
  }

/** A blink placed by hand (the show's metronome blink is switched off for this scene). */
private fun SceneScope.blinkAt(t0: Number, d: Float = 0.16f) =
  animate(t0, d, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeIn) { blink(0) }
    at(40) { blink(1) }
    at(60, Easing.easeOut) { blink(1) }
    to { blink(0) }
  }

/** A stage light hanging from the top that swings on the beat, fading in at the start and out at the end. */
private fun SceneScope.spotlight(px: Float, tint: Int, swing: Float, dir: Direction, peak: Float = 0.6f, period: Float = 1f) {
  val a = actor(ActorKind.SPOTLIGHT, layer = -1) { x = px; y = 40f; alpha = 0f; color = tint }
  a.animate(0, 0.5) { to { alpha(peak) } }
  a.animate(duration - 0.5f, 0.5) { to { alpha(0) } }
  a.animate(0, period, ease = Easing.easeInOut, iterations = INFINITE, direction = dir) {
    from { rotation(-swing) }
    to { rotation(swing) }
  }
}

/** A note that floats up past Pip and fades, looping until [end]. */
private fun SceneScope.musicNote(t0: Float, px: Float, end: Float, tint: Int, drift: Float, period: Float = 2.5f) {
  val loops = floor((end - t0) / period)
  if (loops < 1f) return
  actor(ActorKind.MUSIC_NOTE) { x = px; y = 1100f; alpha = 0f; color = tint }
    .animate(t0, period, ease = Easing.linear, iterations = loops, fill = Fill.NONE) {
      from(Easing.easeOut) { y(1100); x(px); alpha(0); scale(0.5); rotation(-14) }
      at(15) { alpha(1); scale(1) }
      at(50, Easing.easeInOut) { x(px + drift); rotation(14) }
      at(75) { alpha(1) }
      to { y(660); x(px); alpha(0); rotation(-14) }
    }
}

/** A star that twinkles once at ([px], [py]). */
private fun SceneScope.sparkle(t0: Float, px: Float, py: Float, size: Float = 1f) {
  actor(ActorKind.SPARKLE) { x = px; y = py; alpha = 0f }.animate(t0, 0.6, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.outBack) { scale(0); alpha(1); rotation(0) }
    at(35, Easing.easeIn) { scale(size) }
    at(70) { alpha(1) }
    to { scale(0); alpha(0); rotation(90) }
  }
}

private const val PINK = 0xFFFF4FD8.toInt()
private const val CYAN = 0xFF4FE3FF.toInt()
private const val GOLD = 0xFFFFD84F.toInt()
private const val VIOLET = 0xFFA06CFF.toInt()

// ---------------------------------------------------------------------------------------------------------------
// Pip's Jam: 10 s, 20 beats.
//   0-2   eyes closed, nodding into the groove; eyes pop open on beat 3 ("oh, this is good")
//   2-4   side-steps, the eyes leading each step
//   4-6   hands up waving, a wink on beat 10, a double blink and a look up as he decides to go big
//   6-8   crouch, spin jump, land on beat 15 with confetti
//   8-10  a sly shuffle with eye darts, finishing on a cool look to camera

private fun danceScene() = scene("dance", 10) {
  pip {
    home(); mood = Mood.NEUTRAL; mouth = Mouth.SMILE; blush = 0.5f; squint = 0.35f; groundY = HOME_Y
  }
  // Switch off the show's metronome blink; every blink here is placed.
  animate(0, duration, ease = Easing.linear) { from { blink(0) }; to { blink(0) } }

  spotlight(170f, PINK, 22f, Direction.ALTERNATE)
  spotlight(540f, CYAN, 14f, Direction.ALTERNATE_REVERSE, period = 2f)
  spotlight(910f, GOLD, 22f, Direction.ALTERNATE_REVERSE)
  val notes = listOf(360f to PINK, 730f to CYAN, 300f to GOLD, 790f to VIOLET, 420f to CYAN, 670f to PINK)
  notes.forEachIndexed { i, (nx, tint) -> musicNote(i * BEAT, nx, 10f, tint, if (i % 2 == 0) -50f else 50f) }

  val title = actor(ActorKind.BANNER, "♪ Pip's Jam ♪") { x = W / 2; y = 330f; alpha = 0f }
  title.animate(0, 4, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.outBack) { scale(0.2); alpha(1); rotation(-6) }
    at(pc(0.35, 0, 4)) { scale(1); rotation(0) }
    at(pc(3.7, 0, 4)) { alpha(1); scale(1) }
    to { alpha(0); scale(1.12) }
  }
  title.animate(BEAT, BEAT, ease = Easing.easeOut, iterations = 7f, composite = Composite.ADD, fill = Fill.NONE) {
    from { scale(0.07) }
    to { scale(0) }
  }
  cue(0, "sparkle")

  // Bounce through everything up to the jump.
  bounce(0, 12)

  // --- 0-2: lost in the groove. Eyes shut, head nodding a touch behind the beat, fists bobbing low.
  animate(0, 1.5, ease = Easing.linear, fill = Fill.NONE) { from { mood(Mood.HAPPY); squint(0) } }
  animate(0, BEAT, ease = Easing.easeInOut, iterations = 4f, direction = Direction.ALTERNATE, fill = Fill.NONE, composite = Composite.ADD, delay = 0.06) {
    from { rotation(-4); hatSway(0.35) }
    to { rotation(4); hatSway(-0.35) }
  }
  animate(0, BEAT, ease = Easing.easeOut, iterations = 4f, fill = Fill.NONE, composite = Composite.ADD) {
    from { rightHand(y = -45); leftHand(y = -45) }
    to { rightHand(y = 0); leftHand(y = 0) }
  }
  // Beat 3: the eyes pop open, wide, brows up. Then the grin squints back in.
  animate(1.5, 0.7, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.outBack) { eyeScale(1.25); squint(0); brows(raise = 0.8, tilt = -0.2); look(0.15, -0.2) }
    at(30, Easing.easeInOut) { eyeScale(1.05); brows(raise = 0.5) }
    at(70, Easing.easeInOut) { eyeScale(1); squint(0.5); look(0, 0) }
    to { brows(raise = 0, tilt = 0, show = false); squint(0.35) }
  }
  cue(1.5, "pop")

  // --- 2-4: side-steps with a lean. The eyes get there first.
  animate(2, 2, ease = Easing.easeInOut, fill = Fill.NONE) {
    from { x(HOME_X); rotation(0) }
    at(25) { x(430); rotation(-8) }
    at(50) { x(HOME_X); rotation(0) }
    at(75) { x(650); rotation(8) }
    to { x(HOME_X); rotation(0) }
  }
  animate(2, 2, ease = Easing.easeInOut, fill = Fill.NONE, composite = Composite.ADD, delay = 0.08) {
    from { hatSway(0) }
    at(25) { hatSway(0.7) }
    at(50) { hatSway(0) }
    at(75) { hatSway(-0.7) }
    to { hatSway(0) }
  }
  animate(2, 2, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { look(0, 0) }
    at(12, Easing.easeInOut) { look(-0.9, 0.1) }
    at(30, Easing.easeInOut) { look(-0.5, 0) }
    at(52, Easing.easeOut) { look(0.2, 0) }
    at(62, Easing.easeInOut) { look(0.9, 0.1) }
    at(80, Easing.easeInOut) { look(0.5, 0) }
    to { look(0, 0) }
  }
  animate(2, 1, ease = Easing.easeInOut, iterations = 2f, fill = Fill.NONE, composite = Composite.ADD) {
    from { rightHand(y = -100); leftHand(y = 0) }
    at(50) { rightHand(y = 0); leftHand(y = -100) }
    to { rightHand(y = -100); leftHand(y = 0) }
  }
  blinkAt(3.1)

  // --- 4-6: both hands up, waving with the body. A big grin, then a wink.
  animate(4, 0.35, ease = Easing.outBack) {
    from { rightHand(Hand.PALM); leftHand(Hand.PALM) }
    to { rightHand(x = 650, y = 220, angle = 20); leftHand(x = -110, y = 220, angle = -20); squint(0.6) }
  }
  animate(4, BEAT, ease = Easing.easeInOut, iterations = 4f, direction = Direction.ALTERNATE, fill = Fill.NONE, composite = Composite.ADD) {
    from { rightHand(angle = -25); leftHand(angle = -25); rotation(-5); x(-20) }
    to { rightHand(angle = 25); leftHand(angle = 25); rotation(5); x(20) }
  }
  animate(4, BEAT, ease = Easing.easeInOut, iterations = 4f, direction = Direction.ALTERNATE, fill = Fill.NONE, composite = Composite.ADD, delay = 0.1) {
    from { hatSway(0.5) }
    to { hatSway(-0.5) }
  }
  cue(4, "pop")
  // The wink on beat 10: a hard squint with the lid dropping, head tipped, cheeks flushing.
  animate(4.85, 0.5, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { squint(0.6); lidTop(0); look(0, 0) }
    at(30, Easing.easeInOut) { squint(1); lidTop(0.65); look(0.3, 0.2) }
    at(65, Easing.easeOut) { squint(1); lidTop(0.65); look(0.3, 0.2) }
    to { squint(0.6); lidTop(0); look(0, 0) }
  }
  animate(4.85, 0.5, ease = Easing.linear, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.outBack) { rotation(0); blush(0) }
    at(30, Easing.easeInOut) { rotation(-9); blush(0.4) }
    at(65, Easing.easeInOut) { rotation(-9); blush(0.4) }
    to { rotation(0); blush(0) }
  }
  cue(5.0, "sparkle")
  sparkle(5.0f, 650f, 1110f, 0.8f)
  // A double blink as the idea lands, then the eyes go up to where he's about to jump.
  blinkAt(5.5, 0.13f)
  blinkAt(5.7, 0.13f)
  animate(5.55, 0.95, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { look(0, 0); brows(raise = 0, show = true) }
    at(30, Easing.easeInOut) { look(0.1, -1); brows(raise = 0.7, tilt = -0.2); squint(0.2) }
    to { look(0.1, -1); brows(raise = 0.6, tilt = -0.2); squint(0.2) }
  }

  // --- 6-8: crouch on beat 12, launch on 13, turn in the air, land on 15, recover by 16.
  val j0 = 6f; val jd = 2f
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    at(pc(6.5, j0, jd), Easing.easeOut) { y(HOME_Y); shadow(1) }
    at(pc(7.0, j0, jd), Easing.easeIn) { y(1070); shadow(0.35) }
    at(pc(7.5, j0, jd)) { y(HOME_Y); shadow(1) }
  }
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { squash(1, 1) }
    at(pc(6.4, j0, jd)) { squash(1.22, 0.78) }
    at(pc(6.5, j0, jd), Easing.easeOut) { squash(1.22, 0.78) }
    at(pc(6.65, j0, jd), Easing.easeOut) { squash(0.86, 1.18) }
    at(pc(6.9, j0, jd), Easing.easeIn) { squash(1, 1) }
    at(pc(7.4, j0, jd)) { squash(0.92, 1.1) }
    at(pc(7.5, j0, jd), Easing.easeOut) { squash(1.25, 0.76) }
    at(pc(7.75, j0, jd), Easing.easeOut) { squash(0.95, 1.06) }
    to { squash(1, 1) }
  }
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    at(pc(6.6, j0, jd), Easing.easeIn) { facing(1) }
    at(pc(6.8, j0, jd), Easing.easeOut) { facing(-1) }
    at(pc(7.0, j0, jd)) { facing(1) }
  }
  // The hat tip trails the jump: it lifts on the way down and flops on landing.
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    at(pc(6.5, j0, jd), Easing.easeOut) { hatDroop(0) }
    at(pc(6.6, j0, jd), Easing.easeInOut) { hatDroop(0.35) }
    at(pc(7.0, j0, jd), Easing.easeIn) { hatDroop(0) }
    at(pc(7.5, j0, jd), Easing.easeOut) { hatDroop(-0.2) }
    at(pc(7.6, j0, jd), Easing.easeOut) { hatDroop(0.45) }
    at(pc(7.85, j0, jd), Easing.easeInOut) { hatDroop(-0.1) }
    to { hatDroop(0) }
  }
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { rightHand(Hand.FIST); leftHand(Hand.FIST) }
    at(pc(6.4, j0, jd)) { rightHand(x = 630, y = 560, angle = 0); leftHand(x = -90, y = 560, angle = 0) }
    at(pc(6.5, j0, jd), Easing.outBack) { rightHand(x = 630, y = 560, angle = 0); leftHand(x = -90, y = 560, angle = 0) }
    at(pc(6.7, j0, jd)) { rightHand(x = 730, y = 160, angle = 40); leftHand(x = -190, y = 160, angle = -40) }
    at(pc(7.4, j0, jd), Easing.easeOut) { rightHand(x = 730, y = 160, angle = 40); leftHand(x = -190, y = 160, angle = -40) }
    at(pc(7.75, j0, jd)) { restHands() }
  }
  // Face through the jump: brows knit with effort in the crouch, eyes wide on take-off, shut with joy in the air,
  // and a squinting grin on the landing.
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { look(0.1, -1); brows(raise = 0.6, tilt = -0.2, show = true); squint(0.2); eyeScale(1) }
    at(pc(6.35, j0, jd), Easing.easeOut) { look(0, 0.3); brows(raise = -0.4, tilt = 0.5); squint(0.6) }
    at(pc(6.5, j0, jd), Easing.outBack) { look(0, 0.3); brows(raise = -0.4, tilt = 0.5); squint(0.6) }
    at(pc(6.62, j0, jd), Easing.easeInOut) { look(0, -0.4); brows(raise = 1, tilt = -0.3); squint(0); eyeScale(1.3) }
    at(pc(7.5, j0, jd), Easing.outBack) { brows(raise = 0.3, tilt = 0); squint(0.85); eyeScale(1); look(0, 0) }
    to { brows(raise = 0, tilt = 0, show = false); squint(0.45) }
  }
  animate(j0, jd, ease = Easing.linear, fill = Fill.NONE) {
    at(pc(6.62, j0, jd)) { mouth(Mouth.O) }
    at(pc(6.75, j0, jd)) { mood(Mood.HAPPY); mouth(Mouth.SMILE) }
    at(pc(7.45, j0, jd)) { mood(Mood.NEUTRAL) }
  }
  cue(6.5, "whoosh")
  cue(7.0, "sparkle")
  cue(7.5, "land")
  sparkle(6.95f, 390f, 730f, 1.1f)
  sparkle(7.05f, 700f, 690f, 0.9f)
  sparkle(7.15f, 560f, 600f, 1.2f)
  actor(ActorKind.CONFETTI) { x = HOME_X; y = 850f; alpha = 0f }.animate(7.5, 2.5, ease = Easing.linear, fill = Fill.NONE) {
    from(Easing.easeOut) { alpha(1); progress(0); scale(0.6) }
    at(15) { scale(1.1) }
    at(80) { alpha(1) }
    to { alpha(0); progress(1); scale(1.1) }
  }
  blinkAt(7.95)

  // --- 8-10: the sly shuffle. Lids half down, the eyes darting ahead of each slide.
  animate(8, 0.3, ease = Easing.easeOut, fill = Fill.NONE) { from { lidTop(0) }; to { lidTop(0.4) } }
  animate(8.3, 1.2, ease = Easing.linear, fill = Fill.NONE) { from { lidTop(0.4) }; to { lidTop(0.4) } }
  animate(8, BEAT, ease = Easing.easeInOut, iterations = 3f, fill = Fill.NONE, composite = Composite.ADD) {
    from { x(0); rotation(0) }
    at(25) { x(-45); rotation(5) }
    at(50) { x(0); rotation(0) }
    at(75) { x(45); rotation(-5) }
    to { x(0); rotation(0) }
  }
  animate(8, BEAT, ease = Easing.linear, iterations = 3f, fill = Fill.NONE) {
    from(Easing.easeOut) { look(0, 0) }
    at(8) { look(-0.9, 0) }
    at(40, Easing.easeOut) { look(-0.9, 0) }
    at(52) { look(0.9, 0) }
    at(88, Easing.easeOut) { look(0.9, 0) }
    to { look(0, 0) }
  }
  animate(8, BEAT, ease = Easing.easeInOut, iterations = 3f, fill = Fill.NONE, composite = Composite.ADD, delay = 0.07) {
    from { hatSway(0) }
    at(25) { hatSway(0.6) }
    at(50) { hatSway(0) }
    at(75) { hatSway(-0.6) }
    to { hatSway(0) }
  }
  animate(8, BEAT / 2, ease = Easing.linear, iterations = 6f, fill = Fill.NONE, composite = Composite.ADD) {
    from(Easing.easeOut) { squash(0.08, -0.08); y(0) }
    at(50, Easing.easeIn) { squash(-0.02, 0.03); y(-20) }
    to { squash(0.08, -0.08); y(0) }
  }
  animate(8, BEAT, ease = Easing.easeInOut, iterations = 3f, fill = Fill.NONE, composite = Composite.ADD) {
    from { rightHand(x = 0, y = 0); leftHand(x = 0, y = 0) }
    at(25) { rightHand(x = 50, y = -50); leftHand(x = 50, y = 20) }
    at(50) { rightHand(x = 0, y = 0); leftHand(x = 0, y = 0) }
    at(75) { rightHand(x = -50, y = 20); leftHand(x = -50, y = -50) }
    to { rightHand(x = 0, y = 0); leftHand(x = 0, y = 0) }
  }
  blinkAt(9.05)

  // Last beat: settle, then a cool look straight down the lens with one brow cocked and the grin.
  bounce(9.5, 1, 0.7f)
  animate(9.5, BEAT, ease = Easing.linear) {
    from(Easing.outBack) { look(0, 0); lidTop(0.4) }
    at(40, Easing.easeInOut) { look(0, 0.1); lidTop(0.25); squint(0.55); brows(raise = 0.45, tilt = 0.25, show = true) }
    to { look(0, 0.1); lidTop(0.25); squint(0.55); brows(raise = 0.45, tilt = 0.25, show = true) }
  }
  animate(9.5, BEAT, ease = Easing.linear, composite = Composite.ADD) {
    from(Easing.outBack) { rotation(0) }
    at(40, Easing.easeInOut) { rotation(-4) }
    to { rotation(-2.5) }
  }
}
