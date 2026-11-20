package dev.playground.pip.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.rive.Fit
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFile
import app.rive.RiveFileSource
import app.rive.ViewModelInstance
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.rememberViewModelInstanceResult
import app.rive.runtime.kotlin.core.ViewModel.PropertyDataType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

/**
 * Pip played by Rive from `pip.riv`, driven through its `Pip` view model.
 *
 * [view] is the host to add to a window. It intercepts every touch so the owner can handle drag and tap; Rive's own
 * pointer listeners never see them.
 *
 * Values are sticky: the last value set for each property is re-applied whenever a file (re)loads, so a hot reload
 * keeps Pip's mood. A property the current file lacks, or whose type differs, is skipped and logged once per file.
 * Safe to call from any thread.
 */
class PipRive(context: Context) {
  private val owner = OverlayOwner()
  private val sticky = ConcurrentHashMap<String, Value>()
  private val commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
  private val source = MutableStateFlow(Source(fromDownload = false, generation = 0))

  @SuppressLint("ClickableViewAccessibility")
  val view: FrameLayout = object : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent) = true
  }

  init {
    // The Compose API does not load librive-android itself; without this the worker crashes on its first frame.
    app.rive.runtime.kotlin.core.Rive.init(context.applicationContext)
    owner.start()
    view.addView(ComposeView(context).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
      setContent { Pip() }
    })
  }

  /**
   * Compose resolves its recomposer from the window's root view, not from [view], so the window holding [view] must
   * carry these owners. Call on that root before adding it to the WindowManager.
   */
  fun installOwners(windowRoot: View) {
    windowRoot.setViewTreeLifecycleOwner(owner)
    windowRoot.setViewTreeSavedStateRegistryOwner(owner)
  }

  fun setNumber(name: String, value: Float) = send(name, Value.Number(value))
  fun setBoolean(name: String, value: Boolean) = send(name, Value.Bool(value))
  fun setEnum(name: String, value: String) = send(name, Value.Enum(value))
  fun setString(name: String, value: String) = send(name, Value.Text(value))

  /** Sets [name] from text, converted to whatever type the file declares (number, boolean, enum or string). */
  fun setFromText(name: String, value: String) = send(name, Value.Auto(value))

  fun fire(name: String) {
    commands.tryEmit(Command.Fire(name))
  }

  /**
   * Plays [DOWNLOAD_FILE] when [fromDownload] and it is readable, else the bundled asset. Re-reads the file on every
   * call, so pushing a new file and calling this again swaps Pip without reinstalling.
   */
  fun reload(fromDownload: Boolean = true) {
    source.value = Source(fromDownload, source.value.generation + 1)
  }

  private fun send(name: String, value: Value) {
    sticky[name] = value
    commands.tryEmit(Command.Set(name, value))
  }

  @Composable
  private fun Pip() {
    val error = remember { mutableStateOf<Throwable?>(null) }
    val worker = rememberRiveWorkerOrNull(error)
    if (worker == null) {
      Log.e(TAG, "no Rive worker", error.value)
      return
    }
    val requested by source.collectAsState()
    val loaded by produceState<Loaded?>(null, requested) { value = withContext(Dispatchers.IO) { load(requested) } }
    val l = loaded ?: return
    key(l.source.generation) {
      when (val file = rememberRiveFile(RiveFileSource.Bytes(l.bytes), worker)) {
        is Result.Loading -> Unit
        is Result.Error -> Log.e(TAG, "${l.label} failed to load", file.throwable)
        is Result.Success -> when (val vmi = rememberViewModelInstanceResult(file.value)) {
          is Result.Loading -> Unit
          is Result.Error -> {
            Log.w(TAG, "${l.label}: no view model (${vmi.throwable.message}); playing undriven")
            Player(file.value, null, l.label)
          }
          is Result.Success -> Player(file.value, vmi.value, l.label)
        }
      }
    }
  }

  @Composable
  private fun Player(file: RiveFile, vmi: ViewModelInstance?, label: String) {
    if (vmi != null) {
      LaunchedEffect(vmi) {
        val vmName = runCatching { vmi.getViewModelName() }.getOrDefault(VIEW_MODEL)
        val props = runCatching { file.getViewModelProperties(vmName) }.getOrDefault(emptyList())
          .associate { it.name to it.type }
        Log.i(TAG, "playing $label, view model $vmName: ${props.entries.joinToString { "${it.key}:${it.value}" }}")
        val driver = Driver(vmi, props, label)
        commands
          .onSubscription { sticky.forEach { (name, value) -> driver.set(name, value) } }
          .collect { command ->
            when (command) {
              is Command.Set -> driver.set(command.name, command.value)
              is Command.Fire -> driver.fire(command.name)
            }
          }
      }
    }
    Rive(
      file = file,
      viewModelInstance = vmi,
      fit = Fit.Contain(),
      modifier = Modifier.fillMaxSize(),
    )
  }

  private fun load(source: Source): Loaded {
    if (source.fromDownload) {
      val f = File(DOWNLOAD_FILE)
      val bytes = runCatching { f.readBytes() }
      bytes.onSuccess { return Loaded(it, "$DOWNLOAD_FILE (${it.size} bytes)", source) }
      Log.w(
        TAG,
        "reload: can't read $DOWNLOAD_FILE (${bytes.exceptionOrNull()?.message}); playing the bundled file. " +
          "A file adb pushed there is readable once the app has all-files access: " +
          "adb shell appops set --uid dev.playground.pip MANAGE_EXTERNAL_STORAGE allow",
      )
    }
    val bytes = view.context.assets.open(ASSET).use { it.readBytes() }
    return Loaded(bytes, "assets/$ASSET", source)
  }

  /** Applies values to one loaded instance, checking each name and type against the file's view model. */
  private class Driver(val vmi: ViewModelInstance, val props: Map<String, PropertyDataType>, val label: String) {
    private val warned = mutableSetOf<String>()

    fun fire(name: String) {
      if (!check(name, "trigger", PropertyDataType.TRIGGER)) return
      apply("fire $name") { vmi.fireTrigger(name) }
    }

    fun set(name: String, value: Value) {
      val type = props[name]
      val resolved = if (value is Value.Auto) value.resolve(type) else value
      val ok = when (resolved) {
        is Value.Number -> check(name, "number", PropertyDataType.NUMBER)
        is Value.Bool -> check(name, "boolean", PropertyDataType.BOOLEAN)
        is Value.Enum -> check(name, "enum", PropertyDataType.ENUM)
        is Value.Text -> check(name, "string", PropertyDataType.STRING)
        null, is Value.Auto -> check(name, "text '${(value as Value.Auto).text}'", null)
      }
      if (!ok || resolved == null) return
      apply("set $name=$resolved") {
        when (resolved) {
          is Value.Number -> vmi.setNumber(name, resolved.value)
          is Value.Bool -> vmi.setBoolean(name, resolved.value)
          is Value.Enum -> vmi.setEnum(name, resolved.value)
          is Value.Text -> vmi.setString(name, resolved.value)
          is Value.Auto -> Unit
        }
      }
    }

    private fun check(name: String, wanted: String, expected: PropertyDataType?): Boolean {
      val actual = props[name]
      if (actual != null && actual == expected) return true
      if (warned.add(name)) {
        val why = if (actual == null) "has no property '$name'" else "declares '$name' as $actual"
        Log.w(TAG, "$label $why; ignoring $wanted writes to it")
      }
      return false
    }

    private inline fun apply(what: String, block: () -> Unit) {
      runCatching(block)
        .onSuccess { Log.i(TAG, "applied $what") }
        .onFailure { Log.e(TAG, "$what failed", it) }
    }
  }

  private sealed interface Value {
    data class Number(val value: Float) : Value
    data class Bool(val value: Boolean) : Value
    data class Enum(val value: String) : Value
    data class Text(val value: String) : Value

    /** Text from adb whose type is decided by the file. */
    data class Auto(val text: String) : Value {
      fun resolve(type: PropertyDataType?): Value? = when (type) {
        PropertyDataType.NUMBER -> text.toFloatOrNull()?.let(::Number)
        PropertyDataType.BOOLEAN -> text.toBooleanStrictOrNull()?.let(::Bool)
        PropertyDataType.ENUM -> Enum(text)
        PropertyDataType.STRING -> Text(text)
        else -> null
      }
    }
  }

  private sealed interface Command {
    data class Set(val name: String, val value: Value) : Command
    data class Fire(val name: String) : Command
  }

  private data class Source(val fromDownload: Boolean, val generation: Int)

  private class Loaded(val bytes: ByteArray, val label: String, val source: Source)

  /** A window added through WindowManager has no Activity, so Compose needs its own lifecycle and saved state. */
  private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
      savedState.performRestore(null)
      registry.currentState = Lifecycle.State.RESUMED
    }
  }

  companion object {
    const val TAG = "PipRive"
    const val ASSET = "pip/pip.riv"
    const val DOWNLOAD_FILE = "/sdcard/Download/pip.riv"
    private const val VIEW_MODEL = "Pip"
  }
}
