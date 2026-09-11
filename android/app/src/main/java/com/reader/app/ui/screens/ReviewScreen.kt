package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ReviewState
import com.reader.app.data.HighlightEntity
import com.reader.app.prefs.ArticleFont
import com.reader.app.ui.DestinationHeader
import com.reader.app.ui.theme.Motion
import com.reader.app.ui.theme.appColors
import com.reader.app.ui.theme.fontFor
import com.reader.app.ui.theme.rememberReduceMotion

/**
 * Deliberate Review. Comfortable quote measure, truthful progress, a neutral
 * reading surface with the saved colour as a marker, and one distinct source
 * action. Ordinary quote inspection stays separate from this screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
  state: ReviewState?,
  quote: HighlightEntity?,
  loading: Boolean,
  error: String?,
  onBack: () -> Unit,
  onNext: () -> Unit,
  onImportant: () -> Unit,
  scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
  sourceAvailable: Boolean = true,
  onSource: () -> Unit,
  onShare: () -> Unit,
  onRestart: () -> Unit,
  /** Quotes left in the active phase, including the one on screen. */
  remaining: Int = 0,
  inBonus: Boolean = false,
  quoteFont: ArticleFont = ArticleFont.NEWSREADER,
) {
  BackHandler(onBack = onBack)
  val c = appColors()
  val reduceMotion = rememberReduceMotion()
  val presented = state?.let { com.reader.app.core.ReviewScheduler.presentedId(it) }
  val progress: String? = when {
    loading || presented == null -> null
    inBonus -> "Revisiting Important highlights"
    else -> "$remaining remaining in this round"
  }
  Scaffold(
    containerColor = c.background,
    contentColor = c.text,
    contentWindowInsets = WindowInsets.statusBars,
    topBar = {
      Column(Modifier.statusBarsPadding()) {
        DestinationHeader(title = "Review", onBack = onBack)
        if (progress != null) {
          Text(
            progress, style = MaterialTheme.typography.labelMedium, color = c.secondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
          )
        }
      }
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      when {
        error != null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
          Text(error)
          TextButton(onClick = onRestart) { Text("Start a new review") }
        }
        loading || state == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          // Static progress text on e-ink instead of a continuously animated spinner.
          if (com.reader.app.ui.theme.LocalDisplayPolicy.current.monochrome) Text("Preparing review…", color = c.text)
          else CircularProgressIndicator()
        }
        presented == null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
          if (state.members.isEmpty()) {
            Text("No highlights yet", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Save a passage while reading to start a review.", color = c.secondary)
          } else {
            Text("Review complete", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back to highlights") }
            TextButton(onClick = onRestart) { Text("Review again") }
          }
        }
        quote != null -> {
          var distance by remember(quote.id) { mutableFloatStateOf(64f) }
          LaunchedEffect(quote.id) { distance = 0f }
          var dragging by remember(quote.id) { mutableStateOf(false) }
          var exiting by remember(quote.id) { mutableStateOf(false) }
          var advanced by remember(quote.id) { mutableStateOf(false) }
          var width by remember { mutableFloatStateOf(1f) }
          val next by rememberUpdatedState(onNext)
          val important by rememberUpdatedState(onImportant)
          val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
          val translation by animateFloatAsState(
            targetValue = distance,
            animationSpec = if (dragging) snap() else com.reader.app.ui.theme.revealSpec(),
            label = "Review card",
            finishedListener = { if (exiting && !advanced) { advanced = true; next() } },
          )
          fun advance() {
            // Direct manipulation only: the finger must be up before the card
            // moves, and Next may never advance twice for one gesture.
            if (exiting || advanced) return
            if (reduceMotion) { advanced = true; next() }
            else { dragging = false; exiting = true; distance = -width * 1.2f }
          }
          Column(
            Modifier.weight(1f).fillMaxWidth()
              .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
              .graphicsLayer {
                translationX = translation
                rotationZ = (translation / width * 3f).coerceIn(-4f, 4f)
                alpha = (1f - kotlin.math.abs(translation) / width * .3f).coerceIn(.5f, 1f)
              }
              .pointerInput(quote.id) {
                val threshold = 80.dp.toPx()
                detectHorizontalDragGestures(
                  onDragStart = { if (!exiting) { dragging = true; distance = 0f } },
                  onDragCancel = { if (!exiting) { dragging = false; distance = 0f } },
                  onDragEnd = {
                    if (!exiting) {
                      dragging = false
                      if (distance < -threshold) advance()
                      else {
                        if (distance > threshold) {
                          com.reader.app.ui.Haptics.star(haptics)
                          important()
                        }
                        distance = 0f
                      }
                    }
                  },
                  onHorizontalDrag = { change, amount -> change.consume(); if (!exiting) distance += amount },
                )
              }
              .verticalScroll(scrollState)
              .padding(horizontal = 20.dp),
          ) {
            val saved = com.reader.app.ui.theme.HighlightColor.parse(quote.color)
            val dark = com.reader.app.ui.theme.LocalReaderDark.current
            Box(
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface)
                .drawBehind { drawRect(color = saved.background(dark), size = Size(3.dp.toPx(), size.height)) },
            ) {
              Text(
                quote.quote,
                style = MaterialTheme.typography.bodyLarge.copy(
                  fontFamily = fontFor(quoteFont), fontSize = 22.sp, lineHeight = 32.sp,
                ),
                color = c.text,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
              )
            }
            Spacer(Modifier.height(20.dp))
            Text("Source", style = MaterialTheme.typography.labelSmall, color = c.secondary)
            TextButton(
              enabled = sourceAvailable && !exiting,
              onClick = onSource,
              contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
              Text(
                quote.sourceTitle.ifBlank { "Open source" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
              )
            }
            if (!sourceAvailable) {
              Text(
                "The source article is no longer saved. Your highlight and attribution remain here.",
                style = MaterialTheme.typography.bodySmall, color = c.secondary,
              )
            }
            Spacer(Modifier.height(16.dp))
          }
          FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TextButton(enabled = !exiting, onClick = {
              com.reader.app.ui.Haptics.star(haptics)
              onImportant()
            }) { Text(if (quote.important) "★ Important" else "☆ Important") }
            TextButton(enabled = !exiting, onClick = onShare) { Text("Share") }
            Button(enabled = !exiting, onClick = { advance() }) { Text("Next") }
          }
        }
      }
    }
  }
}
