package dev.playground.pip.conversation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationTest {

  private class ScriptedSpeech(private val utterances: ArrayDeque<String?>) : SpeechInput {
    var finished = 0
    override val onDevice = true
    override fun isAvailable() = true
    override fun transcribe(locale: String): Flow<Transcript> = flow {
      val said = utterances.removeFirstOrNull() ?: return@flow
      emit(Transcript(said.substringBefore(' '), final = false, sequence = 0))
      emit(Transcript(said, final = true, sequence = 1))
    }
    override fun finish() { finished++ }
  }

  private class RecordingVoice : SpeechOutput {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String, usage: AudioUsage): Boolean { spoken += text; return true }
    override fun stop() {}
  }

  private class RecordingPresence : PipPresence {
    val talk = mutableListOf<TalkState>()
    val moods = mutableListOf<PipMood>()
    override fun mood(mood: PipMood) { moods += mood }
    override fun talk(state: TalkState) { if (talk.lastOrNull() != state) talk += state }
  }

  private fun TestScope.controller(speech: SpeechInput, voice: RecordingVoice, presence: RecordingPresence) =
    ConversationController(FakeConversationAgent(thinkMs = 10, wordMs = 1), speech, voice, presence, this, quietMs = 0, now = { testScheduler.currentTime })

  @Test fun `fake agent answers on topic and streams the whole reply`() = runTest {
    val out = FakeConversationAgent(thinkMs = 0, wordMs = 0).turn(AgentInput.Text("Someone wants iTunes gift cards")).toList()
    val done = out.last() as AgentOutput.Done
    assertTrue(done.fullText.contains("gift cards"))
    assertEquals(done.fullText, out.filterIsInstance<AgentOutput.TextDelta>().joinToString("") { it.text })
    assertEquals(PipMood.ANGRY, out.filterIsInstance<AgentOutput.Hint>().single().mood)
  }

  @Test fun `every starter and suggestion leads to an on-topic reply`() {
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(FakeConversationAgent.starters)
    while (queue.isNotEmpty()) {
      val said = queue.removeFirst()
      if (!seen.add(said)) continue
      val reply = FakeConversationAgent.replyFor(said)
      assertTrue("\"$said\" fell through to the off-topic reply", reply.keys.isNotEmpty())
      queue += reply.suggestions
    }
    assertTrue(seen.size >= 6)
  }

  @Test fun `off-topic questions are steered back to scams`() {
    val reply = FakeConversationAgent.replyFor("What's the weather tomorrow?")
    assertTrue(reply.keys.isEmpty())
    assertTrue(reply.text.contains("scam"))
  }

  @Test fun `a spoken turn goes listening, thinking, speaking, then idle when the mic times out`() = runTest {
    val voice = RecordingVoice()
    val presence = RecordingPresence()
    val c = controller(ScriptedSpeech(ArrayDeque(listOf("is this text from my bank real", null))), voice, presence)
    c.openMic()
    advanceUntilIdle()
    val s = c.state.value
    assertEquals(listOf(Speaker.YOU, Speaker.PIP), s.turns.map { it.speaker })
    assertEquals("is this text from my bank real", s.turns[0].text)
    assertTrue(s.turns[1].done)
    assertEquals(listOf(s.turns[1].text), voice.spoken)
    assertEquals(listOf(TalkState.LISTENING, TalkState.THINKING, TalkState.SPEAKING, TalkState.IDLE, TalkState.LISTENING, TalkState.IDLE), presence.talk)
    assertEquals(PipMood.WARY, presence.moods.last())
    assertEquals(TalkState.IDLE, s.talk)
    assertFalse(s.micOpen)
  }

  @Test fun `typed turns use the same path and offer follow-ups`() = runTest {
    val voice = RecordingVoice()
    val c = controller(ScriptedSpeech(ArrayDeque()), voice, RecordingPresence())
    c.send("What do you do, Pip?")
    advanceUntilIdle()
    val s = c.state.value
    assertEquals(2, s.turns.size)
    assertTrue(s.turns[1].text.startsWith("I'm Pip"))
    assertTrue(s.suggestions.isNotEmpty())
    assertEquals(TalkState.IDLE, s.talk)
  }

  @Test fun `no recognizer means a notice, not a crash`() = runTest {
    val none = object : SpeechInput {
      override val onDevice = false
      override fun isAvailable() = false
      override fun transcribe(locale: String) = MutableSharedFlow<Transcript>()
      override fun finish() {}
    }
    val c = controller(none, RecordingVoice(), RecordingPresence())
    c.openMic()
    assertTrue(c.state.value.notice != null)
    assertEquals(TalkState.IDLE, c.state.value.talk)
  }

  @Test fun `a live voice session drives Pip's state and blocks the turn mic`() = runTest {
    val live = object : LiveVoice {
      override val state = kotlinx.coroutines.flow.MutableStateFlow<LiveState>(LiveState.Idle)
      override fun start(request: dev.playground.pip.core.TalkToPipRequest) { state.value = LiveState.Live }
      override fun stop() { state.value = LiveState.Ended("Call ended.") }
    }
    val presence = RecordingPresence()
    val c = ConversationController(FakeConversationAgent(), ScriptedSpeech(ArrayDeque(listOf("hi"))), RecordingVoice(), presence, backgroundScope, liveVoice = live)
    c.startLive()
    testScheduler.runCurrent()
    assertEquals(LiveState.Live, c.state.value.live)
    assertEquals(TalkState.LISTENING, presence.talk.last())
    c.openMic()
    assertFalse(c.state.value.micOpen)
    c.stopLive()
    testScheduler.runCurrent()
    assertEquals(TalkState.IDLE, c.state.value.talk)
    assertEquals(null, c.state.value.notice)
  }
}
