package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.reader.app.core.ReviewScheduler
import com.reader.app.data.HighlightSummary
import com.reader.app.data.ReviewSummary
import com.reader.app.ui.Haptics
import com.reader.app.ui.HighlightAction
import com.reader.app.ui.ReaderSearchField
import com.reader.app.ui.theme.appColors
import com.reader.app.ui.theme.Flexoki
import com.reader.app.ui.theme.Motion
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun HighlightsFeed(
  quotes: List<HighlightSummary>,
  db: com.reader.app.data.ReaderDb,
  seed: Long,
  newest: Boolean,
  onNewest: (Boolean) -> Unit,
  /** Deliberate Review entry. `restart` starts a fresh round after completion. */
  onStartReview: (restart: Boolean) -> Unit = {},
  /** Read-only cycle summary for the entry card; never written by this screen. */
  reviewSummary: ReviewSummary = ReviewSummary(),
  onToggleImportant: (String) -> Unit = {},
  onRecolor: (String, String) -> Unit = { _, _ -> },
  onRemoveHighlights: (Set<String>) -> Unit = {},
  onOpenLatest: (() -> Unit)? = null,
  onOpenSource: (String) -> Unit = {},
  onInspect: (String) -> Unit = {},
  query: String = "", onQuery: (String) -> Unit = {},
  importantOnly: Boolean = false, onImportantOnly: (Boolean) -> Unit = {},
  matchingIds: Set<String>? = null,
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
  val sorted = remember(quotes, newest, seed, matchingIds) {
    val quotes = if (matchingIds == null) quotes else quotes.filter { it.id in matchingIds }
    if (newest) quotes.sortedWith(compareByDescending<HighlightSummary> { it.createdAt }.thenBy { it.id })
    else quotes.sortedWith(compareBy<HighlightSummary> { ReviewScheduler.feedKey(seed, it.id) }.thenBy { it.id })
  }
  var selecting by rememberSaveable { mutableStateOf(false) }
  var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
  // Single removal has Undo; batch removal also asks for confirmation.
  var confirmRemoveIds by remember { mutableStateOf<Set<String>?>(null) }
  var menuId by remember { mutableStateOf<String?>(null) }
  // Forget selections for quotes that disappeared elsewhere.
  LaunchedEffect(quotes) {
    val live = quotes.mapTo(hashSetOf()) { it.id }
    selectedIds = selectedIds.filter { it in live }
    if (selectedIds.isEmpty()) { selecting = false; confirmRemoveIds = null }
  }
  BackHandler(enabled = selecting) { selecting = false; selectedIds = emptyList() }
  fun toggle(id: String) {
    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
  }
  val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
  fun star(id: String) {
    Haptics.star(haptics)
    onToggleImportant(id)
  }

  Column(Modifier.fillMaxSize().imePadding()) {
    if (selecting) {
      FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
        TextButton(
          onClick = { if (selectedIds.size == 1) onRemoveHighlights(selectedIds.toSet()) else confirmRemoveIds = selectedIds.toSet() }, enabled = selectedIds.isNotEmpty(),
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove") }
        TextButton(onClick = { selecting = false; selectedIds = emptyList() }) { Text("Done") }
      }
    } else {
      val focusManager = LocalFocusManager.current
      val hasHighlights = quotes.isNotEmpty()
      // Review is the page's primary action until a search is active; then it
      // condenses so results keep the room.
      val condensed = hasHighlights && (query.isNotBlank() || importantOnly)
      if (!condensed) {
        ReviewEntryCard(reviewSummary, hasHighlights, onOpenLatest) { onStartReview(reviewSummary.finished) }
        Spacer(Modifier.height(12.dp))
      }
      ReaderSearchField(
        value = query, onValueChange = onQuery,
        placeholder = "Search highlights", clearLabel = "Clear highlight search",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      )
      FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = newest, onClick = { onNewest(true) }, label = { Text("Newest") })
        FilterChip(selected = importantOnly, onClick = { onImportantOnly(!importantOnly) }, label = { Text("Important") })
        // Order is explicit: Shuffle shows its own selected state, so it is
        // never mistaken for an unselected secondary action.
        FilterChip(selected = !newest, onClick = { onNewest(false) }, label = { Text("Shuffle") })
      }
      if (condensed) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Review highlights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
          TextButton(onClick = { onStartReview(reviewSummary.finished) }) { Text(reviewActionLabel(reviewSummary)) }
        }
      }
    }
    if (quotes.isEmpty()) {
      // Same centered warmth as the other empty states, with one way out.
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
          Text("No highlights yet.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(8.dp))
          Text(
            "While reading, press-and-hold any passage to keep it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    else LazyColumn(Modifier.fillMaxSize(), state = listState) {
      if (sorted.isEmpty()) item { Text("No highlights match. Try another word or remove Important.", Modifier.padding(20.dp)) }
      items(sorted, key = { it.id }) { quote ->
        HighlightSwipeRow(
          quote = quote,
          selecting = selecting,
          selected = quote.id in selectedIds,
          haptics = haptics,
          onOpen = { if (selecting) toggle(quote.id) else onInspect(quote.id) },
          onOpenSource = { onOpenSource(quote.id) },
          onLongPress = {
            Haptics.select(haptics)
            if (!selecting) { selecting = true; selectedIds = listOf(quote.id) }
            else toggle(quote.id)
          },
          onToggle = { toggle(quote.id) },
          onMenu = { menuId = quote.id },
          onSwiped = { action ->
            when (action) {
              HighlightAction.Important -> star(quote.id)
              HighlightAction.Remove -> onRemoveHighlights(setOf(quote.id))
            }
          },
        )
        HorizontalDivider()
      }
    }
  }
  val menuQuote by remember(menuId) { db.highlights().observeById(menuId ?: "") }.collectAsState(initial = null)
  val context = androidx.compose.ui.platform.LocalContext.current
  menuQuote?.let { quote ->
    HighlightActionsSheet(quote,
      onColor = { onRecolor(quote.id, it) }, onImportant = { star(quote.id) },
      onShare = { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "“${quote.quote}”\n— ${quote.sourceTitle}" + (quote.sourceUrl?.let { " ($it)" } ?: ""))
      }, "Share highlight")) },
      onRemove = { menuId = null; onRemoveHighlights(setOf(quote.id)) }, onDismiss = { menuId = null })
  }
  confirmRemoveIds?.let { doomed ->
    val count = doomed.size
    AlertDialog(
      onDismissRequest = { confirmRemoveIds = null },
      title = { Text(if (count == 1) "Remove highlight?" else "Remove $count highlights?") },
      text = { Text("Remove these highlights? The articles stay in your library. Undo will be available afterward.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmRemoveIds = null
            selecting = false; selectedIds = selectedIds.filter { it !in doomed }
            onRemoveHighlights(doomed)
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmRemoveIds = null }) { Text("Cancel") } },
    )
  }
}

