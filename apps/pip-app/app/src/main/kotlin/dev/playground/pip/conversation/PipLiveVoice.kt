package dev.playground.pip.conversation

import android.content.Context
import dev.playground.pip.core.PipLive
import dev.playground.pip.core.TalkToPipRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [LiveVoice] over [PipLive], the GPT-Live WebRTC call that jev-playground's `/api/live/session` sets up. */
class PipLiveVoice(context: Context) : LiveVoice {
  private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
  override val state: StateFlow<LiveState> = _state.asStateFlow()

  private val live = PipLive(context.applicationContext, onState = { s ->
    _state.value = when (s) {
      PipLive.State.Idle -> LiveState.Idle
      PipLive.State.Connecting -> LiveState.Connecting
      is PipLive.State.Connected -> LiveState.Live
      PipLive.State.Closing -> LiveState.Closing
      is PipLive.State.Ended -> LiveState.Ended(s.message)
    }
  })

  override fun start(request: TalkToPipRequest) = live.start(request)
  override fun stop() = live.stop()
}
