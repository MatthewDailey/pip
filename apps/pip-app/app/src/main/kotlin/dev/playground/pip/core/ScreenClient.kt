package dev.playground.pip.core

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import dev.playground.pip.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Posts one screen (JPEG + accessibility text + package + URL) to
 * `POST {PIP_SERVER_URL}/api/screens/classify`, falling back to text-only when the
 * JPEG cannot be kept inside Jev's 32 KiB request budget (or when no image is available).
 *
 * Uses the same [BuildConfig.PIP_SERVER_URL] / [BuildConfig.PIP_SERVER_BYPASS] as [PipServer].
 */
object ScreenClient {
  const val TAG = "ScreenClient"
  const val MAX_REQUEST_BYTES = 32 * 1024
  private const val MIN_SIDE_PX = 280
  private const val MAX_SIDE_PX = 1280
  private const val CONNECT_TIMEOUT_MS = 5_000
  private const val READ_TIMEOUT_MS = 15_000

  data class Payload(
    val imageJpeg: ByteArray? = null,
    val a11yText: String,
    val appPackage: String,
    val windowTitle: String?,
    val url: String?,
    val trigger: String = "window_content_changed",
  )

  fun classify(payload: Payload): ScreenVerdict {
    val url = endpoint()
    if (url.isEmpty()) throw IOException("PIP_SERVER_URL is empty")
    val body = encode(payload)
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
      val bypass = BuildConfig.PIP_SERVER_BYPASS
      if (bypass.isNotEmpty()) setRequestProperty("x-vercel-protection-bypass", bypass)
    }
    try {
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
      return ScreenVerdict.fromJson(text)
    } finally {
      conn.disconnect()
    }
  }

  /** Downscale and JPEG-compress [bitmap] so the JSON body stays under [MAX_REQUEST_BYTES]. */
  fun compressForRequest(bitmap: Bitmap, a11yText: String, appPackage: String, windowTitle: String?, url: String?, trigger: String): Payload {
    val textOnly = Payload(null, a11yText, appPackage, windowTitle, url, trigger)
    // Leave room for the image fields ("image_base64", "image_format") on top of the text JSON.
    val reserved = encode(textOnly).toByteArray(Charsets.UTF_8).size + 80
    val scaled = scale(bitmap)
    try {
      for (quality in intArrayOf(70, 55, 40, 30, 20)) {
        val jpeg = jpeg(scaled, quality)
        val b64 = (jpeg.size + 2) / 3 * 4
        if (b64 + reserved <= MAX_REQUEST_BYTES) {
          val body = encode(Payload(jpeg, a11yText, appPackage, windowTitle, url, trigger))
          Log.i(TAG, "jpeg q=$quality ${jpeg.size}B body=${body.toByteArray(Charsets.UTF_8).size}B ${scaled.width}x${scaled.height}")
          return Payload(jpeg, a11yText, appPackage, windowTitle, url, trigger)
        }
      }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
    Log.w(TAG, "image would exceed ${MAX_REQUEST_BYTES}B; sending text-only")
    return textOnly
  }

  private fun scale(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return src
    val longSide = max(w, h)
    val shortSide = minOf(w, h)
    var factor = 1f
    if (longSide > MAX_SIDE_PX) factor = MAX_SIDE_PX / longSide.toFloat()
    // Jev rejects images with a side outside 280–1568 px.
    if (shortSide * factor < MIN_SIDE_PX) factor = MIN_SIDE_PX / shortSide.toFloat()
    if (factor == 1f) return src
    val nw = max(MIN_SIDE_PX, (w * factor).roundToInt())
    val nh = max(MIN_SIDE_PX, (h * factor).roundToInt())
    return Bitmap.createScaledBitmap(src, nw, nh, true)
  }

  private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
      throw IOException("JPEG compress failed")
    }
    return out.toByteArray()
  }

  private fun encode(payload: Payload): String {
    val o = JSONObject()
    if (payload.imageJpeg != null) {
      o.put("image_base64", Base64.encodeToString(payload.imageJpeg, Base64.NO_WRAP))
      o.put("image_format", "jpg")
      o.put("mode", "image+text")
    } else {
      o.put("mode", "text")
    }
    o.put("a11y_text", payload.a11yText.take(3000))
    o.put("app_package", payload.appPackage)
    if (!payload.windowTitle.isNullOrBlank()) o.put("window_title", payload.windowTitle)
    if (!payload.url.isNullOrBlank()) o.put("url", payload.url)
    o.put("trigger", payload.trigger)
    return o.toString()
  }

  fun endpoint(): String {
    val base = BuildConfig.PIP_SERVER_URL.trim().trimEnd('/')
    return if (base.isEmpty()) "" else "$base/api/screens/classify"
  }
}
