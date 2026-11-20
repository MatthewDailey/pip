package dev.playground.pip.act

/**
 * Whether Pip owns the mic mute right now, and what to do when that changes. The mic-mute flag is global, so a mute
 * Pip leaves behind would silence the next call and every app: Pip clears it when the call ends. If the mute is lifted
 * by someone else mid-call (the person's own Unmute in the phone app, or Telecom on an audio route change), Pip
 * reports it rather than fighting the person. No Android types, so it's unit tested on the JVM.
 */
class MuteLedger {
  /** The path holding Pip's mute, or null when Pip doesn't own one. */
  var owner: String? = null
    private set

  fun muted(path: String) { owner = path }

  /** Pip lifted its own mute (undo); later mute broadcasts aren't a drop. */
  fun unmuted() { owner = null }

  /** The call ended. True when Pip must clear a mic mute it left behind. */
  fun callEnded(micMuted: Boolean): Boolean {
    val clear = owner != null && micMuted
    owner = null
    return clear
  }

  /** The mic-mute flag changed. True when it dropped Pip's mute, so Pip should say the person is unmuted. */
  fun micChanged(micMuted: Boolean): Boolean {
    if (owner == null || micMuted) return false
    owner = null
    return true
  }
}
