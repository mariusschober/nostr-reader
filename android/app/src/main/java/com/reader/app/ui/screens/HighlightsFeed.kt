package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.reader.app.core.ReviewScheduler
import com.reader.app.data.HighlightSummary
import com.reader.app.ui.HighlightAction
import com.reader.app.ui.theme.Flexoki
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightsFeed(
  quotes: List<HighlightSummary>,
  seed: Long,
  newest: Boolean,
  onNewest: (Boolean) -> Unit,
  onReview: (String?) -> Unit,
  onToggleImportant: (String) -> Unit = {},
  onRemoveHighlights: (Set<String>) -> Unit = {},
) {
  val sorted = remember(quotes, newest, seed) {
    if (newest) quotes.sortedWith(compareByDescending<HighlightSummary> { it.createdAt }.thenBy { it.id })
    else quotes.sortedWith(compareBy<HighlightSummary> { ReviewScheduler.feedKey(seed, it.id) }.thenBy { it.id })
  }
  var selecting by rememberSaveable { mutableStateOf(false) }
  var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
  var confirmRemove by remember { mutableStateOf(false) }
  // Forget selections for quotes that disappeared elsewhere.
  LaunchedEffect(quotes) {
    val live = quotes.mapTo(hashSetOf()) { it.id }
    selectedIds = selectedIds.filter { it in live }
    if (selectedIds.isEmpty()) { selecting = false; confirmRemove = false }
  }
  BackHandler(enabled = selecting) { selecting = false; selectedIds = emptyList() }
  fun toggle(id: String) {
    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
  }

  Column(Modifier.fillMaxSize()) {
    if (selecting) {
      Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(
          onClick = { confirmRemove = true }, enabled = selectedIds.isNotEmpty(),
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove") }
        TextButton(onClick = { selecting = false; selectedIds = emptyList() }) { Text("Done") }
      }
    } else {
      Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        FilterChip(selected = !newest, onClick = { onNewest(false) }, label = { Text("Shuffle") })
        FilterChip(selected = newest, onClick = { onNewest(true) }, label = { Text("Newest") })
        Button(enabled = quotes.isNotEmpty(), onClick = { onReview(null) }) { Text("Review") }
      }
      if (quotes.isNotEmpty()) {
        Text(
          "Swipe right to star, left to remove. Long-press to select several.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 20.dp),
        )
      }
    }
    if (quotes.isEmpty()) Text("Save a passage while reading to find it here.", modifier = Modifier.padding(24.dp))
    else LazyColumn(Modifier.fillMaxSize()) {
      items(sorted, key = { it.id }) { quote ->
        HighlightSwipeRow(
          quote = quote,
          selecting = selecting,
          selected = quote.id in selectedIds,
          onOpen = { if (selecting) toggle(quote.id) else onReview(quote.id) },
          onLongPress = {
            if (!selecting) { selecting = true; selectedIds = listOf(quote.id) }
            else toggle(quote.id)
          },
          onToggle = { toggle(quote.id) },
          onSwiped = { action ->
            when (action) {
              HighlightAction.Important -> onToggleImportant(quote.id)
              HighlightAction.Remove -> onRemoveHighlights(setOf(quote.id))
            }
          },
        )
        HorizontalDivider()
      }
    }
  }
  if (confirmRemove) {
    val count = selectedIds.size
    AlertDialog(
      onDismissRequest = { confirmRemove = false },
      title = { Text(if (count == 1) "Remove highlight?" else "Remove $count highlights?") },
      text = { Text("Your saved quotes will be deleted. The articles stay in your library. This cannot be undone.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmRemove = false
            val doomed = selectedIds.toSet()
            selecting = false; selectedIds = emptyList()
            if (doomed.isNotEmpty()) onRemoveHighlights(doomed)
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightSwipeRow(
  quote: HighlightSummary,
  selecting: Boolean,
  selected: Boolean,
  onOpen: () -> Unit,
  onLongPress: () -> Unit,
  onToggle: () -> Unit,
  onSwiped: (HighlightAction) -> Unit,
) {
  // Same tap-vs-swipe arbitration as the article rows: the click owns taps
  // (long-press enters selection), the drag detector owns horizontal swipes
  // and sets a guard so the up ending a swipe can't double-fire onClick.
  val offset = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  var gestureDrag by remember { mutableStateOf(false) }

  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    fun settle() {
      scope.launch {
        val end = offset.value
        val action = when {
          end > 1f -> HighlightAction.Important
          end < -1f -> HighlightAction.Remove
          else -> null
        }?.takeIf { HighlightAction.commits(it, end, widthPx) }
        if (action != null) {
          onSwiped(action)
          offset.snapTo(0f)
        } else {
          offset.animateTo(0f)
        }
        gestureDrag = false
      }
    }
    val action = when {
      offset.value > 1f -> HighlightAction.Important
      offset.value < -1f -> HighlightAction.Remove
      else -> null
    }
    if (action != null && !selecting) {
      val armed = HighlightAction.commits(action, offset.value, widthPx)
      val tint = if (action == HighlightAction.Remove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
      Box(
        Modifier.matchParentSize().padding(horizontal = 20.dp),
        contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (action == HighlightAction.Important) {
            Icon(Icons.Default.Star, contentDescription = null, tint = Flexoki.StarYellow, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
          } else {
            Icon(Icons.Default.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
          }
          Text(
            if (armed) action.releaseLabel else action.label,
            style = MaterialTheme.typography.labelLarge, color = tint,
          )
        }
      }
    }
    Row(
      Modifier
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .combinedClickable(
          indication = null,
          interactionSource = remember { MutableInteractionSource() },
          onClick = { if (!gestureDrag) onOpen() },
          onLongClick = onLongPress,
        )
        .pointerInput(selecting, widthPx) {
          if (selecting) return@pointerInput
          detectHorizontalDragGestures(
            onDragStart = { gestureDrag = true },
            onDragCancel = { settle() },
            onDragEnd = { settle() },
            onHorizontalDrag = { change, dx ->
              scope.launch { offset.snapTo((offset.value + dx).coerceIn(-widthPx, widthPx)) }
              change.consume()
            },
          )
        }
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      if (selecting) {
        Checkbox(
          checked = selected, onCheckedChange = { onToggle() },
          modifier = Modifier.padding(end = 8.dp),
        )
      }
      Column(Modifier.weight(1f)) {
        val dark = com.reader.app.ui.theme.LocalReaderDark.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
          Text(
            quote.preview + if (quote.quoteLength > 800) "…" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = com.reader.app.ui.theme.HighlightColor.text(dark),
            modifier = Modifier.weight(1f)
              .background(com.reader.app.ui.theme.HighlightColor.parse(quote.color).background(dark))
              .padding(12.dp),
          )
          // Important mark: yellow star badge, top right. The dark badge
          // keeps #ECCB60 legible on both light fills and dark surfaces.
          if (quote.important) {
            Box(
              Modifier.padding(start = 8.dp).size(28.dp).background(Flexoki.Base800, CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Default.Star, contentDescription = "Marked important", tint = Flexoki.StarYellow, modifier = Modifier.size(18.dp))
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        Text(quote.sourceTitle, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}
