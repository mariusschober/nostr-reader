package com.reader.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.reader.app.data.ArticleMove
import com.reader.app.ui.MoveNotice
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.background
import com.reader.app.ui.ArticleAction
import com.reader.app.ui.Haptics
import com.reader.app.ui.theme.Motion
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentSummary
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.DestinationHeader
import com.reader.app.ui.ReaderSearchField
import com.reader.app.ui.Triage
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.appColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
  lists: Map<String, List<DocumentSummary>>,
  minutes: Map<String, Int>,
  settings: ReaderSettings,
  onOpen: (String) -> Unit,
  onMove: (ids: Set<String>, target: String) -> Unit,
  onUndoMove: (List<ArticleMove>) -> Unit,
  onDelete: (String) -> Unit,
  onImportFile: () -> Unit,
  onPasteText: (String) -> Unit,
  onPair: () -> Unit,
  onSettings: () -> Unit,
  loaded: Boolean,
  selectedTab: String,
  onSelectTab: (String) -> Unit,
  highlights: @Composable () -> Unit,
  readerMove: MoveNotice? = null,
  onReaderMoveConsumed: (String) -> Unit = {},
  archiveMode: Boolean = false,
  onArchiveOpen: () -> Unit = {},
  onArchiveBack: () -> Unit = {},
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
  highlightCount: Int? = null,
  sort: com.reader.app.prefs.LibrarySort = com.reader.app.prefs.LibrarySort.NEWEST,
  onSort: (com.reader.app.prefs.LibrarySort) -> Unit = {},
  age: com.reader.app.prefs.AgeFilter = com.reader.app.prefs.AgeFilter.ANY,
  onAge: (com.reader.app.prefs.AgeFilter) -> Unit = {},
  labelCounts: List<com.reader.app.data.LabelCount> = emptyList(),
  selectedLabelNorm: String? = null,
  onLabelSelect: (String?) -> Unit = {},
  labelsByDoc: Map<String, Set<String>> = emptyMap(),
  onToggleLabel: (Set<String>, String) -> Unit = { _, _ -> },
  /** Open the article-scoped highlight list for one saved article. */
  onOpenArticleHighlights: (String) -> Unit = {},
  // Library search (offline FTS). All optional so previews stay source-stable;
  // MainActivity wires the real flow. Blank query always means "no search".
  searchActive: Boolean = false,
  onToggleSearch: () -> Unit = {},
  searchText: String = "",
  onSearchText: (String) -> Unit = {},
  onSubmitSearch: () -> Unit = {},
  searchScope: com.reader.app.data.SearchScope = com.reader.app.data.SearchScope.ALL,
  onSearchScope: (com.reader.app.data.SearchScope) -> Unit = {},
  searchResults: com.reader.app.data.SearchResults? = null,
  searchRecents: List<String> = emptyList(),
  onRecentTap: (String) -> Unit = {},
  onRecentRemove: (String) -> Unit = {},
  onRecentsClear: () -> Unit = {},
  searchListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
  onOpenResult: (String) -> Unit = {},
  finishNotice: String? = null,
  onFinishNoticeConsumed: () -> Unit = {},
  onFinishNoticeAction: () -> Unit = {},
  // Retires the one-time Archive gestures coach (see W1.4 in the plan):
  // called on its auto-fade or on the user's first committed archive action.
  onArchiveCoachDone: () -> Unit = {},

  onLibrarySettings: (ReaderSettings) -> Unit = {},
  labelIdsByDoc: Map<String, Set<String>> = emptyMap(),
  /** labelId → palette key; drives the compact row badges without a DB query. */
  labelPalette: Map<String, com.reader.app.prefs.LabelColorKey> = emptyMap(),
  onManageLabels: () -> Unit = {},
  onSample: () -> Unit = {},
  highlightReviewSummary: com.reader.app.data.ReviewSummary = com.reader.app.data.ReviewSummary(),
  onStartHighlightReview: (Boolean) -> Unit = {},
  highlightFilterActive: Boolean = false,
  librarySearch: com.reader.app.data.LibrarySearchResult = com.reader.app.data.LibrarySearchResult(),
  onMoreResults: () -> Unit = {},
  captures: List<com.reader.app.capture.CaptureRequestEntity> = emptyList(),
  onRetryCapture: (com.reader.app.capture.CaptureRequestEntity) -> Unit = {},

) {
  val c = appColors()
  val tab = if (archiveMode) Triage.ARCHIVED else selectedTab
  var selectedIds by rememberSaveable(tab) { mutableStateOf(listOf<String>()) }
  val selecting = selectedIds.isNotEmpty()
  var rowMenu by remember { mutableStateOf<DocumentSummary?>(null) }
  var deleteId by remember { mutableStateOf<String?>(null) }
  var labelIds by remember { mutableStateOf<Set<String>?>(null) }
  var moveIds by remember { mutableStateOf<Set<String>?>(null) }
  var sheet by rememberSaveable { mutableStateOf<String?>(null) }
  var pasted by rememberSaveable { mutableStateOf("") }
  val notice = remember { SnackbarHostState() }
  val focus = LocalFocusManager.current
  // Search receives focus exactly once, on the deliberate header opening.
  // `armed` is saveable so returning from an article (which recreates this
  // composable) neither refocuses nor reopens the keyboard.
  val searchFocus = remember { FocusRequester() }
  var searchFocusArmed by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(searchActive) { if (!searchActive) searchFocusArmed = false }
  LaunchedEffect(readerMove?.id) {
    val move = readerMove ?: return@LaunchedEffect
    try {
      if (notice.showSnackbar(move.message, "Undo", withDismissAction = true,
          duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndoMove(move.moves)
    } finally { onReaderMoveConsumed(move.id) }
  }
  BackHandler(enabled = archiveMode, onBack = onArchiveBack)
  // The IME consumes Back while it is open, so this runs once the keyboard is
  // already gone: it closes the search UI and keeps the query for return.
  BackHandler(enabled = searchActive) { onToggleSearch() }
  BackHandler(enabled = selecting) { selectedIds = emptyList() }
  val all = lists.values.flatten()
  // Compact label badges per document, joined in memory from the already
  // observed label counts/assignments plus the appearance palette — no per-row
  // database query. LabelBadgesRow shows at most two names plus "+N".
  val badgesByDoc = remember(labelIdsByDoc, labelCounts, labelPalette) {
    val byId = labelCounts.associateBy { it.labelId }
    labelIdsByDoc.mapValues { (_, ids) ->
      ids.mapNotNull { id ->
        val label = byId[id] ?: return@mapNotNull null
        LabelBadgeSpec(
          label.labelId, label.name,
          labelPalette[label.labelId] ?: com.reader.app.prefs.LabelColorKey.NEUTRAL,
        )
      }
    }
  }
  // Normalized name → palette key, for the assignment sheet's badge identity.
  val labelColorsByName = remember(labelPalette, labelCounts) {
    labelCounts.associate { it.normalized to (labelPalette[it.labelId] ?: com.reader.app.prefs.LabelColorKey.NEUTRAL) }
  }
  val constrained = settings.age != com.reader.app.prefs.AgeFilter.ANY || settings.labelIds.isNotEmpty() || settings.unlabeled
  val filtered = lists[tab].orEmpty().filter { doc ->
    val assigned = labelIdsByDoc[doc.documentId].orEmpty()
    Triage.ageMatches(doc.createdAt, settings.age) &&
      (if (settings.unlabeled) assigned.isEmpty() else assigned.containsAll(settings.labelIds))
  }
  val displayed = if (searchActive && (searchText.isNotBlank() || constrained)) librarySearch.rows.mapNotNull { hit -> all.find { it.documentId == hit.documentId } }
    else applySortFilter(filtered, settings.sort, com.reader.app.prefs.AgeFilter.ANY)
  val continuation = all.filter { it.lastOpenedAt > 0 && it.finishedAt == null && it.list != Triage.ARCHIVED && it.wordCount > 0 && it.sourceType != "link" }
    .maxByOrNull { it.lastOpenedAt }
  fun toggle(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
  fun clearFilters() = onLibrarySettings(settings.copy(age = com.reader.app.prefs.AgeFilter.ANY, labelIds = emptySet(), unlabeled = false))
  Scaffold(containerColor = c.background, contentColor = c.text,
    contentWindowInsets = WindowInsets.statusBars,
    snackbarHost = { SnackbarHost(notice) },
    topBar = {
      Column(Modifier.statusBarsPadding()) {
        val largeHeader = LocalDensity.current.fontScale > 1.3f
        if (tab == "highlights" && largeHeader) {
          DestinationHeader(title = "Highlights") {}
          Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(
              onClick = { onStartHighlightReview(highlightReviewSummary.finished) },
              modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (highlightFilterActive) "Review all" else "Review") }
          }
        } else {
          DestinationHeader(title = if (tab == "highlights") "Highlights" else "Reader") {
            if (tab == "highlights") {
              TextButton(
                onClick = { onStartHighlightReview(highlightReviewSummary.finished) },
                modifier = Modifier.heightIn(min = 48.dp),
              ) { Text(if (highlightFilterActive) "Review all" else "Review") }
            } else {
              IconButton(onClick = {
                if (!searchActive) searchFocusArmed = true
                onToggleSearch()
              }) { Icon(if (searchActive) Icons.Default.Close else Icons.Default.Search, if (searchActive) "Close search" else "Search saved articles") }
              IconButton(onClick = { sheet = "add" }) { Icon(Icons.Default.Add, "Add article") }
            }
          }
        }
        if (tab != "highlights") {
          val columns = if (LocalDensity.current.fontScale > 1.3f) 2 else 4
          Triage.TABS.chunked(columns).forEach { group ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
              group.forEach { destination ->
                Column(Modifier.weight(1f).selectable(tab == destination, role = Role.Tab,
                  onClick = { if (destination != tab) { selectedIds = emptyList(); onSelectTab(destination) } }).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  Text(Triage.tabLabel(destination), fontWeight = if (tab == destination) FontWeight.Bold else FontWeight.Normal, color = if (tab == destination) c.text else c.secondary)
                  Spacer(Modifier.height(6.dp))
                  Box(Modifier.width(24.dp).height(2.dp).background(if (tab == destination) c.text else c.background))
                }
              }
            }
          }
        }
      }
    },
  ) { pad ->
    if (tab == "highlights") Box(Modifier.padding(pad).fillMaxSize()) { highlights() }
    else Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
      if (searchActive) {
        ReaderSearchField(
          value = searchText, onValueChange = onSearchText,
          placeholder = "Search articles", clearLabel = "Clear article search",
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(); focus.clearFocus() }),
          focusRequester = searchFocus,
          autoFocus = searchFocusArmed,
          onAutoFocused = { searchFocusArmed = false },
        )
        val labelTerm = Regex("(?:^|\\s)#([^#]*)$").find(searchText)
        if (labelTerm != null) {
          FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labelCounts.filter { it.name.contains(labelTerm.groupValues[1], true) }.take(4).forEach { label ->
              AssistChip(onClick = {
                onLibrarySettings(settings.copy(labelIds = settings.labelIds + label.labelId, unlabeled = false))
                onSearchText(searchText.substring(0, labelTerm.range.first).trimEnd())
              }, label = { Text("#${label.name}") })
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (searchActive) {
            val location = if (settings.searchCurrentShelf) "Current list · ${Triage.tabLabel(tab)}" else "All saved articles"
            val fields = if (settings.searchTitlesOnly) "Titles only" else "Title and text"
            "$location · $fields"
          } else "${displayed.size} article${if (displayed.size == 1) "" else "s"}",
          color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        if (!searchActive) TextButton(onClick = { sheet = "sort" }) { Text("Sort") }
        TextButton(onClick = { sheet = "filter" }) { Text("Filter${if (constrained) " •" else ""}") }
      }
      if (constrained) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (settings.age != com.reader.app.prefs.AgeFilter.ANY) InputChip(selected = true,
          onClick = { onLibrarySettings(settings.copy(age = com.reader.app.prefs.AgeFilter.ANY)) },
          label = { Text(ageName(settings.age) + " ×") })
        if (settings.unlabeled) InputChip(selected = true, onClick = { onLibrarySettings(settings.copy(unlabeled = false)) }, label = { Text("Unlabeled ×") })
        settings.labelIds.forEach { id -> InputChip(selected = true,
          onClick = { onLibrarySettings(settings.copy(labelIds = settings.labelIds - id)) },
          label = { Text((labelCounts.find { it.labelId == id }?.name ?: "Removed label") + " ×") }) }
        TextButton(onClick = ::clearFilters) { Text("Clear filters") }
      }
      if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { selectedIds = emptyList() }) { Icon(Icons.Default.Close, "Clear selection") }
        Text("${selectedIds.size}", modifier = Modifier.weight(1f))
        TextButton(onClick = { selectedIds = displayed.map { it.documentId } }) { Text("Select all") }
        TextButton(onClick = { labelIds = selectedIds.toSet() }) { Text("Labels") }
        TextButton(onClick = { moveIds = selectedIds.toSet() }) { Text("Move to…") }
      }
      LazyColumn(state = if (searchActive) searchListState else listState,
        modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 16.dp, bottom = 24.dp)) {
        if (!searchActive && !constrained && !selecting && continuation != null) item(key = "continue") {
          Surface(onClick = { onOpen(continuation.documentId) }, color = c.divider.copy(alpha = .35f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
              Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = c.secondary)
              Text(continuation.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
          }
        }
        if (!searchActive && tab == Triage.INBOX && captures.isNotEmpty()) item(key = "captures") {
          Column(Modifier.padding(vertical = 12.dp)) {
            val active = captures.count { it.state != "failed" }
            if (active > 0) Text("Fetching $active article${if (active == 1) "" else "s"}… Your links are saved.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
            captures.filter { it.state == "failed" }.take(3).forEach { request -> Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Couldn’t fetch ${request.title ?: UriHost(request.originalUrl)}", modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodySmall)
              TextButton(onClick = { onRetryCapture(request) }) { Text("Retry") }
            } }
          }
        }
        if (!loaded) item { Text("Loading your library…", Modifier.padding(vertical = 24.dp), color = c.secondary) }
        else if (searchActive && searchText.isBlank() && !constrained) item {
          Column(Modifier.padding(top = 4.dp, bottom = 12.dp)) {
            Text("Use “quotes” for a phrase or # to choose a label.", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            if (searchRecents.isNotEmpty()) {
              Spacer(Modifier.height(16.dp))
              Text("Recent searches", style = MaterialTheme.typography.labelLarge, color = c.secondary)
              searchRecents.forEach { q ->
                Row(
                  Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClickLabel = "Search $q") { onRecentTap(q) },
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(Icons.Default.History, contentDescription = null, tint = c.secondary, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(12.dp))
                  Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.text, modifier = Modifier.weight(1f))
                  IconButton(onClick = { onRecentRemove(q) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove recent search $q", tint = c.secondary, modifier = Modifier.size(18.dp))
                  }
                }
              }
            }
          }
        }
        else if (searchActive && librarySearch.status != com.reader.app.data.SearchStatus.READY) item {
          Text(when (librarySearch.status) {
            com.reader.app.data.SearchStatus.LOADING -> "Searching saved articles…"
            com.reader.app.data.SearchStatus.INVALID -> librarySearch.message ?: "Check your search."
            com.reader.app.data.SearchStatus.FAILED -> "Search couldn’t load. Change the query or try again."
            else -> "Type to search."
          }, Modifier.padding(vertical = 20.dp), color = c.secondary)
        }
        else if (displayed.isEmpty()) item {
          Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (searchActive || constrained) "No articles match" else if (all.isEmpty()) "Your next good read starts here" else "${Triage.tabLabel(tab)} is clear", style = MaterialTheme.typography.titleLarge)
            Text(if (searchActive || constrained) "Try fewer words or remove a filter." else "Save something you want to give your attention to.", color = c.secondary)
            if (constrained) TextButton(onClick = ::clearFilters) { Text("Clear filters") }
            if (!searchActive) {
              Button(onClick = { sheet = "add" }) { Text(if (all.isEmpty()) "Add your first article" else "Add an article") }
              if (all.isEmpty()) TextButton(onClick = onSample) { Text("Read a sample introduction") }
              Text("You can also share a link to Reader from any Android app.", style = MaterialTheme.typography.bodySmall, color = c.secondary)
            }
          }
        }
        else {
          if (searchActive) item { Text("${displayed.size}${if (librarySearch.hasMore) "+" else ""} matches · ${if (settings.searchTitlesOnly) "Titles only" else "Title and text"}", color = c.secondary, style = MaterialTheme.typography.bodySmall) }
          items(displayed, key = { it.documentId }) { doc ->
            val open = { if (selecting) toggle(doc.documentId) else { focus.clearFocus(); if (searchActive) onOpenResult(doc.documentId) else onOpen(doc.documentId) } }
            val select = { toggle(doc.documentId) }
            if (searchActive) Text(Triage.tabLabel(doc.list), color = c.secondary, style = MaterialTheme.typography.labelSmall)
            if (doc.list == Triage.ARCHIVED) ArchiveSwipeRow(doc, c, selecting, doc.documentId in selectedIds,
              open, select, select, { rowMenu = doc }, { action -> if (action == ArticleAction.Delete) deleteId = doc.documentId else action.target?.let { onMove(setOf(doc.documentId), it) } },
              badges = badgesByDoc[doc.documentId].orEmpty())
            else SwipeRow(doc, doc.list, c, selecting, doc.documentId in selectedIds,
              open, select, select, { rowMenu = doc }, { onMove(setOf(doc.documentId), it) },
              badges = badgesByDoc[doc.documentId].orEmpty())
            HorizontalDivider(color = c.divider.copy(alpha = .6f))
          }
          if (searchActive && librarySearch.hasMore) item { TextButton(onClick = onMoreResults, modifier = Modifier.fillMaxWidth()) { Text("Show 50 more") } }
        }
      }
    }
  }
  if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = c.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      when (sheet) {
        "add" -> {
          Text("Add to Reader", style = MaterialTheme.typography.titleLarge)
          TextButton(onClick = { sheet = "paste" }) { Text("Paste a link or text") }
          TextButton(onClick = { sheet = null; onImportFile() }) { Text("Import a file") }
          TextButton(onClick = { sheet = null; onPair() }) { Text("Connect Chrome") }
          Text("On Android, choose Share → Reader from another app. No connection is needed.", color = c.secondary)
        }
        "paste" -> {
          Text("Paste a link or text", style = MaterialTheme.typography.titleLarge)
          OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp), placeholder = { Text("https://… or article text") })
          Button(enabled = pasted.isNotBlank(), onClick = { onPasteText(pasted); pasted = ""; sheet = null; focus.clearFocus() }) { Text("Save to Reader") }
        }
        "sort" -> {
          Text("Sort articles", style = MaterialTheme.typography.titleLarge)
          com.reader.app.prefs.LibrarySort.entries.forEach { order ->
            TextButton(onClick = { onSort(order); sheet = null }) { Text((if (order == settings.sort) "✓ " else "") + sortName(order)) }
          }
        }
        "filter" -> {
          Text("Filter articles", style = MaterialTheme.typography.titleLarge)
          if (searchActive) {
            Text("Search location", style = MaterialTheme.typography.labelLarge)
            listOf(false to "All saved articles", true to "Current list · ${Triage.tabLabel(tab)}").forEach { (current, title) ->
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().selectable(settings.searchCurrentShelf == current, onClick = { onLibrarySettings(settings.copy(searchCurrentShelf = current)) })) {
                RadioButton(settings.searchCurrentShelf == current, null); Text(title, Modifier.padding(start = 8.dp))
              }
            }
            Text("Search fields", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf(false to "Title and text", true to "Titles only").forEach { (titles, title) -> FilterChip(settings.searchTitlesOnly == titles, { onLibrarySettings(settings.copy(searchTitlesOnly = titles)) }, { Text(title) }) }
            }
          }
          Text("Saved", style = MaterialTheme.typography.labelLarge)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.reader.app.prefs.AgeFilter.entries.forEach { age -> FilterChip(settings.age == age, { onAge(age) }, { Text(ageName(age)) }) }
          }
          Text("Labels · match all selected", style = MaterialTheme.typography.labelLarge)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(settings.unlabeled, { onLibrarySettings(settings.copy(unlabeled = !settings.unlabeled, labelIds = emptySet())) }, { Text("Unlabeled") })
            labelCounts.forEach { label -> FilterChip(label.labelId in settings.labelIds,
              { onLibrarySettings(settings.copy(unlabeled = false, labelIds = if (label.labelId in settings.labelIds) settings.labelIds - label.labelId else settings.labelIds + label.labelId)) },
              { Text("${label.name} · ${label.count}") }) }
          }
          TextButton(onClick = { sheet = null; onManageLabels() }) { Text("Manage labels") }
          Row { TextButton(onClick = ::clearFilters) { Text("Clear filters") }; Spacer(Modifier.weight(1f)); Button(onClick = { sheet = null }) { Text("Show articles") } }
        }
      }
    }
  }
  val menu = rowMenu
  if (menu != null) ModalBottomSheet(onDismissRequest = { rowMenu = null }, containerColor = c.background) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
      Text(menu.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
      TextButton(onClick = { rowMenu = null; moveIds = setOf(menu.documentId) }) { Text("Move to…") }
      TextButton(onClick = { rowMenu = null; labelIds = setOf(menu.documentId) }) { Text("Edit labels") }
      TextButton(onClick = { rowMenu = null; onOpenArticleHighlights(menu.documentId) }) { Text("View article highlights") }
      TextButton(onClick = { rowMenu = null; toggle(menu.documentId) }) { Text("Select") }
      if (menu.list == Triage.ARCHIVED) TextButton(onClick = { rowMenu = null; deleteId = menu.documentId }) { Text("Delete article…", color = c.error) }
    }
  }
  moveIds?.let { ids -> ModalBottomSheet(onDismissRequest = { moveIds = null }, containerColor = c.background) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
      Text("Move ${ids.size} article${if (ids.size == 1) "" else "s"} to", style = MaterialTheme.typography.titleLarge)
      Triage.TABS.forEach { target -> TextButton(onClick = { onMove(ids, target); selectedIds = emptyList(); moveIds = null }) { Text(Triage.tabLabel(target)) } }
    }
  } }
  labelIds?.let { ids -> LabelsDialog("Labels", ids.flatMap { labelsByDoc[it].orEmpty() }.groupingBy { norm -> labelCounts.find { it.normalized == norm }?.name ?: norm }.eachCount(), ids.size,
    labelCounts.map { it.name }, { onToggleLabel(ids, it) }, { labelIds = null }, c,
    palette = labelColorsByName, onManageLabels = { labelIds = null; onManageLabels() }) }
  deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete article?") },
    text = { Text("This permanently removes the saved article. Your highlights and their attribution stay saved.") },
    confirmButton = { TextButton(onClick = { deleteId = null; onDelete(id) }) { Text("Delete article", color = c.error) } },
    dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep article") } }) }
}

