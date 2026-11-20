package dev.playground.pip.ui.show

import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.StopScope
import kotlin.math.min
import kotlin.math.sign

/** Pip arrives, hops about, plays peekaboo with the screen edges and the home-screen icons. */
fun movementScenes(): List<Scene> = listOf(introScene(), hopsScene(), edgePeekScene(), iconsScene())

// Ballistic halves of a jump: decelerating on the way up, accelerating on the way down.
private val rise = Easing.cubicBezier(0.25f, 0.6f, 0.5f, 1f)
private val fall = Easing.cubicBezier(0.5f, 0f, 0.75f, 0.4f)
private val snappy = Easing.cubicBezier(0.2f, 0.9f, 0.3f, 1f)

/** Sets values almost instantly (a cut), holding them until something later takes over. */
private fun SceneScope.snap(at: Number, values: StopScope<PipState>.() -> Unit) =
  animate(at, 0.001, ease = Easing.linear) { to(values) }

private fun SceneScope.expr(at: Number, mood: Mood, mouth: Mouth) = snap(at) { mood(mood); mouth(mouth) }

private fun SceneScope.blinkAt(t0: Number, double: Boolean = false) =
  animate(t0, if (double) 0.42 else 0.2, ease = Easing.linear, fill = Fill.NONE) {
    from { blink(0) }
    if (double) { at(22) { blink(1) }; at(46) { blink(0) }; at(70) { blink(1) } } else at(45) { blink(1) }
    to { blink(0) }
  }

/**
 * One springy hop from (x0, y0) to (x1, y1). Crouch for the first ~22% (anticipation), airborne until 82%,
 * then absorb the landing. Squash, rotation and gaze start from whatever Pip is doing, so hops chain cleanly.
 */
private fun SceneScope.hop(
  t0: Number, dur: Number, x0: Float, y0: Float, x1: Float, y1: Float, height: Float,
  size0: Float? = null, size1: Float? = null, sounds: Boolean = true, lean: Float = 7f,
) {
  val dir = sign(x1 - x0)
  val peak = min(y0, y1) - height
  if (dir != 0f) animate(t0, 0.16, ease = Easing.easeInOut) { to { facing(dir) } }
  animate(t0, dur, ease = Easing.linear) {
    from { y(y0) }
    at(22, rise) { y(y0) }
    at(52, fall) { y(peak) }
    at(82) { y(y1) }
    to { y(y1) }
  }
  animate(t0, dur, ease = Easing.linear) {
    from { x(x0) }
    at(22, Easing.cubicBezier(0.3f, 0.2f, 0.7f, 0.85f)) { x(x0) }
    at(82) { x(x1) }
    to { x(x1) }
    if (size0 != null && size1 != null) {
      at(0) { size(size0) }; at(22) { size(size0) }; at(82) { size(size1) }; at(100) { size(size1) }
    }
  }
  animate(t0, dur, ease = Easing.easeInOut) {
    at(20, Easing.easeOut) { squash(1.2, 0.8); rotation(-0.6f * lean * dir); look(0.7, 0.4) }
    at(28) { squash(0.8, 1.24); rotation(lean * dir); look(0.6, -0.5) }
    at(50) { squash(0.98, 1.03); rotation(0.4f * lean * dir) }
    at(76, Easing.easeIn) { squash(0.88, 1.14); rotation(-0.2f * lean * dir); look(0.6, 0.6) }
    at(84, Easing.outBack) { squash(1.3, 0.74); rotation(0) }
    at(92) { squash(0.95, 1.06) }
    to { squash(1, 1); look(0, 0) }
  }
  // The floppy tip lags behind the body: dragged down on takeoff, floats at the top, whips on landing.
  animate(t0, dur, ease = Easing.easeInOut, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }
    at(22) { hatSway(0.3) }
    at(32) { hatSway(-0.9) }
    at(55) { hatSway(0.5) }
    at(84) { hatSway(-0.8) }
    at(93) { hatSway(0.35) }
    to { hatSway(0) }
  }
  if (sounds) { cue(t0.toFloat() + 0.22f * dur.toFloat(), "hop"); cue(t0.toFloat() + 0.82f * dur.toFloat(), "land") }
}

