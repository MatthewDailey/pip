package dev.playground.pip.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.playground.pip.TalkToPipActivity
import org.json.JSONObject

/**
 * What Pip should know when a Talk to Pip call starts. Every field is optional.
 *
 * @property context Free text Pip reads before he speaks, e.g. "She just got a text saying her bank account is
 *   locked, from 555-0142." Capped at [MAX_CONTEXT] characters.
 * @property opening What Pip should open the call with or steer toward, e.g. "Ask whether she clicked the link."
 * @property callId A call jev-playground scored (`/api/calls/:callId`); its transcript and verdict go to Pip too.
 * @property autoStart Start the call as soon as the screen opens, instead of waiting for the button.
 */
data class TalkToPipRequest(
  val context: String? = null,
  val opening: String? = null,
  val callId: String? = null,
  val autoStart: Boolean = false,
) {
  /** The body for jev-playground's `POST /api/live/session`, with the device's WebRTC offer. */
  fun sessionBody(sdp: String): String = JSONObject().apply {
    put("sdp", sdp)
    context?.takeIf { it.isNotBlank() }?.let { put("context", it.take(MAX_CONTEXT)) }
    opening?.takeIf { it.isNotBlank() }?.let { put("opening", it.take(MAX_CONTEXT)) }
    callId?.takeIf { it.isNotBlank() }?.let { put("callId", it) }
  }.toString()

  companion object {
    const val MAX_CONTEXT = 4000
  }
}

/**
 * The one way into a Talk to Pip call from anywhere in the app:
 *
 * ```
 * TalkToPip.launch(context, TalkToPipRequest(
 *   context = "Pip muted a call from an unknown number asking for gift cards.",
 *   opening = "Explain why you muted it and what to do next.",
 *   autoStart = true,
 * ))
 * ```
 *
 * From adb, the same extras: `adb shell am start -n dev.playground.pip/.TalkToPipActivity
 * --es context "..." --es opening "..." --es callId <id> --ez autoStart true`.
 */
object TalkToPip {
  const val EXTRA_CONTEXT = "context"
  const val EXTRA_OPENING = "opening"
  const val EXTRA_CALL_ID = "callId"
  const val EXTRA_AUTO_START = "autoStart"

  fun intent(context: Context, request: TalkToPipRequest = TalkToPipRequest()): Intent =
    Intent(context, TalkToPipActivity::class.java).apply {
      request.context?.let { putExtra(EXTRA_CONTEXT, it) }
      request.opening?.let { putExtra(EXTRA_OPENING, it) }
      request.callId?.let { putExtra(EXTRA_CALL_ID, it) }
      putExtra(EXTRA_AUTO_START, request.autoStart)
      // Services, receivers and the overlay aren't activities; they need a new task.
      if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      // A fresh request replaces whatever Talk to Pip screen is already open.
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

  fun launch(context: Context, request: TalkToPipRequest = TalkToPipRequest()) {
    context.startActivity(intent(context, request))
  }

  fun requestFrom(intent: Intent?): TalkToPipRequest = TalkToPipRequest(
    context = intent?.getStringExtra(EXTRA_CONTEXT),
    opening = intent?.getStringExtra(EXTRA_OPENING),
    callId = intent?.getStringExtra(EXTRA_CALL_ID),
    autoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, false) ?: false,
  )
}
