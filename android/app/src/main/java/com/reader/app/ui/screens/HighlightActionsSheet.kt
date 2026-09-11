package com.reader.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reader.app.data.HighlightEntity
import com.reader.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun HighlightActionsSheet(quote: HighlightEntity, overlapping: Boolean = false,
  onNextOverlap: () -> Unit = {}, onColor: (String) -> Unit, onImportant: () -> Unit,
  onShare: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
  val c = appColors()
  val dark = LocalReaderDark.current
  val mono = com.reader.app.ui.theme.LocalDisplayPolicy.current.monochrome
  val context = LocalContext.current
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Highlight", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Done") }
      }
      if (overlapping) TextButton(onClick = onNextOverlap) { Text("Next overlapping highlight") }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        HighlightColor.entries.forEach { color -> IconToggleButton(quote.color == color.name, { onColor(color.name) },
          modifier = Modifier.size(52.dp).semantics { contentDescription = "Highlight color ${color.label}" }) {
          Box(Modifier.size(30.dp).background(if (mono) c.surface else color.background(dark), CircleShape).border(if (quote.color == color.name) 2.dp else 1.dp, c.secondary, CircleShape), contentAlignment = Alignment.Center) {
            if (quote.color == color.name) Icon(Icons.Default.Check, null, tint = if (mono) c.text else HighlightColor.text(dark), modifier = Modifier.size(18.dp))
          }
        } }
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Important", modifier = Modifier.weight(1f))
        Switch(quote.important, { onImportant() }, modifier = Modifier.semantics { contentDescription = "Important highlight" })
      }
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("Highlight", quote.quote)); onDismiss() }) { Text("Copy") }
        TextButton(onClick = onShare) { Text("Share") }
        TextButton(onClick = onRemove) { Text("Remove", color = c.error) }
      }
    }
  }
}
