package dev.playground.pip.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The composition root for the conversation: one place that picks each implementation. */
object ConversationWiring {
  /**
   * The real backend plugs in here. Return an agent that sends each turn to our server (the contracts PR's
   * `PipApi`, or a realtime session for cloud voice once the person consents) and streams its reply back as
   * [AgentOutput.TextDelta]s. Until then Pip answers with canned replies.
   */
  fun agent(): ConversationAgent = FakeConversationAgent()

  fun speechInput(app: Application): SpeechInput = AndroidSpeechInput(app)

  fun speechOutput(app: Application): SpeechOutput = AndroidSpeechOutput(app)

  /** The speech-to-speech alternative: #15's GPT-Live call through jev-playground. Offered while exploratory is on. */
  fun liveVoice(app: Application): LiveVoice = PipLiveVoice(app)
}

/** Pip's presence as state the screen applies to the Rive rig, so the controller never touches a view. */
class StatePresence : PipPresence {
  private val _mood = MutableStateFlow(PipMood.NEUTRAL)
  private val _talk = MutableStateFlow(TalkState.IDLE)
  val moodState: StateFlow<PipMood> = _mood.asStateFlow()
  val talkState: StateFlow<TalkState> = _talk.asStateFlow()
  override fun mood(mood: PipMood) { _mood.value = mood }
  override fun talk(state: TalkState) { _talk.value = state }
}

class ConversationViewModel(app: Application) : AndroidViewModel(app) {
  val presence = StatePresence()
  private val speechOut = ConversationWiring.speechOutput(app)
  val controller = ConversationController(
    agent = ConversationWiring.agent(),
    speechIn = ConversationWiring.speechInput(app),
    speechOut = speechOut,
    presence = presence,
    scope = viewModelScope,
    liveVoice = ConversationWiring.liveVoice(app),
  )

  override fun onCleared() {
    controller.cancel()
    controller.stopLive()
    (speechOut as? AndroidSpeechOutput)?.shutdown()
  }
}
