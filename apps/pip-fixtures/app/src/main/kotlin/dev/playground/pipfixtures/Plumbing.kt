package dev.playground.pipfixtures

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Posts a chat-style notification as "ExampleChat", as if a messaging app had received it:
 * ```
 * adb shell am broadcast -n dev.playground.pipfixtures/.NotifyReceiver \
 *   --es title "+44 7700 900123" --es text "Hi Mum, ..." [--ei id 7]
 * ```
 * Tapping it opens the thread in [ChatActivity]. `am force-stop dev.playground.pipfixtures` clears it.
 */
class NotifyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val title = intent.getStringExtra("title") ?: "Unknown"
    val text = intent.getStringExtra("text") ?: return
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(
      context, 0,
      Intent(context, ChatActivity::class.java).putExtra("contact", title).putExtra("message", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val n = Notification.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.sym_action_chat)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setSubText("ExampleChat")
      .setCategory(Notification.CATEGORY_MESSAGE)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    nm.notify(intent.getIntExtra("id", 1), n)
  }

  companion object { const val CHANNEL = "messages" }
}

/** Serves the bundled voicemail to whichever app the share sheet picks (read-only, one file). */
class VoicemailProvider : ContentProvider() {
  override fun onCreate() = true
  override fun getType(uri: Uri) = "audio/mp4"
  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = context?.resources?.openRawResourceFd(R.raw.voicemail)
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
    val size = context?.resources?.openRawResourceFd(R.raw.voicemail)?.use { it.length } ?: 0L
    return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("voicemail.m4a", size)) }
  }
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
