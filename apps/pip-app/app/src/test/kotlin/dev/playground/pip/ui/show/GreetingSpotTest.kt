package dev.playground.pip.ui.show

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the first-run greeting moves: Pip's and the Phone's launcher icons when they're on screen, else stand-ins. */
class GreetingSpotTest {
  @Test
  fun `with Pip's and the Phone's icons on the home screen the story binds to them`() {
    assertTrue(GreetingSpot.overrides(listOf("Play Store", "Pip", "Phone", "Gmail"), 1080f, 2424f).isEmpty())
  }

  @Test
  fun `without it he rises from just below the bottom edge, centred`() {
    val spot = GreetingSpot.overrides(listOf("Play Store", "Gmail", "Pipeline", "Phone"), 1080f, 2424f)
    assertEquals(540f, spot.getValue("pipX"))
    assertTrue(spot.getValue("pipY") > 2424f)
    assertEquals(1080f, spot.getValue("pipLabelW"))
    assertFalse("phoneX" in spot)
  }

  @Test
  fun `without a Phone icon he eyes the dock's first slot`() {
    val spot = GreetingSpot.overrides(listOf("Pip", "Phone Book", "Gmail"), 1080f, 2424f)
    assertEquals(158f, spot.getValue("phoneX"), 0.01f)
    assertEquals(2235f, spot.getValue("phoneY"), 0.01f)
    assertFalse("pipX" in spot)
  }
}
