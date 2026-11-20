package dev.playground.pip.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.playground.pip.conversation.SpeechModel
import dev.playground.pip.sources.GuardAccessibilityService
import dev.playground.pip.sources.GuardNotificationListener

/** How a capability gets turned on: a runtime permission prompt, a role request, or a system settings screen. */
sealed interface Grant {
  data class Permissions(val names: List<String>) : Grant
  data class Role(val role: String) : Grant
  data class SettingsScreen(val action: String) : Grant
  /** The on-device speech model: asked for with [dev.playground.pip.conversation.SpeechModel.download]. */
  data object SpeechModel : Grant
  data object None : Grant
}

data class Capability(val title: String, val why: String, val enabled: Boolean, val grant: Grant)

object Capabilities {
  fun all(ctx: Context): List<Capability> {
    fun has(vararg p: String) = p.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val roles = ctx.getSystemService(RoleManager::class.java)
    return listOf(
      Capability(
        "Accessibility service", "Reads on-screen text in every app and is the only way to record the mic during a call",
        accessibilityOn(ctx), Grant.SettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS),
      ),
      Capability(
        "Call screening role", "Sees each incoming number, contact match and spoof check before it rings",
        roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING), Grant.Role(RoleManager.ROLE_CALL_SCREENING),
      ),
      Capability(
        "Notification access", "Reads notifications from SMS, WhatsApp, email and banking apps",
        notificationListenerOn(ctx), Grant.SettingsScreen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
      ),
      Capability(
        "Phone, call log and contacts", "Knows when a call starts and ends, whether the caller is a known contact, and can hang up a scam call",
        has(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS),
        Grant.Permissions(listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS)),
      ),
      Capability(
        "SMS", "Receives the full text and sender of every SMS",
        has(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
        Grant.Permissions(listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)),
      ),
      Capability(
        "Microphone", "Records the call audio (both sides when on speaker) for live transcription",
        has(Manifest.permission.RECORD_AUDIO), Grant.Permissions(listOf(Manifest.permission.RECORD_AUDIO)),
      ),
      Capability(
        "Warnings", "Shows scam warnings as high-priority notifications",
        has(Manifest.permission.POST_NOTIFICATIONS), Grant.Permissions(listOf(Manifest.permission.POST_NOTIFICATIONS)),
      ),
      speech(),
    )
  }

  /**
   * A setup step only while the phone's recognizer could fetch the model but hasn't: installed, unsupported or not
   * yet checked, it is listed for information and setup skips it.
   */
  private fun speech(): Capability {
    val s = SpeechModel.state
    val askable = s == SpeechModel.State.DOWNLOADABLE || s == SpeechModel.State.PENDING
    return Capability(
      "On-device speech recognition", "Transcribes call audio without sending it off the phone",
      s == SpeechModel.State.INSTALLED || s == SpeechModel.State.PENDING, if (askable) Grant.SpeechModel else Grant.None,
    )
  }

  private fun accessibilityOn(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val me = ComponentName(ctx, GuardAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
  }

  private fun notificationListenerOn(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(ctx, GuardNotificationListener::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
  }

  fun intentFor(ctx: Context, grant: Grant): Intent? = when (grant) {
    is Grant.Role -> ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(grant.role)
    is Grant.SettingsScreen -> Intent(grant.action)
    else -> null
  }
}
