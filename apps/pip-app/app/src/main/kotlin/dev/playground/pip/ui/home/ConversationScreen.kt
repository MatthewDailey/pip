package dev.playground.pip.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.playground.pip.conversation.ConversationState
import dev.playground.pip.conversation.LiveState
import dev.playground.pip.conversation.PipMood
import dev.playground.pip.conversation.Speaker
import dev.playground.pip.conversation.TalkState
import dev.playground.pip.conversation.Turn
import dev.playground.pip.ui.theme.Pip

/** What the screen can ask for; the activity wires these to the controller, permissions and navigation. */
data class ConversationActions(
  val talk: () -> Unit,
  val stop: () -> Unit,
  val close: () -> Unit,
  val send: (String) -> Unit,
  val openSettings: () -> Unit,
  val fixSetup: () -> Unit,
  /** Starts or ends the live voice session; null hides it (exploratory off). */
  val live: (() -> Unit)? = null,
)

/**
 * Direction A, "Quiet home": Pip in his halo, one line of status and a 104 dp Talk button. Talking keeps the same
 * screen: listening → thinking → replying shows as a calm transcript under a smaller Pip, and typing is the
 * fallback, never the default.
 */
@Composable
fun ConversationScreen(
  state: ConversationState,
  mood: PipMood,
  protectedFully: Boolean,
  actions: ConversationActions,
  modifier: Modifier = Modifier,
) {
  val c = Pip.colors
  var typing by rememberSaveable { mutableStateOf(false) }
  val quiet = state.turns.isEmpty() && state.talk == TalkState.IDLE && !state.micOpen
  val talking = !quiet

  Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
    TopBar(talking, state, onClose = { typing = false; actions.close() }, onSettings = actions.openSettings)

    if (state.turns.isEmpty()) {
      Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        PipFigure(mood, state.talk, size = 248.dp)
        Spacer(Modifier.height(24.dp))
        when {
          state.talk == TalkState.LISTENING && state.heard.isNotBlank() ->
            Text("“${state.heard}…”", style = MaterialTheme.typography.titleLarge, color = c.tx, textAlign = TextAlign.Center)
          state.talk == TalkState.LISTENING -> {
            Text("Just talk. I'm listening.", style = MaterialTheme.typography.titleLarge, color = c.tx, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("I stop after 30 quiet seconds.", style = MaterialTheme.typography.bodySmall, color = c.tx2)
          }
          else -> QuietStatus(protectedFully, actions.fixSetup)
        }
        if (quiet && !typing) {
          Spacer(Modifier.height(28.dp))
          Suggestions(state.suggestions, actions.send, center = true)
        }
      }
    } else {
      Transcript(state, mood, Modifier.weight(1f), onSuggestion = actions.send)
    }

    state.notice?.let { Notice(it) }
    if (state.talk == TalkState.LISTENING) ListeningDisclosure(state.onDeviceSpeech)

    if (typing) {
      TypeBar(onSend = actions.send, onMic = { typing = false; actions.talk() }, busy = state.talk == TalkState.THINKING)
    } else {
      Dock(state, onTalk = actions.talk, onStop = actions.stop, onType = { actions.close(); typing = true }, onLive = actions.live)
    }
  }
  LaunchedEffect(state.notice) { if (state.notice != null) typing = true }
}

@Composable
private fun TopBar(talking: Boolean, state: ConversationState, onClose: () -> Unit, onSettings: () -> Unit) {
  val c = Pip.colors
  Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    if (talking) {
      IconButton(onClose) { Icon(PipIcons.close, "Done talking", tint = c.tx2) }
    } else {
      Text("Pip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.tx, modifier = Modifier.padding(start = 12.dp))
    }
    Text(
      when {
        !talking -> ""
        state.talk == TalkState.LISTENING -> "Listening…"
        state.talk == TalkState.THINKING -> "Thinking…"
        else -> "Pip"
      },
      style = MaterialTheme.typography.labelLarge, color = c.tx2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
    )
    IconButton(onSettings) { Icon(PipIcons.gear, "Settings and about", tint = c.tx2) }
  }
}

