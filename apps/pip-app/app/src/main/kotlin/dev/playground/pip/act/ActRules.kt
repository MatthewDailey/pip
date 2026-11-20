package dev.playground.pip.act

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * The person's opt-in act rules (the policy layer). Play allows accessibility actions only as fixed rules the user
 * switched on, so Pip mutes only while "Mute me on scam calls" is on. Until the setup screen exists, debug builds
 * default the rule on and release builds off; [setMuteScamCalls] (the `pip:mute-rule` debug broadcast) overrides it.
 */
class ActRules(context: Context) {
  private val prefs = context.getSharedPreferences("act_rules", Context.MODE_PRIVATE)
  private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

  /** The rule's state and where it came from: "user" when set, else "default-debug" or "default-release". */
  fun muteScamCalls(): Pair<Boolean, String> =
    if (prefs.contains(MUTE_SCAM_CALLS)) prefs.getBoolean(MUTE_SCAM_CALLS, false) to "user"
    else debuggable to if (debuggable) "default-debug" else "default-release"

  /** null forgets the person's choice, so the build default applies again. */
  fun setMuteScamCalls(on: Boolean?) {
    prefs.edit().apply { if (on == null) remove(MUTE_SCAM_CALLS) else putBoolean(MUTE_SCAM_CALLS, on) }.apply()
  }

  /** Danger becomes act only when the rule is on; otherwise Pip warns and offers the action as a button. */
  fun decide(a: Assessment): Decision {
    val (on, source) = muteScamCalls()
    val id = "decision-${System.currentTimeMillis()}"
    return if (a.level >= Escalation.DANGER && on) Decision(id, Escalation.ACT, "$MUTE_SCAM_CALLS ($source)", null)
    else Decision(id, a.level, null, if (a.level >= Escalation.DANGER) "$MUTE_SCAM_CALLS off ($source)" else null)
  }

  companion object { const val MUTE_SCAM_CALLS = "mute_scam_calls" }
}
