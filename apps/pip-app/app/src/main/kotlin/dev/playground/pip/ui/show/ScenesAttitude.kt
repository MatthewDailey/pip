package dev.playground.pip.ui.show

import dev.playground.pip.ui.Hand
import dev.playground.pip.ui.Headwear
import dev.playground.pip.ui.Mood
import dev.playground.pip.ui.Mouth
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.Fill
import dev.playground.pip.ui.anim.INFINITE

/** Pip's attitude block: the ninja who slices a scam call, the disappointed sigh, and the finger-wagging "no". */
fun attitudeScenes(): List<Scene> = listOf(ninjaScene(), disappointedScene(), noNoNoScene())

private const val HOME_X = 540f
private const val HOME_Y = 1350f

/** A whip-fast stroke: nearly all of the travel happens in the first few frames. */
private val snap = Easing.cubicBezier(0.08f, 0.9f, 0.2f, 1f)

/** Shared base: Pip at home, hands parked beside him (hidden until a scene gives them a kind). */
private fun SceneScope.homeBase() = pip {
  x = HOME_X; y = HOME_Y; size = 300f
  right.x = 600f; right.y = 470f
  left.x = -60f; left.y = 470f
}

private fun ninjaScene() = scene("ninja", 9) {
  homeBase()

  // Card hovers to his right, at about eye height, where the sword can reach it.
  val cardX = 880f
  val cardY = 1130f

  // --- Poof: a crouch, the smoke, and he's a ninja. ---
  animate(at = 0.05, duration = 0.3, ease = Easing.easeOut) {
    to { squash(1.1, 0.9) }
  }
  cue(0.35, "poof")
  actor(ActorKind.SMOKE, anchorToPip = true, layer = 1) { y = 200f; scale = 1.7f; visible = false }
    .animate(at = 0.35, duration = 0.85, ease = Easing.easeOut, fill = Fill.NONE) {
      from { visible(true); progress(0); scale(1.2) }
      to { progress(1); scale(1.9) }
    }
  animate(at = 0.5, duration = 0.45, ease = Easing.linear) {
    from(Easing.easeOut) { headwear(Headwear.NINJA); mood(Mood.ANGRY); mouth(Mouth.FLAT); squash(0.88, 1.16) }
    at(45, Easing.easeInOut) { squash(1.08, 0.94) }
    to { squash(1, 1) }
  }

  // --- Sword drawn with a spinning flourish. ---
  cue(0.95, "whoosh")
  animate(at = 0.9, duration = 0.55, ease = Easing.linear) {
    from(Easing.easeOut) { rightHand(Hand.SWORD, x = 540, y = 540, angle = -560, scale = 0.2) }
    at(70, Easing.easeInOut) { rightHand(x = 625, y = 430, angle = 30, scale = 1.05) }
    to { rightHand(x = 610, y = 450, angle = 15, scale = 1) }
  }

  // --- The scam card spins in from the left and hovers. ---
  cue(1.25, "whoosh")
  val card = actor(ActorKind.SCAM_CARD, "SCAM CALL", anchorToPip = false, layer = -1) { x = -260f; y = cardY + 120f; alpha = 0f }
  card.animate(at = 1.25, duration = 0.7, ease = Easing.linear) {
    from(Easing.cubicBezier(0.2f, 0.7f, 0.3f, 1f)) { x(-260); alpha(1); rotation(-900) }
    at(80, Easing.easeOut) { x(cardX + 25); rotation(12) }
    to { x(cardX); rotation(0) }
  }
  card.animate(at = 1.25, duration = 0.7, ease = Easing.linear) {
    from(Easing.easeOut) { y(cardY + 120) }
    at(55, Easing.easeInOut) { y(cardY - 40) }
    to { y(cardY) }
  }
  // Hover bob until the slashes land; an even count ends back at rest.
  card.animate(at = 1.95, duration = 0.6, ease = Easing.easeInOut, iterations = 4f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { y(-10); rotation(-3) }
    to { y(10); rotation(3) }
  }
  // Pip's eyes follow it across the screen.
  animate(at = 1.25, duration = 0.7, ease = Easing.easeInOut) {
    from { look(-1, 0.1) }
    to { look(1, 0) }
  }

  // --- Anticipation: crouch, lean back, sword cocked. ---
  animate(at = 2.05, duration = 0.45, ease = Easing.easeOut) {
    to { squash(1.14, 0.84); rotation(-7); eyesOpen(0.7); rightHand(x = 560, y = 400, angle = -75) }
  }

  // --- Three lightning slashes. Each: the blade sweeps, he dashes and leans, a trail appears. ---
  fun stroke(at: Float, fromX: Float, toX: Float, fromAngle: Float, toAngle: Float, lean: Float, trail: Float, endSquash: Pair<Float, Float>) {
    cue(at + 0.02f, "slash")
    animate(at = at, duration = 0.14, ease = snap) {
      from { x(fromX); rightHand(angle = fromAngle, x = 590, y = 440) }
      to { x(toX); rightHand(angle = toAngle, x = 620, y = 470) }
    }
    animate(at = at, duration = 0.3, ease = Easing.linear) {
      from(snap) { squash(0.86, 1.16) }
      at(35, Easing.easeOut) { rotation(lean); squash(endSquash.first, endSquash.second) }
      to { rotation(lean * 0.85f) }
    }
    actor(ActorKind.SLASH, anchorToPip = false, layer = 2) { x = cardX; y = cardY; rotation = trail; scale = 1.25f; visible = false }
      .animate(at = at, duration = 0.5, ease = Easing.linear, fill = Fill.NONE) {
        from(snap) { visible(true); progress(0); alpha(1) }
        at(32, Easing.easeIn) { progress(1); alpha(1) }
        to { progress(1); alpha(0) }
      }
  }
  stroke(2.6f, HOME_X, 600f, -75f, 130f, 12f, 40f, 1.06f to 0.95f)
  stroke(2.95f, 600f, 640f, 130f, -40f, -8f, -40f, 1.05f to 0.96f)
  stroke(3.3f, 640f, 670f, -40f, 105f, 14f, 0f, 1.15f to 0.88f)

  // --- A beat of stillness: eyes shut, frozen in the follow-through. ---
  animate(at = 3.45, duration = 0.25, ease = Easing.easeOut) {
    to { eyesOpen(0.12); look(0, 0) }
  }
  animate(at = 3.6, duration = 0.7, ease = Easing.easeInOut) {
    to { rotation(10); squash(1.1, 0.91) }
  }

  // --- The card splits and falls behind him. ---
  cue(4.32, "split")
  card.animate(at = 4.3, duration = 1.0, ease = Easing.easeIn) {
    from { progress(0) }
    to { progress(1) }
  }
  card.animate(at = 5.0, duration = 0.4, ease = Easing.easeIn) {
    to { alpha(0) }
  }
  animate(at = 4.45, duration = 0.2, ease = Easing.easeOut) {
    to { eyesOpen(1); mouth(Mouth.SMILE); look(1, 0.2) }
  }

  // --- Hop back home, sword settling upright. ---
  cue(4.62, "hop")
  cue(5.06, "land")
  animate(at = 4.6, duration = 0.62, ease = Easing.linear) {
    from(Easing.easeOut) { squash(1.12, 0.86) }
    at(10, Easing.easeOut) { x(670); y(HOME_Y); squash(0.9, 1.12); rotation(-6) }
    at(40, Easing.easeIn) { y(HOME_Y - 120) }
    at(72, Easing.easeOut) { x(HOME_X); y(HOME_Y); squash(1.16, 0.84); rotation(0) }
    to { squash(1, 1); look(0, 0) }
  }
  animate(at = 4.6, duration = 0.45, ease = Easing.easeInOut) {
    to { rightHand(x = 610, y = 450, angle = 20) }
  }

  // --- Twirl, then sheath (the blade slides away and the hand is gone). ---
  cue(5.25, "whoosh")
  animate(at = 5.2, duration = 0.7, ease = Easing.easeInOut) {
    from { rightHand(angle = 20, y = 450) }
    at(50) { rightHand(y = 400) }
    to { rightHand(angle = 740, y = 450) }
  }
  animate(at = 5.95, duration = 0.4, ease = Easing.linear) {
    from(Easing.easeInOut) { rightHand(angle = 740, x = 610, y = 450, scale = 1) }
    at(55, Easing.inBack) { rightHand(angle = 900, x = 590, y = 560, scale = 1) }
    at(99.9) { rightHand(scale = 0) }
    to { rightHand(Hand.NONE, scale = 0) }
  }
  cue(6.35, "ding")

  // --- Strike a pose and deliver the line. ---
  animate(at = 6.35, duration = 0.4, ease = Easing.linear) {
    from(Easing.easeOut) { squash(0.9, 1.14); rotation(0) }
    at(45, Easing.easeInOut) { squash(1.05, 0.96); rotation(-5) }
    to { squash(1, 1); rotation(-4) }
  }
  animate(at = 6.35, duration = 0.3, ease = Easing.outBack) {
    from { leftHand(Hand.PALM, x = 0, y = 520, angle = -10, scale = 0.2); rightHand(Hand.FIST, x = 560, y = 520, scale = 0.2) }
    to { leftHand(x = -70, y = 420, angle = -30, scale = 1); rightHand(x = 610, y = 410, scale = 1) }
  }
  animate(at = 6.5, duration = 0.2, ease = Easing.easeOut) {
    to { eyesOpen(0.75); look(0.3, 0) }
  }
  say("Scam: sliced.", at = 6.5, duration = 1.7)

  // --- Headband vanishes in a small poof; back to plain Pip. ---
  animate(at = 8.15, duration = 0.2, ease = Easing.inBack) {
    to { leftHand(scale = 0); rightHand(scale = 0) }
  }
  animate(at = 8.35, duration = 0.01) {
    to { leftHand(Hand.NONE); rightHand(Hand.NONE) }
  }
  cue(8.25, "poof")
  actor(ActorKind.SMOKE, anchorToPip = true, layer = 1) { y = 180f; scale = 0.8f; visible = false }
    .animate(at = 8.25, duration = 0.6, ease = Easing.easeOut, fill = Fill.NONE) {
      from { visible(true); progress(0); scale(0.6) }
      to { progress(1); scale(1.0) }
    }
  animate(at = 8.38, duration = 0.5, ease = Easing.linear) {
    from(Easing.easeOut) { headwear(Headwear.NONE); squash(1.08, 0.93); eyesOpen(1); look(0, 0) }
    at(15) { mood(Mood.NEUTRAL); mouth(Mouth.NONE) }
    at(40, Easing.easeInOut) { squash(0.97, 1.03); rotation(0) }
    to { squash(1, 1); rotation(0) }
  }
}

