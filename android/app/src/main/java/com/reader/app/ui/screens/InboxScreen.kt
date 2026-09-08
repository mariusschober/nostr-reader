package com.reader.app.ui.screens

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.filled.MoreVert
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun InboxScreen(
  lists: Map<String, List<DocumentSummary>>,
  minutes: Map<String, Int>,
  settings: ReaderSettings,
  onOpen: (String) -> Unit,
  onMove: (id: String, target: String) -> Unit,
  onUndoMove: (id: String, previous: String) -> Unit,
  onUnarchive: (String) -> Unit,
  onDelete: (String) -> Unit,
  onImportFile: () -> Unit,
  onPasteText: (String) -> Unit,
  onPair: () -> Unit,
  onSettings: () -> Unit,
  loaded: Boolean,
  selectedTab: String,
  onSelectTab: (String) -> Unit,
  highlights: @Composable () -> Unit,
  readerMove: Triple<String, String, String>? = null,
  onReaderMoveConsumed: () -> Unit = {},
  archiveMode: Boolean = false,
  onArchiveOpen: () -> Unit = {},
  onArchiveBack: () -> Unit = {},
  listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),

) {
  val c = appColors()
  val tab = if (archiveMode) Triage.ARCHIVED else selectedTab
  BackHandler(enabled = archiveMode, onBack = onArchiveBack)
  LaunchedEffect(loaded, lists[Triage.INBOX]?.size, tab) {
    if (!archiveMode && selectedTab == Triage.ARCHIVED) onSelectTab(Triage.PRIORITY)
    if (loaded && lists[Triage.INBOX].isNullOrEmpty() && tab == Triage.INBOX) onSelectTab(Triage.PRIORITY)
  }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var rowMenu by remember { mutableStateOf<DocumentSummary?>(null) }
  // Long-press multi-select. Only already-legal actions are offered:
  // Archive in the triage lists, Unarchive in Archive. Article deletion
  // stays archive-only single-item flow; it is never batched here.
  var selecting by rememberSaveable { mutableStateOf(false) }
  var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
  BackHandler(enabled = selecting) { selecting = false; selectedIds = emptyList() }
  LaunchedEffect(tab, archiveMode) {
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
  val scope = rememberCoroutineScope()
  LaunchedEffect(readerMove) {
    val move = readerMove ?: return@LaunchedEffect
    if (snackbar.showSnackbar(Triage.movedLabel(move.third), "Undo", withDismissAction = true,
        duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) onUndoMove(move.first, move.second)
    onReaderMoveConsumed()
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
    topBar = {
      Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (archiveMode) IconButton(onClick = onArchiveBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(if (archiveMode) "Archive" else "Reader", fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.text)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { showAdd = true }) {
          Icon(Icons.Default.Add, contentDescription = "Import, paste, or pair", tint = c.text)
        }
        // The Archive destination has its own Back control; a disabled
        // Archive icon here added visual noise without an action.
        if (!archiveMode) IconButton(onClick = onArchiveOpen) {
          Icon(Icons.Default.Archive, contentDescription = "Open archive", tint = c.text)
        }
        IconButton(onClick = onSettings) {
          Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = c.text)
        }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad)) {
      if (!archiveMode) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        (listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER, "highlights").filter { it != Triage.INBOX || !loaded || !lists[Triage.INBOX].isNullOrEmpty() }).forEachIndexed { i, key ->
          if (i > 0) Spacer(Modifier.width(20.dp))
          TabText(Triage.tabLabel(key), selected = tab == key, color = c, onClick = { onSelectTab(key) })
        }
        Spacer(Modifier.weight(1f))
        Text(
          ReaderCore.formatAttention(minutes[tab] ?: 0),
          fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary,
        )
      }
      val list = lists[tab].orEmpty()
      if (!loaded) CircularProgressIndicator(Modifier.padding(24.dp))
      else if (tab == "highlights") highlights()
      else if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(emptyHint(tab), fontFamily = ReaderFonts.Ui, color = c.secondary, fontSize = 15.sp)
        }
      } else {
        if (selecting) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              "${selectedIds.size} selected", fontFamily = ReaderFonts.Ui, fontSize = 15.sp,
              color = c.text, modifier = Modifier.weight(1f),
            )
            if (tab == Triage.ARCHIVED) {
              TextButton(onClick = {
                val doomed = selectedIds.toSet()
                selecting = false; selectedIds = emptyList()
                doomed.forEach { onUnarchive(it) }
              }, enabled = selectedIds.isNotEmpty()) { Text("Unarchive", fontFamily = ReaderFonts.Ui, color = c.text) }
            } else {
              TextButton(onClick = {
                val doomed = selectedIds.toSet()
                val previous = tab
                selecting = false; selectedIds = emptyList()
                doomed.forEach { onMove(it, Triage.ARCHIVED) }
                scope.launch {
                  val res = snackbar.showSnackbar(
                    if (doomed.size == 1) Triage.movedLabel(Triage.ARCHIVED) else "Archived ${doomed.size} articles",
                    actionLabel = "Undo",
                  )
                  if (res == SnackbarResult.ActionPerformed) doomed.forEach { onUndoMove(it, previous) }
                }
              }, enabled = selectedIds.isNotEmpty()) { Text("Archive", fontFamily = ReaderFonts.Ui, color = c.text) }
            }
            TextButton(onClick = { selecting = false; selectedIds = emptyList() }) { Text("Done", fontFamily = ReaderFonts.Ui, color = c.text) }
          }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState) {
          items(list, key = { it.documentId }) { d ->
            if (tab == Triage.ARCHIVED) {
              ArchiveSwipeRow(d, c,
                selecting = selecting, selected = d.documentId in selectedIds,
                onOpen = { if (selecting) toggleSelect(d.documentId) else onOpen(d.documentId) },
                onLongPress = {
                  if (!selecting) { selecting = true; selectedIds = listOf(d.documentId) }
                  else toggleSelect(d.documentId)
                },
                onToggle = { toggleSelect(d.documentId) },
                onMenu = { menuFor = d.documentId }, onAction = { action ->
                if (action == ArticleAction.Delete) onDelete(d.documentId) else onUnarchive(d.documentId)
              })
            } else {
              SwipeRow(
                doc = d, list = tab, colors = c,
                selecting = selecting, selected = d.documentId in selectedIds,
                onOpen = { if (selecting) toggleSelect(d.documentId) else onOpen(d.documentId) },
                onLongPress = {
                  if (!selecting) { selecting = true; selectedIds = listOf(d.documentId) }
                  else toggleSelect(d.documentId)
                },
                onToggle = { toggleSelect(d.documentId) },
                onMenu = { rowMenu = d },
                onSwiped = { target ->
                  val previous = tab
                  onMove(d.documentId, target)
                  scope.launch {
                    val res = snackbar.showSnackbar(Triage.movedLabel(target), actionLabel = "Undo")
                    if (res == SnackbarResult.ActionPerformed) onUndoMove(d.documentId, previous)
                  }
                },
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
          AddOption("Paste text or markdown", c) { showAdd = false; showPaste = true }
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
          placeholder = { Text("Paste text or markdown…", fontFamily = ReaderFonts.Ui) },
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
    AlertDialog(
      onDismissRequest = { menuFor = null },
      containerColor = c.background,
      title = { Text("Article", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Column {
          Text("Unarchive returns it to the inbox.", fontFamily = ReaderFonts.Ui, color = c.text)
          TextButton(onClick = { menuFor = null; selecting = true; selectedIds = listOf(id) }) {
            Text("Select", fontFamily = ReaderFonts.Ui, color = c.text)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { menuFor = null; onUnarchive(id) }, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
          Text("Unarchive", fontFamily = ReaderFonts.Ui)
        }
      },
      dismissButton = {
        TextButton(onClick = { menuFor = null; onDelete(id) }, colors = ButtonDefaults.textButtonColors(contentColor = c.error)) {
          Text("Delete permanently", fontFamily = ReaderFonts.Ui)
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
      onMove(d.documentId, target)
      scope.launch {
        val res = snackbar.showSnackbar(Triage.movedLabel(target), actionLabel = "Undo")
        if (res == SnackbarResult.ActionPerformed) onUndoMove(d.documentId, previous)
      }
    }
    AlertDialog(
      onDismissRequest = { rowMenu = null },
      containerColor = c.background,
      title = { Text("Move article", fontFamily = ReaderFonts.Ui, color = c.text) },
      text = {
        Column {
          if (previous != Triage.PRIORITY) TextButton(onClick = { move(Triage.PRIORITY) }) { Text("Move to Priority", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.INBOX) TextButton(onClick = { move(Triage.INBOX) }) { Text("Move to Inbox", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.LATER) TextButton(onClick = { move(Triage.LATER) }) { Text("Save for later", fontFamily = ReaderFonts.Ui, color = c.text) }
          if (previous != Triage.ARCHIVED) TextButton(onClick = { move(Triage.ARCHIVED) }) { Text("Archive", fontFamily = ReaderFonts.Ui, color = c.text) }
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
}

private fun emptyHint(tab: String): String = when (tab) {
  Triage.INBOX -> "Nothing here yet.\nSend something from Chrome."
  Triage.PRIORITY -> "Nothing prioritized.\nSwipe a row right in the Inbox."
  Triage.LATER -> "Nothing saved for later."
  else -> "No archived articles."
}

@Composable
private fun AddOption(label: String, c: com.reader.app.ui.theme.ReaderColors, onClick: () -> Unit) {
  TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
    Text(label, fontFamily = ReaderFonts.Ui)
  }
}

@Composable
private fun TabText(text: String, selected: Boolean, color: com.reader.app.ui.theme.ReaderColors, onClick: () -> Unit) {
  Column(Modifier.clickable(onClick = onClick)) {
    Text(
      text, fontFamily = ReaderFonts.Ui, fontSize = 17.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      color = if (selected) color.text else color.secondary,
    )
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
  val offset = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()

  var gestureDrag by remember { mutableStateOf(false) }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val thresholdPx = widthPx * 0.35f
    fun settle() {
      scope.launch {
        val end = offset.value
        val hit = if (kotlin.math.abs(end) > thresholdPx) {
          Triage.swipeTarget(
            list,
            if (end > 0) Triage.Swipe.RIGHT else Triage.Swipe.LEFT,
          )
        } else {
          null
        }
        if (hit != null) {
          onSwiped(hit)
          offset.snapTo(0f)
        } else {
          offset.animateTo(0f)
        }
        gestureDrag = false
      }
    }
    val target = if (offset.value > 1f) {
      Triage.swipeTarget(list, Triage.Swipe.RIGHT)
    } else if (offset.value < -1f) {
      Triage.swipeTarget(list, Triage.Swipe.LEFT)
    } else {
      null
    }
    if (target != null) {
      val tint = if (target == Triage.PRIORITY) colors.success else colors.warning
      Box(
        Modifier.matchParentSize().padding(horizontal = 8.dp),
        contentAlignment = if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd,
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
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .pointerInput(list, widthPx, selecting) {
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
        modifier = Modifier.padding(end = 8.dp),
      )
    }
    Column(
      Modifier.weight(1f).combinedClickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = { onOpen() },
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
          Icon(Icons.Default.MoreVert, contentDescription = "Article options", tint = c.secondary)
        }
      }
    }
    Spacer(Modifier.height(2.dp))
    val mins = ReaderCore.readingMinutes(d.wordCount)
    Text("${d.sourceType} · ${mins} min", fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.secondary, maxLines = 1)
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
        onOpen = { if (!dragging && !selecting) onOpen() },
        onMenu = if (selecting) null else onMenu,
        selecting = selecting, selected = selected, onToggleSelect = onToggle,
        onLongPress = onLongPress)
    }
  }
}