private fun ageName(age: com.reader.app.prefs.AgeFilter) = when(age) {
  com.reader.app.prefs.AgeFilter.ANY -> "Any time"
  com.reader.app.prefs.AgeFilter.TODAY -> "Today"
  com.reader.app.prefs.AgeFilter.WEEK -> "Past week"
  com.reader.app.prefs.AgeFilter.MONTH -> "Past month"
  com.reader.app.prefs.AgeFilter.OLDER -> "Older than a month"
}
private fun sortName(sort: com.reader.app.prefs.LibrarySort) = when(sort) {
  com.reader.app.prefs.LibrarySort.NEWEST -> "Newest first"
  com.reader.app.prefs.LibrarySort.OLDEST -> "Oldest first"
  com.reader.app.prefs.LibrarySort.QUICKEST -> "Shortest first"
  com.reader.app.prefs.LibrarySort.LONGEST -> "Longest first"
  com.reader.app.prefs.LibrarySort.TITLE -> "Title A–Z"
}


@Composable
private fun EmptyShelf(
  tab: String,
  libraryEmpty: Boolean,
  onPair: () -> Unit,
  onAdd: () -> Unit,
  onBrowse: (String) -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors = appColors(),
) {
  val title: String
  val body: String
  var primary: Pair<String, () -> Unit>? = null
  var secondary: Pair<String, () -> Unit>? = null
  when {
    tab == Triage.INBOX && libraryEmpty -> {
      title = "Your quiet library awaits"
      body = "Save long reads from Chrome, files, or a paste.\nEverything stays on this device and reads offline."
      primary = "Pair Chrome" to onPair
      secondary = "Add a first piece" to onAdd
    }
    tab == Triage.INBOX -> {
      title = "Inbox zero. Nice."
      body = "Everything has a home in Priority, Later, or Archive."
      secondary = "Browse Priority" to { onBrowse(Triage.PRIORITY) }
    }
    tab == Triage.PRIORITY -> {
      title = "Nothing prioritized."
      body = "Swipe any Inbox row right to lift it here.\nLong-press to pick several."
      secondary = "Browse Inbox" to { onBrowse(Triage.INBOX) }
    }
    tab == Triage.LATER -> {
      title = "Nothing saved for later."
      body = "Swipe any row left to park it here for the weekend."
      secondary = "Browse Inbox" to { onBrowse(Triage.INBOX) }
    }
    else -> {
      title = "No archived articles."
      body = "Finished pieces land here. They stay searchable, never count toward your reading time."
      secondary = "Browse Inbox" to { onBrowse(Triage.INBOX) }
    }
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(title, fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = colors.text)
      Spacer(Modifier.height(8.dp))
      Text(body, fontFamily = ReaderFonts.Ui, color = colors.secondary, fontSize = 15.sp)
      Spacer(Modifier.height(16.dp))
      primary?.let { (label, action) ->
        Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = colors.text, contentColor = colors.background)) {
          Text(label, fontFamily = ReaderFonts.Ui)
        }
      }
      secondary?.let { (label, action) ->
        TextButton(onClick = action, colors = ButtonDefaults.textButtonColors(contentColor = colors.text)) {
          Text(label, fontFamily = ReaderFonts.Ui)
        }
      }
      if (tab == Triage.INBOX && libraryEmpty) {
        Text("Tip: Share any page in Android → Reader.", fontFamily = ReaderFonts.Ui, color = colors.secondary, fontSize = 12.sp)
      }
    }
  }
}

