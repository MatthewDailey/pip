package dev.playground.pip.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class PipVoicePickerTest {
  private fun v(name: String, locale: Locale = Locale.US, quality: Int = 400, latency: Int = 200, installed: Boolean = true) =
    VoiceSpec(name, locale, quality, latency, network = name.endsWith("-network"), installed = installed)

  /** What Speech Services by Google lists on the Pixel emulator image (API 37), minus other languages. */
  private val google = listOf("iob", "iog", "iol", "iom", "sfg", "tpc", "tpd", "tpf").flatMap {
    listOf(v("en-us-x-$it-local"), v("en-us-x-$it-network"))
  } + listOf("gba", "gbb", "gbd", "rjs").map { v("en-gb-x-$it-local", Locale.UK, installed = false) } +
    v("en-US-language")

  @Test fun picksTheFirstPreferredVoiceOnDevice() {
    val pick = PipVoicePicker.pick(google, VoicePurpose.GREETING)
    assertEquals("en-us-x-iol-local", pick.voice?.name)
    assertEquals(VoiceTuning(1.1f, 0.95f), pick.tuning)
  }

  @Test fun fallsDownThePreferredList() {
    val pick = PipVoicePicker.pick(google.filterNot { it.name.contains("-iol-") }, VoicePurpose.GREETING)
    assertEquals("en-us-x-iom-local", pick.voice?.name)
  }

  @Test fun greetingMayUseANetworkVoiceButWarningsNever() {
    val networkOnly = listOf(v("en-us-x-iol-network"), v("en-us-x-sfg-local"))
    assertEquals("en-us-x-iol-network", PipVoicePicker.pick(networkOnly, VoicePurpose.GREETING).voice?.name)
    val warning = PipVoicePicker.pick(networkOnly, VoicePurpose.WARNING)
    assertEquals("en-us-x-sfg-local", warning.voice?.name)
    assertEquals(PipVoicePicker.NEUTRAL, warning.tuning)
  }

  @Test fun skipsVoicesStillDownloading() {
    val pick = PipVoicePicker.pick(listOf(v("en-gb-x-gbd-local", Locale.UK, installed = false), v("en-GB-x-other", Locale.UK)), VoicePurpose.CHAT)
    assertEquals("en-GB-x-other", pick.voice?.name)
  }

  @Test fun unknownEngineTakesTheBestEnglishVoiceAtNeutralPitch() {
    val samsung = listOf(v("ko-KR-SMTf00", Locale.KOREA, quality = 500), v("en-US-SMTf00", quality = 300), v("en-US-SMTl01", quality = 400, latency = 300))
    val pick = PipVoicePicker.pick(samsung, VoicePurpose.WARNING)
    assertEquals("en-US-SMTl01", pick.voice?.name)
    assertEquals(PipVoicePicker.NEUTRAL, pick.tuning)
  }

  @Test fun overrideWinsWhenUsable() {
    assertEquals("en-us-x-tpd-local", PipVoicePicker.pick(google, VoicePurpose.GREETING, "en-us-x-tpd-local").voice?.name)
    assertEquals("override", PipVoicePicker.pick(google, VoicePurpose.GREETING, "en-us-x-tpd-local").why)
    assertEquals("en-us-x-iol-local", PipVoicePicker.pick(google, VoicePurpose.GREETING, "no-such-voice").voice?.name)
  }

  @Test fun noVoicesKeepsTheEngineDefault() {
    assertNull(PipVoicePicker.pick(emptyList(), VoicePurpose.WARNING).voice)
  }

  @Test fun slugMatchesTheSoundtrackLookup() {
    assertEquals("hi-im-pip", PipVoicePicker.slug("Hi! I'm Pip."))
    assertEquals("gift-cards-not-on-my-watch", PipVoicePicker.slug("Gift cards? Not on my watch."))
  }
}
