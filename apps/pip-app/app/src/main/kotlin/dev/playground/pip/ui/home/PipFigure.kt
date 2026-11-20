package dev.playground.pip.ui.home

import android.view.ViewGroup
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.playground.pip.conversation.PipMood
import dev.playground.pip.conversation.TalkState
import dev.playground.pip.ui.PipRive
import dev.playground.pip.ui.theme.Pip

/** The rig's `mood` enum has five values; the escalation moods borrow the nearest one. */
fun PipMood.rigMood(): String = when (this) {
  PipMood.NEUTRAL -> "neutral"
  PipMood.HAPPY -> "happy"
  PipMood.SAD, PipMood.CONCERNED -> "sad"
  PipMood.ANGRY -> "angry"
  PipMood.SURPRISED, PipMood.WARY, PipMood.ALARMED -> "surprised"
}

/**
 * Pip, big and animated by the Rive rig, in his halo. Listening pulses rings around the halo; thinking glances up
 * and to the side; speaking sets the rig's `talking`. A long press reaches [onLongPress] (the About screen's
 * developer switch); a tap pokes him.
 */
@Composable
fun PipFigure(
  mood: PipMood,
  talk: TalkState,
  size: Dp,
  modifier: Modifier = Modifier,
  onLongPress: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val rive = remember { PipRive(context) }
  val colors = Pip.colors

  LaunchedEffect(mood) { rive.setEnum("mood", mood.rigMood()) }
  LaunchedEffect(talk) {
    rive.setBoolean("talking", talk == TalkState.SPEAKING)
    val thinking = talk == TalkState.THINKING
    rive.setNumber("lookY", if (thinking) -0.8f else 0f)
    rive.setNumber("lookX", if (thinking) 0.4f else 0f)
    if (talk == TalkState.LISTENING) rive.setNumber("browRaise", 0.4f) else rive.setNumber("browRaise", 0f)
  }

  val pulse = rememberInfiniteTransition(label = "rings")
  val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "ring")
  val listening = talk == TalkState.LISTENING

  Box(modifier.size(size), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val r = this.size.minDimension / 2
      if (listening) {
        drawCircle(colors.pr.copy(alpha = 0.06f), radius = r)
        drawCircle(colors.pr.copy(alpha = 0.12f), radius = r * (0.84f + 0.06f * ring))
      }
      drawCircle(colors.sf2, radius = r * 0.74f)
    }
    AndroidView(
      factory = { rive.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
      modifier = Modifier.fillMaxSize().padding(size * 0.12f),
      update = { v ->
        v.setOnClickListener { rive.fire("poke") }
        v.setOnLongClickListener { onLongPress?.invoke(); onLongPress != null }
      },
    )
  }
  DisposableEffect(Unit) { onDispose { (rive.view.parent as? ViewGroup)?.removeView(rive.view) } }
}
