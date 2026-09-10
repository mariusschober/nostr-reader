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
import androidx.compose.ui.unit.sp
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
  /** Tapping a card enters Review at that quote without losing the round. */
  onReviewFromQuote: (String) -> Unit = {},
  query: String = "", onQuery: (String) -> Unit = {},
  importantOnly: Boolean = false, onImportantOnly: (Boolean) -> Unit = {},
  matchingIds: Set<String>? = null,
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
  quoteFont: com.reader.app.prefs.ArticleFont = com.reader.app.prefs.ArticleFont.NEWSREADER,
  quoteBaseSp: Float = 19f,
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

  // Floating Review banner: an overlay above the bottom navigation. Visible on
  // arrival and at the top, hidden after a deliberate downward scroll and shown
  // again on upward scroll. It never relayouts the quote list.
  val hasHighlights = quotes.isNotEmpty()
  var bannerVisible by rememberSaveable { mutableStateOf(true) }
  val bannerSuppressed = selecting || menuId != null || confirmRemoveIds != null || query.isNotBlank()
  // Full quote text is loaded only for composed (visible/nearby) cards.
  val loadFullQuote: suspend (String) -> String? = { id -> runCatching { db.highlights().byId(id)?.quote }.getOrNull() }
  val bannerThreshold = with(LocalDensity.current) { 16.dp.toPx() }
  LaunchedEffect(listState, bannerSuppressed) {
    if (bannerSuppressed) return@LaunchedEffect
    var lastIndex = listState.firstVisibleItemIndex
    var lastOffset = listState.firstVisibleItemScrollOffset
    var accumulated = 0f
    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
      .collect { (index, offset) ->
        if (index == lastIndex) accumulated += (offset - lastOffset).toFloat()
        else accumulated += if (index > lastIndex) bannerThreshold * 4f else -bannerThreshold * 4f
        lastIndex = index; lastOffset = offset
        when {
          index == 0 && offset == 0 -> { accumulated = 0f; bannerVisible = true }
          accumulated >= bannerThreshold -> { bannerVisible = false; accumulated = 0f }
          accumulated <= -bannerThreshold -> { bannerVisible = true; accumulated = 0f }
        }
      }
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
          if (onOpenLatest != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenLatest) { Text("Open your latest read") }
          }
        }
      }
    } else Box(Modifier.fillMaxSize()) {
      LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        // Room beneath the last card so its footer stays reachable above the
        // floating banner.
        contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        if (sorted.isEmpty()) item { Text("No highlights match. Try another word or remove Important.", Modifier.padding(20.dp)) }
        items(sorted, key = { it.id }) { quote ->
          HighlightSwipeRow(
            quote = quote,
            selecting = selecting,
            selected = quote.id in selectedIds,
            haptics = haptics,
            onOpen = { if (selecting) toggle(quote.id) else onReviewFromQuote(quote.id) },
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
            loadFullQuote = loadFullQuote,
            quoteFont = quoteFont,
            quoteBaseSp = quoteBaseSp,
          )
          HorizontalDivider()
        }
      }
      if (hasHighlights && bannerVisible && !bannerSuppressed) {
        ReviewBanner(reviewSummary, Modifier.align(Alignment.BottomCenter)) { onStartReview(reviewSummary.finished) }
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

private fun reviewBannerSecondary(summary: ReviewSummary): String = when {
  !summary.exists -> "Start a round"
  summary.phase == "done" -> "Review again"
  summary.remaining > 0 -> "Continue · ${summary.remaining} remaining"
  else -> "Continue"
}

/**
 * Floating Review entry above the bottom navigation, showing the persisted
 * state without writing it. Only the tap calls the deliberate
 * start/continue/restart path.
 */
@Composable
private fun ReviewBanner(summary: ReviewSummary, modifier: Modifier = Modifier, onStart: () -> Unit) {
  val c = appColors()
  Surface(
    color = c.surface,
    contentColor = c.text,
    shape = RoundedCornerShape(12.dp),
    shadowElevation = 6.dp,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
      .heightIn(min = 64.dp)
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClickLabel = "Review highlights", onClick = onStart),
  ) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Review highlights", style = MaterialTheme.typography.titleMedium)
        Text(
          reviewBannerSecondary(summary),
          style = MaterialTheme.typography.bodySmall, color = c.secondary,
          maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
  loadFullQuote: suspend (String) -> String?,
  quoteFont: com.reader.app.prefs.ArticleFont,
  quoteBaseSp: Float,
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
        // Full quote for this visible card; the lightweight summary stands in
        // while it loads. Lazy composition is what bounds the work to on-screen
        // and nearby cards.
        val full by produceState(initialValue = quote.preview, quote.id) {
          value = loadFullQuote(quote.id) ?: quote.preview
        }
        val characters = remember(full) { graphemeCount(full) }
        // Named `quoteSp`, not `size`, so `DrawScope.size` stays in scope below.
        val quoteSp = quoteBaseSp + when {
          characters <= 160 -> 6f
          characters <= 450 -> 3f
          else -> 0f
        }
        val saved = com.reader.app.ui.theme.HighlightColor.parse(quote.color)
        // Reserve space above so the badge and the text never overlap.
        Box(Modifier.fillMaxWidth().padding(top = if (quote.important) 12.dp else 0.dp)) {
          Box(
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(saved.background(dark).copy(alpha = if (dark) .32f else .55f))
              .drawBehind { drawRect(color = saved.background(dark), size = Size(3.dp.toPx(), size.height)) },
          ) {
            Text(
              full,
              style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = com.reader.app.ui.theme.fontFor(quoteFont),
                fontSize = quoteSp.sp,
                lineHeight = (quoteSp * 1.5f).sp,
              ),
              color = com.reader.app.ui.theme.HighlightColor.text(dark),
              modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            )
          }
          if (quote.important) {
            Box(
              Modifier.align(Alignment.TopEnd).offset(y = (-12).dp).size(24.dp).background(Flexoki.Base800, CircleShape)
                .semantics { contentDescription = "Important highlight" },
              contentAlignment = Alignment.Center,
            ) {
              Icon(Icons.Default.Star, contentDescription = null, tint = Flexoki.StarYellow, modifier = Modifier.size(15.dp))
            }
          }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            quote.sourceTitle,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Open source article") { onOpenSource() },
          )
          TextButton(onClick = onOpenSource, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Open source", style = MaterialTheme.typography.labelMedium)
          }
          if (!selecting) IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Highlight options")
          }
        }
      }
    }
  }
}

/** Visible-character count in grapheme clusters, not UTF-16 units. */
private fun graphemeCount(text: String): Int {
  val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
  iterator.setText(text)
  var count = 0
  while (iterator.next() != java.text.BreakIterator.DONE) count++
  return count
}
