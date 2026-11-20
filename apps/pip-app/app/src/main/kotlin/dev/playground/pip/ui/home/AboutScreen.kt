package dev.playground.pip.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.playground.pip.conversation.PipMood
import dev.playground.pip.conversation.TalkState
import dev.playground.pip.ui.Capability
import dev.playground.pip.ui.Grant
import dev.playground.pip.ui.theme.Pip

/**
 * Settings and About: which protections are on, set up again, and the privacy line. A long press on Pip reveals the
 * developer section, which holds [dev.playground.pip.core.PipFlags.exploratory] and the way into the playground.
 */
@Composable
fun AboutScreen(
  capabilities: List<Capability>,
  version: String,
  exploratory: Boolean,
  onExploratory: (Boolean) -> Unit,
  onGrant: (Capability) -> Unit,
  onSetupAgain: () -> Unit,
  onPlayground: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val c = Pip.colors
  var developer by rememberSaveable { mutableStateOf(exploratory) }

  Column(modifier.fillMaxSize().background(c.bg)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onBack) { Icon(PipIcons.back, "Back", tint = c.tx2) }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PipFigure(PipMood.HAPPY, TalkState.IDLE, size = 150.dp, onLongPress = { developer = !developer })
        Text("Pip", style = MaterialTheme.typography.headlineMedium, color = c.tx)
        Text("Your scam guard. Version $version", style = MaterialTheme.typography.bodySmall, color = c.tx2)
      }
      Spacer(Modifier.height(24.dp))
      Text("What I'm watching", style = MaterialTheme.typography.titleMedium, color = c.tx)
      Spacer(Modifier.height(8.dp))
      Card {
        setupSteps(capabilities).forEach { cap ->
          Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(cap.title, style = MaterialTheme.typography.labelLarge, color = c.tx, modifier = Modifier.weight(1f))
            if (cap.enabled) {
              Icon(PipIcons.check, "On", tint = c.info, modifier = Modifier.size(20.dp))
            } else if (cap.grant != Grant.None) {
              Text(
                "Turn on", style = MaterialTheme.typography.labelLarge, color = c.pr,
                modifier = Modifier.clip(CircleShape).clickable { onGrant(cap) }.padding(horizontal = 10.dp, vertical = 6.dp),
              )
            }
          }
        }
      }
      Text(
        "Set up again", style = MaterialTheme.typography.labelLarge, color = c.pr,
        modifier = Modifier.padding(top = 8.dp).clip(CircleShape).clickable(onClick = onSetupAgain).padding(horizontal = 4.dp, vertical = 8.dp),
      )
      Spacer(Modifier.height(16.dp))
      Text(
        "Scoring, transcripts and history stay on this phone. In this early version my answers come from this phone too; your phone's own speech service hears you when you talk to me.",
        style = MaterialTheme.typography.bodyMedium, color = c.tx2,
      )
      if (developer) {
        Spacer(Modifier.height(24.dp))
        Text("Developer", style = MaterialTheme.typography.titleMedium, color = c.tx)
        Spacer(Modifier.height(8.dp))
        Card {
          Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Exploratory features", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.tx)
              Text("Wandering Pip, idle bubbles, roam, showreels, generic warnings, the playground", style = MaterialTheme.typography.bodySmall, color = c.tx2)
            }
            Switch(exploratory, onExploratory, colors = SwitchDefaults.colors(checkedTrackColor = c.pr, checkedThumbColor = c.onPr))
          }
          if (exploratory) {
            Text(
              "Open the playground ›", style = MaterialTheme.typography.labelLarge, color = c.pr,
              modifier = Modifier.clip(CircleShape).clickable(onClick = onPlayground).padding(vertical = 10.dp),
            )
          }
        }
      }
      Spacer(Modifier.height(32.dp))
    }
  }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  val c = Pip.colors
  Column(
    Modifier.fillMaxWidth().background(c.sf, RoundedCornerShape(24.dp)).border(1.dp, c.ln, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
  ) { content() }
}