@Composable
private fun AddOption(label: String, c: com.reader.app.ui.theme.ReaderColors, onClick: () -> Unit) {
  TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
    Text(label, fontFamily = ReaderFonts.Ui)
  }
}

@Composable
private fun TabText(text: String, count: Int?, selected: Boolean, color: com.reader.app.ui.theme.ReaderColors, onClick: () -> Unit) {
  Column(
    Modifier.heightIn(min = 48.dp)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick)
      .semantics {
        contentDescription = if (count != null) "$text, $count articles" else text
        stateDescription = if (selected) "Selected" else "Not selected"
      },
    verticalArrangement = Arrangement.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text, fontFamily = ReaderFonts.Ui, fontSize = 17.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) color.text else color.secondary,
      )
      if (count != null) {
        Spacer(Modifier.width(4.dp))
        Text(
          count.toString(), fontFamily = ReaderFonts.Ui, fontSize = 13.sp,
          color = if (selected) color.text else color.secondary,
        )
      }
    }
    Spacer(Modifier.height(2.dp))
    if (selected) Divider(color = color.focal, thickness = 2.dp, modifier = Modifier.width(28.dp))
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRow(
  doc: DocumentSummary,
  list: String,
  colors: com.reader.app.ui.theme.ReaderColors,
  selecting: Boolean,
  selected: Boolean,
  onOpen: () -> Unit,
  onLongPress: () -> Unit,
  onToggle: () -> Unit,
  onMenu: () -> Unit,
  onSwiped: (target: String) -> Unit,
  badges: List<LabelBadgeSpec> = emptyList(),
) {
  // Tap-vs-swipe arbitration, textbook pattern: stock clickable owns
  // taps (long-press enters selection); detectHorizontalDragGestures owns
  // horizontal swipes and sets a guard so the up that ends a swipe can't
  // double-fire onClick. Swipes are disabled while selecting.
  // NOTE (bug #1 post-mortem, 2026-09-04): row taps were never a gesture
  // problem — every detector variant fired fine. Navigation itself was
  // broken: MainActivity pushed routes + bumped a `tick` state nobody read,
  // so no recomposition ever rendered the new route. Fixed by keying the
  // route lookup on tick. (Also: Log.d is invisible on the TCL — verify
  // via screenshots/DB only.)
  var offset by remember(doc.documentId) { mutableFloatStateOf(0f) }
  var gestureDrag by remember { mutableStateOf(false) }
  val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
  var armed by remember { mutableStateOf(false) }
  val displayedOffset by animateFloatAsState(
    offset,
    if (gestureDrag) snap() else com.reader.app.ui.theme.settleSpec(),
    label = "Article swipe",
  )
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    // Same 30% move threshold as ArticleAction (.30f moves, .60f delete):
    // one muscle memory across triage rows, archive rows and highlights.
    val thresholdPx = widthPx * 0.30f
    fun settle(commit: Boolean) {
      val hit = if (commit && kotlin.math.abs(offset) > thresholdPx) {
        Triage.swipeTarget(list, if (offset > 0) Triage.Swipe.RIGHT else Triage.Swipe.LEFT)
      } else null
      offset = 0f
      gestureDrag = false
      armed = false
      if (hit != null) {
        Haptics.commit(haptics)
        onSwiped(hit)
      }
    }
    val target = if (offset > 1f) {
      Triage.swipeTarget(list, Triage.Swipe.RIGHT)
    } else if (offset < -1f) {
      Triage.swipeTarget(list, Triage.Swipe.LEFT)
    } else {
      null
    }
    if (target != null) {
      val nowArmed = kotlin.math.abs(offset) > thresholdPx
      if (nowArmed != armed) {
        armed = nowArmed

      }
      // Right brings nearer (green), left sets aside (blue). Warning is
      // reserved for the destructive arm (Archive delete, Remove highlight).
      val tint = if (target == Triage.PRIORITY || target == Triage.INBOX) colors.success else colors.link
      Box(
        Modifier.matchParentSize().padding(horizontal = 8.dp),
        contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            when (target) {
              Triage.PRIORITY -> Icons.Default.Star
              Triage.INBOX -> Icons.Default.MoveToInbox
              Triage.LATER -> Icons.Default.Schedule
              else -> Icons.Default.Inventory2
            },
            contentDescription = null, tint = tint, modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            Triage.tabLabel(target), fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, color = tint,
            modifier = Modifier.graphicsLayer {
              scaleX = if (armed) 1.15f else 1f
              scaleY = if (armed) 1.15f else 1f
            },
          )
        }
      }
    }
    // NOTE (2026-09-09): tap and long-press live on the inner ArticleRow
    // alone. An outer combinedClickable here would be shadowed by it (the
    // inner clickable consumes the down press), which once silently broke
    // long-press selection entry — verified on the TCL.
    Box(
      Modifier
        .offset { IntOffset(displayedOffset.roundToInt(), 0) }
        .pointerInput(list, widthPx, selecting) {
          if (selecting) return@pointerInput
          detectHorizontalDragGestures(
            onDragStart = { gestureDrag = true },
            onDragCancel = { settle(false) },
            onDragEnd = { settle(true) },
            onHorizontalDrag = { change, dx ->
              offset = (offset + dx).coerceIn(-widthPx, widthPx)
              change.consume()
            },
          )
        },
    ) {
      // gestureDrag keeps its original role: the up ending a swipe must not
      // double-fire the row tap.
      ArticleRow(doc, colors, onOpen = { if (!gestureDrag) onOpen() }, onMenu = if (selecting) null else onMenu,
        selecting = selecting, selected = selected, onToggleSelect = onToggle,
        onLongPress = onLongPress, badges = badges)
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(
  d: DocumentSummary,
  c: com.reader.app.ui.theme.ReaderColors,
  onOpen: () -> Unit,
  onMenu: (() -> Unit)?,
  selecting: Boolean = false,
  selected: Boolean = false,
  onToggleSelect: (() -> Unit)? = null,
  onLongPress: (() -> Unit)? = null,
  /** Compact descriptive label badges under the meta line; never a tap target. */
  badges: List<LabelBadgeSpec> = emptyList(),
) {
  val complete = d.finishedAt != null
  // Finished pieces are the memory layer, not a defect: full-strength title,
  // a small Done pill, and the finished date (+ highlight count) in meta.
  val dark = com.reader.app.ui.theme.LocalReaderDark.current
  val siteHue = ReaderCore.siteHue(d.sourceName, d.sourceUrl)
  val siteColor = siteHue?.let {
    androidx.compose.ui.graphics.Color.hsl(it, saturation = 0.48f, lightness = if (dark) 0.62f else 0.42f)
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
    if (siteColor != null) {
      // A quiet site cue rather than a competing bar: short and slightly
      // translucent. It never carries meaning on its own - the source name
      // always appears in the meta line directly below it.
      Box(
        Modifier.padding(top = 3.dp, end = 8.dp).width(3.dp).height(22.dp)
          .background(siteColor.copy(alpha = 0.6f)),
      )
    }
    if (selecting) {
      Checkbox(
        checked = selected, onCheckedChange = { onToggleSelect?.invoke() },
        modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Select ${d.title}" },
      )
    }
    Column(
      Modifier.weight(1f).semantics { if (selecting) stateDescription = if (selected) "Selected" else "Not selected" }.combinedClickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = { onOpen() },
        onLongClickLabel = "Select article",
        onLongClick = onLongPress,
      ),
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        d.title, fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        color = c.text,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
      )
      if (complete) {
        Spacer(Modifier.width(8.dp))
        // A positive Done pill instead of a half-faded title: attended, not
        // glitched. The check icon folds into the meta line below.
        Text(
          "Done", fontFamily = ReaderFonts.Ui, fontSize = 11.sp, color = c.text,
          modifier = Modifier.background(c.divider, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        )
      }
      // Tap opens (or toggles selection); ⋮ opens the row menu. The menu
      // is hidden while selecting to keep the selection gesture unambiguous.
      if (onMenu != null) {
        IconButton(onClick = onMenu) {
          Icon(Icons.Default.MoreVert, contentDescription = "Article options", tint = c.text.copy(alpha = 0.6f))
        }
      }
    }
    Spacer(Modifier.height(2.dp))
    val mins = ReaderCore.readingMinutes(d.wordCount)
    val left = ReaderCore.timeLeft(mins, d.progressFraction)
    val metaLine = when {
      d.sourceType == "link" -> "${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · Link only"
      complete -> {
        val finished = ReaderCore.formatAge(if (d.finishedAt != null) d.finishedAt else d.createdAt)
        val hl = d.highlightCount
        "${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · Finished $finished${if (hl > 0) " · $hl highlight${if (hl == 1) "" else "s"}" else ""}"
      }
      d.progressFraction >= .999f -> "${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · At end"
      left != null -> "${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · ${left.coerceAtLeast(1)} min left · ${ReaderCore.formatAge(d.createdAt)}"
      else -> "${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · $mins min · Unread"
    }
    Text(metaLine, fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.secondary, maxLines = 1)
    if (badges.isNotEmpty()) {
      Spacer(Modifier.height(6.dp))
      LabelBadgesRow(badges)
    }
    if (d.progressFraction > 0.01f && d.progressFraction < 0.999f) {
      Spacer(Modifier.height(6.dp))
      // Subordinate to the title: progress informs, it does not compete.
      LinearProgressIndicator(
        progress = d.progressFraction.coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = c.secondary, trackColor = c.divider,
      )
    }
    }
  }
}