private fun introScene() = scene("intro", 6) {
  val cx = 540f; val home = 1400f
  val below = H + 420f; val peek = H + 250f; val crouch = peek + 80f
  pip { x = cx; y = below; size = 300f; mouth = Mouth.NONE }

  // The hat tip pokes up over the bottom edge, has a wiggle, ducks...
  animate(0.15, 0.4, Easing.outBack) { to { y(peek) } }
  cue(0.2, "peek")
  animate(0.55, 0.4, Easing.easeInOut, composite = Composite.ADD, fill = Fill.NONE) {
    from { rotation(0) }; at(25) { rotation(-8) }; at(60) { rotation(7) }; to { rotation(0) }
  }
  animate(0.95, 0.18, Easing.easeIn) { to { y(crouch) } }
  // ...and launches: up past home, then drops onto his spot.
  animate(1.13, 0.75, Easing.linear) {
    at(0, rise) { y(crouch) }
    at(62, fall) { y(home - 190) }
    to { y(home) }
  }
  cue(1.13, "hop"); cue(1.88, "land")
  animate(0.95, 1.35, Easing.linear) {
    from { squash(1, 1) }
    at(13, Easing.easeOut) { squash(1.15, 0.86) }
    at(22, Easing.easeInOut) { squash(0.76, 1.3) }
    at(50, Easing.easeInOut) { squash(1, 1) }
    at(64, Easing.easeIn) { squash(0.9, 1.12) }
    at(69, Easing.easeOut) { squash(1.34, 0.7) }
    at(80, Easing.easeInOut) { squash(0.92, 1.09) }
    at(90, Easing.easeInOut) { squash(1.04, 0.97) }
    to { squash(1, 1) }
  }
  animate(1.1, 1.3, Easing.easeInOut, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }; at(15) { hatSway(-1) }; at(40) { hatSway(0.6) }; at(60) { hatSway(-0.9) }
    at(75) { hatSway(0.45) }; at(88) { hatSway(-0.2) }; to { hatSway(0) }
  }
  // Blinks himself awake at the top of the jump.
  animate(1.45, 0.3, Easing.easeOut) { from { look(0, -0.6) }; to { look(0, 0) } }

  // Hello! Happy squint, smile, a wave.
  expr(2.1, Mood.HAPPY, Mouth.SMILE)
  animate(2.1, 0.3, Easing.easeOut) { to { blush(0.6) } }
  animate(2.15, 0.3, Easing.outBack) {
    from { rightHand(Hand.PALM, x = 560, y = 560, angle = 30, scale = 0) }
    to { rightHand(x = 610, y = 400, angle = 0, scale = 1) }
  }
  animate(2.45, 0.24, Easing.easeInOut, iterations = 5f, direction = Direction.ALTERNATE) {
    from { rightHand(angle = -22) }; to { rightHand(angle = 24) }
  }
  animate(2.3, 0.6, Easing.easeInOut, iterations = 2f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { rotation(0) }; to { rotation(3) }
  }
  say("Hi! I'm Pip, your scam-spotting sidekick!", 2.2, 2.1)
  animate(3.65, 0.25, Easing.inBack) { to { rightHand(x = 560, y = 560, scale = 0) } }
  snap(3.9) { rightHand(Hand.NONE) }

  // A curious look left, then right, with the body following the eyes a little late.
  expr(3.8, Mood.NEUTRAL, Mouth.SMILE)
  animate(3.85, 0.2, snappy) { to { look(-1, 0) } }
  animate(3.9, 0.3, Easing.easeOut) { to { rotation(-4) } }
  animate(4.3, 0.2, snappy) { to { look(1, 0) } }
  animate(4.35, 0.3, Easing.easeOut) { to { rotation(4) } }
  animate(4.75, 0.2, Easing.easeInOut) { to { look(0, 0); rotation(0) } }
  blinkAt(4.85, double = true)

  // Happy double-bounce to finish.
  expr(5.3, Mood.HAPPY, Mouth.SMILE)
  hop(5.3, 0.38, cx, home, cx, home, 70f, sounds = false)
  hop(5.68, 0.32, cx, home, cx, home, 35f, sounds = false)
}

