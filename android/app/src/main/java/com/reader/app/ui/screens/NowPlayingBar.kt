package com.reader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reader.app.tts.TtsPlaybackState
import com.reader.app.ui.theme.ReaderColors
import com.reader.app.ui.theme.ReaderFonts

/**
 * Compact now-playing row shown above the bottom navigation while narration
 * continues away from its article: source title, Play/Pause and Stop. Inside
 * the narrated article the reader's own player is reused instead, so the two
 * rows are never on screen together.
 */
@Composable
fun NowPlayingBar(
  state: TtsPlaybackState,
  colors: ReaderColors,
  onOpen: () -> Unit,
  onToggle: () -> Unit,
  onStop: () -> Unit,
) {
  Surface(color = colors.surface, tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      TextButton(
        onClick = onOpen,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
      ) {
        Text(
          state.sourceTitle.ifBlank { "Listening" },
          fontFamily = ReaderFonts.Ui,
          color = colors.text,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      IconButton(onClick = onToggle) {
        Icon(
          if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (state.playing) "Pause" else "Play",
          tint = colors.text,
        )
      }
      IconButton(onClick = onStop) {
        Icon(Icons.Default.Stop, contentDescription = "Stop listening", tint = colors.text)
      }
    }
  }
}
