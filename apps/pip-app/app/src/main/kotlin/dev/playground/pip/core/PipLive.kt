package dev.playground.pip.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.playground.pip.BuildConfig
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * A GPT-Live voice call with Pip over WebRTC.
 *
 * GPT-Live has no client token, so the SDP offer goes to jev-playground (`POST {PIP_SERVER_URL}/api/live/session`),
 * which posts it to OpenAI with the API key and Pip's instructions (plus the [TalkToPipRequest]'s context) and
 * returns the SDP answer. After that the phone
 * talks to OpenAI directly: mic audio out and Pip's voice back on the media tracks, events on the `oai-events` data
 * channel. Stopping sends `session.close` and waits up to 15 s for `session.closed`.
 * https://developers.openai.com/api/docs/guides/voice-webrtc?api=live
 */
class PipLive(
  private val context: Context,
  private val onState: (State) -> Unit,
  /** Live captions: `pip = true` for Pip's speech, false for the person's. Called on the main thread. */
  private val onTranscript: (pip: Boolean, delta: String) -> Unit = { _, _ -> },
) {
  sealed interface State {
    data object Idle : State
    data object Connecting : State
    data class Connected(val sessionId: String) : State
    data object Closing : State
    data class Ended(val message: String) : State
  }

  private val main = Handler(Looper.getMainLooper())
  private val io = Executors.newSingleThreadExecutor()
  private var factory: PeerConnectionFactory? = null
  private var peer: PeerConnection? = null
  private var events: DataChannel? = null
  private var mic: AudioTrack? = null
  private var closeTimeout: Runnable? = null
  @Volatile private var finished = false
  private val audio = context.getSystemService(AudioManager::class.java)
  /** What [speakerOn] changed, so [speakerOff] can put it back: the audio mode and the voice-call volume. */
  private var savedMode: Int? = null
  private var savedVolume: Int? = null

  fun start(request: TalkToPipRequest = TalkToPipRequest()) {
    finished = false
    post(State.Connecting)
    speakerOn()
    initFactoryOnce(context)
    val f = PeerConnectionFactory.builder()
      .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
      .createPeerConnectionFactory()
    factory = f

    val config = PeerConnection.RTCConfiguration(emptyList()).apply {
      sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
    val pc = f.createPeerConnection(config, observer(request)) ?: return fail("Couldn't create a WebRTC connection")
    peer = pc

    val track = f.createAudioTrack("pip-mic", f.createAudioSource(MediaConstraints()))
    mic = track
    pc.addTrack(track, listOf("pip"))

    // The data channel must exist before the offer so it's negotiated in it.
    events = pc.createDataChannel("oai-events", DataChannel.Init()).also { it.registerObserver(dataObserver()) }

    // Once the local description is set, ICE gathering runs; the offer is sent when it completes.
    pc.createOffer(sdp { offer -> pc.setLocalDescription(sdp { }, offer) }, MediaConstraints())
  }

  fun stop() {
    val dc = events
    if (dc == null || dc.state() != DataChannel.State.OPEN) return release(State.Ended("Call ended."))
    post(State.Closing)
    dc.send(DataChannel.Buffer(ByteBuffer.wrap("""{"type":"session.close"}""".toByteArray()), false))
    closeTimeout = Runnable { release(State.Ended("Ended without a final event from GPT-Live.")) }
      .also { main.postDelayed(it, 15_000) }
  }

  private fun observer(request: TalkToPipRequest) = object : PeerConnection.Observer {
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
      if (state == PeerConnection.IceGatheringState.COMPLETE) {
        val offer = peer?.localDescription?.description ?: return fail("No local SDP offer")
        io.execute { exchange(offer, request) }
      }
    }
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
      Log.i(TAG, "connection $state")
      if (state == PeerConnection.PeerConnectionState.FAILED) fail("Connection to GPT-Live failed")
    }
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    // Pip's voice arrives here; the audio device module plays remote audio tracks on its own.
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
  }

  /** Sends the offer to our server and applies OpenAI's answer. Runs on [io]. */
  private fun exchange(offer: String, request: TalkToPipRequest) {
    val url = URL("${BuildConfig.PIP_SERVER_URL.trim().trimEnd('/')}/api/live/session")
    val body = request.sessionBody(offer)
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 10_000
      readTimeout = 20_000
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      val bypass = BuildConfig.PIP_SERVER_BYPASS
      if (bypass.isNotEmpty()) setRequestProperty("x-vercel-protection-bypass", bypass)
    }
    try {
      conn.outputStream.use { it.write(body.toByteArray()) }
      val code = conn.responseCode
      val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) return fail("Server $code: ${raw.take(160)}")
      val answer = JSONObject(raw).getJSONObject("transport").getString("sdp")
      peer?.setRemoteDescription(sdp { }, SessionDescription(SessionDescription.Type.ANSWER, answer))
    } catch (e: Exception) {
      fail("Couldn't reach ${url.host}:${url.port}: ${e.message}")
    } finally {
      conn.disconnect()
    }
  }

  private fun dataObserver() = object : DataChannel.Observer {
    override fun onBufferedAmountChange(previous: Long) {}
    override fun onStateChange() {
      if (events?.state() == DataChannel.State.CLOSED && !finished) release(State.Ended("Disconnected."))
    }
    override fun onMessage(buffer: DataChannel.Buffer) {
      val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
      val event = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return
      when (event.optString("type")) {
        "session.started" -> post(State.Connected(event.optJSONObject("session")?.optString("id").orEmpty()))
        "session.closed" -> release(State.Ended("Call ended."))
        "session.output_transcript.delta" -> event.optString("delta").let { d -> main.post { onTranscript(true, d) } }
        "session.input_transcript.delta" -> event.optString("delta").let { d -> main.post { onTranscript(false, d) } }
        "error" -> Log.w(TAG, "GPT-Live error: $event")
        else -> Log.d(TAG, event.optString("type"))
      }
    }
  }

  private fun fail(message: String) {
    Log.w(TAG, message)
    release(State.Ended(message))
  }

  private fun release(final: State) {
    main.post {
      if (finished) return@post
      finished = true
      closeTimeout?.let(main::removeCallbacks)
      events?.unregisterObserver()
      events?.close()
      peer?.close()
      mic?.dispose()
      peer?.dispose()
      factory?.dispose()
      events = null; peer = null; mic = null; factory = null
      speakerOff()
      onState(final)
    }
  }

  private fun post(state: State) = main.post { onState(state) }

  /**
   * Pip talks on the loudspeaker at full call volume, like speakerphone: WebRTC plays on the voice-call path,
   * which otherwise goes to the earpiece. Undone by [speakerOff] when the call ends.
   */
  private fun speakerOn() {
    runCatching {
      savedMode = audio.mode
      audio.mode = AudioManager.MODE_IN_COMMUNICATION
      audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?.let { audio.setCommunicationDevice(it) }
    }.onFailure { Log.w(TAG, "speakerphone failed: ${it.message}") }
    // The call volume is kept per device, and the switch to the speaker lands a moment later: raise it once it has.
    main.postDelayed({
      if (finished) return@postDelayed
      runCatching {
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        Log.i(TAG, "speakerphone on: ${audio.communicationDevice?.type}, volume ${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}")
      }.onFailure { Log.w(TAG, "speaker volume failed: ${it.message}") }
    }, SPEAKER_SETTLE_MS)
  }

  private fun speakerOff() {
    runCatching {
      // Put the speaker's volume back while it's still the active device, then hand routing back.
      savedVolume?.let { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0) }
      audio.clearCommunicationDevice()
      savedMode?.let { audio.mode = it }
    }.onFailure { Log.w(TAG, "restoring audio failed: ${it.message}") }
    savedMode = null
    savedVolume = null
  }

  private fun sdp(onCreated: (SessionDescription) -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = onCreated(desc)
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) = fail("SDP: $error")
    override fun onSetFailure(error: String) = fail("SDP: $error")
  }

  companion object {
    private const val TAG = "PipLive"
    private const val SPEAKER_SETTLE_MS = 500L
    private var initialized = false

    private fun initFactoryOnce(context: Context) {
      if (initialized) return
      PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
      )
      initialized = true
    }
  }
}
