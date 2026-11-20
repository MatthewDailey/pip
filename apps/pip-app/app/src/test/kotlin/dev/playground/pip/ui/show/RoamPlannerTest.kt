package dev.playground.pip.ui.show

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The roam director's choices: seeded, varied, and landing where the Rive pieces will put Pip. */
class RoamPlannerTest {
  /** The emulator's home screen: one row of labelled icons, the dock, the At a glance widget and the search bar. */
  private fun home(shiftX: Float = 0f) = RoamStage(
    1080f, 2424f, 142f,
    icons = listOf("Play Store" to 50f, "Gmail" to 305f, "Photos" to 560f, "YouTube" to 815f).map { (l, x) ->
      RoamIcon.fromCell(l, x + shiftX, 1581f, x + shiftX + 216f, 1834f, 216f)
    } + listOf("Phone" to 71f, "Messages" to 326f, "Chrome" to 581f, "Photos" to 836f).map { (l, x) ->
      RoamIcon.fromCell(l, x, 1929f, x + 173f, 2124f, 216f)
    },
    bars = listOf(RoamBar("At a glance", 50f, 171f, 1030f, 424f), RoamBar("Search", 78f, 2149f, 1002f, 2314f)),
  )

  private fun run(seed: Long, stage: RoamStage, n: Int = 40): List<RoamMove> {
    val p = RoamPlanner(seed)
    p.start(stage)
    return List(n) { p.next(stage).also(p::landed) }
  }

  /** Undoes [Roam.blendValue]. */
  private fun played(v: Float, k: Float) = if (v >= 0f) v else -(v * v) / k

  /**
   * Gmail's and Phone's circles as drawn on the emulator (screencap of Pixel Launcher, API 37): Gmail spans
   * x 333-493, y 1595-1755; Phone x 78-238, y 1947-2107. The mask must cover each with the margin to spare.
   */
  @Test
  fun `the icon mask covers each drawn circle with a margin, and labels start below it`() {
    val gmail = RoamIcon.fromCell("Gmail", 305f, 1581f, 521f, 1834f, 216f)
    val phone = RoamIcon.fromCell("Phone", 71f, 1929f, 244f, 2124f, 216f)
    for ((icon, drawn) in listOf(gmail to floatArrayOf(333f, 1595f, 493f, 1755f), phone to floatArrayOf(78f, 1947f, 238f, 2107f))) {
      val (l, t, r, b) = drawn.toList()
      val reach = icon.d / 2
      assertTrue("${icon.label} left", icon.cx - reach <= l - 2f)
      assertTrue("${icon.label} right", icon.cx + reach >= r + 2f)
      assertTrue("${icon.label} top", icon.cy - reach <= t - 2f)
      assertTrue("${icon.label} bottom", icon.cy + reach >= b + 2f)
    }
    assertTrue(gmail.canHide && !phone.canHide)
    // The label box starts where the circle's mask ends, so the two never overlap (one even-odd clip cuts both).
    val slots = Roam.iconSlots(RoamStage(1080f, 2424f, 142f, listOf(gmail), emptyList()))
    assertEquals(gmail.cy + gmail.d / 2, slots.getValue("label0Y"), 0.01f)
    // "Gmail" reads about 90 px wide, from 1785 to 1815: the box covers it.
    assertTrue(slots.getValue("label0W") >= 95f && slots.getValue("label0Y") + slots.getValue("label0H") >= 1820f)
    assertEquals(0f, slots.getValue("icon1D"))
    // He stands on the masked rim, never inside it.
    assertEquals(gmail.cy - gmail.d / 2, gmail.top.y, 0.01f)
  }

  @Test
  fun `a seed replays exactly and another seed differs`() {
    assertEquals(run(7, home()), run(7, home()))
    assertNotEquals(run(7, home()).map { it.target }, run(8, home()).map { it.target })
  }

  @Test
  fun `never the same kind twice running, and every kind shows up`() {
    val moves = run(20261120, home(), 200)
    moves.zipWithNext().forEach { (a, b) -> assertNotEquals("${a.trigger} then ${b.trigger}", a.kind, b.kind) }
    assertEquals(RoamKind.entries.toSet(), moves.map { it.kind }.toSet())
  }

  @Test
  fun `hops are short, swings are long, and each lands where its numbers say`() {
    val p = RoamPlanner(3)
    val stage = home()
    p.start(stage)
    repeat(200) {
      val from = p.feet!!
      val m = p.next(stage)
      val v = m.values
      when (m.kind) {
        RoamKind.HOP -> {
          assertTrue(m.end.dist(from) <= 520f || m.end.y > from.y)
          assertEquals(m.end.x, v.getValue("anchorX") + played(v.getValue("dx"), Roam.BLEND_PX), 0.5f)
          assertEquals(m.end.y, v.getValue("anchorY") + played(v.getValue("dy"), Roam.BLEND_PX), 0.5f)
        }
        RoamKind.SWING -> {
          assertTrue(m.end.dist(from) >= 380f)
          // The Rive chain: anchor, the arm turned by armAngle + sweep, armLength + reel down it, then grip.
          val angle = v.getValue("armAngle") + played(v.getValue("sweep"), Roam.BLEND_RAD)
          val len = v.getValue("armLength") + played(v.getValue("reel"), Roam.BLEND_PX)
          assertEquals(m.end.x, v.getValue("anchorX") - len * sin(angle), 0.5f)
          assertEquals(m.end.y, v.getValue("anchorY") + len * cos(angle) + v.getValue("grip"), 0.5f)
          // And it starts where he stands.
          val a0 = v.getValue("armAngle")
          assertEquals(from.x, v.getValue("anchorX") - v.getValue("armLength") * sin(a0), 0.5f)
          assertEquals(from.y, v.getValue("anchorY") + v.getValue("armLength") * cos(a0) + Roam.GRIP, 0.5f)
          assertEquals(-a0, v.getValue("bodyAngle"), 1e-5f)
        }
        RoamKind.PEEK, RoamKind.LURK -> {
          val icon = m.hole!!
          assertTrue("hides behind the dock's ${icon.label}", icon.canHide)
          assertEquals(icon.cx, v.getValue("anchorX") + played(v.getValue("dx"), Roam.BLEND_PX), 0.5f)
          assertEquals(icon.cy, v.getValue("anchorY") + played(v.getValue("dy"), Roam.BLEND_PX), 0.5f)
          assertEquals(icon.cy + Roam.PEEK_END * icon.d, m.end.y, 0.5f)
          assertEquals(icon.d * 1.3f, v.getValue("holeH"), 0.01f)
        }
        RoamKind.LOOK -> assertEquals(from, m.end)
      }
      p.landed(m)
    }
  }

  @Test
  fun `targets follow the real icons when they move`() {
    val moved = home(shiftX = 37f)
    val iconsX = moved.icons.map { it.cx }.toSet()
    for (m in run(11, moved, 120)) {
      val icon = m.hole ?: continue
      assertTrue("${icon.cx} not an icon", icon.cx in iconsX)
    }
    val stranded = RoamPlanner(5).apply { start(home()) }
    // The page changed under him: the next move gets him onto something on the new page.
    val other = RoamStage(1080f, 2424f, 142f, listOf(RoamIcon("Clock", 300f, 900f, 158f, 216f, 198f)), emptyList())
    val m = stranded.next(other)
    assertTrue(m.kind == RoamKind.HOP || m.kind == RoamKind.SWING)
    assertTrue(abs(m.end.x - 300f) < 1f)
  }
}