private fun hopsScene() = scene("hops", 6) {
  pip { x = 540f; y = 1400f; size = 300f; mood = Mood.NEUTRAL; mouth = Mouth.SMILE }
  hop(0.1, 1.0, 540f, 1400f, 260f, 1240f, 230f)
  hop(1.2, 0.95, 260f, 1240f, 500f, 950f, 200f)
  hop(2.25, 0.95, 500f, 950f, 800f, 1160f, 240f)
  // A wide one back across the screen: the mouth goes "o" at the top.
  hop(3.3, 1.05, 800f, 1160f, 330f, 790f, 230f, lean = 9f)
  expr(3.6, Mood.NEUTRAL, Mouth.O)
  expr(4.15, Mood.NEUTRAL, Mouth.SMILE)
  // The big finale hop, with a grin on landing.
  hop(4.5, 1.2, 330f, 790f, 880f, 1050f, 250f, lean = 10f)
  expr(5.5, Mood.HAPPY, Mouth.SMILE)
}

/**
 * Peekaboo with the screen edges. His eyes find the right edge, he leans away and zips off; then he creeps back in
 * from that edge, slow and curious (see [edgePeek]), is spotted and ducks out. A beat later he pops in from the
 * opposite edge, quick and bold ("Psst!"), and springs out of it across the screen to perch above YouTube.
 */
private fun edgePeekScene() = scene("edgePeek", 7) {
  val off = W + 300f
  val sneaky = Easing.cubicBezier(0.55f, 0f, 0.9f, 0.4f)
  pip { x = 880f; y = 1050f; size = 300f; mood = Mood.HAPPY; mouth = Mouth.SMILE }

  // A thought: blink out of the grin, eyes slide to the edge, a sly squint, lean away... and zip.
  animate(0.0, 0.22, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.easeIn) { blink(0) }; at(30) { blink(1) }; at(42, Easing.easeOut) { blink(1) }; to { blink(0) }
  }
  expr(0.07, Mood.NEUTRAL, Mouth.SMILE)
  animate(0.14, 0.11, snappy) { to { look(1, 0.05) } }
  animate(0.14, 0.3, Easing.easeInOutSine) { to { lidTop(0.35); squint(0.25) } }
  animate(0.3, 0.24, Easing.easeOut) { to { squash(1.14, 0.88); rotation(-8); x(862) } }
  animate(0.54, 0.26, sneaky) { to { x(off) } }
  animate(0.54, 0.26, Easing.easeOut) { at(35) { squash(0.8, 1.2); rotation(16) }; to { squash(0.86, 1.16); rotation(18) } }
  animate(0.3, 0.6, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }; at(35, Easing.easeInOutSine) { hatSway(0.35) }; at(70) { hatSway(-0.9) }; to { hatSway(-0.7) }
  }
  animate(0.54, 0.2) { to { shadow(0) } }
  cue(0.54, "whoosh")

  // Back in from the same edge, slow and curious; spotted, he snaps out.
  edgePeek(1.0, Edge.RIGHT, along = 1000f, creep = 0.85f, hold = 0.3f)

  // Then the other edge, quick and bold, and this time he stays.
  val left = 1250f
  val l = edgePeek(4.5, Edge.LEFT, along = left, creep = 0.35f, hold = 0f, beat = 0.5f, exit = false)
  val (bx, by) = edgeBubbleSpot(Edge.LEFT, left)
  peekBubble("Psst!", bx, by, l.spotted + 0.02f, 0.55f)

  // Out he springs from the edge, across the screen, shrinking to perch on the YouTube icon.
  val tHop = 6.0f
  animate(tHop, 0.25, Easing.easeInOutSine) { to { lidTop(0); squint(0); eyeScale(1); brows(0, 0, false); life(1) } }
  expr(tHop + 0.1f, Mood.NEUTRAL, Mouth.SMILE)
  hop(tHop, 0.98, -95f * 300f / 540f, left, 922f, 1500f, 260f, size0 = 300f, size1 = 170f, lean = 10f)
  animate(tHop + 0.66f, 0.18, Easing.easeOut) { to { shadow(1) } }
  expr(tHop + 0.8f, Mood.HAPPY, Mouth.SMILE)
}