private fun reviewActionLabel(summary: ReviewSummary): String = when {
  !summary.exists -> "Start review"
  summary.phase == "done" -> "Review again"
  else -> "Continue review"
}

private fun reviewStateLine(summary: ReviewSummary, hasHighlights: Boolean): String = when {
  !hasHighlights -> "Save a passage while reading to start a review."
  !summary.exists -> "Revisit your saved passages."
  summary.phase == "bonus" -> "Revisiting Important highlights"
  summary.phase == "done" -> "Round complete."
  else -> "${summary.remaining} remaining in this round"
}

/**
 * The page's single primary action. It reads persisted review state but never
 * writes it; only the button calls the deliberate start/continue/restart path.
 */
@Composable
private fun ReviewEntryCard(summary: ReviewSummary, hasHighlights: Boolean, onOpenLatest: (() -> Unit)?, onStart: () -> Unit) {
  val c = appColors()
  Surface(
    color = c.divider.copy(alpha = .35f),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
  ) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Review highlights", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
          reviewStateLine(summary, hasHighlights),
          style = MaterialTheme.typography.bodySmall, color = c.secondary,
          maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(Modifier.width(12.dp))
      if (hasHighlights) {
        FilledTonalButton(onClick = onStart) { Text(reviewActionLabel(summary)) }
      } else if (onOpenLatest != null) {
        TextButton(onClick = onOpenLatest) { Text("Open your latest read") }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightSwipeRow(
  quote: HighlightSummary,
  selecting: Boolean,
  selected: Boolean,
  haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
  onOpen: () -> Unit,
  onOpenSource: () -> Unit,
  onLongPress: () -> Unit,
  onToggle: () -> Unit,
  onMenu: () -> Unit,
  onSwiped: (HighlightAction) -> Unit,
) {
  // Same tap-vs-swipe arbitration as the article rows: the click owns taps
  // (long-press enters selection), the drag detector owns horizontal swipes
  // and sets a guard so the up ending a swipe can't double-fire onClick.
  var offset by remember(quote.id) { mutableFloatStateOf(0f) }
  var gestureDrag by remember { mutableStateOf(false) }
  var armedAction by remember { mutableStateOf<HighlightAction?>(null) }
  val view = androidx.compose.ui.platform.LocalView.current
  val displayedOffset by animateFloatAsState(offset, if (gestureDrag) snap() else Motion.Settle, label = "Highlight swipe")

  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    fun settle(commit: Boolean) {
      val action = if (commit) {
        HighlightAction.forSwipe(offset > 0).takeIf { HighlightAction.commits(it, offset, widthPx) }
      } else null
      offset = 0f
      gestureDrag = false
      if (action != null) { Haptics.commit(haptics); onSwiped(action) }
    }
    val action = when {
      offset > 1f -> HighlightAction.Important
      offset < -1f -> HighlightAction.Remove
      else -> null
    }
    if (action != null && !selecting) {
      val armed = HighlightAction.commits(action, offset, widthPx)
      val tint = if (action == HighlightAction.Remove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
      Box(
        Modifier.matchParentSize().padding(horizontal = 20.dp),
        contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd,
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
        .offset { IntOffset(displayedOffset.roundToInt(), 0) }
        .combinedClickable(
          indication = null,
          interactionSource = remember { MutableInteractionSource() },
          onClick = { if (!gestureDrag) onOpen() },
          onLongClickLabel = "Select highlight",
          onLongClick = onLongPress,
        )
        .pointerInput(selecting, widthPx) {
          if (selecting) return@pointerInput
          detectHorizontalDragGestures(
            onDragStart = { gestureDrag = true },
            onDragCancel = { settle(false); armedAction = null },
            onDragEnd = { settle(true); armedAction = null },
            onHorizontalDrag = { change, dx ->
              offset = (offset + dx).coerceIn(-widthPx, widthPx)
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
          modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Select quote from ${quote.sourceTitle}" },
        )
      }
      Column(Modifier.weight(1f).semantics { if (selecting) stateDescription = if (selected) "Selected" else "Not selected" }) {
        val dark = com.reader.app.ui.theme.LocalReaderDark.current
        Text(quote.sourceTitle, style = MaterialTheme.typography.labelMedium, maxLines = 2,
          modifier = Modifier.padding(bottom = 8.dp).clickable(onClickLabel = "Open source article") { onOpenSource() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
          // Quieter preview: a restrained tint with a thin saved-colour marker
          // instead of a saturated full block, still six readable lines.
          val saved = com.reader.app.ui.theme.HighlightColor.parse(quote.color)
          Box(
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(saved.background(dark).copy(alpha = if (dark) .32f else .55f))
              .drawBehind { drawRect(color = saved.background(dark), size = Size(3.dp.toPx(), size.height)) },
          ) {
            Text(
              quote.preview,
              maxLines = 6, overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodyLarge,
              fontFamily = com.reader.app.ui.theme.ReaderFonts.Newsreader,
              color = com.reader.app.ui.theme.HighlightColor.text(dark),
              modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            )
          }
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
        // The way back: a quote is a door to the essay it came from.
        Text(
          "Read full highlight", style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.clickable(onClickLabel = "Read full highlight") { onOpen() },
        )
      }
      if (!selecting) IconButton(onClick = onMenu) {
        Icon(Icons.Default.MoreVert, contentDescription = "Highlight options")
      }
    }
  }
}
