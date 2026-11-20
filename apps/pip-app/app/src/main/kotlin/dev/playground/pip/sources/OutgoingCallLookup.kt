package dev.playground.pip.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log

/**
 * CallScreeningService only sees inbound calls, so an outbound call would otherwise go to Pip4
 * with no number and the default inbound/unknown-caller context. The dialed number is in the
 * call log (READ_CALL_LOG is already in the manifest); we also resolve a contact name when
 * READ_CONTACTS is granted.
 */
object OutgoingCallLookup {
  const val TAG = "pip-outgoing"
  data class Party(val number: String, val contactName: String?, val inContacts: Boolean)

  /** Most recent outgoing call started within [withinMs], or null if none / no permission. */
  fun recent(app: Context, withinMs: Long = 20_000): Party? {
    if (app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_CALL_LOG not granted; cannot capture outbound number")
      return null
    }
    val cutoff = System.currentTimeMillis() - withinMs
    return try {
      app.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE),
        "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>=?",
        arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), cutoff.toString()),
        "${CallLog.Calls.DATE} DESC",
      )?.use { c ->
        if (!c.moveToFirst()) return@use null
        val number = c.getString(0).orEmpty().trim()
        if (number.isBlank()) return@use null
        val cached = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
        val contact = cached ?: contactName(app, number)
        Party(number, contact, contact != null)
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "call log query blocked: ${e.message}")
      null
    }
  }

  private fun contactName(app: Context, number: String): String? {
    if (number.isBlank() || app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
      app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
      }
    } catch (_: SecurityException) {
      null
    }
  }
}
