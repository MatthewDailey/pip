package dev.playground.pip.ui.show

import dev.playground.pip.ui.show.GreetingGate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** The greeting plays on the first launcher open after setup is complete, then never again. */
class GreetingGateTest {
  private fun open(setupDone: Boolean, greeted: Boolean = false, granted: Boolean = true, overlay: Boolean = true) =
    GreetingGate.onOpen(setupDone, greeted, { granted }, overlay)

  @Test fun theOpenThatShowsSetupNeverGreets() {
    assertEquals(Action.APP, open(setupDone = false))
  }

  @Test fun theFirstOpenAfterSetupGreets() {
    assertEquals(Action.GREET, open(setupDone = true))
  }

  @Test fun laterOpensShowTheApp() {
    assertEquals(Action.APP, open(setupDone = true, greeted = true))
    assertEquals(Action.APP, open(setupDone = true, greeted = true, granted = false))
  }

  @Test fun aMissingGrantShowsSetupAgainAndTheGreetingWaits() {
    assertEquals(Action.SETUP, open(setupDone = true, granted = false))
    // Setup on screen again: that open doesn't greet; the first open after it completes does.
    assertEquals(Action.APP, open(setupDone = false))
    assertEquals(Action.GREET, open(setupDone = true))
  }

  @Test fun withoutTheOverlayTheAppOpensAndTheNextOpenTriesAgain() {
    assertEquals(Action.APP, open(setupDone = true, overlay = false))
    assertEquals(Action.GREET, open(setupDone = true))
  }

  @Test fun grantsAreOnlyCheckedWhenTheGreetingIsDue() {
    var checked = false
    GreetingGate.onOpen(setupDone = true, greeted = true, allGranted = { checked = true; true }, overlayReady = true)
    GreetingGate.onOpen(setupDone = false, greeted = false, allGranted = { checked = true; true }, overlayReady = true)
    assertEquals(false, checked)
  }
}
