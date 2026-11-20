package dev.playground.pip.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.pip.twilio.PipVoice

private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "⌫")

/** "Call through Pip": a dial pad, then the in-call controls with what Pip hears and thinks. */
@Composable
fun PipCallCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val call by PipVoice.state.collectAsState()
  var number by rememberSaveable { mutableStateOf("") }
  val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.RECORD_AUDIO] == true) PipVoice.dial(context, number)
  }
  fun dial() {
    val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
      .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (needed.isEmpty()) PipVoice.dial(context, number) else askMic.launch(needed.toTypedArray())
  }

  val tint = if (call.takenOver) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.surfaceVariant
  Card(colors = CardDefaults.cardColors(containerColor = tint), modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Call through Pip", style = MaterialTheme.typography.titleMedium)
      if (call.active || call.phase == PipVoice.Phase.ENDED || call.phase == PipVoice.Phase.FAILED) {
        InCall(call, onHangUp = PipVoice::hangUp, onMute = PipVoice::toggleMute, onSpeaker = { PipVoice.toggleSpeaker(context) }, onDone = PipVoice::reset)
      } else {
        Text(
          number.ifEmpty { "Enter a number" },
          fontSize = 28.sp,
          color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
          modifier = Modifier.testTag("pip-dial-number"),
        )
        keys.chunked(3).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { k ->
              FilledTonalButton(
                onClick = { number = if (k == "⌫") number.dropLast(1) else (number + k).take(16) },
                modifier = Modifier.weight(1f).height(52.dp),
              ) { Text(k, fontSize = 22.sp) }
            }
          }
        }
        Button(
          onClick = ::dial,
          enabled = number.count { it.isDigit() } >= 3,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("pip-dial-call"),
        ) { Text("Call", fontSize = 18.sp) }
        if (!PipVoice.configured) {
          Text("This build has no Pip app secret, so calls can't start. Add pip.appSecret to local.properties.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB71C1C))
        }
      }
    }
  }
}

@Composable
private fun InCall(call: PipVoice.CallState, onHangUp: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit, onDone: () -> Unit) {
  val who = call.contactName ?: call.number
  val status = when (call.phase) {
    PipVoice.Phase.CONNECTING -> "Connecting…"
    PipVoice.Phase.RINGING -> "Ringing…"
    PipVoice.Phase.CONNECTED -> "Connected"
    PipVoice.Phase.RECONNECTING -> "Reconnecting…"
    PipVoice.Phase.ENDED -> "Call ended"
    PipVoice.Phase.FAILED -> call.error ?: "Call failed"
    PipVoice.Phase.IDLE -> ""
  }
  Text(who, fontSize = 24.sp, fontWeight = FontWeight.Medium)
  Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pip-call-status"))

  if (call.takenOver) {
    Text(
      "Pip paused this call. It sounds like a scam: don't send money, gift cards or codes.",
      fontWeight = FontWeight.Bold,
      color = Color(0xFFB71C1C),
      modifier = Modifier.testTag("pip-call-warning"),
    )
  } else if (call.level != null) {
    Text("Pip: ${call.level}${call.reason?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, maxLines = 3)
  }
  call.lines.takeLast(4).forEach { l ->
    Text("${if (l.speaker == "caller") "Them" else "You"}: ${l.text}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
  }

  if (call.active) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton(onMute, Modifier.weight(1f)) { Text(if (call.muted) "Unmute" else "Mute") }
      OutlinedButton(onSpeaker, Modifier.weight(1f)) { Text(if (call.speaker) "Earpiece" else "Speaker") }
    }
    Button(
      onClick = onHangUp,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("pip-call-hangup"),
    ) { Text("Hang up", fontSize = 18.sp) }
  } else {
    TextButton(onDone) { Text("Back to dial pad") }
  }
}
