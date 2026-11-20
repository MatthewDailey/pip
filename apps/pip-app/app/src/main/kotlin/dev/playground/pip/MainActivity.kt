package dev.playground.pip

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.playground.pip.conversation.ConversationViewModel
import dev.playground.pip.conversation.SpeechModel
import dev.playground.pip.core.EventLog
import dev.playground.pip.core.Guard
import dev.playground.pip.core.PipFlags
import dev.playground.pip.core.Source
import dev.playground.pip.sources.GuardAccessibilityService
import dev.playground.pip.twilio.PipVoice
import dev.playground.pip.ui.Capabilities
import dev.playground.pip.ui.Capability
import dev.playground.pip.ui.Grant
import dev.playground.pip.ui.PlaygroundScreen
import dev.playground.pip.ui.home.AboutScreen
import dev.playground.pip.ui.home.ConversationActions
import dev.playground.pip.ui.home.ConversationScreen
import dev.playground.pip.ui.home.SetupScreen
import dev.playground.pip.ui.show.FirstRunGreeting
import dev.playground.pip.ui.theme.Pip
import dev.playground.pip.ui.theme.PipTheme

/**
 * The app is Pip's conversation home (direction A, "Quiet home"). The first launch goes through Pip-led setup; the
 * gear opens About, where a long press on Pip reveals the developer switch and, with it on, the old playground.
 */
class MainActivity : ComponentActivity() {
  private enum class Screen { HOME, ABOUT, PLAYGROUND }

  private val capabilities = mutableStateOf<List<Capability>>(emptyList())
  /** The speech model check has answered at least once, so setup knows whether it is a step. */
  private val capabilitiesLoaded = mutableStateOf(false)
  private var screen by mutableStateOf(Screen.HOME)
  private val conversation: ConversationViewModel by viewModels()

