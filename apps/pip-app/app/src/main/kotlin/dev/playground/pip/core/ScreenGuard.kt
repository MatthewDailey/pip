package dev.playground.pip.core

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.playground.pip.R
import dev.playground.pip.sources.GuardAccessibilityService
import dev.playground.pip.ui.ScreenWarningBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device screen fraud pipeline: debounce window changes, take a screenshot, POST it to
 * jev-playground, and surface a warning when Jev says to intervene.
 *
 * Kept off [Guard] / [PipServer] so the parallel call-transcript work can merge cleanly.
 */
object ScreenGuard {
  const val TAG = "ScreenGuard"
  private const val DEBOUNCE_MS = 2_000L
  private const val GLOBAL_MIN_MS = 3_000L

  private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pip-screen").apply { isDaemon = true } }
  private val main = Handler(Looper.getMainLooper())
  private val inFlight = AtomicBoolean(false)

  @Volatile private var service: AccessibilityService? = null
  @Volatile private var banner: ScreenWarningBanner? = null
  @Volatile private var lastPostedAt = 0L
  @Volatile private var lastFingerprint: String? = null
  /** Package the current on-screen banner was raised for, or null if none is showing. */
  @Volatile private var flaggedPkg: String? = null
  @Volatile private var lastSubmittedPkg: String? = null

  private val _verdict = MutableStateFlow<ScreenVerdict?>(null)
  val verdict: StateFlow<ScreenVerdict?> = _verdict

  fun attach(svc: AccessibilityService) {
    service = svc
    main.post {
      if (banner == null) {
        banner = ScreenWarningBanner(svc) { top ->
          GuardAccessibilityService.instance?.reservePipTop(top)
        }
      }
    }
    // The first screen is already up when the service connects; wait a beat then classify it.
    main.postDelayed({ evaluateCurrent(svc) }, 1_500)
  }

  fun evaluateCurrent(svc: AccessibilityService) {
    val root = svc.rootInActiveWindow ?: return
    val pkg = root.packageName?.toString() ?: return
    if (pkg == svc.packageName || pkg == "com.android.systemui") return
    val sb = StringBuilder()
    collectText(root, sb, 0)
    val text = sb.toString().trim().take(3000)
    val url = extractUrl(root)
    if (text.isEmpty() && url == null) return
    val title = root.window?.title?.toString() ?: pkg.substringAfterLast('.')
    evaluate(svc, pkg, title, text, url)
  }

