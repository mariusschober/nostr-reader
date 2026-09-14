package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.reader.app.data.LabelNorm
import com.reader.app.prefs.LabelColorKey
import com.reader.app.ui.theme.LocalDisplayPolicy
import com.reader.app.ui.theme.LocalReaderDark
import com.reader.app.ui.theme.labelAccent

/** Checked means every selected article; partial chips state the exact count. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable fun LabelsDialog(title: String, assignedCounts: Map<String, Int>, totalDocs: Int,
  suggestions: List<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors,
  /** Normalized label name → palette key, for the badge identity on each chip. */
  palette: Map<String, LabelColorKey> = emptyMap(),
  /** Optional entry to the full label editor; null hides it. */
  onManageLabels: (() -> Unit)? = null,
) {
  var text by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  fun add() {
    val normalized = LabelNorm.normalize(text)
    if (normalized == null) { error = "Use 1–50 characters, without commas, quotes or backslashes."; return }
    if (assignedCounts.values.count { it >= totalDocs } >= LabelNorm.MAX_PER_DOC && normalized !in assignedCounts.keys.mapNotNull(LabelNorm::normalize)) {
      error = "An article can have up to 20 labels. Remove one first."; return
    }
    onToggle(text); text = ""; error = null
  }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().heightIn(max = 530.dp).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Done") }
      }
      Text("Labels group related reading across shelves. You can give an article several labels.", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
      if (assignedCounts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        assignedCounts.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, n) ->
          val full = n >= totalDocs
          FilterChip(full, { onToggle(name) }, { Text(if (full) name else "$name · $n/$totalDocs") },
            leadingIcon = { PaletteDot(name, palette) },
            modifier = Modifier.semantics { contentDescription = "Label $name"; stateDescription = if (full) "On all selected articles" else "On $n of $totalDocs articles" })
        }
      }
      OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth(),
        label = { Text("Find or create a label") }, singleLine = true, isError = error != null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
      error?.let { Text(it, color = colors.error) }
      if (text.isNotBlank()) Button(onClick = ::add) { Text(if (suggestions.any { LabelNorm.normalize(it) == LabelNorm.normalize(text) }) "Apply label" else "Create & apply label") }
      suggestions.filterNot { it in assignedCounts }.filter { it.contains(text.trim(), true) }.take(12).forEach { suggestion ->
        TextButton(onClick = { onToggle(suggestion); text = ""; error = null }) {
          PaletteDot(suggestion, palette); Spacer(Modifier.width(6.dp)); Text(suggestion)
        }
      }
      onManageLabels?.let { manage ->
        HorizontalDivider(Modifier.padding(top = 8.dp))
        TextButton(onClick = manage) { Text("Manage labels") }
      }
    }
  }
}

/** Small palette cue so the assignment sheet shows the same badge identity as the library. */
@Composable
private fun PaletteDot(name: String, palette: Map<String, LabelColorKey>) {
  val mono = LocalDisplayPolicy.current.monochrome
  val dark = LocalReaderDark.current
  val key = palette[LabelNorm.normalize(name) ?: name] ?: LabelColorKey.NEUTRAL
  Box(
    Modifier.size(12.dp).clip(CircleShape)
      .background(if (mono) MaterialTheme.colorScheme.onSurface else labelAccent(key, dark)),
  )
}
