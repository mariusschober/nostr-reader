package com.reader.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.prefs.*
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.fontFor
import com.reader.app.ui.theme.readerColors

@Composable
fun AppearanceSheet(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onClose: () -> Unit) {
  val c = readerColors(settings.background)
  AlertDialog(
    onDismissRequest = onClose,
    containerColor = c.background,
    title = { Text("Appearance", fontFamily = ReaderFonts.Ui, color = c.text) },
    text = {
      Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        Text("Font", fontFamily = ReaderFonts.Ui, color = c.secondary)
        ArticleFont.entries.forEach { f ->
          val label = when (f) {
            ArticleFont.NEWSREADER -> "Newsreader"
            ArticleFont.CRIMSON_PRO -> "Crimson Pro"
            ArticleFont.ASUL -> "Asul"
            ArticleFont.ATKINSON -> "Atkinson Hyperlegible"
            ArticleFont.ABEEZEE -> "ABeeZee"
          }
          Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.font == f, role = Role.RadioButton, onClick = { onChange(settings.copy(font = f)) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
              selected = settings.font == f, onClick = null,
              colors = RadioButtonDefaults.colors(selectedColor = c.text, unselectedColor = c.secondary),
            )
            Text(label, fontFamily = fontFor(f), color = c.text)
          }
        }
        Spacer(Modifier.height(8.dp))
        Text("Size: ${settings.fontSizeSp.toInt()}", fontFamily = ReaderFonts.Ui, color = c.secondary)
        Slider(
          value = settings.fontSizeSp, onValueChange = { onChange(settings.copy(fontSizeSp = it)) },
          valueRange = 14f..32f,
          colors = SliderDefaults.colors(thumbColor = c.text, activeTrackColor = c.text, inactiveTrackColor = c.divider),
        )
        Text("Margins", fontFamily = ReaderFonts.Ui, color = c.secondary)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
          ArticleMargin.entries.forEach { m ->
            FilterChip(
              selected = settings.margin == m, onClick = { onChange(settings.copy(margin = m)) },
              label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, fontFamily = ReaderFonts.Ui) },
              modifier = Modifier.padding(end = 8.dp),
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = c.text, selectedLabelColor = c.background,
                containerColor = c.background, labelColor = c.text,
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = c.divider, selectedBorderColor = c.text,
                enabled = true, selected = settings.margin == m,
              ),
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Text("Background", fontFamily = ReaderFonts.Ui, color = c.secondary)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
          ArticleBackground.entries.forEach { b ->
            FilterChip(
              selected = settings.background == b, onClick = { onChange(settings.copy(background = b)) },
              label = { Text(when (b) { ArticleBackground.FOLLOW_APP -> "Follow system"; ArticleBackground.PAPER -> "Paper"; ArticleBackground.SOFT -> "Soft"; ArticleBackground.INK -> "Ink"; ArticleBackground.BLACK -> "Black" }, fontFamily = ReaderFonts.Ui, maxLines = 1) },
              modifier = Modifier.padding(end = 8.dp),
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = c.text, selectedLabelColor = c.background,
                containerColor = c.background, labelColor = c.text,
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = c.divider, selectedBorderColor = c.text,
                enabled = true, selected = settings.background == b,
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
        Text("Done", fontFamily = ReaderFonts.Ui)
      }
    },
  )
}