@Composable
private fun ArchiveSwipeRow(doc: DocumentSummary, colors: com.reader.app.ui.theme.ReaderColors,
                            selecting: Boolean, selected: Boolean,
                            onOpen: () -> Unit, onLongPress: () -> Unit, onToggle: () -> Unit,
                            onMenu: () -> Unit, onAction: (ArticleAction) -> Unit,
                            badges: List<LabelBadgeSpec> = emptyList()) {
  var offset by remember(doc.documentId) { mutableFloatStateOf(0f) }
  var dragging by remember { mutableStateOf(false) }
  val latestAction by rememberUpdatedState(onAction)
  val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
  val view = androidx.compose.ui.platform.LocalView.current
  var wasArmed by remember { mutableStateOf(false) }
  val displayedOffset by animateFloatAsState(
    offset,
    if (dragging) snap() else com.reader.app.ui.theme.settleSpec(),
    label = "Archive swipe",
  )
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val width = with(LocalDensity.current) { maxWidth.toPx() }
    val action = ArticleAction.forArticle(Triage.ARCHIVED, offset > 0)!!
    val armed = ArticleAction.commits(action, offset, width)
    if (offset != 0f) Row(Modifier.matchParentSize().padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = if (offset > 0) Arrangement.Start else Arrangement.End) {
      val tint = if (action == ArticleAction.Delete) colors.error else colors.link
      Icon(if (action == ArticleAction.Delete) Icons.Default.Delete else Icons.Default.MoveToInbox, null, tint = tint)
      Spacer(Modifier.width(6.dp))
      Text(
        if (armed) action.releaseLabel else action.label, color = tint, fontFamily = ReaderFonts.Ui, fontSize = 13.sp,
        modifier = Modifier.graphicsLayer {
          scaleX = if (armed) 1.15f else 1f
          scaleY = if (armed) 1.15f else 1f
        },
      )
    }
    Box(Modifier.offset { IntOffset(displayedOffset.roundToInt(), 0) }.background(colors.background).pointerInput(doc.documentId, width, selecting) {
      if (selecting) return@pointerInput
      detectHorizontalDragGestures(
        onDragStart = { dragging = true },
        onDragCancel = { offset = 0f; dragging = false; wasArmed = false },
        onDragEnd = {
          val selected = ArticleAction.forArticle(Triage.ARCHIVED, offset > 0)!!
          val commit = ArticleAction.commits(selected, offset, width)
          offset = 0f; dragging = false; wasArmed = false
          if (commit) {
            Haptics.commit(haptics)
            latestAction(selected)
          }
        },
        onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-width, width) },
      )
    }) {
      ArticleRow(doc, colors,
        onOpen = { if (!dragging) onOpen() },
        onMenu = if (selecting) null else onMenu,
        selecting = selecting, selected = selected, onToggleSelect = onToggle,
        onLongPress = onLongPress, badges = badges)
    }
  }
}

