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

/** A tour of every pose and prop Pip has, one card at a time, for checking the drawing on a device. */
fun samplerShow(): Show {
  data class Card(val label: String, val style: dev.playground.pip.ui.PipState.() -> Unit)
  val cards = listOf(
    Card("Neutral") {},
    Card("Happy") { mood = Mood.HAPPY; mouth = Mouth.SMILE; blush = 1f },
    Card("Sad") { mood = Mood.SAD; mouth = Mouth.FROWN; hatDroop = 1f },
    Card("Angry") { mood = Mood.ANGRY; mouth = Mouth.FLAT },
    Card("Surprised") { mood = Mood.SURPRISED; mouth = Mouth.O },
    Card("Shouting") { mood = Mood.ANGRY; mouth = Mouth.SHOUT },
    Card("Ninja + sword") { headwear = Headwear.NINJA; right.kind = Hand.SWORD; right.x = 600f; right.y = 470f; right.angle = 20f },
    Card("Flower crown + peace") { headwear = Headwear.FLOWERS; right.kind = Hand.PEACE; right.x = 610f; right.y = 420f; mood = Mood.HAPPY },
    Card("Sweatband + cube") { headwear = Headwear.SWEATBAND; left.kind = Hand.CUBE; left.x = -70f; left.y = 520f },
    Card("Talk to the hand") { right.kind = Hand.PALM; right.x = 600f; right.y = 400f; right.scale = 1.6f; facing = -1f },
    Card("Phone") { right.kind = Hand.PHONE; right.x = 560f; right.y = 470f; right.angle = -15f; mouth = Mouth.SMILE },
    Card("Deal with it") { sunglasses = 1f; mouth = Mouth.FLAT },
    Card("Pointing") { right.kind = Hand.POINT; right.x = 620f; right.y = 330f; right.angle = 35f; mood = Mood.HAPPY },
  )
  val per = 2.5f
  val scenes = cards.map { card ->
    scene(card.label, per) {
      pip { x = W / 2; y = H * 0.62f; size = 360f; card.style(this) }
      actor(ActorKind.BANNER, card.label, anchorToPip = false) { x = W / 2; y = H * 0.25f }
      // Pop in on each card, then breathe.
      animate(at = 0, duration = 0.45, ease = Easing.linear) {
        at(0, Easing.easeOut) { squash(0.8, 1.2) }
        at(40, Easing.easeInOut) { squash(1.15, 0.85) }
        to { squash(1, 1) }
      }
      animate(at = 0.5, duration = 1.0, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE, composite = Composite.ADD, fill = Fill.BOTH) {
        from { look(-1, 0) }
        to { look(1, 0.3) }
      }
    }
  }
  val hop = scene("Hops", 4) {
    pip { x = W / 2; y = H * 0.62f; size = 360f; mood = Mood.HAPPY }
    actor(ActorKind.BANNER, "Hops", anchorToPip = false) { x = W / 2; y = H * 0.25f }
    animate(at = 0, duration = 1.0, ease = Easing.linear, iterations = INFINITE) {
      at(0, Easing.easeOut) { squash(1, 1); y(H * 0.62f) }
      at(12, Easing.easeOut) { squash(1.2, 0.8) }
      at(25, Easing.easeOut) { squash(0.85, 1.18) }
      at(55, Easing.easeIn) { y(H * 0.40f); squash(1, 1) }
      at(85, Easing.easeOut) { y(H * 0.62f); squash(0.9, 1.1) }
      at(92, Easing.easeOut) { squash(1.22, 0.8) }
      to { squash(1, 1) }
    }
  }
  return Show(scenes + hop) {
    animate(at = 0, duration = 3.2, ease = Easing.linear, iterations = INFINITE) {
      at(0) { blink(0) }; at(90) { blink(0) }; at(94) { blink(1) }; to { blink(0) }
    }
    animate(at = 0, duration = 1.5, ease = Easing.easeInOut, iterations = INFINITE, direction = Direction.ALTERNATE, composite = Composite.ADD) {
      from { hatSway(-0.4) }; to { hatSway(0.4) }
    }
  }
}
