package com.reader.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.foundation.background
import com.reader.app.ui.ArticleAction
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentSummary
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.Triage
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.appColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
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

) {
  val c = appColors()
  val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
  val tab = if (archiveMode) Triage.ARCHIVED else selectedTab
  BackHandler(enabled = archiveMode, onBack = onArchiveBack)
  LaunchedEffect(loaded, lists[Triage.INBOX]?.size, tab) {
    if (!archiveMode && selectedTab == Triage.ARCHIVED) onSelectTab(Triage.PRIORITY)
    if (loaded && lists[Triage.INBOX].isNullOrEmpty() && tab == Triage.INBOX) onSelectTab(Triage.PRIORITY)
  }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var confirmDeleteId by remember { mutableStateOf<String?>(null) }
  var labelDialogIds by remember { mutableStateOf<Set<String>?>(null) }
  var rowMenu by remember { mutableStateOf<DocumentSummary?>(null) }
  // Long-press multi-select. Only already-legal actions are offered:
  // Archive in the triage lists, Unarchive in Archive. Article deletion
  // stays archive-only single-item flow; it is never batched here.
  var selecting by rememberSaveable { mutableStateOf(false) }
  var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
  BackHandler(enabled = selecting) { selecting = false; selectedIds = emptyList() }
  // Search dismisses before anything else: first Back clears the query,
  // second collapses the field. Registered last so it wins while active.
  BackHandler(enabled = searchActive) {
    if (searchText.isNotBlank()) onSearchText("") else onToggleSearch()
  }
  LaunchedEffect(tab, archiveMode, lists[tab]) {
    val live = lists[tab].orEmpty().mapTo(hashSetOf()) { it.documentId }
    selectedIds = selectedIds.filter { it in live }
    if (selectedIds.isEmpty()) selecting = false
  }
  fun toggleSelect(id: String) {
    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
  }
  var showAdd by remember { mutableStateOf(false) }
  var showPaste by remember { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(readerMove?.id) {    val move = readerMove ?: return@LaunchedEffect
    try {
      // Action labels default to Indefinite in Material. Always set a finite
      // duration; accessibility may extend it. A newer notice cancels this one.
      snackbar.currentSnackbarData?.dismiss()
      if (snackbar.showSnackbar(move.message, "Undo", withDismissAction = true,
          duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndoMove(move.moves)
    } finally { onReaderMoveConsumed(move.id) }
  }
  // Finish + milestone notices: same finite-duration contract as moves.
  LaunchedEffect(finishNotice) {
    val notice = finishNotice ?: return@LaunchedEffect
    try {
      snackbar.currentSnackbarData?.dismiss()
      if (snackbar.showSnackbar(notice, "View Archive", withDismissAction = true,
          duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onFinishNoticeAction()
    } finally { onFinishNoticeConsumed() }
  }
  Scaffold(
    containerColor = c.background,
    snackbarHost = {
      SnackbarHost(snackbar) { data ->
        Snackbar(
          snackbarData = data, containerColor = c.text, contentColor = c.background,
          actionColor = if (com.reader.app.ui.theme.LocalReaderDark.current) com.reader.app.ui.theme.Flexoki.Blue600 else com.reader.app.ui.theme.Flexoki.Blue400,
        )
      }
    },
    bottomBar = {
      if (selecting) {
        // Bottom bar (not in-flow): the list never jumps on long-press.
        val liveIds = (if (searchActive) searchResults?.rows?.map { it.documentId } else lists[tab]?.map { it.documentId }).orEmpty()
        Row(
          Modifier.fillMaxWidth().background(c.background).padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = { selecting = false; selectedIds = emptyList() }) {
            Icon(Icons.Default.Close, contentDescription = "Done selecting", tint = c.text)
          }
          Text("${selectedIds.size}", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.text,
            modifier = Modifier.semantics { stateDescription = "${selectedIds.size} selected" })
          Spacer(Modifier.width(4.dp))
          val targets = if (tab == Triage.ARCHIVED) listOf(Triage.INBOX)
          else listOf(Triage.PRIORITY, Triage.LATER, Triage.INBOX, Triage.ARCHIVED).filter { it != tab }
          // Labels chip arrives with Batch 3; the filter row hosts it.
          targets.forEach { target ->
            TextButton(
              onClick = {
                val ids = selectedIds.toSet()
                selecting = false; selectedIds = emptyList()
                onMove(ids, target)
              },
              enabled = selectedIds.isNotEmpty(),
              colors = ButtonDefaults.textButtonColors(contentColor = c.text),
            ) { Text(Triage.tabLabel(target), fontFamily = ReaderFonts.Ui) }
          }
          if (selectedIds.size < liveIds.size) {
            TextButton(onClick = { selectedIds = liveIds }) {
              Text("Select all", fontFamily = ReaderFonts.Ui, color = c.text)
            }
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = {
            labelDialogIds = selectedIds.toSet()
          }, enabled = selectedIds.isNotEmpty()) {
            Text("Label", fontFamily = ReaderFonts.Ui, color = c.text)
          }
          TextButton(onClick = { selecting = false; selectedIds = emptyList() }) {
            Text("Done", fontFamily = ReaderFonts.Ui, color = c.text)
          }
        }
      }
    },
    topBar = {
      Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (archiveMode) IconButton(onClick = onArchiveBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(if (archiveMode) "Archive" else "Reader", fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.text, modifier = Modifier.weight(1f))
        IconButton(onClick = { showAdd = true }) {
          Icon(Icons.Default.Add, contentDescription = "Import, paste, or pair", tint = c.text)
        }
        // The Archive destination has its own Back control; a disabled
        // Archive icon here added visual noise without an action.
        if (!archiveMode) IconButton(onClick = onArchiveOpen) {
          Icon(Icons.Default.Archive, contentDescription = "Open archive", tint = c.text)
        }
        IconButton(onClick = onToggleSearch) {
          Icon(Icons.Default.Search, contentDescription = if (searchActive) "Close search" else "Search library", tint = c.text)
        }
        IconButton(onClick = onSettings) {
          Icon(Icons.Default.Settings, contentDescription = "Settings and devices", tint = c.text)
        }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad)) {
      if (!archiveMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
          Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          (listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER, "highlights").filter { it != Triage.INBOX || !loaded || !lists[Triage.INBOX].isNullOrEmpty() }).forEachIndexed { i, key ->
            if (i > 0) Spacer(Modifier.width(20.dp))
            val count = if (key == "highlights") highlightCount else lists[key]?.size
            TabText(Triage.tabLabel(key), count, selected = tab == key, color = c, onClick = { onSelectTab(key) })
          }
        }
        // Pinned outside the scroll: the time can never collide with tabs,
        // and Highlights (which has no minutes) shows nothing, not "0m".
        if (tab != "highlights") Text(
          ReaderCore.formatAttention(minutes[tab] ?: 0),
          fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary,
          maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 20.dp),
        )
      }
      val list = lists[tab].orEmpty()
      // One predictable predicate: tab scope, label, age, then sort. Search
      // mode bypasses this entirely (relevance rules instead).
      val displayList = remember(list, sort, age, selectedLabelNorm, labelsByDoc) {
        applySortFilter(list, sort, age, selectedLabelNorm, labelsByDoc)
      }
      if (!searchActive && tab != "highlights") {
        Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SortMenu(sort, onSort, c)
          AgeChip("Any time", age == com.reader.app.prefs.AgeFilter.ANY, c) { onAge(com.reader.app.prefs.AgeFilter.ANY) }
          AgeChip("Today", age == com.reader.app.prefs.AgeFilter.TODAY, c) { onAge(com.reader.app.prefs.AgeFilter.TODAY) }
          AgeChip("Past week", age == com.reader.app.prefs.AgeFilter.WEEK, c) { onAge(com.reader.app.prefs.AgeFilter.WEEK) }
          AgeChip("Past month", age == com.reader.app.prefs.AgeFilter.MONTH, c) { onAge(com.reader.app.prefs.AgeFilter.MONTH) }
          AgeChip("Older", age == com.reader.app.prefs.AgeFilter.OLDER, c) { onAge(com.reader.app.prefs.AgeFilter.OLDER) }
          labelCounts.forEach { label ->
            val selected = selectedLabelNorm == label.normalized
            FilterChip(
              selected = selected, onClick = { onLabelSelect(if (selected) null else label.normalized) },
              label = { Text("#${label.name} · ${label.count}", fontFamily = ReaderFonts.Ui) },
              modifier = Modifier.semantics {
                contentDescription = "Filter by label ${label.name}, ${label.count} articles"
                stateDescription = if (selected) "Selected" else "Not selected"
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = c.text, selectedLabelColor = c.background,
                containerColor = c.background, labelColor = c.text,
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = c.divider, selectedBorderColor = c.text,
                enabled = true, selected = selected,
              ),
            )
          }
          if (sort != com.reader.app.prefs.LibrarySort.NEWEST || age != com.reader.app.prefs.AgeFilter.ANY || selectedLabelNorm != null) {
            TextButton(onClick = {
              onSort(com.reader.app.prefs.LibrarySort.NEWEST)
              onAge(com.reader.app.prefs.AgeFilter.ANY)
              onLabelSelect(null)
            }) {
              Text("Clear", fontFamily = ReaderFonts.Ui, color = c.text)
            }
          }
        }
      }
      if (!loaded) CircularProgressIndicator(Modifier.padding(24.dp))
      else if (searchActive) SearchContent(
        query = searchText, onQuery = onSearchText, onSubmit = onSubmitSearch,
        scope = searchScope, onScope = onSearchScope,
        results = searchResults, recents = searchRecents,
        onRecentTap = onRecentTap, onRecentRemove = onRecentRemove, onRecentsClear = onRecentsClear,
        listState = searchListState, onOpenResult = onOpenResult, colors = c,
      )
      else if (tab == "highlights") highlights()
      else if (displayList.isEmpty()) {
        if (list.isNotEmpty()) {
          val labelName = selectedLabelNorm?.let { norm ->
            labelCounts.firstOrNull { it.normalized == norm }?.name ?: norm
          }
          FilterEmpty(
            tab = tab, age = age, labelName = labelName,
            onClear = {
              onSort(com.reader.app.prefs.LibrarySort.NEWEST)
              onAge(com.reader.app.prefs.AgeFilter.ANY)
              onLabelSelect(null)
            },
            colors = c,
          )
        } else {
          EmptyShelf(
            tab = tab,
            libraryEmpty = (lists[Triage.INBOX].orEmpty() + lists[Triage.PRIORITY].orEmpty() +
              lists[Triage.LATER].orEmpty() + lists[Triage.ARCHIVED].orEmpty()).isEmpty(),
            onPair = onPair,
            onAdd = { showAdd = true },
            onBrowse = onSelectTab,
          )
        }
      } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
          items(displayList, key = { it.documentId }) { d ->
            if (tab == Triage.ARCHIVED) {
              ArchiveSwipeRow(d, c,
                selecting = selecting, selected = d.documentId in selectedIds,
                onOpen = { if (selecting) toggleSelect(d.documentId) else onOpen(d.documentId) },
                onLongPress = {
                  haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                  if (!selecting) { selecting = true; selectedIds = listOf(d.documentId) }
                  else toggleSelect(d.documentId)
                },
                onToggle = { toggleSelect(d.documentId) },
                onMenu = { menuFor = d.documentId }, onAction = { action ->
                if (action == ArticleAction.Delete) confirmDeleteId = d.documentId else onMove(setOf(d.documentId), Triage.INBOX)
              })
            } else {
              SwipeRow(
                doc = d, list = tab, colors = c,
                selecting = selecting, selected = d.documentId in selectedIds,
                onOpen = { if (selecting) toggleSelect(d.documentId) else onOpen(d.documentId) },
                onLongPress = {
                  haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                  if (!selecting) { selecting = true; selectedIds = listOf(d.documentId) }
                  else toggleSelect(d.documentId)
                },
                onToggle = { toggleSelect(d.documentId) },
                onMenu = { rowMenu = d },
                onSwiped = { target -> onMove(setOf(d.documentId), target) },
              )
            }
            Divider(color = c.divider, thickness = 0.5.dp)
          }
        }
      }
    }
  }
  if (showAdd) {
    AlertDialog(
      onDismissRequest = { showAdd = false },
      containerColor = c.background,
      title = { Text("Add", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Column {
          AddOption("Import file", c) { showAdd = false; onImportFile() }
          AddOption("Paste text, markdown, or link", c) { showAdd = false; showPaste = true }
          AddOption("Pair Chrome", c) { showAdd = false; onPair() }
        }
      },
      confirmButton = {},
    )
  }
  if (showPaste) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showPaste = false },
      containerColor = c.background,
      title = { Text("Paste", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        OutlinedTextField(
          value = text, onValueChange = { text = it },
          placeholder = { Text("Paste text, markdown, or an article link…", fontFamily = ReaderFonts.Ui) },
          modifier = Modifier.fillMaxWidth().height(220.dp),
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
      },
      confirmButton = {
        TextButton(
          onClick = { showPaste = false; onPasteText(text) },
          enabled = text.isNotBlank(),
          colors = ButtonDefaults.textButtonColors(contentColor = c.text),
        ) { Text("Save", fontFamily = ReaderFonts.Ui) }
      },
      dismissButton = {
        TextButton(onClick = { showPaste = false }, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
          Text("Cancel", fontFamily = ReaderFonts.Ui)
        }
      },
    )
  }
  menuFor?.let { id ->
    val docTitle = lists[Triage.ARCHIVED].orEmpty().firstOrNull { it.documentId == id }?.title
      ?.take(60).orEmpty()
    AlertDialog(
      onDismissRequest = { menuFor = null },
      containerColor = c.background,
      title = { Text(docTitle.ifBlank { "Article" }, fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Column {
          Text("Unarchive sends it back to Inbox. Delete is permanent.", fontFamily = ReaderFonts.Ui, color = c.text)
          TextButton(onClick = { menuFor = null; selecting = true; selectedIds = listOf(id) }) {
            Text("Select", fontFamily = ReaderFonts.Ui, color = c.text)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { menuFor = null; onMove(setOf(id), Triage.INBOX) }, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
          Text("Move to Inbox", fontFamily = ReaderFonts.Ui)
        }
      },
      dismissButton = {
        TextButton(onClick = { menuFor = null; confirmDeleteId = id }, colors = ButtonDefaults.textButtonColors(contentColor = c.error)) {
          Text("Delete forever", fontFamily = ReaderFonts.Ui)
        }
      },
    )
  }
  confirmDeleteId?.let { id ->
    val docTitle = lists[Triage.ARCHIVED].orEmpty().firstOrNull { it.documentId == id }?.title
      ?.take(80).orEmpty()
    AlertDialog(
      onDismissRequest = { confirmDeleteId = null },
      containerColor = c.background,
      title = { Text("Delete forever?", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Text(
          "“$docTitle” will be permanently deleted. Highlights you saved stay in Highlights. This cannot be undone.",
          fontFamily = ReaderFonts.Ui, color = c.text,
        )
      },
      confirmButton = {
        TextButton(onClick = { confirmDeleteId = null; onDelete(id) }, colors = ButtonDefaults.textButtonColors(contentColor = c.error)) {
          Text("Delete forever", fontFamily = ReaderFonts.Ui)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDeleteId = null }, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
          Text("Cancel", fontFamily = ReaderFonts.Ui)
        }
      },
    )
  }
  // Non-gesture equivalents for row swipes: every list row offers the same
  // moves as its swipe directions plus Archive, with Undo via snackbar.
  // Permanent deletion stays archive-only and is not offered here.
  rowMenu?.let { d ->
    val previous = tab
    fun move(target: String) {
      rowMenu = null
      if (target == previous) return
      onMove(setOf(d.documentId), target)
    }
    AlertDialog(
      onDismissRequest = { rowMenu = null },
      containerColor = c.background,
      title = { Text("Move article", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          if (previous != Triage.PRIORITY) TextButton(onClick = { move(Triage.PRIORITY) }) { Text("Move to Priority", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.INBOX) TextButton(onClick = { move(Triage.INBOX) }) { Text("Move to Inbox", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.LATER) TextButton(onClick = { move(Triage.LATER) }) { Text("Save for later", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.ARCHIVED) TextButton(onClick = { move(Triage.ARCHIVED) }) { Text("Archive", fontFamily = ReaderFonts.Ui, color = c.text) }
          TextButton(onClick = { val target = d.documentId; rowMenu = null; labelDialogIds = setOf(target) }) {
            Text("Edit labels", fontFamily = ReaderFonts.Ui, color = c.text)
          }
          TextButton(onClick = { val target = d.documentId; rowMenu = null; selecting = true; selectedIds = listOf(target) }) {
            Text("Select", fontFamily = ReaderFonts.Ui, color = c.text)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { rowMenu = null; onOpen(d.documentId) }) { Text("Open", fontFamily = ReaderFonts.Ui, color = c.text) }
      },
      dismissButton = {
        TextButton(onClick = { rowMenu = null }) { Text("Cancel", fontFamily = ReaderFonts.Ui, color = c.text) }
      },
    )
  }
  labelDialogIds?.let { ids ->
    val counts = remember(ids, labelsByDoc, labelCounts) {
      ids.flatMap { labelsByDoc[it].orEmpty() }.groupingBy { it }.eachCount()
        .mapNotNull { (norm, n) ->
          labelCounts.firstOrNull { it.normalized == norm }?.let { it.name to n }
        }.toMap()
    }
    LabelsDialog(
      title = if (ids.size == 1) "Labels" else "Labels (${ids.size} articles)",
      assignedCounts = counts,
      totalDocs = ids.size,
      suggestions = labelCounts.map { it.name },
      onToggle = { onToggleLabel(ids, it) },
      onDismiss = { labelDialogIds = null },
      colors = c,
    )
  }
}

private fun emptyHint(tab: String): String = when (tab) {
  Triage.INBOX -> "Nothing here yet.\nSend something from Chrome."
  Triage.PRIORITY -> "Nothing prioritized.\nSwipe a row right in the Inbox."
  Triage.LATER -> "Nothing saved for later."
  else -> "No archived articles."
}

/** Warm empty states with a way forward. Never a dead end. */
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
      title = "Your quiet shelf awaits"
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
      title = "Archive is empty."
      body = "Finished pieces land here. They stay searchable, never count toward your reading time."
      secondary = "Browse library" to { onBrowse(Triage.INBOX) }
    }
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(title, fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = colors.text)
      Spacer(Modifier.height(8.dp))
      Text(body, fontFamily = ReaderFonts.Ui, color = colors.secondary, fontSize = 15.sp)
      Spacer(Modifier.height(4.dp))
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
    if (gestureDrag) snap() else tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
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
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
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
        if (nowArmed) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
      }
      val tint = if (target == Triage.PRIORITY) colors.success else colors.warning
      Box(
        Modifier.matchParentSize().padding(horizontal = 8.dp),
        contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd,
      ) {
        Text(
          Triage.tabLabel(target), fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.Bold,
          fontSize = 14.sp, color = tint,
        )
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
        onLongPress = onLongPress)
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
) {
  val complete = d.progressFraction >= 0.999f
  Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
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
        color = if (complete) c.text.copy(alpha = 0.55f) else c.text,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
      )
      if (complete) {
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.Check, contentDescription = "Read", tint = c.secondary)
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
    Text("${ReaderCore.shortDisplaySource(d.sourceType, d.sourceName, d.sourceUrl)} · ${mins} min · ${ReaderCore.formatAge(d.createdAt)}", fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.secondary, maxLines = 1)
    if (d.progressFraction > 0.01f && d.progressFraction < 0.999f) {
      Spacer(Modifier.height(6.dp))
      LinearProgressIndicator(
        progress = d.progressFraction.coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = c.text, trackColor = c.divider,
      )
    }
    }
  }
}