private fun disappointedScene() = scene("disappointed", 6) {
  homeBase()

  // --- Face falls: brows, frown, the hat wilts, eyes drop. ---
  animate(at = 0.0, duration = 0.6, ease = Easing.easeInOut) {
    from { mood(Mood.SAD); mouth(Mouth.FROWN) }
    to { hatDroop(0.85); eyesOpen(0.6); look(0, 0.7) }
  }

  // --- The sigh: a slow inhale up, then a long deflation into a slump. ---
  cue(0.7, "sigh")
  animate(at = 0.55, duration = 1.75, ease = Easing.linear) {
    from(Easing.easeInOut) { squash(1, 1) }
    at(28, Easing.easeOut) { squash(0.95, 1.07); look(0, 0.2) }
    at(85, Easing.easeInOut) { squash(1.13, 0.85); look(0, 0.8) }
    to { squash(1.1, 0.88) }
  }
  // Slumped breathing while he stews.
  animate(at = 2.3, duration = 1.1, ease = Easing.easeInOut, iterations = 2f, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { squash(0, 0) }
    to { squash(-0.015, 0.02) }
  }

  // --- His own personal rain cloud drifts in over his head. ---
  val cloud = actor(ActorKind.RAIN_CLOUD, anchorToPip = true, layer = 1) { x = 10f; y = -420f; alpha = 0f }
  cloud.animate(at = 1.1, duration = 0.8, ease = Easing.easeOut) {
    from { y(-420); alpha(0) }
    to { y(-120); alpha(1) }
  }
  cloud.animate(at = 1.1, duration = 0.9, ease = Easing.linear, iterations = INFINITE) {
    from { progress(0) }
    to { progress(1) }
  }
  // Floats up out of the way of the bubble, then bobs.
  cloud.animate(at = 2.35, duration = 0.35, ease = Easing.easeInOut) {
    to { y(-330) }
  }
  cloud.animate(at = 1.9, duration = 0.9, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE, composite = Composite.ADD) {
    from { x(-8); y(-4) }
    to { x(8); y(4) }
  }
  cloud.animate(at = 5.4, duration = 0.5, ease = Easing.easeIn) {
    to { alpha(0) }
  }

  // --- A bead of sweat. ---
  actor(ActorKind.SWEAT, anchorToPip = true, layer = 1) { x = 120f; y = 70f; alpha = 0f }
    .animate(at = 2.1, duration = 1.4, ease = Easing.linear) {
      from(Easing.outBack) { scale(0.3); alpha(1); y(70) }
      at(20, Easing.easeIn) { scale(1) }
      at(75, Easing.easeIn) { alpha(1) }
      to { y(170); alpha(0) }
    }

  // --- The line, then a little head shake of disbelief. ---
  say("You clicked the link… didn't you?", at = 2.6, duration = 2.6)
  animate(at = 2.6, duration = 0.3, ease = Easing.easeOut) {
    to { look(0.2, 0); eyesOpen(0.55) }
  }
  animate(at = 3.7, duration = 0.9, ease = Easing.linear, composite = Composite.ADD) {
    from(Easing.easeInOut) { rotation(0); look(0, 0) }
    at(18, Easing.easeInOut) { rotation(-2.5); look(-0.4, 0) }
    at(42, Easing.easeInOut) { rotation(2.5); look(0.4, 0) }
    at(66, Easing.easeInOut) { rotation(-1.5); look(-0.25, 0) }
    to { rotation(0); look(0, 0) }
  }

  // --- Perk back up to neutral shape so the next scene picks up cleanly. ---
  animate(at = 5.3, duration = 0.6, ease = Easing.easeInOut) {
    to { squash(1, 1); hatDroop(0); eyesOpen(1); look(0, 0) }
  }
}

