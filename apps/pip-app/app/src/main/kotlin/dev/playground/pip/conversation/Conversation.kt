/**
 * The seams behind Pip's conversation home: the agent that answers, the speech that goes in and out, and Pip's
 * presence (mood and talking state for the rig). Nothing here imports `android.*`, so the controller and the fake
 * agent run in plain JVM tests.
 *
 * These mirror layer 7 (voice and agent) and the presentation port of the interfaces ref and the draft contracts PR
 * (`dev.playground.pip.contracts`, not merged yet). When it lands, each type here becomes a typealias or is
 * replaced one for one:
 *
 * | Here                         | Contracts PR                                                       |
 * | ---------------------------- | ------------------------------------------------------------------ |
 * | [ConversationAgent]          | `voice.PipAgent` (`turn`, `reset`; tools come with the real agent)  |
 * | [AgentInput], [AgentOutput]  | `voice.AgentInput`, `voice.AgentOutput` (same cases)               |
 * | [AgentOutput.Hint]           | local addition: mood and suggested replies; becomes a tool call    |
 * | [Transcript]                 | `voice.Transcript` (no confidence or timestamp yet)                |
 * | [SpeechInput]                | `voice.SpeechToText` with `AudioInput.Microphone(APP_FOREGROUND)`   |
 * | [SpeechOutput], [AudioUsage] | `voice.TextToSpeech`, `voice.AudioUsage`                           |
 * | [TalkState]                  | `presentation.TalkState` (`ListenState` minus the blocked states)  |
 * | [PipMood]                    | `presentation.PipMood`                                             |
 * | [PipPresence]                | `presentation.PipStage` receiving `PipCue.Mood` and `PipCue.Talk`   |
 * | [LiveVoice], [LiveState]     | `voice.RealtimeVoice` + `RealtimeSession` (open, close, state only) |
 */
package dev.playground.pip.conversation

import kotlinx.coroutines.flow.Flow

sealed interface AgentInput {
  data class Text(val text: String) : AgentInput
  data class Spoken(val transcript: String) : AgentInput
}

sealed interface AgentOutput {
  /** Part of the reply, streamed as it is written. */
  data class TextDelta(val text: String) : AgentOutput
  /** How Pip should look while he says this, and short replies the person can tap next. */
  data class Hint(val mood: PipMood, val suggestions: List<String> = emptyList()) : AgentOutput
  data class Done(val fullText: String) : AgentOutput
  data class Failed(val message: String) : AgentOutput
}

/** One conversation with Pip: turns in, replies streamed out. Cold: collecting a turn runs it. */
interface ConversationAgent {
  fun turn(input: AgentInput): Flow<AgentOutput>
  /** Forgets the conversation so far. */
  fun reset()
}

data class Transcript(val text: String, val final: Boolean, val sequence: Int)

/** Speech to text from the phone's mic while the app is in the foreground. */
interface SpeechInput {
  /** Recognition stays on the phone. */
  val onDevice: Boolean
  fun isAvailable(): Boolean
  /**
   * Cold: collecting starts one utterance, cancelling stops it. Emits partials, then at most one final transcript,
   * then completes. Completes with no final when the person said nothing.
   */
  fun transcribe(locale: String): Flow<Transcript>
  /** Ends the utterance now; the final transcript still arrives. */
  fun finish()
}

/** Which audio stream Pip's voice uses, which decides whether a call ducks or mutes it. */
enum class AudioUsage { ASSISTANT, ALARM, NOTIFICATION, VOICE_COMMUNICATION }

interface SpeechOutput {
  /** Speaks [text] and returns when it finished: true, or false when nothing could be said (no TTS engine). */
  suspend fun speak(text: String, usage: AudioUsage = AudioUsage.ASSISTANT): Boolean
  fun stop()
}

enum class TalkState { IDLE, LISTENING, THINKING, SPEAKING }

enum class PipMood { NEUTRAL, HAPPY, SAD, ANGRY, SURPRISED, WARY, ALARMED, CONCERNED }

/** What the rig shows: Pip's mood and whether he is listening, thinking or talking. */
interface PipPresence {
  fun mood(mood: PipMood)
  fun talk(state: TalkState)
}

/**
 * A speech-to-speech session with Pip (GPT-Live style): audio both ways, no turns or text through this app. The
 * turn-based path ([ConversationAgent] + [SpeechInput] + [SpeechOutput]) and this one are alternatives behind the
 * same Talk button; `PipLiveVoice` (the GPT-Live call from the Talk to Pip screen) is the first implementation.
 */
interface LiveVoice {
  val state: kotlinx.coroutines.flow.StateFlow<LiveState>
  /** [request] is what Pip should know, as for [dev.playground.pip.core.TalkToPip.launch]. */
  fun start(request: dev.playground.pip.core.TalkToPipRequest = dev.playground.pip.core.TalkToPipRequest())
  fun stop()
}

sealed interface LiveState {
  data object Idle : LiveState
  data object Connecting : LiveState
  data object Live : LiveState
  data object Closing : LiveState
  data class Ended(val message: String) : LiveState
}