@Composable
private fun ArchiveSwipeRow(doc: DocumentSummary, colors: com.reader.app.ui.theme.ReaderColors,
                            selecting: Boolean, selected: Boolean,
                            onOpen: () -> Unit, onLongPress: () -> Unit, onToggle: () -> Unit,
                            onMenu: () -> Unit, onAction: (ArticleAction) -> Unit) {
  var offset by remember(doc.documentId) { mutableFloatStateOf(0f) }
  var dragging by remember { mutableStateOf(false) }
  val latestAction by rememberUpdatedState(onAction)
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
      Text(if (armed) action.releaseLabel else action.label, color = tint, fontFamily = ReaderFonts.Ui, fontSize = 13.sp)
    }
    Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.background(colors.background).pointerInput(doc.documentId, width, selecting) {
      if (selecting) return@pointerInput
      detectHorizontalDragGestures(
        onDragStart = { dragging = true },
        onDragCancel = { offset = 0f; dragging = false },
        onDragEnd = {
          val selected = ArticleAction.forArticle(Triage.ARCHIVED, offset > 0)!!
          val commit = ArticleAction.commits(selected, offset, width)
          offset = 0f; dragging = false
          if (commit) latestAction(selected)
        },
        onHorizontalDrag = { change, amount -> change.consume(); offset = (offset + amount).coerceIn(-width, width) },
      )
    }) {
      ArticleRow(doc, colors,
        onOpen = { if (!dragging) onOpen() },
        onMenu = if (selecting) null else onMenu,
        selecting = selecting, selected = selected, onToggleSelect = onToggle,
        onLongPress = onLongPress)
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
