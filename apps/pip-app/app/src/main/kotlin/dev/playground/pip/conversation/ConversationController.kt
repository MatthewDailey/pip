package dev.playground.pip.conversation

import dev.playground.pip.core.TalkToPipRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Speaker { YOU, PIP }

data class Turn(val id: Int, val speaker: Speaker, val text: String, val done: Boolean = true)

data class ConversationState(
  val talk: TalkState = TalkState.IDLE,
  /** Oldest first. The screen shows the last few as a calm transcript. */
  val turns: List<Turn> = emptyList(),
  /** What the mic has heard so far in this utterance. */
  val heard: String = "",
  val suggestions: List<String> = FakeConversationAgent.starters,
  /** True while the mic session is open, including the pauses between turns. */
  val micOpen: Boolean = false,
  /** Pip's reply is being read aloud. */
  val speakingAloud: Boolean = false,
  /** A live voice session ([LiveVoice]) is connecting or connected instead of the turn-based path. */
  val live: LiveState = LiveState.Idle,
  /** Speech recognition runs on the phone, so the listening disclosure can say so. */
  val onDeviceSpeech: Boolean = true,
  /** Set when the mic can't be used, so the screen offers typing instead. */
  val notice: String? = null,
)

/**
 * The conversation's state machine: idle → listening → thinking → replying → idle (or listening again).
 *
 * "Talk while Pip is visible": [openMic] keeps listening, one utterance after another, until [quietMs] pass with
 * nothing said, then closes on its own. While Pip answers the mic is closed, so he never hears himself.
 * Typed turns go through the same path.
 */
