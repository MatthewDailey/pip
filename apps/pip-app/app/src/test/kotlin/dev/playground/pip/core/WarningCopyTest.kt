package dev.playground.pip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WarningCopyTest {
  @Test fun giftCardReadoutIsPlainLanguage() {
    val text = WarningCopy.forCall("gift_card_readout", "Tripwire gift_card_readout: Reading gift card numbers to a caller")
    assertEquals(
      "You're reading gift card numbers to a caller. Real agencies never ask for gift cards.",
      text,
    )
    assertFalse(text.contains("Tripwire"))
    assertFalse(text.contains("gift_card_readout"))
  }

  @Test fun unknownCallerGiftCardsIsPlainLanguage() {
    val text = WarningCopy.forCall("gift_card_unknown_caller", "Tripwire gift_card_unknown_caller: Gift cards mentioned")
    assertEquals("An unknown caller is asking about gift cards.", text)
  }

  @Test fun tripwirePrefixInReasonIsMappedWithoutARule() {
    val text = WarningCopy.forCall(null, "Tripwire gift_card_readout: Reading gift card numbers to a caller (digits)")
    assertEquals(
      "You're reading gift card numbers to a caller. Real agencies never ask for gift cards.",
      text,
    )
  }

  @Test fun internalJevScoreReasonIsGeneric() {
    val text = WarningCopy.forCall(null, "risk 0.85 ≥ takeover 0.8")
    assertFalse(text.contains("0.85"))
    assertFalse(text.contains("takeover"))
    assertFalse(text.contains("Tripwire"))
  }

  @Test fun screenFraudFallsBackToPlainLine() {
    val text = WarningCopy.forScreen("phishing_login", null, "phishing_login")
    assertEquals("Stop! This page isn't who it says it is. Don't type your password or card here.", text)
    assertFalse(text.contains("phishing_login"))
  }

  @Test fun confidenceLineRoundsPercent() {
    assertEquals("Pip is 97% sure this is a scam", WarningCopy.confidenceLine(0.9745))
    assertEquals("Pip is 95% sure this is a scam", WarningCopy.confidenceLine(0.95))
    assertEquals("Pip is 100% sure this is a scam", WarningCopy.confidenceLine(1.0))
    assertEquals("Pip is 0% sure this is a scam", WarningCopy.confidenceLine(0.0))
    assertEquals(null, WarningCopy.confidenceLine(null))
    assertEquals(null, WarningCopy.confidenceLine(-1.0))
    assertEquals(null, WarningCopy.confidenceLine(1.01))
  }

  @Test fun screenSuggestedLineIsPreferred() {
    val text = WarningCopy.forScreen("phishing_login", "Don't type your password on this fake page.", "phishing_login")
    assertEquals("Don't type your password on this fake page.", text)
  }
}