  private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
  private val openScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
  private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    refresh()
    if (granted) conversation.controller.openMic()
  }

  /** The next resume is the person coming back to the app, so it counts as an open (see [countsAsOpen]). */
  private var reopened = false
  /** We sent the person to Settings or a permission prompt; coming back from it isn't an open. */
  private var awayForGrant = false
  /** The screen went off while the app was showing; turning it back on isn't an open. */
  private var stoppedByScreenOff = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A fresh launcher open, not a recreation, a restore from Recents, a deep link or a notification.
    if (savedInstanceState == null && isLauncherOpen(intent) && FirstRunGreeting.onLauncherOpen(this)) return finish()
    enableEdgeToEdge()
    setContent {
      val caps by capabilities
      val setupDone by PipFlags.setupDone.collectAsState()
      val exploratory by PipFlags.exploratoryState.collectAsState()
      val talk by conversation.controller.state.collectAsState()
      val mood by conversation.presence.moodState.collectAsState()
      PipTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          val pad = Modifier.background(Pip.colors.bg).safeDrawingPadding()
          when {
            !setupDone -> SetupScreen(caps, capabilitiesLoaded.value, onGrant = ::grant, onDone = { PipFlags.setSetupDone(true) }, modifier = pad)
            screen == Screen.PLAYGROUND && exploratory -> {
              BackHandler { screen = Screen.ABOUT }
              val events by EventLog.events.collectAsState()
              PlaygroundScreen(
                capabilities = caps,
                events = events,
                onGrant = ::grant,
                onTest = { Guard.report(Source.MANUAL, "Manual test", it) },
                onClear = EventLog::clear,
                modifier = pad,
              )
            }
            screen == Screen.ABOUT -> {
              BackHandler { screen = Screen.HOME }
              AboutScreen(
                capabilities = caps,
                version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "",
                exploratory = exploratory,
                onExploratory = PipFlags::setExploratory,
                onGrant = ::grant,
                onSetupAgain = { PipFlags.setSetupDone(false); screen = Screen.HOME },
                onPlayground = { screen = Screen.PLAYGROUND },
                onBack = { screen = Screen.HOME },
                modifier = pad,
              )
            }
            else -> {
              val c = conversation.controller
              ConversationScreen(
                state = talk,
                mood = mood,
                protectedFully = caps.firstOrNull { it.title == "Accessibility service" }?.enabled ?: true,
                actions = ConversationActions(
                  talk = ::talk,
                  stop = c::stopListening,
                  close = c::cancel,
                  send = c::send,
                  openSettings = { c.closeMic(); screen = Screen.ABOUT },
                  fixSetup = { PipFlags.setSetupDone(false) },
                  live = if (exploratory) ::toggleLive else null,
                ),
                modifier = pad,
              )
            }
          }
        }
      }
    }
    dialFrom(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!isLauncherOpen(intent)) reopened = false
    dialFrom(intent)
  }

  /**
   * Back in front after being stopped: a launcher tap on the running app, or (indistinguishably, without asking the
   * launcher) a switch back from Recents. Not counted: returning from a grant, the screen coming back on, or an
   * intent other than the launcher's ([onNewIntent], which arrives before [onResume]).
   */
  override fun onRestart() {
    super.onRestart()
    reopened = !awayForGrant && !stoppedByScreenOff
  }

  override fun onStop() {
    super.onStop()
    stoppedByScreenOff = !getSystemService(PowerManager::class.java).isInteractive
  }

  private fun isLauncherOpen(i: Intent?) = i != null && i.action == Intent.ACTION_MAIN &&
    i.hasCategory(Intent.CATEGORY_LAUNCHER) && i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

  /** Automated test calls: `adb shell am start -n dev.playground.pip/.MainActivity --es pip_dial +15551234567`. */
  private fun dialFrom(intent: Intent?) {
    val number = intent?.getStringExtra(EXTRA_DIAL) ?: return
    intent.removeExtra(EXTRA_DIAL)
    PipVoice.dial(this, number)
  }

  override fun onResume() {
    super.onResume()
    val countsAsOpen = reopened
    reopened = false
    awayForGrant = false
    stoppedByScreenOff = false
    if (countsAsOpen && FirstRunGreeting.onLauncherOpen(this)) return finish()
    refresh()
    // Only the shell (which holds DUMP) can drive the conversation; see [talkReceiver].
    registerReceiver(talkReceiver, IntentFilter(TALK_ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
    // Talk while Pip is visible: the mic opens on its own when the home is showing and closes after 30 quiet seconds.
    if (PipFlags.setupDone.value && screen == Screen.HOME && micGranted() && !autoListenOff) conversation.controller.openMic()
  }

  override fun onPause() {
    super.onPause()
    runCatching { unregisterReceiver(talkReceiver) }
    conversation.controller.closeMic()
  }

  private fun talk() {
    if (micGranted()) conversation.controller.openMic() else askMic.launch(Manifest.permission.RECORD_AUDIO)
  }

  private fun toggleLive() {
    val c = conversation.controller
    val s = c.state.value.live
    if (s == dev.playground.pip.conversation.LiveState.Connecting || s == dev.playground.pip.conversation.LiveState.Live) c.stopLive()
    else if (micGranted()) c.startLive() else askMic.launch(Manifest.permission.RECORD_AUDIO)
  }

  private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** Set by the TALK broadcast's `auto false`, so screenshots of the quiet home aren't interrupted by the mic. */
  private var autoListenOff = false

  /**
   * Debug driver for demos and proof on an emulator without a microphone:
   * ```
   * adb shell am broadcast -a dev.playground.pip.TALK --es heard "is this text from my bank real"  # as if spoken
   * adb shell am broadcast -a dev.playground.pip.TALK --es type "someone wants gift cards"          # as if typed
   * adb shell am broadcast -a dev.playground.pip.TALK --es do listen|stop|close|reset
   * adb shell am broadcast -a dev.playground.pip.TALK --ez auto false   # don't open the mic on resume
   * ```
   */
  private val talkReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val c = conversation.controller
      if (intent.hasExtra("auto")) autoListenOff = !intent.getBooleanExtra("auto", true)
      intent.getStringExtra("heard")?.let(c::simulateHeard)
      intent.getStringExtra("type")?.let(c::send)
      when (intent.getStringExtra("do")) {
        "listen" -> c.openMic()
        "stop" -> c.stopListening()
        "close" -> c.cancel()
        "reset" -> c.reset()
      }
    }
  }

  private fun refresh() {
    capabilities.value = Capabilities.all(this)
    GuardAccessibilityService.instance?.registerCallState()
    SpeechModel.check(this) {
      capabilities.value = Capabilities.all(this)
      capabilitiesLoaded.value = true
    }
  }

  private fun grant(c: Capability) {
    awayForGrant = true
    when (val g = c.grant) {
      is Grant.Permissions -> askPermissions.launch(g.names.toTypedArray())
      Grant.SpeechModel -> SpeechModel.download(this) { refresh() }
      else -> Capabilities.intentFor(this, g)?.let { openScreen.launch(it) }
    }
  }

  companion object {
    const val EXTRA_DIAL = "pip_dial"
    const val TALK_ACTION = "dev.playground.pip.TALK"
  }
}
