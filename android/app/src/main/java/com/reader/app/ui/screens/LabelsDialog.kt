package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.ui.theme.ReaderFonts

/**
 * Assign labels to one or several articles. Union semantics for batches:
 * a chip checked means every selected article has it; tapping adds it to
 * those missing it, or removes it from all when everyone has it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelsDialog(
  title: String,
  assignedCounts: Map<String, Int>,
  totalDocs: Int,
  suggestions: List<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors,
) {
  var text by remember { mutableStateOf("") }
  val matches = remember(text, suggestions, assignedCounts) {
    val shown = assignedCounts.keys.toMutableSet()
    (suggestions + assignedCounts.keys).distinct()
      .filter { it.contains(text.trim(), ignoreCase = true) }
      .sortedWith(compareBy<String> { it !in shown }.thenBy { it.lowercase(java.util.Locale.getDefault()) })
      .take(8)
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.background,
    title = { Text(title, fontFamily = ReaderFonts.Ui, color = colors.text) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        if (assignedCounts.isNotEmpty()) {
          FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            assignedCounts.toList().sortedBy { it.first.lowercase(java.util.Locale.getDefault()) }.forEach { (name, n) ->
              val full = totalDocs > 0 && n >= totalDocs
              FilterChip(
                selected = full, onClick = { onToggle(name) },
                label = { Text(if (totalDocs > 1 && !full) "$name ($n)" else name, fontFamily = ReaderFonts.Ui) },
                modifier = Modifier.semantics {
                  contentDescription = "Label $name"
                  stateDescription = if (full) "On all selected" else "On $n of $totalDocs"
                },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = colors.text, selectedLabelColor = colors.background,
                  containerColor = colors.background, labelColor = colors.text,
                ),
              )
            }
          }
          Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
          value = text, onValueChange = { text = it },
          placeholder = { Text("New label…", fontFamily = ReaderFonts.Ui) },
          modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "New label name" },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            val clean = text.trim()
            if (clean.length >= 2) {
              onToggle(clean)
              text = ""
            }
          }),
        )
        matches.filterNot { it in assignedCounts }.forEach { suggestion ->
          TextButton(onClick = { onToggle(suggestion); text = "" }) {
            Text(suggestion, fontFamily = ReaderFonts.Ui, color = colors.text)
          }
        }
        Text("Labels are topics, not folders — an article can wear several.",
          fontFamily = ReaderFonts.Ui, fontSize = 12.sp, color = colors.secondary)
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.text)) {
        Text("Done", fontFamily = ReaderFonts.Ui)
      }
    },
  )
}
