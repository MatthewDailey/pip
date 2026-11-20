package dev.playground.pip.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.playground.pip.conversation.PipMood
import dev.playground.pip.conversation.TalkState
import dev.playground.pip.ui.Capability
import dev.playground.pip.ui.Grant
import dev.playground.pip.ui.theme.Pip

/** Pip's words for each grant: a short name for the checklist, the question, and why he needs it. */
private data class Ask(val name: String, val question: String, val why: String)

private fun ask(c: Capability): Ask = when (c.title) {
  "Accessibility service" -> Ask("Screen helper", "Can I look at your screen?", "I read what's on screen to spot scam pages and messages, and it lets me step in during a call. Nothing leaves this phone.")
  "Call screening role" -> Ask("Calls", "Can I check who's calling?", "I look at each number before it rings and tell you if it's one I don't trust.")
  "Notification access" -> Ask("Notifications", "Can I read your notifications?", "Scam texts and chats show up there first. I only look for scams.")
  "Phone, call log and contacts" -> Ask("Phone", "Can I know when you're on a call?", "So I can listen for scam tricks during calls, and know when it's someone in your contacts.")
  "SMS" -> Ask("Texts", "Can I watch your texts?", "I read new texts only to spot scams. Nothing leaves this phone.")
  "Microphone" -> Ask("Microphone", "Can I hear you?", "So you can talk to me, and so I can hear scam tricks on a call when it's on speaker.")
  "On-device speech recognition" -> Ask("Speech", "Can I learn to understand speech?", "I need the phone's English speech pack, about 60 MB, to follow calls without sending audio anywhere.")
  "Warnings" -> Ask("Warnings", "Can I warn you?", "If something looks like a scam, I'll send you a notification you can't miss.")
  else -> Ask(c.title, c.title, c.why)
}

/** Grants Pip can ask for, in the order he asks. */
fun setupSteps(caps: List<Capability>) = caps.filter { it.grant != Grant.None }

/**
 * First-run setup, led by Pip: one grant per step, Pip says why before Android asks. "Not now" skips a step. When
 * every step is granted or skipped (including a phone where everything was already granted, like the dev emulator),
 * Pip shows the checklist with its ticks and one Continue, which calls [onDone] and lands on the conversation home.
 * Nothing shows until [loaded]: the checks are read in onResume, and an empty or stale list would look finished.
 */
@Composable
fun SetupScreen(capabilities: List<Capability>, loaded: Boolean, onGrant: (Capability) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
  val c = Pip.colors
  val steps = setupSteps(capabilities)
  val skipped = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
    mutableStateListOf<String>()
  }
  if (!loaded || steps.isEmpty()) {
    Box(modifier.fillMaxSize().background(c.bg))
    return
  }
  val current = steps.firstOrNull { !it.enabled && it.title !in skipped }
  val allOn = steps.all { it.enabled }
  val index = current?.let(steps::indexOf) ?: steps.lastIndex
  val words = when {
    current != null -> ask(current)
    allOn -> Ask("", "You're all set!", "Everything's on. I'll keep an eye out for scams from here.")
    else -> Ask("", "That's it for now", "I'll work with what I have. You can turn the rest on any time from settings.")
  }

  Column(modifier.fillMaxSize().background(c.bg).padding(horizontal = 24.dp)) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        if (current == null) "Setup done" else "Step ${index + 1} of ${steps.size}",
        style = MaterialTheme.typography.labelLarge, color = c.tx2, modifier = Modifier.weight(1f),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, _ ->
          Box(Modifier.width(18.dp).height(6.dp).clip(CircleShape).background(if (i <= index) c.pr else c.ln))
        }
      }
    }
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      PipFigure(PipMood.HAPPY, TalkState.IDLE, size = 180.dp)
      Spacer(Modifier.height(12.dp))
      Text(words.question, style = MaterialTheme.typography.headlineMedium, color = c.tx, textAlign = TextAlign.Center)
      Spacer(Modifier.height(8.dp))
      Text(words.why, style = MaterialTheme.typography.bodyLarge, color = c.tx2, textAlign = TextAlign.Center)
      Spacer(Modifier.height(22.dp))
      Column(
        Modifier.fillMaxWidth().background(c.sf, RoundedCornerShape(24.dp)).border(1.dp, c.ln, RoundedCornerShape(24.dp))
          .padding(horizontal = 16.dp, vertical = 6.dp),
      ) {
        steps.forEach { s ->
          Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ask(s).name, style = MaterialTheme.typography.labelLarge, color = c.tx, modifier = Modifier.weight(1f))
            when {
              s.enabled -> Icon(PipIcons.check, "On", tint = c.info, modifier = Modifier.size(20.dp))
              s == current -> Text("Now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.pr)
              s.title in skipped -> Text("Later", style = MaterialTheme.typography.labelMedium, color = c.tx2)
              else -> Text("Next", style = MaterialTheme.typography.labelMedium, color = c.tx2)
            }
          }
        }
      }
    }
    if (current == null) {
      Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.pr).clickable(onClick = onDone),
          contentAlignment = Alignment.Center,
        ) { Text("Continue", style = MaterialTheme.typography.titleMedium, color = c.onPr) }
      }
    } else Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.pr).clickable { onGrant(current) },
        contentAlignment = Alignment.Center,
      ) { Text("Yes, allow", style = MaterialTheme.typography.titleMedium, color = c.onPr) }
      Text(
        "Not now", style = MaterialTheme.typography.labelLarge, color = c.tx2,
        modifier = Modifier.padding(top = 8.dp).clip(CircleShape).clickable { skipped += current.title }.padding(horizontal = 16.dp, vertical = 10.dp),
      )
    }
  }
}