  private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
    if (depth > 40 || sb.length > 3000) return
    if (node.isPassword) return
    node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
    for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, sb, depth + 1) }
  }

  fun detach() {
    main.post {
      banner?.dismiss()
      banner = null
      flaggedPkg = null
    }
    service = null
  }

  /** Drop a leftover banner (for example when the call warning takes the screen). */
  fun dismissBanner() {
    main.post {
      banner?.dismiss()
      flaggedPkg = null
    }
  }

  /**
   * Dismiss the banner once the person leaves the flagged app, including into Pip's own UI
   * (which this pipeline otherwise skips, so a later benign verdict would never arrive).
   */
  fun onForegroundPackage(pkg: String) {
    val flagged = flaggedPkg ?: return
    if (pkg != flagged && pkg != "com.android.systemui") dismissBanner()
  }

  /**
   * Called after the accessibility tree text for [pkg] has changed. Takes a screenshot (API 30+)
   * and posts it; falls back to text-only when the capture fails (FLAG_SECURE, interval, etc.).
   */
  fun evaluate(svc: AccessibilityService, pkg: String, title: String, text: String, url: String?) {
    if (pkg == svc.packageName || pkg == "com.android.systemui") return
    lastSubmittedPkg = pkg
    val fingerprint = "$pkg\n${url.orEmpty()}\n$text"
    if (fingerprint == lastFingerprint) return
    val now = System.currentTimeMillis()
    if (now - lastPostedAt < GLOBAL_MIN_MS) return
    if (!inFlight.compareAndSet(false, true)) return
    lastFingerprint = fingerprint
    lastPostedAt = now

    fun submit(bitmap: Bitmap?) {
      io.execute {
        try {
          val payload = if (bitmap != null) {
            try {
              ScreenClient.compressForRequest(bitmap, text, pkg, title, url, "window_content_changed")
            } finally {
              bitmap.recycle()
            }
          } else {
            ScreenClient.Payload(null, text, pkg, title, url, "window_content_changed")
          }
          if (payload.a11yText.isBlank() && payload.imageJpeg == null) return@execute
          val verdict = ScreenClient.classify(payload)
          Log.i(TAG, "verdict intervene=${verdict.intervene} type=${verdict.fraudType} risk=${verdict.risk} mode=${verdict.mode} ${verdict.latencyMs}ms")
          main.post { applyVerdict(verdict) }
        } catch (e: Exception) {
          Log.w(TAG, "classify failed: ${e.message}")
          Guard.note(Source.SERVER, "Screen classify failed", e.message ?: e.javaClass.simpleName, app = pkg)
        } finally {
          inFlight.set(false)
        }
      }
    }

    takeScreenshot(svc, onDone = { submit(it) })
  }

  fun extractUrl(root: AccessibilityNodeInfo): String? {
    val ids = listOf(
      "com.android.chrome:id/url_bar",
      "com.android.chrome:id/url_bar_title",
      "com.android.chrome:id/search_box_text",
      "org.mozilla.firefox:id/url_bar_title",
      "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
      "com.brave.browser:id/url_bar",
    )
    for (id in ids) {
      val raw = root.findAccessibilityNodeInfosByViewId(id)
        ?.firstOrNull()
        ?.text
        ?.toString()
        ?.trim()
        .orEmpty()
      if (raw.isNotEmpty() && raw != "Search or type web address") return normalizeUrl(raw)
    }
    return null
  }

  private fun normalizeUrl(raw: String): String =
    if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"

  private fun takeScreenshot(svc: AccessibilityService, onDone: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT < 30) {
      onDone(null)
      return
    }
    try {
      svc.takeScreenshot(
        Display.DEFAULT_DISPLAY,
        io,
        object : AccessibilityService.TakeScreenshotCallback {
          override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            var hardware: Bitmap? = null
            val buffer = screenshot.hardwareBuffer
            try {
              hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
              val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
              onDone(copy)
            } catch (e: Exception) {
              Log.w(TAG, "screenshot convert failed: ${e.message}")
              onDone(null)
            } finally {
              buffer.close()
              hardware?.recycle()
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.w(TAG, "takeScreenshot failed code=$errorCode; using text-only")
            onDone(null)
          }
        },
      )
    } catch (e: Exception) {
      Log.w(TAG, "takeScreenshot threw: ${e.message}")
      onDone(null)
    }
  }

  private fun applyVerdict(v: ScreenVerdict) {
    _verdict.value = v
    val line = v.suggestedLine ?: v.reason
    EventLog.add(
      GuardEvent(
        System.currentTimeMillis(),
        Source.SERVER,
        v.appPackage,
        "Screen verdict: ${v.fraudType} risk=${"%.2f".format(v.risk)}",
        "intervene=${v.intervene} mode=${v.mode} ${v.latencyMs}ms $line",
        score = (v.risk * 100).toInt(),
        rules = listOfNotNull(v.fraudType, if (v.intervene) "intervene" else null),
      ),
    )
    if (!v.intervene) {
      dismissBanner()
      return
    }
    val text = WarningCopy.forScreen(v.fraudType, v.suggestedLine, line)
    flaggedPkg = v.appPackage ?: lastSubmittedPkg
    Guard.onWarn?.invoke(text)
    banner?.show(text)
    notify(v, text)
  }

  private fun notify(v: ScreenVerdict, text: String) {
    val app = service ?: return
    if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val n = NotificationCompat.Builder(app, "scam-alerts")
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("This screen may be a scam")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(
        "$text\n\nDon't type a password, card, or Social Security number. Don't let anyone control this phone.",
      ))
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(app).notify(("screen:" + v.id).hashCode(), n)
  }
}