/** Offline library search UI. Deterministic, no network, no new routes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchContent(
  query: String,
  onQuery: (String) -> Unit,
  onSubmit: () -> Unit,
  scope: com.reader.app.data.SearchScope,
  onScope: (com.reader.app.data.SearchScope) -> Unit,
  results: com.reader.app.data.SearchResults?,
  recents: List<String>,
  onRecentTap: (String) -> Unit,
  onRecentRemove: (String) -> Unit,
  onRecentsClear: () -> Unit,
  listState: androidx.compose.foundation.lazy.LazyListState,
  onOpenResult: (String) -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors,
) {
  val focus = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  // Autofocus a fresh search, but never steal the keyboard when returning
  // from an article into an existing query.
  LaunchedEffect(Unit) { if (query.isBlank()) focus.requestFocus() }
  Column(Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = query,
      onValueChange = onQuery,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus)
        .semantics { contentDescription = "Search library" },
      placeholder = { Text("Search library", fontFamily = ReaderFonts.Ui) },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = colors.secondary) },
      trailingIcon = {
        if (query.isNotBlank()) IconButton(onClick = { onQuery("") }) {
          Icon(Icons.Default.Close, contentDescription = "Clear search", tint = colors.secondary)
        }
      },
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        // Exact queries anchor scroll positions: never let autocorrect
        // silently rewrite proper nouns, code or CJK into something else.
        autoCorrect = false,
        imeAction = ImeAction.Search,
      ),
      keyboardActions = KeyboardActions(onSearch = {
        onSubmit()
        focusManager.clearFocus()
      }),
    )
    Spacer(Modifier.height(4.dp))
    Text(
      "Matches ignore case and accents",
      fontFamily = ReaderFonts.Ui, fontSize = 12.sp, color = colors.secondary,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(4.dp))
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SearchScopeChip("All", scope == com.reader.app.data.SearchScope.ALL, colors) {
        onScope(com.reader.app.data.SearchScope.ALL)
      }
      SearchScopeChip("Titles", scope == com.reader.app.data.SearchScope.TITLES, colors) {
        onScope(com.reader.app.data.SearchScope.TITLES)
      }
      SearchScopeChip("This list", scope == com.reader.app.data.SearchScope.THIS_LIST, colors) {
        onScope(com.reader.app.data.SearchScope.THIS_LIST)
      }
    }
    Spacer(Modifier.height(4.dp))
    if (query.isBlank()) {
      if (recents.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("Type two or more letters to search titles and article text.\nEverything stays on this device.",
            fontFamily = ReaderFonts.Ui, color = colors.secondary, fontSize = 15.sp)
        }
      } else {
        Text("Recent", fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = colors.secondary,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
          items(recents, key = { "recent:$it" }) { recent ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.History, contentDescription = null, tint = colors.secondary,
                modifier = Modifier.padding(end = 12.dp))
              Text(recent, fontFamily = ReaderFonts.Ui, fontSize = 16.sp, color = colors.text,
                modifier = Modifier.weight(1f).combinedClickable(
                  indication = null,
                  interactionSource = remember { MutableInteractionSource() },
                  onClick = { onRecentTap(recent) },
                ),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
              IconButton(onClick = { onRecentRemove(recent) }) {
                Icon(Icons.Default.Close, contentDescription = "Remove $recent", tint = colors.secondary)
              }
            }
            Divider(color = colors.divider, thickness = 0.5.dp)
          }
          item {
            TextButton(onClick = onRecentsClear) {
              Text("Clear all", fontFamily = ReaderFonts.Ui, color = colors.secondary)
            }
            Text("On this device only", fontFamily = ReaderFonts.Ui, fontSize = 12.sp, color = colors.secondary,
              modifier = Modifier.padding(bottom = 16.dp))
          }
        }
      }
      return@Column
    }
    val rows = results?.rows.orEmpty()
    Text(
      if (rows.isEmpty()) "No results for “$query”" else resultCountText(rows.size, query),
      fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = colors.secondary,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        .semantics { liveRegion = LiveRegionMode.Polite; stateDescription = countState(rows.size, query) },
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    if (rows.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp)) {
          Text("No results for “$query”.", fontFamily = ReaderFonts.Ui, color = colors.text, fontSize = 16.sp)
          Spacer(Modifier.height(8.dp))
          Text("• Try fewer words\n• Check spelling\n• Browse the Inbox, Priority and Later tabs",
            fontFamily = ReaderFonts.Ui, color = colors.secondary, fontSize = 15.sp)
          Spacer(Modifier.height(12.dp))
          TextButton(onClick = { onQuery("") }) {
            Text("Clear search", fontFamily = ReaderFonts.Ui, color = colors.text)
          }
        }
      }
      return@Column
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
      items(rows, key = { it.documentId }) { row ->
        SearchResultRow(row, colors, onOpen = { onOpenResult(row.documentId) })
        Divider(color = colors.divider, thickness = 0.5.dp)
      }
      if (rows.size >= 50) {
        item {
          Text("Showing first 50 — keep typing to narrow",
            fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = colors.secondary,
            modifier = Modifier.padding(vertical = 12.dp))
        }
      }
    }
  }
}

private fun resultCountText(n: Int, query: String): String =
  if (n == 1) "1 result for “$query”" else "$n results for “$query”"

private fun countState(n: Int, query: String): String =
  if (n == 0) "No results" else resultCountText(n, query)

@Composable
private fun SearchScopeChip(
  label: String,
  selected: Boolean,
  colors: com.reader.app.ui.theme.ReaderColors,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected, onClick = onClick,
    label = { Text(label, fontFamily = ReaderFonts.Ui) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = colors.text, selectedLabelColor = colors.background,
      containerColor = colors.background, labelColor = colors.text,
    ),
    border = FilterChipDefaults.filterChipBorder(
      borderColor = colors.divider, selectedBorderColor = colors.text,
      enabled = true, selected = selected,
    ),
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
  row: com.reader.app.data.SearchRow,
  colors: com.reader.app.ui.theme.ReaderColors,
  onOpen: () -> Unit,
) {
  val mins = ReaderCore.readingMinutes(row.wordCount)
  Column(
    Modifier.fillMaxWidth().padding(vertical = 12.dp)
      .semantics(mergeDescendants = true) {
        contentDescription = "${row.title}, in ${Triage.tabLabel(row.list)}, $mins minutes"
        onClick(label = "Open at match", action = { onOpen(); true })
      }
      .combinedClickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onOpen,
      ),
  ) {
    Text(row.title, fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
      color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(2.dp))
    Text("${Triage.tabLabel(row.list)} · ${ReaderCore.shortDisplaySource(row.sourceType, row.sourceName, row.sourceUrl)} · $mins min",
      fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = colors.secondary, maxLines = 1,
      overflow = TextOverflow.Ellipsis)
    row.bodySnippet?.takeIf { it.isNotBlank() }?.let { snippet ->
      Spacer(Modifier.height(2.dp))
      Text(snippetAnnotated(snippet), fontFamily = ReaderFonts.Ui, fontSize = 14.sp,
        color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** Renders FTS <b> sentinels as bold; anything else is literal text. */
