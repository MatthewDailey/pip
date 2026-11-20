package dev.playground.pip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.playground.pip.core.GuardEvent
import dev.playground.pip.core.Source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun PlaygroundScreen(
  capabilities: List<Capability>,
  events: List<GuardEvent>,
  onGrant: (Capability) -> Unit,
  onTest: (String) -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var filter by remember { mutableStateOf<Source?>(null) }
  var flaggedOnly by remember { mutableStateOf(false) }
  var testText by remember { mutableStateOf("") }
  val shown = events.filter { (filter == null || it.source == filter) && (!flaggedOnly || it.score > 0) }

  LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Text("Pip playground", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
    item { PipCallCard() }
    item { Text("Signals", style = MaterialTheme.typography.titleMedium) }
    items(capabilities, key = { it.title }) { CapabilityRow(it, onGrant) }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Try the rules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(testText, { testText = it }, Modifier.fillMaxWidth(), placeholder = { Text("Paste a message or call line") }, minLines = 2)
        Button({ onTest(testText); testText = "" }, enabled = testText.isNotBlank()) { Text("Score it") }
      }
    }

    item {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Event feed (${shown.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClear) { Text("Clear") }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        FilterChip(flaggedOnly, { flaggedOnly = !flaggedOnly }, label = { Text("Flagged") })
        val sources = events.map { it.source }.distinct()
        FilterChip(filter == null, { filter = null }, label = { Text("All") })
        sources.forEach { s -> FilterChip(filter == s, { filter = if (filter == s) null else s }, label = { Text(s.name.lowercase().replace('_', ' ')) }) }
      }
    }
    items(shown, key = { "${it.time}-${it.source}-${it.title.hashCode()}-${it.detail.hashCode()}" }) { EventRow(it) }
    item { Spacer(Modifier.size(32.dp)) }
  }
}

@Composable
private fun CapabilityRow(c: Capability, onGrant: (Capability) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Spacer(Modifier.size(10.dp).clip(CircleShape).background(if (c.enabled) Color(0xFF2E7D32) else Color(0xFFC62828)))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(c.title, fontWeight = FontWeight.Medium)
      Text(c.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!c.enabled && c.grant != Grant.None) TextButton({ onGrant(c) }) { Text("Turn on") }
  }
}

@Composable
private fun EventRow(e: GuardEvent) {
  val tint = when {
    e.score >= 5 -> Color(0xFFFFCDD2)
    e.score > 0 -> Color(0xFFFFF3C4)
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  Card(colors = CardDefaults.cardColors(containerColor = tint), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(10.dp)) {
      Row {
        Text("${timeFmt.format(Date(e.time))}  ${e.source.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), color = Color.DarkGray)
        if (e.score > 0) Text("risk ${e.score}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.DarkGray)
      }
      Text(e.title, fontWeight = FontWeight.Medium, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
      e.app?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray) }
      if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Color.Black, maxLines = 6, overflow = TextOverflow.Ellipsis)
      if (e.rules.isNotEmpty()) Text(e.rules.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
    }
  }
}
