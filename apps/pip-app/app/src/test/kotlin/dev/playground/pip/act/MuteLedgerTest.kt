package dev.playground.pip.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteLedgerTest {
  @Test fun clearsPipsMuteWhenTheCallEnds() {
    val ledger = MuteLedger().apply { muted(CallMuter.AUDIO) }
    assertTrue(ledger.callEnded(micMuted = true))
    assertNull(ledger.owner)
  }

  @Test fun leavesTheMicAloneWhenPipDidNotMute() {
    assertFalse(MuteLedger().callEnded(micMuted = true))
  }

  @Test fun nothingToClearWhenTelecomAlreadyUnmuted() {
    val ledger = MuteLedger().apply { muted(CallMuter.AUDIO) }
    assertFalse(ledger.callEnded(micMuted = false))
  }

  @Test fun anUnmuteOutsidePipIsReportedOnce() {
    val ledger = MuteLedger().apply { muted(CallMuter.AUDIO) }
    assertFalse(ledger.micChanged(micMuted = true))
    assertTrue(ledger.micChanged(micMuted = false))
    assertFalse(ledger.micChanged(micMuted = false))
  }

  @Test fun pipsOwnUndoIsNotADrop() {
    val ledger = MuteLedger().apply { muted(CallMuter.AUDIO) }
    ledger.unmuted()
    assertFalse(ledger.micChanged(micMuted = false))
    assertEquals(false, ledger.callEnded(micMuted = false))
  }
}