private fun snippetAnnotated(raw: String): AnnotatedString = buildAnnotatedString {
  var i = 0
  while (true) {
    val open = raw.indexOf("<b>", i)
    if (open < 0) {
      append(raw.substring(i))
      break
    }
    append(raw.substring(i, open))
    val close = raw.indexOf("</b>", open)
    if (close < 0) {
      append(raw.substring(open))
      break
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(open + 3, close)) }
    i = close + 4
  }
}

/** Single predictable library predicate: tab scope, then age, then sort. Order only, never membership, from sort. */
private fun applySortFilter(
  list: List<DocumentSummary>,
  sort: com.reader.app.prefs.LibrarySort,
  age: com.reader.app.prefs.AgeFilter,
  labelNorm: String? = null,
  labelsByDoc: Map<String, Set<String>> = emptyMap(),
): List<DocumentSummary> {
  var out = if (labelNorm == null) list
  else list.filter { labelsByDoc[it.documentId]?.contains(labelNorm) == true }
  out = when (age) {
    com.reader.app.prefs.AgeFilter.ANY -> out
    else -> out.filter { Triage.ageMatches(it.createdAt, age) }
  }
  return when (sort) {
    com.reader.app.prefs.LibrarySort.NEWEST -> out.sortedWith(compareByDescending<DocumentSummary> { it.createdAt }.thenBy { it.documentId })
    com.reader.app.prefs.LibrarySort.OLDEST -> out.sortedWith(compareBy<DocumentSummary> { it.createdAt }.thenBy { it.documentId })
    com.reader.app.prefs.LibrarySort.QUICKEST -> out.sortedWith(compareBy<DocumentSummary> { it.wordCount }.thenByDescending { it.createdAt })
    com.reader.app.prefs.LibrarySort.LONGEST -> out.sortedWith(compareByDescending<DocumentSummary> { it.wordCount }.thenByDescending { it.createdAt })
    com.reader.app.prefs.LibrarySort.TITLE -> out.sortedWith(compareBy<DocumentSummary> { it.title.lowercase(java.util.Locale.getDefault()) }.thenByDescending { it.createdAt })
  }
}

