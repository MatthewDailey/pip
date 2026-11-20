package dev.playground.pip.ui.show

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * Dumps exactly what [Show.frame] produces at 600 seeded times to `build/showreel/frames.txt`, float bits included.
 *
 * Run it before and after a change to the animation engine and compare the two files: identical files prove the
 * show plays identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShowFramesTest {
  @Test
  fun dumpFrames() {
    val show = pipShow()
    val pip = show.prepare(emulatorStage())
    val rnd = Random(20261120)
    val times = (List(500) { rnd.nextFloat() * show.duration } + List(100) { it * show.duration / 100f }).sorted()
    val actors = mutableListOf<ActorState>()
    val sb = StringBuilder()
    for (t in times) {
      show.frame(t, actors)
      sb.append("t=").append(t).append('\n')
      sb.append("  pip ").append(pipValues(pip).joinToString(" ") { (k, v) -> "$k=${exact(v)}" }).append('\n')
      for ((i, a) in actors.withIndex()) {
        sb.append("  actor$i ${a.kind} ").append(actorValues(a).joinToString(" ") { (k, v) -> "$k=${exact(v)}" }).append('\n')
      }
    }
    val out = File(showreelOutDir(), "frames.txt")
    out.writeText(sb.toString())
    assertTrue(times.size == 600)
  }

  private fun exact(v: Any?): String = if (v is Float) "${v}#${v.toRawBits()}" else v.toString()
}
