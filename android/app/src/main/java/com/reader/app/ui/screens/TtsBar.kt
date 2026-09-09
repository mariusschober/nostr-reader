package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reader.app.tts.TtsController
import com.reader.app.ui.theme.ReaderColors
import com.reader.app.ui.theme.ReaderFonts

/** Small bottom media bar while TTS is active. Article stays visible. */
@OptIn(ExperimentalLayoutApi::class)
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
  val context = LocalContext.current
  Surface(color = colors.surface, tonalElevation = 2.dp) {
    Column {
    if (state.voiceRequiresNetwork == true) Text("This voice requires a network connection", color = colors.secondary, modifier = Modifier.padding(horizontal = 12.dp))
    state.error?.let {
      Text(it, color = colors.error, modifier = Modifier.padding(12.dp))
      TextButton(onClick = { runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("Speech settings") }
    }
    FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous sentence", tint = colors.text) }
      IconButton(onClick = onToggle) {
        Icon(
          if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (state.playing) "Pause" else "Play",
          tint = colors.text,
        )
      }
      IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next sentence", tint = colors.text) }
      Text("${state.index + 1}/${state.units.size}", fontFamily = ReaderFonts.Ui, color = colors.secondary, modifier = Modifier.align(Alignment.CenterVertically))
      TextButton(onClick = { onSpeed((state.speed + 0.25f).let { if (it > 2.5f) 0.75f else it }) }) {
        Text("${state.speed}x", fontFamily = ReaderFonts.Ui, color = colors.text)
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "Close player", tint = colors.text)
      }
    }
  }
  }
}