private fun sortLabel(sort: com.reader.app.prefs.LibrarySort): String = when (sort) {
  com.reader.app.prefs.LibrarySort.NEWEST -> "Newest"
  com.reader.app.prefs.LibrarySort.OLDEST -> "Oldest"
  com.reader.app.prefs.LibrarySort.QUICKEST -> "Quickest"
  com.reader.app.prefs.LibrarySort.LONGEST -> "Longest"
  com.reader.app.prefs.LibrarySort.TITLE -> "Title"
}

@Composable
private fun SortMenu(
  sort: com.reader.app.prefs.LibrarySort,
  onSort: (com.reader.app.prefs.LibrarySort) -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { expanded = true },
      colors = ButtonDefaults.textButtonColors(contentColor = colors.text),
      modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Sort order, currently ${sortLabel(sort)}" },
    ) { Text("Sort: ${sortLabel(sort)}", fontFamily = ReaderFonts.Ui) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      com.reader.app.prefs.LibrarySort.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(sortLabel(option), fontFamily = ReaderFonts.Ui, color = colors.text) },
          leadingIcon = {
            RadioButton(
              selected = option == sort, onClick = null,
              colors = RadioButtonDefaults.colors(selectedColor = colors.text, unselectedColor = colors.secondary),
            )
          },
          onClick = { expanded = false; onSort(option) },
        )
      }
    }
  }
}