@Composable
private fun QuietStatus(protectedFully: Boolean, fixSetup: () -> Unit) {
  val c = Pip.colors
  Text(if (protectedFully) "All quiet." else "I can't see everything yet.", style = MaterialTheme.typography.headlineMedium, color = c.tx, textAlign = TextAlign.Center)
  Spacer(Modifier.height(8.dp))
  if (protectedFully) {
    Text("I'm watching your calls, texts and notifications.", style = MaterialTheme.typography.bodyLarge, color = c.tx2, textAlign = TextAlign.Center)
  } else {
    Text(
      "Finish setup so I can watch your calls and texts ›",
      style = MaterialTheme.typography.labelLarge, color = c.pr, textAlign = TextAlign.Center,
      modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = fixSetup).padding(8.dp),
    )
  }
}

@Composable
private fun Transcript(state: ConversationState, mood: PipMood, modifier: Modifier, onSuggestion: (String) -> Unit) {
  val c = Pip.colors
  val list = rememberLazyListState()
  val shown = state.turns.takeLast(6)
  LaunchedEffect(shown.size, shown.lastOrNull()?.text?.length, state.heard, state.talk) {
    list.animateScrollToItem(Int.MAX_VALUE.coerceAtMost(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1))
  }
  Column(modifier.fillMaxWidth()) {
    PipFigure(mood, state.talk, size = 168.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
    LazyColumn(
      state = list,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(shown, key = { it.id }) { TurnBubble(it) }
      if (state.talk == TalkState.LISTENING && state.heard.isNotBlank()) {
        item(key = "heard") { YouBubble("${state.heard}…", Modifier.alpha(0.6f)) }
      }
      if (state.talk == TalkState.THINKING) item(key = "dots") { ThinkingDots() }
      if (state.speakingAloud) {
        item(key = "aloud") {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PipIcons.speaker, null, tint = c.tx2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Speaking aloud", style = MaterialTheme.typography.bodySmall, color = c.tx2)
          }
        }
      }
      if (state.talk == TalkState.IDLE && state.suggestions.isNotEmpty()) {
        item(key = "chips") { Suggestions(state.suggestions, onSuggestion, center = false) }
      }
    }
  }
}

@Composable
private fun TurnBubble(t: Turn) = if (t.speaker == Speaker.YOU) YouBubble(t.text) else PipBubble(t.text)

@Composable
private fun YouBubble(text: String, modifier: Modifier = Modifier) {
  val c = Pip.colors
  Row(Modifier.fillMaxWidth().padding(start = 56.dp), horizontalArrangement = Arrangement.End) {
    Text(
      text, style = MaterialTheme.typography.bodyLarge, color = c.tx,
      modifier = modifier.background(c.sf2, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
    )
  }
}

@Composable
private fun PipBubble(text: String) {
  val c = Pip.colors
  val shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
  Row(Modifier.fillMaxWidth().padding(end = 32.dp)) {
    Text(
      text, style = MaterialTheme.typography.bodyLarge, color = c.tx,
      modifier = Modifier.background(c.sf, shape).border(1.dp, c.ln, shape).padding(horizontal = 16.dp, vertical = 12.dp)
        .semantics { contentDescription = "Pip says: $text" },
    )
  }
}

@Composable
private fun ThinkingDots() {
  val c = Pip.colors
  val t = rememberInfiniteTransition(label = "dots")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
    repeat(3) { i ->
      val a by t.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse), label = "dot$i")
      Box(Modifier.size(12.dp).clip(CircleShape).background(c.pr.copy(alpha = a)))
    }
  }
}

@Composable
private fun Suggestions(items: List<String>, onPick: (String) -> Unit, center: Boolean) {
  val c = Pip.colors
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    items.forEach { s ->
      Text(
        s, style = MaterialTheme.typography.labelLarge, color = c.tx,
        modifier = Modifier.clip(CircleShape).border(1.5.dp, c.ln, CircleShape).clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
      )
    }
  }
}

