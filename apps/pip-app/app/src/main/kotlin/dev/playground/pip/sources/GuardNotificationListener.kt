package dev.playground.pip.sources

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source

/** Reads every app's notifications: SMS and RCS previews, WhatsApp, email, banking, delivery apps. */
class GuardNotificationListener : NotificationListenerService() {
  private val seen = LinkedHashSet<String>()

  companion object { @Volatile var instance: GuardNotificationListener? = null }

  override fun onListenerConnected() {
    instance = this
    Guard.note(Source.SYSTEM, "Notification listener connected")
  }

  override fun onListenerDisconnected() { if (instance === this) instance = null }

  /**
   * The ongoing call notification's own Mute or Unmute action (Google's dialer posts one, category `call`), so Pip
   * can mute through the phone app itself and the system's call UI stays in step. Null when there's no such action.
   */
  fun callMuteAction(): Pair<String, PendingIntent>? {
    val calls = runCatching { activeNotifications }.getOrNull().orEmpty()
      .filter { it.notification.category == Notification.CATEGORY_CALL && it.isOngoing }
    for (sbn in calls) for (a in sbn.notification.actions.orEmpty()) {
      val title = a.title?.toString()?.trim() ?: continue
      if (title.equals("Mute", true) || title.equals("Unmute", true)) return title to a.actionIntent
    }
    return null
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName == packageName || sbn.isOngoing) return
    val extras = sbn.notification.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    if (title.isBlank() && body.isBlank()) return
    // Apps repost the same notification on every update; only log new content.
    val key = "${sbn.packageName}|$title|$body"
    if (!seen.add(key)) return
    if (seen.size > 200) seen.remove(seen.first())
    Guard.report(Source.NOTIFICATION, title.ifBlank { sbn.packageName }, body, app = sbn.packageName)
  }
}
