package dev.playground.pip.sources

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import dev.playground.pip.core.Guard
import dev.playground.pip.core.Source

/**
 * Sees every call before it rings, once this app holds the call-screening role. The playground only observes
 * and always lets the call through; blocking or silencing is a product decision to explore later.
 */
class GuardCallScreeningService : CallScreeningService() {
  override fun onScreenCall(details: Call.Details) {
    val number = details.handle?.schemeSpecificPart.orEmpty()
    val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
    val verification = when (details.callerNumberVerificationStatus) {
      Connection.VERIFICATION_STATUS_PASSED -> "verified"
      Connection.VERIFICATION_STATUS_FAILED -> "FAILED verification (likely spoofed)"
      else -> "not verified"
    }
    val contact = contactName(number)
    val flags = buildList {
      if (contact == null) add("not in contacts")
      if (details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED) add("spoof check failed")
      if (number.isBlank()) add("hidden number")
    }
    val title = "${if (incoming) "Incoming" else "Outgoing"} call: ${contact ?: number.ifBlank { "Unknown" }}"
    val detail = "number=$number, $verification" + if (flags.isEmpty()) "" else ", flags=${flags.joinToString()}"
    Guard.report(Source.CALL_SCREEN, title, detail, alertKey = "screen:$number")
    val verificationKey = when (details.callerNumberVerificationStatus) {
      Connection.VERIFICATION_STATUS_PASSED -> "verified"
      Connection.VERIFICATION_STATUS_FAILED -> "failed"
      else -> "not_verified"
    }
    dev.playground.pip.live.PipCallVerdicts.setCaller(
      number = number,
      contactName = contact,
      incoming = incoming,
      verification = verificationKey,
    )
    respondToCall(details, CallResponse.Builder().build())
  }

  private fun contactName(number: String): String? {
    if (number.isBlank() || checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    }
  }
}
