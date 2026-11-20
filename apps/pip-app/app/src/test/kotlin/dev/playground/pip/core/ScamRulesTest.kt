package dev.playground.pip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScamRulesTest {
  private val rules = ScamRules.parse(File("src/main/assets/scam_rules.json").readText())

  @Test fun giftCardAndSecrecyAlert() {
    val a = rules.assess("This is the IRS. Go buy Google Play cards right now and don't tell your family.")
    assertTrue(a.alert)
    assertEquals(setOf("gift-card", "secrecy", "urgency", "gov-impersonation"), a.hits.map { it.id }.toSet())
  }

  @Test fun codeRequestAloneAlerts() {
    assertTrue(rules.assess("Can you read me the code we just sent to your phone?").alert)
  }

  @Test fun ordinaryMessageIsClean() {
    val a = rules.assess("Hi Mom, dinner at 6 on Sunday? I'll bring dessert.")
    assertEquals(0, a.score)
    assertFalse(a.alert)
  }

  @Test fun callLinesAddUpAcrossTheCall() {
    val lines = listOf(
      "Hi, this is Sarah from the fraud department at your bank.",
      "You need to move your savings to a safe account by wire transfer today.",
      "Please don't tell anyone at the branch.",
    )
    val each = lines.map { rules.assess(it) }
    assertTrue(each.none { it.alert })
    assertTrue(rules.combine(each.flatMap { it.hits }).alert)
  }

  @Test fun eachRuleCountsOnce() {
    assertEquals(rules.assess("gift card").score, rules.assess("gift card gift card gift card").score)
  }
}
