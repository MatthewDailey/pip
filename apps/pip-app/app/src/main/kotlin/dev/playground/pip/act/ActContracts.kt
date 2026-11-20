/**
 * Local mirrors of the contract types in the unmerged contracts PR (`dev.playground.pip.contracts`), cut down to what
 * "Pip mutes a scam call" needs. Names and shapes match, so when that PR lands each type here is replaced by an import:
 *
 * | Here | Contracts |
 * | --- | --- |
 * | [Escalation] | `contracts.Escalation` |
 * | [ConversationKey] (call only) | `contracts.ConversationKey` with `ConversationKind.CALL` |
 * | [Unavailable] | `contracts.Unavailable` (subset) |
 * | [ScamCallDetected] | a `signals.Signal` carrying a detection for a live call |
 * | [Assessment] | `detection` layer's assessment of one conversation |
 * | [Decision] | `policy` layer's answer: which escalation, which rule fired |
 * | [ActionKind], [ActionRequest], [ActionResult], [UndoToken] | `actions.*` |
 * | [Choice], [UndoOffer], [WarningEvent] | `presentation.*` |
 * | [ActLog] lines | `observability.PipEvent` (`SignalSeen`, `Assessed`, `Decided`, `Acted`, `Cued`, `Responded`) |
 *
 * No `android.*` imports here, as in the contracts.
 */
package dev.playground.pip.act

enum class Escalation { NONE, INFO, CAUTION, DANGER, ACT }

data class ConversationKey(val kind: String, val id: String) {
  companion object { fun call(id: String) = ConversationKey("CALL", id) }
}

sealed interface Unavailable {
  data class MissingCapability(val capability: String) : Unavailable
  data class NotOnThisPlatform(val why: String) : Unavailable
  data class TurnedOffByUser(val setting: String) : Unavailable
  data object TooLate : Unavailable
}

/** A detector (today: the debug broadcast) says the live call is a scam. [reason] is what Pip tells the person. */
data class ScamCallDetected(
  val id: String,
  val at: Long,
  val conversation: ConversationKey,
  val reason: String,
  val evidence: String?,
  val detector: String,
)

data class Assessment(val conversation: ConversationKey, val level: Escalation, val reason: String, val engine: String)

/** [rule] is the opt-in rule that turned danger into act, or null when Pip only warns. */
data class Decision(val id: String, val escalation: Escalation, val rule: String?, val suppressed: String?)

enum class ActionKind { MUTE_MIC, PRESS_DIALER_BUTTON, END_CALL }

sealed interface ActionRequest {
  val kind: ActionKind
  data class MuteMic(val callId: String, val mute: Boolean = true) : ActionRequest { override val kind = ActionKind.MUTE_MIC }
  data class EndCall(val callId: String) : ActionRequest { override val kind = ActionKind.END_CALL }
}

@JvmInline value class UndoToken(val value: String)

sealed interface ActionResult {
  data class Done(val undo: UndoToken?, val note: String? = null) : ActionResult
  data class NotDone(val reason: Unavailable) : ActionResult
  data class Failed(val message: String) : ActionResult
}

data class Choice(val id: String, val label: String, val primary: Boolean = false)

data class UndoOffer(val token: UndoToken, val label: String, val expiresAt: Long)

data class WarningEvent(
  val id: String,
  val conversation: ConversationKey,
  val escalation: Escalation,
  val headline: String,
  val reason: String,
  val choices: List<Choice>,
  val undo: UndoOffer? = null,
)