private fun noNoNoScene() = scene("nonono", 5) {
  homeBase()

  // --- Snap to cross: a quick dip, brows down, finger up. ---
  animate(at = 0.0, duration = 0.35, ease = Easing.linear) {
    from(Easing.easeOut) { mood(Mood.ANGRY); mouth(Mouth.FLAT); squash(1, 1) }
    at(35, Easing.easeOut) { squash(1.1, 0.9) }
    at(70, Easing.easeInOut) { squash(0.95, 1.06) }
    to { squash(1, 1) }
  }
  animate(at = 0.1, duration = 0.3, ease = Easing.outBack) {
    from { rightHand(Hand.POINT, x = 560, y = 520, angle = 0, scale = 0.2) }
    to { rightHand(x = 610, y = 400, scale = 1.1) }
  }

  // --- The wag: ±25° forever, until the stare takes over. ---
  animate(at = 0.35, duration = 0.2, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE) {
    from { rightHand(angle = -25) }
    to { rightHand(angle = 25) }
  }

  // --- One bubble, stepping through the escalation. ---
  val bubble = actor(ActorKind.BUBBLE, "No.", anchorToPip = true) { alpha = 0f }
  bubble.animate(at = 0.4, duration = 3.3, ease = Easing.linear, fill = Fill.NONE) {
    from { text("No."); alpha(1) }
    at(100f * 0.9f / 3.3f) { text("No no.") }
    at(100f * 1.9f / 3.3f) { text("No no no!") }
    at(92) { alpha(1) }
    to { alpha(0) }
  }
  // A pop each time the text grows.
  for ((t, s) in listOf(0.4f to 1.0f, 1.3f to 1.08f, 2.3f to 1.18f)) {
    bubble.animate(at = t, duration = 0.28, ease = Easing.outBack) {
      from { scale(0.5) }
      to { scale(s) }
    }
  }

  // --- Each "no": a head shake (rotation, layered) and a little stamp. ---
  fun shake(at: Float, deg: Float, dur: Float) {
    animate(at = at, duration = dur, ease = Easing.linear, composite = Composite.ADD) {
      from(Easing.easeOut) { rotation(0) }
      at(25, Easing.easeInOut) { rotation(-deg) }
      at(60, Easing.easeInOut) { rotation(deg) }
      to { rotation(0) }
    }
    animate(at = at, duration = 0.18, ease = Easing.linear, composite = Composite.ADD) {
      from(Easing.easeOut) { squash(0, 0) }
      at(40, Easing.easeOut) { squash(0.06, -0.07) }
      to { squash(0, 0) }
    }
  }
  val noTimes = listOf(0.4f, 1.3f, 1.55f, 2.3f, 2.52f, 2.74f)
  for ((i, t) in noTimes.withIndex()) {
    cue(t, "no")
    shake(t, 2.5f + i * 0.6f, 0.22f)
  }
  // Escalating eyes: narrower each round.
  animate(at = 1.3, duration = 0.15, ease = Easing.easeOut) { to { eyesOpen(0.85) } }
  animate(at = 2.3, duration = 0.15, ease = Easing.easeOut) { to { eyesOpen(0.7); mouth(Mouth.SHOUT) } }

  // --- Stern stare: finger freezes on the viewer, he leans in. ---
  animate(at = 3.05, duration = 0.3, ease = Easing.outBack) {
    to { rightHand(angle = 8, x = 600, y = 380); mouth(Mouth.FLAT); eyesOpen(0.55); look(0, 0); size(318); squash(1, 1) }
  }
  animate(at = 3.35, duration = 0.9, ease = Easing.easeInOut) {
    to { size(322); eyesOpen(0.5) }
  }

  // --- Quick reset to neutral. ---
  animate(at = 4.25, duration = 0.18, ease = Easing.inBack) {
    to { rightHand(scale = 0) }
  }
  animate(at = 4.4, duration = 0.45, ease = Easing.linear) {
    from(Easing.easeOut) { rightHand(Hand.NONE); mood(Mood.NEUTRAL); mouth(Mouth.NONE); size(322); eyesOpen(1); squash(1.08, 0.93) }
    at(40, Easing.easeInOut) { size(300); squash(0.97, 1.03) }
    to { size(300); squash(1, 1) }
  }
}
