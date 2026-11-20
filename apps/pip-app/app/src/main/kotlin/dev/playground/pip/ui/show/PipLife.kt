package dev.playground.pip.ui.show

import dev.playground.pip.ui.PipState
import dev.playground.pip.ui.anim.Composite
import dev.playground.pip.ui.anim.Direction
import dev.playground.pip.ui.anim.Easing
import dev.playground.pip.ui.anim.FloatProp
import dev.playground.pip.ui.anim.INFINITE
import dev.playground.pip.ui.anim.KeyframesBuilder
import dev.playground.pip.ui.anim.StopScope

/**
 * Pip's always-on "aliveness": idle blinks, eye darts, breathing, hat sway and a brow flicker.
 *
 * These drive their own `life*` channels on [PipState], which scenes never touch, and the renderer adds them on top
 * of whatever the scene is doing. So a scene that holds `look(1, 0)` still gets tiny darts around that target, and a
 * scene blink simply overlaps the idle ones. A scene that wants a dead-locked stare sets `life(0)` (or animates it).
 *
 * Every loop has its own period, chosen so no two share a factor (2.9, 3.1, 4.3, 5.9, 7.1, 8.9, 9.7, 11.7, 13.3 s).
 * The combination doesn't line up into a visible cycle within the show, so the eyes never "do the same thing".
 */
object LifeProps {
  val life = FloatProp<PipState>("life", { it.life }, { s, v -> s.life = v })
  val lookX = FloatProp<PipState>("lifeLookX", { it.lifeLookX }, { s, v -> s.lifeLookX = v })
  val lookY = FloatProp<PipState>("lifeLookY", { it.lifeLookY }, { s, v -> s.lifeLookY = v })
  val blink = FloatProp<PipState>("lifeBlink", { it.lifeBlink }, { s, v -> s.lifeBlink = v })
  val sway = FloatProp<PipState>("lifeSway", { it.lifeSway }, { s, v -> s.lifeSway = v })
  val breath = FloatProp<PipState>("lifeBreath", { it.lifeBreath }, { s, v -> s.lifeBreath = v })
  val brow = FloatProp<PipState>("lifeBrow", { it.lifeBrow }, { s, v -> s.lifeBrow = v })
}

/** How alive the idle layer is: 1 = normal, 0 = frozen (a locked, unblinking stare). Scenes may animate it. */
fun StopScope<PipState>.life(v: Number) = set(LifeProps.life, v)

private fun StopScope<PipState>.dart(x: Number, y: Number) { set(LifeProps.lookX, x); set(LifeProps.lookY, y) }
private fun StopScope<PipState>.lid(v: Number) = set(LifeProps.blink, v)

/**
 * One blink inside a loop of [cycle] seconds, starting at [startPct] percent. Lids snap shut, hold briefly and open
 * more softly (close : open about 1 : 1.8), per the playbook's Bloop timings. Times are seconds.
 */
private fun KeyframesBuilder<PipState>.blinkIn(cycle: Double, startPct: Double, close: Double = 0.07, hold: Double = 0.03, open: Double = 0.13, depth: Double = 1.0) {
  fun pct(sec: Double) = startPct + sec / cycle * 100.0
  at(startPct, Easing.easeIn) { lid(0) }
  at(pct(close), Easing.linear) { lid(depth) }
  at(pct(close + hold), Easing.easeOutSine) { lid(depth) }
  at(pct(close + hold + open)) { lid(0) }
}

/** A saccade: hold the previous target until [atPct], then dart to (x, y) in about [dur] seconds. */
private fun KeyframesBuilder<PipState>.dartTo(cycle: Double, atPct: Double, fromX: Double, fromY: Double, x: Double, y: Double, dur: Double = 0.11) {
  at(atPct, Easing.easeOut) { dart(fromX, fromY) }
  at(atPct + dur / cycle * 100.0) { dart(x, y) }
}

