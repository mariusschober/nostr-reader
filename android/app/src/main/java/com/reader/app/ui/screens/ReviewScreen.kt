package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(state: ReviewState?, quote: HighlightEntity?, loading: Boolean, error: String?,
                 onBack: () -> Unit, onNext: () -> Unit, onImportant: () -> Unit,
                 scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
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
          var distance by remember(quote.id) { mutableFloatStateOf(64f) }
          LaunchedEffect(quote.id) { distance = 0f }
          var dragging by remember(quote.id) { mutableStateOf(false) }
          var exiting by remember(quote.id) { mutableStateOf(false) }
          var width by remember { mutableFloatStateOf(1f) }
          val next by rememberUpdatedState(onNext)
          val important by rememberUpdatedState(onImportant)
          val translation by animateFloatAsState(
            targetValue = distance,
            animationSpec = if (dragging) snap() else tween(220),
            label = "Review card",
            finishedListener = { if (exiting) next() },
          )
          fun advance() {
            if (!exiting) { dragging = false; exiting = true; distance = -width * 1.2f }
          }
          Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
              translationX = translation
              rotationZ = (translation / width * 7f).coerceIn(-9f, 9f)
              alpha = (1f - kotlin.math.abs(translation) / width * .45f).coerceIn(.2f, 1f)
            }.pointerInput(quote.id) {
            val threshold = 80.dp.toPx()
            detectHorizontalDragGestures(
              onDragStart = { if (!exiting) { dragging = true; distance = 0f } },
              onDragCancel = { if (!exiting) { dragging = false; distance = 0f } },
              onDragEnd = {
                if (!exiting) {
                  dragging = false
                  if (distance < -threshold) advance()
                  else { if (distance > threshold) important(); distance = 0f }
                }
              },
              onHorizontalDrag = { change, amount -> change.consume(); if (!exiting) distance += amount },
            )
          }.verticalScroll(scrollState)) {
            val dark = com.reader.app.ui.theme.LocalReaderDark.current
            Text(quote.quote, style = MaterialTheme.typography.headlineSmall,
              fontFamily = com.reader.app.ui.theme.ReaderFonts.Asul,
              color = com.reader.app.ui.theme.HighlightColor.text(dark),
              modifier = Modifier.background(com.reader.app.ui.theme.HighlightColor.parse(quote.color).background(dark)).clickable(enabled = sourceAvailable && !exiting, onClick = onSource).padding(12.dp))
            Spacer(Modifier.height(24.dp))
            if (!sourceAvailable) Text(quote.sourceTitle)
            TextButton(enabled = sourceAvailable && !exiting, onClick = onSource) { Text(if (sourceAvailable) quote.sourceTitle.ifBlank { "Open source" } else "Source article deleted") }
          }
          // Action bar wraps instead of clipping on narrow screens and large
          // fonts. Next stays the emphasized button; all three remain
          // reachable with TalkBack labels from their visible text.
          FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TextButton(enabled = !exiting, onClick = onImportant) { Text(if (quote.important) "★ Important" else "☆ Important") }
            TextButton(enabled = !exiting, onClick = onShare) { Text("Share") }
            Button(enabled = !exiting, onClick = { advance() }) { Text("Next") }
          }
        }
      }
    }
  }
}
