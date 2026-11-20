package dev.playground.pip.conversation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The agent Pip ships with until a backend exists: canned, friendly cyber-safety answers picked by keyword, streamed
 * word by word so the UI behaves as it will with a real model. The replies stay on topic (scams, codes, links,
 * gift cards, calls) and steer anything else back to what Pip is for.
 *
 * The scripted demo exchanges are the [starters] and each reply's suggestions: tapping through them walks a whole
 * conversation, e.g. "Is this text from my bank real?" then "What should I do with it?".
 *
 * Replace it in [ConversationWiring.agent].
 */
class FakeConversationAgent(
  private val thinkMs: Long = 700,
  private val wordMs: Long = 45,
) : ConversationAgent {

  data class Reply(val keys: List<String>, val text: String, val mood: PipMood, val suggestions: List<String> = emptyList())

  private var turns = 0

  override fun turn(input: AgentInput): Flow<AgentOutput> = flow {
    val said = when (input) {
      is AgentInput.Text -> input.text
      is AgentInput.Spoken -> input.transcript
    }
    val reply = replyFor(said, turns++)
    delay(thinkMs)
    emit(AgentOutput.Hint(reply.mood, reply.suggestions))
    val words = reply.text.split(' ')
    words.forEachIndexed { i, w ->
      emit(AgentOutput.TextDelta(if (i == 0) w else " $w"))
      delay(wordMs)
    }
    emit(AgentOutput.Done(reply.text))
  }

  override fun reset() { turns = 0 }

  companion object {
    /** Openers shown on the quiet home; each starts a scripted exchange. */
    val starters = listOf(
      "Is this text from my bank real?",
      "Someone on the phone wants gift cards",
      "What do you do, Pip?",
    )

    private val replies = listOf(
      Reply(
        listOf("what should i do with it", "delete"),
        "Don't reply and don't tap anything in it. Delete it, and if you're worried, call your bank on the number on the back of your card.",
        PipMood.HAPPY, listOf("How do I spot the next one?"),
      ),
      Reply(
        listOf("spot the next", "how do i spot", "how can i tell"),
        "Three tells: they rush you, they want a code or a payment, and the message came out of the blue. Any one of those, check with me first.",
        PipMood.HAPPY, listOf("Thanks, Pip"),
      ),
      Reply(
        listOf("gift card", "giftcard", "itunes", "google play card", "steam card"),
        "That's a scam. Nobody real asks to be paid in gift cards, not the IRS, not your bank, not tech support. Hang up, and call someone you trust.",
        PipMood.ANGRY, listOf("They said it's urgent", "Can you call my daughter?"),
      ),
      Reply(
        listOf("urgent", "right now", "hurry", "arrested", "police"),
        "Rushing you is the trick. A real problem can wait for you to hang up and call back on a number you already know.",
        PipMood.CONCERNED, listOf("Thanks, Pip"),
      ),
      Reply(
        listOf("call my daughter", "call my son", "call family", "buddy"),
        "I can't place calls yet, but that's exactly the right move. Hang up first, then call them from your contacts.",
        PipMood.HAPPY, listOf("Thanks, Pip"),
      ),
      Reply(
        listOf("bank", "text", "sms", "message", "code"),
        "Probably not. Banks never ask you to reply with a code by text. That code is the key to your account, so it's for your eyes only.",
        PipMood.WARY, listOf("What should I do with it?", "How do I spot the next one?"),
      ),
      Reply(
        listOf("link", "click", "website", "url", "delivery", "package", "parcel"),
        "Don't tap links you weren't expecting. If it's about a delivery or a bill, open the company's own app or type their address yourself.",
        PipMood.WARY, listOf("How do I spot the next one?"),
      ),
      Reply(
        listOf("grandson", "granddaughter", "nephew", "jail", "bail"),
        "That's a common one. Hang up and call your grandson on his usual number. If it really was him, he'll understand.",
        PipMood.CONCERNED, listOf("They said it's urgent"),
      ),
      Reply(
        listOf("computer", "virus", "microsoft", "apple support", "remote", "anydesk", "teamviewer"),
        "Real tech support never calls you first, and never asks to take over your screen. Hang up, and don't install anything they name.",
        PipMood.ANGRY, listOf("How do I spot the next one?"),
      ),
      Reply(
        listOf("password", "pin", "social security", "ssn"),
        "Keep that to yourself. No real company will ask for your password or PIN, on the phone or by text.",
        PipMood.WARY, listOf("How do I spot the next one?"),
      ),
      Reply(
        listOf("what do you do", "who are you", "what are you", "what can you do", "help"),
        "I'm Pip. I keep an eye on your calls, texts and notifications for scams, and I step in if something looks wrong. You can ask me about anything that feels off.",
        PipMood.HAPPY, listOf("Is this text from my bank real?", "Someone on the phone wants gift cards"),
      ),
      Reply(
        listOf("thank", "thanks", "great", "ok", "okay"),
        "Anytime. I'm right here if anything else feels off.",
        PipMood.HAPPY,
      ),
      Reply(
        listOf("hello", "hi", "hey"),
        "Hi! I'm keeping watch. Is anything bothering you today?",
        PipMood.HAPPY, starters.take(2),
      ),
    )

    private val fallback = listOf(
      Reply(emptyList(), "I'm best with scams: odd calls, texts, links and anyone asking for money or codes. Is something like that worrying you?", PipMood.NEUTRAL, starters.take(2)),
      Reply(emptyList(), "I'm not sure about that one. If someone is asking you for money, a code or to hurry, tell me what they said.", PipMood.NEUTRAL, starters.take(2)),
    )

    /** First reply whose key (or its plural) appears in what was said; otherwise a gentle steer back on topic. */
    fun replyFor(said: String, turn: Int = 0): Reply {
      val s = said.lowercase()
      return replies.firstOrNull { r -> r.keys.any { k -> Regex("\\b${Regex.escape(k)}s?\\b").containsMatchIn(s) } }
        ?: fallback[turn % fallback.size]
    }
  }
}
