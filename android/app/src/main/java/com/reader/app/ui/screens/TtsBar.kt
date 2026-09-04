package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reader.app.tts.TtsController
import com.reader.app.ui.theme.ReaderColors
import com.reader.app.ui.theme.ReaderFonts

/** Small bottom media bar while TTS is active. Article stays visible. */
@Composable
fun TtsBar(
  state: TtsController.State,
  colors: ReaderColors,
  onPrev: () -> Unit,
  onToggle: () -> Unit,
  onNext: () -> Unit,
  onSpeed: (Float) -> Unit,
  onClose: () -> Unit,
) {
  Surface(color = colors.surface, tonalElevation = 2.dp) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onPrev) { Text("|<", fontFamily = ReaderFonts.Ui, color = colors.text) }
      IconButton(onClick = onToggle) {
        Icon(
          if (state.playing) Icons.Default.Close else Icons.Default.PlayArrow,
          contentDescription = if (state.playing) "Pause" else "Play",
          tint = colors.text,
        )
      }
      TextButton(onClick = onNext) { Text(">|", fontFamily = ReaderFonts.Ui, color = colors.text) }
      Text("${state.index + 1}/${state.units.size}", fontFamily = ReaderFonts.Ui, color = colors.secondary)
      Spacer(Modifier.weight(1f))
      TextButton(onClick = { onSpeed((state.speed + 0.25f).let { if (it > 2.5f) 0.75f else it }) }) {
        Text("${state.speed}x", fontFamily = ReaderFonts.Ui, color = colors.text)
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "Close player", tint = colors.text)
      }
    }
  }
}