@Composable
private fun AgeChip(
  label: String,
  selected: Boolean,
  colors: com.reader.app.ui.theme.ReaderColors,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected, onClick = onClick,
    label = { Text(label, fontFamily = ReaderFonts.Ui) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = colors.text, selectedLabelColor = colors.background,
      containerColor = colors.background, labelColor = colors.text,
    ),
    border = FilterChipDefaults.filterChipBorder(
      borderColor = colors.divider, selectedBorderColor = colors.text,
      enabled = true, selected = selected,
    ),
  )
}

/** Filtered-to-empty: name the active filter and offer one tap back. */
@Composable
private fun FilterEmpty(
  tab: String,
  age: com.reader.app.prefs.AgeFilter,
  labelName: String? = null,
  onClear: () -> Unit,
  colors: com.reader.app.ui.theme.ReaderColors,
) {
  val ageText = when (age) {
    com.reader.app.prefs.AgeFilter.TODAY -> " from today"
    com.reader.app.prefs.AgeFilter.WEEK -> " from the last 7 days"
    com.reader.app.prefs.AgeFilter.MONTH -> " from the last 30 days"
    com.reader.app.prefs.AgeFilter.OLDER -> " older than 30 days"
    com.reader.app.prefs.AgeFilter.ANY -> ""
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        buildString {
          append("Nothing")
          append(ageText)
          if (labelName != null) append(" labeled “$labelName”")
          append(" in ${Triage.tabLabel(tab)}.")
        },
        fontFamily = ReaderFonts.Ui,
        fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = colors.text,
      )
      Spacer(Modifier.height(8.dp))
      Text("Try a wider age range, or clear the sort.", fontFamily = ReaderFonts.Ui,
        color = colors.secondary, fontSize = 15.sp)
      Spacer(Modifier.height(4.dp))
      TextButton(onClick = onClear, colors = ButtonDefaults.textButtonColors(contentColor = colors.text)) {
        Text("Show everything", fontFamily = ReaderFonts.Ui)
      }
    }
  }
}

private fun UriHost(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "article"
