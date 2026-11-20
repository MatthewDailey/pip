package dev.playground.pip.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source
import dev.playground.pip.live.PipCallVerdicts

/**
 * Accepts call transcript lines piped in over adb by scripts/simulate.sh and
 * live-call.sh, so simulated calls exercise the same scoring and warnings as real call audio. Guarded by
 * the DUMP permission, which only the adb shell and the system hold, so other apps can't inject transcripts.
 *
 *   adb shell am broadcast -n dev.playground.pip/.sources.SimTranscriptReceiver \
 *     --es speaker user --es text "..." --es call gift-demo \
 *     --es direction inbound --es number 5551112222 --ez in_contacts false
 *
 * User lines are posted to pip-call (then Pip4) with listen=user. Local keyword rules still score caller
 * lines as an offline fallback. Partials are marked with --ez final false.
 */
class SimTranscriptReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val text = intent.getStringExtra("text")?.trim().orEmpty()
    if (text.isEmpty()) return
    val speaker = if (intent.getStringExtra("speaker") == "user") "user" else "caller"
    val rawCall = intent.getStringExtra("call") ?: "sim"
    val callId = if (rawCall.startsWith("call-") || rawCall.startsWith("sim-") || rawCall.startsWith("live-") || rawCall.startsWith("app-")) {
      rawCall
    } else {
      "call-$rawCall"
    }
    val title = if (speaker == "caller") "Caller said (simulated)" else "You said (simulated)"

    val number = intent.getStringExtra("number")
    val direction = intent.getStringExtra("direction")
    val name = intent.getStringExtra("name")
    val inContacts = if (intent.hasExtra("in_contacts")) intent.getBooleanExtra("in_contacts", false) else null
    val verification = intent.getStringExtra("verification")
    if (number != null || direction != null || name != null || inContacts != null || verification != null) {
      PipCallVerdicts.setCaller(
        number = number ?: "",
        contactName = name,
        incoming = (direction ?: "inbound") != "outbound",
        verification = verification,
        inContacts = inContacts,
      )
    } else {
      PipCallVerdicts.ensureDefaultCaller()
    }

    val t = if (intent.hasExtra("t")) intent.getFloatExtra("t", 0f).toDouble() else null
    val final = intent.getBooleanExtra("final", true)

    // Score only the caller's words locally for warnings; the person's own replies are logged for context.
    if (speaker == "caller") Guard.reportCallLine(callId, title, text)
    else Guard.note(Source.TRANSCRIPT, title, text)

    // Pip4 listens to Mom's side only (the phone's mic); the verdict comes back to PipCallVerdicts, which mutes and warns.
    if (speaker != "caller") PipCallVerdicts.send(callId, "user", text, t = t, final = final)
  }
}
