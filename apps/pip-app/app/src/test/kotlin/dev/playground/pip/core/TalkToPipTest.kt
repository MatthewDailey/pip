package dev.playground.pip.core

import android.app.Application
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkToPipTest {
  private val app: Application = RuntimeEnvironment.getApplication()

  @Test fun requestSurvivesTheIntent() {
    val request = TalkToPipRequest(context = "A text said her bank account is locked.", opening = "Ask if she clicked it.", callId = "call-7", autoStart = true)
    val intent = TalkToPip.intent(app, request)
    assertEquals(request, TalkToPip.requestFrom(intent))
    // Launched from a non-activity context, so it needs its own task.
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test fun noExtrasMeansAnEmptyRequest() {
    assertEquals(TalkToPipRequest(), TalkToPip.requestFrom(Intent()))
    assertEquals(TalkToPipRequest(), TalkToPip.requestFrom(null))
  }

  @Test fun sessionBodyCarriesOnlyWhatIsSet() {
    val body = JSONObject(TalkToPipRequest(context = "  ", opening = "Say hi.").sessionBody("v=0"))
    assertEquals("v=0", body.getString("sdp"))
    assertEquals("Say hi.", body.getString("opening"))
    assertFalse(body.has("context"))
    assertFalse(body.has("callId"))
  }

  @Test fun sessionBodyCapsLongContext() {
    val body = JSONObject(TalkToPipRequest(context = "x".repeat(10_000)).sessionBody("v=0"))
    assertEquals(TalkToPipRequest.MAX_CONTEXT, body.getString("context").length)
  }
}
