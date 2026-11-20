package dev.playground.pip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.playground.pip.core.PipLive
import dev.playground.pip.core.PipLive.State
import dev.playground.pip.core.TalkToPip
import dev.playground.pip.core.TalkToPipRequest
import dev.playground.pip.ui.theme.PipTheme

/**
 * Talk to Pip: one button that starts or ends a GPT-Live voice call with Pip (see [PipLive]).
 * Open it with [TalkToPip.launch] and a [TalkToPipRequest] to give Pip context or start the call right away.
 * Shows live captions for both sides, which also helps on an emulator with no speaker.
 *
 *   adb shell am start -n dev.playground.pip/.TalkToPipActivity [--es context "..."] [--es opening "..."]
 *     [--es callId <id>] [--ez autoStart true]
 */
class TalkToPipActivity : ComponentActivity() {
  private val state = mutableStateOf<State>(State.Idle)
  /** Captions, one entry per turn: (is Pip, text so far). */
  private val captions = mutableStateListOf<Pair<Boolean, String>>()
  /** One [PipLive] per call; updates from a call that's been replaced are ignored. */
  private var live: PipLive? = null

  private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) startCall() else state.value = State.Ended("Pip needs the microphone to talk.")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val current by state
      PipTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          // Title pinned at the top, the button pinned at the bottom, captions filling in under the title and
          // scrolling to keep the newest line in view, so nothing jumps around as Pip talks.
          Column(
            Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            val talking = current is State.Connecting || current is State.Connected
            Row(verticalAlignment = Alignment.CenterVertically) {
              Image(painterResource(R.drawable.pip), contentDescription = "Pip", modifier = Modifier.size(56.dp))
              Spacer(Modifier.width(12.dp))
              Text("Talking to Pip", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(16.dp))
            val scroll = rememberScrollState()
            LaunchedEffect(captions.size, captions.lastOrNull()?.second?.length) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
              statusText(current)?.let {
                Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
              }
              for ((pip, text) in captions) {
                Text(
                  "${if (pip) "Pip" else "You"}: ${text.trim()}",
                  style = MaterialTheme.typography.bodyLarge,
                  color = if (pip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(vertical = 4.dp),
                )
              }
            }
            Spacer(Modifier.height(16.dp))
            Button(
              onClick = { if (talking) endCall() else onCallPressed() },
              enabled = current !is State.Closing,
              modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
              Text(if (talking) "End call" else "Call Pip", style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      }
    }
    if (savedInstanceState == null) begin(intent) else request = TalkToPip.requestFrom(intent)
  }

  override fun onDestroy() {
    live?.stop()
    super.onDestroy()
  }

  private fun onCallPressed() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCall()
    else askMic.launch(Manifest.permission.RECORD_AUDIO)
  }

  private var request = TalkToPipRequest()

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    live?.stop()
    live = null
    state.value = State.Idle
    begin(intent)
  }

  /** Reads the request this screen was opened with; starts the call now if it asks to. */
  private fun begin(intent: Intent?) {
    request = TalkToPip.requestFrom(intent)
    if (request.autoStart) onCallPressed()
  }

  /** End call closes the screen: Pip says goodbye to GPT-Live ([PipLive.stop]) and the screen goes away. */
  private fun endCall() {
    live?.stop()
    finish()
  }

  private fun startCall() {
    captions.clear()
    lateinit var call: PipLive
    call = PipLive(
      this,
      onState = { if (live === call) state.value = it },
      onTranscript = { pip, delta -> if (live === call) caption(pip, delta) },
    )
    live = call
    call.start(request)
  }

  private fun caption(pip: Boolean, delta: String) {
    val last = captions.lastOrNull()
    if (last != null && last.first == pip) captions[captions.lastIndex] = pip to last.second + delta
    else captions += pip to delta
  }

  /** The line under the title; none once connected, when the captions take over. */
  private fun statusText(s: State): String? = when (s) {
    State.Idle -> "Press the button and Pip will answer."
    State.Connecting -> "Calling Pip…"
    is State.Connected -> null
    State.Closing -> "Saying goodbye…"
    is State.Ended -> s.message
  }
}