class ConversationController(
  private val agent: ConversationAgent,
  private val speechIn: SpeechInput,
  private val speechOut: SpeechOutput,
  private val presence: PipPresence,
  private val scope: CoroutineScope,
  private val locale: String = "en-US",
  private val quietMs: Long = 30_000,
  private val now: () -> Long = System::currentTimeMillis,
  private val liveVoice: LiveVoice? = null,
) {
  private val _state = MutableStateFlow(ConversationState())
  val state: StateFlow<ConversationState> = _state.asStateFlow()

  private var job: Job? = null
  private var liveJob: Job? = null

  val hasLiveVoice: Boolean get() = liveVoice != null

  /** Switches to the live voice session: the mic and speaker belong to it until [stopLive] or it ends. */
  fun startLive(request: TalkToPipRequest = TalkToPipRequest()) {
    val voice = liveVoice ?: return
    cancel()
    liveJob?.cancel()
    liveJob = scope.launch {
      voice.state.collect { s ->
        _state.update { it.copy(live = s, notice = (s as? LiveState.Ended)?.message?.takeIf { m -> m != "Call ended." }) }
        setTalk(
          when (s) {
            LiveState.Connecting, LiveState.Closing -> TalkState.THINKING
            LiveState.Live -> TalkState.LISTENING
            else -> TalkState.IDLE
          },
        )
      }
    }
    voice.start(request)
  }

  fun stopLive() { liveVoice?.stop() }
  private var nextId = 0
  private var lastWordAt = 0L

  /** Opens the mic until [quietMs] of silence. Does nothing while Pip is busy answering. */
  fun openMic() {
    val s = _state.value
    if (s.talk == TalkState.THINKING || s.talk == TalkState.SPEAKING || s.micOpen) return
    if (s.live == LiveState.Connecting || s.live == LiveState.Live) return
    if (!speechIn.isAvailable()) {
      _state.update { it.copy(notice = "I can't hear on this phone right now. Type to me instead.") }
      return
    }
    lastWordAt = now()
    job?.cancel()
    job = scope.launch { listenLoop() }
  }

  /** Stop button: ends the utterance (what was said still gets answered) and closes the mic afterwards. */
  fun stopListening() {
    if (_state.value.talk == TalkState.LISTENING) {
      _state.update { it.copy(micOpen = false) }
      speechIn.finish()
    } else {
      closeMic()
    }
  }

  /** Closes the mic now, dropping anything half-heard. A reply in progress keeps going. */
  fun closeMic() {
    val s = _state.value
    if (s.talk == TalkState.LISTENING || s.micOpen) {
      job?.cancel()
      setTalk(TalkState.IDLE)
      _state.update { it.copy(micOpen = false, heard = "") }
    }
  }

  /** The "x": stops everything, including Pip's voice. */
  fun cancel() {
    job?.cancel()
    speechOut.stop()
    setTalk(TalkState.IDLE)
    _state.update { it.copy(micOpen = false, heard = "", speakingAloud = false, turns = it.turns.map { t -> t.copy(done = true) }) }
  }

  fun send(text: String) {
    val t = text.trim()
    if (t.isEmpty()) return
    job?.cancel()
    _state.update { it.copy(micOpen = false, heard = "") }
    job = scope.launch { answer(AgentInput.Text(t), t) }
  }

  /**
   * Plays a spoken turn without the mic: the listening state shows the words arriving, then Pip answers. For demos
   * and the debug broadcast on an emulator with no microphone.
   */
  fun simulateHeard(text: String) {
    job?.cancel()
    speechOut.stop()
    job = scope.launch {
      setTalk(TalkState.LISTENING)
      _state.update { it.copy(micOpen = true, heard = "", notice = null) }
      val words = text.split(' ')
      for (i in words.indices) {
        _state.update { it.copy(heard = words.take(i + 1).joinToString(" ")) }
        delay(160)
      }
      delay(400)
      _state.update { it.copy(micOpen = false, heard = "") }
      answer(AgentInput.Spoken(text), text)
    }
  }

  fun reset() {
    cancel()
    agent.reset()
    _state.value = ConversationState()
    presence.mood(PipMood.NEUTRAL)
  }

  private suspend fun listenLoop() {
    _state.update { it.copy(micOpen = true, notice = null, onDeviceSpeech = speechIn.onDevice) }
    while (_state.value.micOpen) {
      setTalk(TalkState.LISTENING)
      _state.update { it.copy(heard = "") }
      var final: String? = null
      speechIn.transcribe(locale).collect { t ->
        lastWordAt = now()
        _state.update { it.copy(heard = t.text) }
        if (t.final) final = t.text
      }
      val said = final?.trim().orEmpty()
      if (said.isNotEmpty()) {
        val keepOpen = _state.value.micOpen
        _state.update { it.copy(heard = "") }
        answer(AgentInput.Spoken(said), said)
        lastWordAt = now()
        _state.update { it.copy(micOpen = keepOpen) }
      } else if (now() - lastWordAt >= quietMs) {
        _state.update { it.copy(micOpen = false) }
      }
    }
    setTalk(TalkState.IDLE)
    _state.update { it.copy(heard = "") }
  }

  private suspend fun answer(input: AgentInput, said: String) {
    val you = Turn(nextId++, Speaker.YOU, said)
    val pipId = nextId++
    _state.update { it.copy(turns = it.turns + you, suggestions = emptyList(), notice = null) }
    setTalk(TalkState.THINKING)
    presence.mood(PipMood.NEUTRAL)
    var text = ""
    var mood = PipMood.HAPPY
    agent.turn(input).collect { out ->
      when (out) {
        is AgentOutput.Hint -> {
          mood = out.mood
          _state.update { it.copy(suggestions = out.suggestions) }
        }
        is AgentOutput.TextDelta -> {
          if (text.isEmpty()) {
            presence.mood(mood)
            setTalk(TalkState.SPEAKING)
          }
          text += out.text
          upsertPip(pipId, text, done = false)
        }
        is AgentOutput.Done -> { text = out.fullText; upsertPip(pipId, text, done = true) }
        is AgentOutput.Failed -> {
          text = "Sorry, I lost my train of thought. Could you say that again?"
          upsertPip(pipId, text, done = true)
        }
      }
    }
    if (text.isNotEmpty()) {
      setTalk(TalkState.SPEAKING)
      _state.update { it.copy(speakingAloud = true) }
      speechOut.speak(text)
      _state.update { it.copy(speakingAloud = false) }
    }
    setTalk(TalkState.IDLE)
  }

  private fun upsertPip(id: Int, text: String, done: Boolean) = _state.update { s ->
    val turn = Turn(id, Speaker.PIP, text, done)
    val i = s.turns.indexOfFirst { it.id == id }
    s.copy(turns = if (i < 0) s.turns + turn else s.turns.toMutableList().also { it[i] = turn })
  }

  private fun setTalk(t: TalkState) {
    _state.update { it.copy(talk = t) }
    presence.talk(t)
  }
}
