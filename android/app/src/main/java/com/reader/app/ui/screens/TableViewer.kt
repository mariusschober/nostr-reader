package com.reader.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reader.app.core.RenderedProjection
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.theme.ReaderColors
import com.reader.app.ui.theme.fontFor

/** Readable-size inspection for wide tables; dismissing preserves the native article position. */
@Composable
internal fun TableViewer(projection: RenderedProjection, selected: Int, settings: ReaderSettings, colors: ReaderColors,
                         onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
  val table = projection.tables.getOrNull(selected) ?: return
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.text) {
      Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
          TextButton(onClick = onDismiss) { Text("Close table") }
          Spacer(Modifier.weight(1f))
          Text("${selected + 1} / ${projection.tables.size}", Modifier.padding(12.dp))
        }
        Text("Scroll sideways to see every column. Long-press text to copy.",
          style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
          Column(Modifier.padding(16.dp)) {
            table.rows.forEachIndexed { rowIndex, row ->
              Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEach { cell ->
                  Text(projection.text.substring(cell.start, cell.end),
                    fontFamily = fontFor(settings.font), fontSize = settings.fontSizeSp.sp,
                    fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                    color = colors.text,
                    modifier = Modifier.width(176.dp).fillMaxHeight().border(.5.dp, colors.divider).padding(12.dp))
                }
              }
            }
          }
        }
        if (projection.tables.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(enabled = selected > 0, onClick = { onSelect(selected - 1) }) { Text("Previous table") }
          TextButton(enabled = selected < projection.tables.lastIndex, onClick = { onSelect(selected + 1) }) { Text("Next table") }
        }
      }
    }
  }
}