@Composable
private fun Notice(text: String) {
  val c = Pip.colors
  Text(
    text, style = MaterialTheme.typography.bodyMedium, color = c.cau,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).background(c.cauC, RoundedCornerShape(16.dp)).padding(12.dp),
  )
}

@Composable
private fun ListeningDisclosure(onDevice: Boolean) {
  val c = Pip.colors
  Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).background(c.sf, RoundedCornerShape(20.dp)).border(1.dp, c.ln, RoundedCornerShape(20.dp)).padding(12.dp)) {
    Text("When Pip listens", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.tx)
    Text(
      if (onDevice) "Only while you can see me. Your words become text on this phone."
      else "Only while you can see me. Your phone's speech service turns your words into text.",
      style = MaterialTheme.typography.bodySmall, color = c.tx2)
  }
}

@Composable
private fun Dock(state: ConversationState, onTalk: () -> Unit, onStop: () -> Unit, onType: () -> Unit, onLive: (() -> Unit)?) {
  val c = Pip.colors
  val inLive = state.live == LiveState.Connecting || state.live == LiveState.Live
  if (inLive && onLive != null) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      TalkCircle(PipIcons.stop, "End live call", 104.dp, onClick = onLive)
      Spacer(Modifier.height(10.dp))
      Text(if (state.live == LiveState.Live) "Live with Pip. Just talk." else "Calling Pip…", style = MaterialTheme.typography.labelLarge, color = c.tx2)
    }
    return
  }
  val listening = state.talk == TalkState.LISTENING
  val busy = state.talk == TalkState.THINKING || state.talk == TalkState.SPEAKING
  Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    TalkCircle(
      icon = if (listening || busy) PipIcons.stop else PipIcons.mic,
      label = when { listening -> "Stop listening"; busy -> "Stop Pip"; else -> "Talk to Pip" },
      size = 104.dp,
      onClick = if (listening || busy) onStop else onTalk,
    )
    Spacer(Modifier.height(10.dp))
    Text(
      when { listening -> "Tap when you're done"; busy -> "Tap to stop"; else -> "Talk to Pip" },
      style = if (listening || busy) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
      color = if (listening || busy) c.tx2 else c.tx,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable(onClick = onType).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Icon(PipIcons.keyboard, null, tint = c.tx2, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text("Type instead", style = MaterialTheme.typography.labelLarge, color = c.tx2)
    }
    if (onLive != null && !listening && !busy) {
      Text(
        "Live voice (GPT-Live, exploratory)", style = MaterialTheme.typography.labelMedium, color = c.pr,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onLive).padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

@Composable
private fun TalkCircle(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit, enabled: Boolean = true) {
  val c = Pip.colors
  Box(
    Modifier.size(size).clip(CircleShape).background(c.pr).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, null, tint = c.onPr, modifier = Modifier.size(size * 0.38f))
  }
}

@Composable
private fun TypeBar(onSend: (String) -> Unit, onMic: () -> Unit, busy: Boolean) {
  val c = Pip.colors
  var text by rememberSaveable { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  val submit = { if (text.isNotBlank() && !busy) { onSend(text); text = "" } }
  Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      placeholder = { Text("Ask Pip anything…", color = c.tx2) },
      textStyle = MaterialTheme.typography.bodyLarge,
      shape = RoundedCornerShape(28.dp),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.pr, unfocusedBorderColor = c.ln, cursorColor = c.pr),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { submit() }),
      modifier = Modifier.weight(1f).focusRequester(focus),
    )
    Spacer(Modifier.width(10.dp))
    if (text.isNotBlank()) {
      TalkCircle(PipIcons.send, "Send", 56.dp, onClick = submit, enabled = !busy)
    } else {
      TalkCircle(PipIcons.mic, "Talk instead", 56.dp, onClick = onMic)
    }
  }
}
