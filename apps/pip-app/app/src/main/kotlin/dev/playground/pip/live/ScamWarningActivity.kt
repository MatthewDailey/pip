package dev.playground.pip.live

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import dev.playground.pip.core.ScreenGuard
import dev.playground.pip.core.TalkToPip
import dev.playground.pip.core.TalkToPipRequest
import dev.playground.pip.core.WarningCopy
import dev.playground.pip.sources.GuardAccessibilityService

/**
 * The full-screen warning Pip shows during a call, after a short ping (no notification). Amber for an alert
 * ("this might be a scam"), red for a takeover (Mom is already muted, and the caller's voice is turned down as
 * far as her phone allows). Both offer the same three actions:
 *   Hang up · Hang up and talk to Pip ([talkToPip]: Pip already knows what happened) · Keep talking (unmutes her
 *   and brings the caller's voice back)
 */
class ScamWarningActivity : ComponentActivity() {
  companion object {
    const val EXTRA_LEVEL = "level"
    const val EXTRA_REASON = "reason"
    const val EXTRA_TRIPWIRE = "tripwire"
    const val EXTRA_TYPE = "scam_type"
    const val EXTRA_RISK = "risk"
    /** pip-call's call id, which is also Pip4's; Talk to Pip passes it so the server adds the transcript. */
    const val EXTRA_CALL_ID = "call_id"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    render()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    render()
  }

  override fun onDestroy() {
    GuardAccessibilityService.instance?.releasePipPark()
    super.onDestroy()
  }

  private fun render() {
    val takeover = intent.getStringExtra(EXTRA_LEVEL) == PipCallVerdicts.Level.TAKEOVER.name
    val reason = WarningCopy.forCall(intent.getStringExtra(EXTRA_TRIPWIRE), intent.getStringExtra(EXTRA_REASON))
    val confidence = WarningCopy.confidenceLine(intent.getDoubleExtra(EXTRA_RISK, -1.0))
    // A leftover screen-fraud banner would cover the title and Pip; drop it for the call warning.
    ScreenGuard.dismissBanner()
    GuardAccessibilityService.instance?.parkPipInMiddle()
    setContent {
      Warning(
        takeover = takeover,
        reason = reason,
        confidence = confidence,
        onHangUp = ::hangUp,
        onTalkToPip = { talkToPip(takeover, reason) },
        onDismiss = {
          // Keep talking: she accepts this issue, so Pip won't warn about it again on this call.
          PipCallVerdicts.accept(
            intent.getStringExtra(EXTRA_CALL_ID).orEmpty(),
            PipCallVerdicts.issueOf(intent.getStringExtra(EXTRA_TRIPWIRE), intent.getStringExtra(EXTRA_TYPE)),
          )
          if (takeover) {
            PipCallVerdicts.unmute()
            Guard.note(Source.TRANSCRIPT, "Mom unmuted the call", "she chose to trust the caller")
          }
          finish()
        },
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun hangUp() {
    if (!endCall()) runCatching { getSystemService(TelecomManager::class.java).showInCallScreen(false) }
    finish()
  }

  /**
   * Ends the call; true when it did. TelecomManager.endCall needs ANSWER_PHONE_CALLS; without it (or if the dialer
   * refuses), Pip taps the phone app's End call button through his accessibility service.
   */
  @SuppressLint("MissingPermission")
  private fun endCall(): Boolean {
    val viaTelecom = runCatching { getSystemService(TelecomManager::class.java).endCall() }.getOrDefault(false)
    val ended = viaTelecom || GuardAccessibilityService.instance?.pressEndCall() == true
    Guard.note(
      Source.TRANSCRIPT, "Mom chose to hang up",
      when {
        viaTelecom -> "call ended"
        ended -> "call ended (Pip pressed End call)"
        else -> "couldn't end it from Pip; opened the call screen"
      },
    )
    return ended
  }

  /**
   * Hangs up and opens Talk to Pip about this call. It has to end the call first: Pip's mute silences the whole
   * mic, and the call holds the audio, so GPT-Live couldn't hear her mid-call. Pip gets the server's verdict
   * here as context, and the call id, from which the server adds the call's transcript and caller details.
   */
  private fun talkToPip(takeover: Boolean, reason: String?) {
    if (!endCall()) {
      // Never start Pip over a live call: send her to the call screen to hang up there.
      runCatching { getSystemService(TelecomManager::class.java).showInCallScreen(false) }
      return
    }
    PipCallVerdicts.unmute()
    val context = buildString {
      append(if (takeover) "Pip just took over a phone call he judged to be a scam: he muted her, and she has now hung up."
      else "Pip just warned her that a phone call might be a scam, and she has now hung up.")
      intent.getStringExtra(EXTRA_TYPE)?.takeIf { it.isNotBlank() && it != "none" }?.let { append(" Scam type: ${it.replace('_', ' ')}.") }
      reason?.let { append(" What Pip told her: $it") }
      intent.getStringExtra(EXTRA_REASON)?.takeIf { it.isNotBlank() && it != reason }?.let { append(" The scam check's reason: $it") }
    }
    Guard.note(Source.TRANSCRIPT, "Mom chose to talk to Pip", context)
    TalkToPip.launch(
      this,
      TalkToPipRequest(
        context = context,
        opening = "Reassure her that hanging up was right, explain in a sentence or two why this was a scam, " +
          "and suggest one safe next step. Then ask how she's feeling.",
        callId = intent.getStringExtra(EXTRA_CALL_ID),
        autoStart = true,
      ),
    )
    finish()
  }

}

@Composable
private fun Warning(
  takeover: Boolean,
  reason: String?,
  confidence: String?,
  onHangUp: () -> Unit,
  onTalkToPip: () -> Unit,
  onDismiss: () -> Unit,
) {
  val bg = if (takeover) Color(0xFFB71C1C) else Color(0xFFF9A825)
  val fg = if (takeover) Color.White else Color.Black
  Column(
    Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(24.dp).testTag(if (takeover) "pip-warning-takeover" else "pip-warning-alert"),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Small inset only: Pip sits in the empty middle, not behind the status bar / call chip.
    Spacer(Modifier.height(8.dp))
    Text(if (takeover) "Pip muted you" else "This might be a scam", color = fg, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Text(
      if (takeover) "This call is very likely a scam. The caller can't hear you right now, and Pip has turned them down. Don't send money, gift cards or codes."
      else "Pip heard something worrying. Real banks and government offices never ask for gift cards, codes or secrecy.",
      color = fg,
      fontSize = 20.sp,
    )
    reason?.let { Text(it, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    confidence?.let { Text(it, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
    Spacer(Modifier.weight(1f))
    Button(onHangUp, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))) {
      Text("Hang up", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    OutlinedButton(onTalkToPip, Modifier.fillMaxWidth().height(56.dp).testTag("pip-warning-talk")) {
      Text("Hang up and talk to Pip", color = fg, fontSize = 18.sp)
    }
    TextButton(onDismiss, Modifier.fillMaxWidth()) {
      Text("Keep talking", color = fg, fontSize = 18.sp)
    }
  }
}