/**
 * Peekaboo with the home-screen icons. He hops down behind YouTube and does the full curious peek over its rim
 * (see [iconPeek]) until he's spotted and ducks. Then, quicker and bolder, he leans out from behind Photos; caught
 * again, he owns it ("Boo!"), rises right out of the icon and hops back to centre stage.
 */
private fun iconsScene() = scene("icons", 9) {
  val yt = stage.icon("YouTube"); val ph = stage.icon("Photos")
  val yHid = yt.cell.bottom + 34f
  pip { x = 922f; y = 1500f; size = 170f; mood = Mood.HAPPY; mouth = Mouth.SMILE }

  // Eyes drop to the icon, a crouch, a little hop... and down behind it, stretching as he drops.
  animate(0.0, 0.22, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.easeIn) { blink(0) }; at(30) { blink(1) }; at(42, Easing.easeOut) { blink(1) }; to { blink(0) }
  }
  expr(0.07, Mood.NEUTRAL, Mouth.SMILE)
  animate(0.12, 0.12, snappy) { to { look(0, 1) } }
  animate(0.2, 0.18, Easing.easeOut) { to { squash(1.18, 0.84) } }
  animate(0.38, 0.55, Easing.linear) { at(0, rise) { y(1500); x(922) }; at(35, fall) { y(1430) }; to { y(yHid); x(yt.cx) } }
  animate(0.38, 0.55, Easing.linear) {
    at(0, Easing.easeOut) { squash(1.18, 0.84) }; at(14, Easing.easeInOutSine) { squash(0.82, 1.22); look(0, -0.3) }
    at(35, Easing.easeInOutSine) { squash(0.97, 1.03) }; at(62, Easing.easeIn) { squash(0.84, 1.2) }; to { squash(1, 1) }
  }
  animate(0.38, 0.6, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }; at(18, Easing.easeInOutSine) { hatSway(-0.7) }; at(40, Easing.easeInOutSine) { hatSway(0.4) }
    at(70, Easing.easeInOutSine) { hatSway(0.8) }; to { hatSway(0) }
  }
  animate(0.38, 0.2) { to { shadow(0) } }
  snap(0.58) { hideBehind("YouTube") }
  cue(0.38, "hop"); cue(0.72, "whoosh")

  // YouTube: the full, slow, curious peek; spotted, he ducks.
  iconPeek(1.15, "YouTube", IconPeekStyle.UP, rise = 0.75f, hold = 0.2f)

  // Photos: quicker and bolder, leaning out of the side. Caught again, and this time he owns it.
  val p = iconPeek(4.7, "Photos", IconPeekStyle.SIDE_LEFT, rise = 0.6f, beat = 0.65f, exit = false)
  // Pinned up and to his left so it stays clear of him when he rises out of the icon.
  peekBubble("Boo!", ph.cx - 160f, ph.top - 30f, p.spotted + 0.05f, 0.6f)
  cue(p.spotted + 0.05f, "boo")

  // Up and out of the icon with a grin, then a big hop back to centre stage, growing to full size.
  val t = p.spotted + 0.42f
  animate(t - 0.06f, 0.22, ease = Easing.linear, fill = Fill.NONE) {
    at(0, Easing.easeIn) { blink(0) }; at(30) { blink(1) }; at(42, Easing.easeOut) { blink(1) }; to { blink(0) }
  }
  expr(t, Mood.HAPPY, Mouth.SMILE)
  animate(t, 0.25, Easing.easeInOutSine) { to { lidTop(0); squint(0); eyeScale(1); brows(0, 0, false); life(1); look(0, 0) } }
  animate(t, 0.34, Easing.outBack) { to { x(ph.cx); y(ph.top - 4f); rotation(0) } }
  animate(t, 0.5, Easing.easeOut) { at(0) { squash(0.84, 1.2) }; at(55) { squash(1.08, 0.94) }; to { squash(1, 1) } }
  animate(t, 0.6, Easing.linear, composite = Composite.ADD, fill = Fill.NONE) {
    from { hatSway(0) }; at(25, Easing.easeInOutSine) { hatSway(0.7) }; at(55, Easing.easeInOutSine) { hatSway(-0.35) }; to { hatSway(0) }
  }
  animate(t + 0.2f, 0.2) { to { shadow(1) } }
  snap(t + 0.3f) { hideBehind(null) }
  cue(t, "pop")
  hop(t + 0.42f, 1.15, ph.cx, ph.top - 4f, 540f, 1350f, 200f, size0 = 170f, size1 = 300f)
  expr(t + 0.42f + 0.82f * 1.15f, Mood.HAPPY, Mouth.SMILE)
}

