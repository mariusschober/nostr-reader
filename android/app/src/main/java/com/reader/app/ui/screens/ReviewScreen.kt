package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.reader.app.core.ReviewState
import com.reader.app.data.HighlightEntity

@Composable
fun ReviewScreen(state: ReviewState?, quote: HighlightEntity?, loading: Boolean, error: String?,
                 onBack: () -> Unit, onNext: () -> Unit, onImportant: () -> Unit,
                 sourceAvailable: Boolean = true, onSource: () -> Unit, onShare: () -> Unit, onRestart: () -> Unit) {
  BackHandler(onBack = onBack)
  Scaffold(topBar = {
    Row(Modifier.fillMaxWidth().padding(8.dp)) {
      TextButton(onClick = onBack) { Text("Back") }
      Spacer(Modifier.weight(1f))
      Text("Review", modifier = Modifier.padding(12.dp))
    }
  }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().padding(24.dp)) {
      when {
        error != null -> { Text(error); TextButton(onClick = onRestart) { Text("Start a new review") } }
        loading || state == null -> CircularProgressIndicator()
        state.currentId == null -> {
          Text(if (state.members.isEmpty()) "No highlights yet" else "You’re caught up", style = MaterialTheme.typography.headlineSmall)
          Spacer(Modifier.height(16.dp))
          if (state.members.isNotEmpty()) Button(onClick = onRestart) { Text("Review again") }
        }
        quote != null -> {
          var distance by remember(quote.id) { mutableFloatStateOf(0f) }
          val next by rememberUpdatedState(onNext)
          val important by rememberUpdatedState(onImportant)
          Column(Modifier.weight(1f).fillMaxWidth().pointerInput(quote.id) {
            val threshold = 80.dp.toPx()
            detectHorizontalDragGestures(
              onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
              onDragEnd = {
                if (distance < -threshold) next() else if (distance > threshold) important()
                distance = 0f
              },
              onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
            )
          }.verticalScroll(rememberScrollState())) {
            val dark = com.reader.app.ui.theme.LocalReaderDark.current
            Text(quote.quote, style = MaterialTheme.typography.headlineSmall,
              color = com.reader.app.ui.theme.HighlightColor.text(dark),
              modifier = Modifier.background(com.reader.app.ui.theme.HighlightColor.parse(quote.color).background(dark)).clickable(enabled = sourceAvailable, onClick = onSource).padding(12.dp))
            Spacer(Modifier.height(24.dp))
            if (!sourceAvailable) Text(quote.sourceTitle)
            TextButton(enabled = sourceAvailable, onClick = onSource) { Text(if (sourceAvailable) quote.sourceTitle.ifBlank { "Open source" } else "Source article deleted") }
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onImportant) { Text(if (quote.important) "★ Important" else "☆ Important") }
            TextButton(onClick = onShare) { Text("Share") }
            Button(onClick = onNext) { Text("Next") }
          }
        }
      }
    }
  }
}