fun SceneScope.pipLife() {
  val add = Composite.ADD

  // --- Blinks: three independent rhythms (average one every ~2.6 s, never on a beat). ---
  animate(at = 0, duration = 5.3, ease = Easing.linear, iterations = INFINITE, composite = add) {
    from { lid(0) }; blinkIn(5.3, 58.0); to { lid(0) }
  }
  animate(at = 0, duration = 11.7, ease = Easing.linear, iterations = INFINITE, composite = add) {
    // A double blink, the second a touch quicker and shallower: a "thought change" now and then.
    from { lid(0) }
    blinkIn(11.7, 23.0)
    blinkIn(11.7, 23.0 + 0.30 / 11.7 * 100.0, close = 0.06, hold = 0.0, open = 0.11, depth = 0.9)
    to { lid(0) }
  }
  animate(at = 0, duration = 8.9, ease = Easing.linear, iterations = INFINITE, composite = add) {
    // A slower, lazier blink.
    from { lid(0) }; blinkIn(8.9, 81.0, close = 0.08, hold = 0.05, open = 0.21); to { lid(0) }
  }

  // --- Eye darts (saccades): quick ~110 ms moves, long holds. Three loops sum to an ever-changing gaze. ---
  animate(at = 0, duration = 2.9, ease = Easing.linear, iterations = INFINITE, composite = add) {
    from { dart(0, 0) }
    dartTo(2.9, 31.0, 0.0, 0.0, 0.2, -0.06)
    dartTo(2.9, 66.0, 0.2, -0.06, 0.0, 0.0)
    to { dart(0, 0) }
  }
  animate(at = 0, duration = 7.1, ease = Easing.linear, iterations = INFINITE, composite = add) {
    from { dart(0, 0) }
    dartTo(7.1, 12.0, 0.0, 0.0, -0.3, 0.05)
    dartTo(7.1, 39.0, -0.3, 0.05, -0.12, 0.14)
    dartTo(7.1, 57.0, -0.12, 0.14, 0.16, -0.03)
    dartTo(7.1, 84.0, 0.16, -0.03, 0.0, 0.0)
    to { dart(0, 0) }
  }
  animate(at = 0, duration = 13.3, ease = Easing.linear, iterations = INFINITE, composite = add) {
    from { dart(0, 0) }
    dartTo(13.3, 18.0, 0.0, 0.0, 0.08, 0.2)
    dartTo(13.3, 27.0, 0.08, 0.2, 0.0, 0.0)
    dartTo(13.3, 61.0, 0.0, 0.0, -0.1, -0.16)
    dartTo(13.3, 70.0, -0.1, -0.16, 0.0, 0.0)
    to { dart(0, 0) }
  }

  // --- Breathing: a slow sine stretch, about 1.4% at the top of the breath. ---
  animate(at = 0, duration = 3.1, ease = Easing.easeInOutSine, iterations = INFINITE, direction = Direction.ALTERNATE) {
    from { set(LifeProps.breath, 0) }; to { set(LifeProps.breath, 0.014) }
  }

  // --- Hat tip: two sways with unrelated periods, so it drifts rather than ticks. ---
  animate(at = 0, duration = 1.9, ease = Easing.easeInOutSine, iterations = INFINITE, direction = Direction.ALTERNATE, composite = add) {
    from { set(LifeProps.sway, -0.15) }; to { set(LifeProps.sway, 0.15) }
  }
  animate(at = 0, duration = 5.9, ease = Easing.easeInOutSine, iterations = INFINITE, direction = Direction.ALTERNATE, composite = add) {
    from { set(LifeProps.sway, -0.09) }; to { set(LifeProps.sway, 0.09) }
  }

  // --- Brows (only seen when drawn): a tiny lift now and then, as if listening. ---
  animate(at = 0, duration = 9.7, ease = Easing.linear, iterations = INFINITE, composite = add) {
    from { set(LifeProps.brow, 0) }
    at(34, Easing.easeOutSine) { set(LifeProps.brow, 0) }
    at(37, Easing.easeInOutSine) { set(LifeProps.brow, 0.12) }
    at(52, Easing.easeInOutSine) { set(LifeProps.brow, 0.1) }
    at(58) { set(LifeProps.brow, 0) }
    to { set(LifeProps.brow, 0) }
  }
}