/** Goodbye wave, then a big hop off the bottom of the screen. */
fun outroScene(): Scene = scene("outro", 5) {
  val cx = 540f; val home = 1350f
  pip { x = cx; y = home; size = 300f; mood = Mood.HAPPY; mouth = Mouth.SMILE }

  animate(0.0, 0.3, Easing.easeOut) { to { blush(0.6) } }
  animate(0.1, 0.3, Easing.outBack) {
    from { rightHand(Hand.PALM, x = 560, y = 560, angle = 30, scale = 0) }
    to { rightHand(x = 615, y = 390, angle = 0, scale = 1) }
  }
  animate(0.35, 0.26, Easing.easeInOut, iterations = 7f, direction = Direction.ALTERNATE) {
    from { rightHand(angle = -24) }; to { rightHand(angle = 24) }
  }
  animate(0.35, 0.52, Easing.easeInOut, iterations = 4f, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.NONE) {
    from { rotation(-2) }; to { rotation(3) }
  }
  say("Stay safe out there! — Pip", 0.3, 2.3)
  animate(2.3, 0.25, Easing.inBack) { to { rightHand(x = 560, y = 560, scale = 0) } }
  snap(2.55) { rightHand(Hand.NONE) }

  // A last look at the viewer: open eyes, a blink, a warm smile.
  expr(2.6, Mood.NEUTRAL, Mouth.SMILE)
  blinkAt(2.85)
  expr(3.15, Mood.HAPPY, Mouth.SMILE)

  // Deep crouch, a hop up... and off the bottom of the screen.
  animate(3.25, 1.35, Easing.linear) {
    from { y(home) }
    at(22, rise) { y(home) }
    at(48, Easing.cubicBezier(0.55f, 0f, 0.9f, 0.5f)) { y(home - 300) }
    to { y(H + 500f) }
  }
  animate(3.25, 1.35, Easing.easeInOut) {
    at(20, Easing.easeOut) { squash(1.26, 0.74) }
    at(28) { squash(0.78, 1.28) }
    at(48) { squash(1, 1) }
    at(70) { squash(0.82, 1.25) }
    to { squash(0.75, 1.35) }
  }
  animate(3.25, 1.35, Easing.easeInOut, composite = Composite.ADD, fill = Fill.NONE) {
    at(22) { hatSway(0.3) }; at(32) { hatSway(-1) }; at(50) { hatSway(0.6) }; to { hatSway(1) }
  }
  expr(3.6, Mood.HAPPY, Mouth.O)
  cue(3.55, "hop")
  cue(4.0, "whoosh")
}
