package dev.playground.pip.ui.show

/**
 * Pip's showreel. Scene lengths are fixed here so the soundtrack can be written against the same clock:
 * a scene starts where the previous one ends.
 */
fun pipShow() = Show(
  movementScenes() + attitudeScenes() + danceScenes() +
    sixtiesSeventies() + eightiesNineties() + modernDecades() + listOf(outroScene()),
) {
  // Always-on life under every scene: blinks, eye darts, breathing, hat sway (see PipLife.kt).
  pipLife()
}
