package dev.playground.pip.voice

import java.util.Locale

/** One text-to-speech voice as [PipVoicePicker] sees it: Android's `Voice`, without Android. */
data class VoiceSpec(
  val name: String,
  val locale: Locale,
  /** `Voice.QUALITY_*`: 100 very low to 500 very high. */
  val quality: Int,
  /** `Voice.LATENCY_*`: 100 very low to 500 very high. */
  val latency: Int,
  val network: Boolean,
  /** False while the engine still has to download the voice's data (the `notInstalled` feature). */
  val installed: Boolean,
)

/** Pitch and rate multipliers for `TextToSpeech.setPitch` / `setSpeechRate` (1.0 is the engine's normal). */
data class VoiceTuning(val pitch: Float, val rate: Float)

/** Why Pip is speaking: a warning must work offline and start at once; the greeting and chat may wait for the network. */
enum class VoicePurpose { GREETING, WARNING, CHAT }

/** The voice Pip uses, its tuning and a one-line reason for logs. A null [voice] keeps the engine's default voice. */
data class VoicePick(val voice: VoiceSpec?, val tuning: VoiceTuning, val why: String)

/**
 * Chooses Pip's voice from what the engine offers.
 *
 * Named voices come first, in [PREFERRED] order: Google's on-device US male voices (Speech Services by Google ships
 * them on Pixels and most Samsungs), then UK ones. Pip's audience skews older, and age-related hearing loss takes
 * the high frequencies first, so Pip is a male voice lifted only slightly for warmth and slowed a touch; the
 * playfulness lives in his lines, not a chipmunk pitch.
 *
 * Without a named voice he takes the best installed en-US or en-GB voice (quality, then latency, then on-device) at
 * neutral pitch, since an unknown voice may already be high. Warnings use on-device voices only, so they speak with no
 * network and no lag. A [override] (set with `turbo run pip:voice -- set <name>`) wins when that voice is usable.
 */
object PipVoicePicker {
  /** Google voice ids without their `-local` / `-network` suffix, best first, each with its tuning. */
  val PREFERRED: List<Pair<String, VoiceTuning>> = listOf(
    "en-us-x-iol" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
    "en-us-x-iom" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
    "en-us-x-tpd" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
    "en-gb-x-gbd" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
    "en-gb-x-rjs" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
    "en-gb-x-gbb" to VoiceTuning(pitch = 1.1f, rate = 0.95f),
  )

  /** For a voice that isn't in [PREFERRED]: its own pitch, a little slower. */
  val NEUTRAL = VoiceTuning(pitch = 1.0f, rate = 0.95f)

  private val LOCALES = listOf(Locale.US, Locale.UK)

  fun tuningFor(name: String): VoiceTuning = PREFERRED.firstOrNull { (base, _) -> baseName(name) == base }?.second ?: NEUTRAL

  fun pick(voices: List<VoiceSpec>, purpose: VoicePurpose, override: String? = null): VoicePick {
    val usable = voices.filter { it.installed && (!it.network || purpose != VoicePurpose.WARNING) }
    if (!override.isNullOrBlank()) {
      usable.firstOrNull { it.name == override }?.let { return VoicePick(it, tuningFor(it.name), "override") }
    }
    for ((base, tuning) in PREFERRED) {
      val named = usable.filter { baseName(it.name) == base }.minByOrNull { if (it.network) 1 else 0 }
      if (named != null) return VoicePick(named, tuning, "preferred")
    }
    val english = usable.filter { v -> LOCALES.any { it.language == v.locale.language && it.country == v.locale.country } }
    val best = english.sortedWith(
      compareBy<VoiceSpec>({ -it.quality }, { it.latency }, { it.network }, { LOCALES.indexOfFirst { l -> l.country == it.locale.country } }),
    ).firstOrNull()
    if (best != null) return VoicePick(best, NEUTRAL, "best available")
    return VoicePick(null, NEUTRAL, if (override.isNullOrBlank()) "engine default" else "engine default (override $override unavailable)")
  }

  /** `en-us-x-iol-local` -> `en-us-x-iol`. */
  fun baseName(name: String): String = name.removeSuffix("-local").removeSuffix("-network")

  /** Where a rendered line is saved: `Hi! I'm Pip.` -> `hi-im-pip`. tools/pip-show/soundtrack.py looks lines up by it. */
  fun slug(line: String): String = line.lowercase(Locale.US).replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
