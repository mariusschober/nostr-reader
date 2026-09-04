package com.reader.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.Triage
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.colorsFor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun InboxScreen(
  lists: Map<String, List<DocumentEntity>>,
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
) {
  val c = colorsFor(settings.background)
  var tab by remember { mutableStateOf(Triage.INBOX) }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var showAdd by remember { mutableStateOf(false) }
  var showPaste by remember { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  Scaffold(
    containerColor = c.background,
    snackbarHost = {
      SnackbarHost(snackbar) { data ->
        Snackbar(
          snackbarData = data, containerColor = c.text, contentColor = c.background,
          actionColor = c.focal,
        )
      }
    },
    topBar = {
      Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Reader", fontFamily = ReaderFonts.Ui, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.text)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { showAdd = true }) {
          Icon(Icons.Default.Add, contentDescription = "Import, paste, or pair", tint = c.text)
        }
        IconButton(onClick = onSettings) {
          Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = c.text)
        }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad)) {
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        Triage.TABS.forEachIndexed { i, key ->
          if (i > 0) Spacer(Modifier.width(20.dp))
          TabText(Triage.tabLabel(key), selected = tab == key, color = c, onClick = { tab = key })
        }
        Spacer(Modifier.weight(1f))
        Text(
          ReaderCore.formatAttention(minutes[tab] ?: 0),
          fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary,
        )
      }
      val list = lists[tab].orEmpty()
      if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(emptyHint(tab), fontFamily = ReaderFonts.Ui, color = c.secondary, fontSize = 15.sp)
        }
      } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
          items(list, key = { it.documentId }) { d ->
            if (tab == Triage.ARCHIVED) {
              ArticleRow(d, c, onOpen = { onOpen(d.documentId) }, onMenu = { menuFor = d.documentId })
            } else {
              SwipeRow(
                doc = d, list = tab, colors = c,
                onOpen = { onOpen(d.documentId) },
                onSwiped = { target ->
                  onMove(d.documentId, target)
                  scope.launch {
                    val res = snackbar.showSnackbar(Triage.movedLabel(target), actionLabel = "Undo")
                    if (res == SnackbarResult.ActionPerformed) onUndoMove(d.documentId, tab)
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
      text = { Text("Unarchive returns it to the inbox.", fontFamily = ReaderFonts.Ui, color = c.text) },
      confirmButton = {
        TextButton(onClick = { menuFor = null; onUnarchive(id) }, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
          Text("Unarchive", fontFamily = ReaderFonts.Ui)
        }
      },
      dismissButton = {
        TextButton(onClick = { menuFor = null; onDelete(id) }, colors = ButtonDefaults.textButtonColors(contentColor = c.error)) {
          Text("Delete", fontFamily = ReaderFonts.Ui)
        }
      },
    )
  }
}

private fun emptyHint(tab: String): String = when (tab) {
  Triage.INBOX -> "Nothing here yet.\nSend something from Chrome."
  Triage.PRIORITY -> "Nothing prioritized.\nSwipe a row right in the Inbox."
  Triage.LATER -> "Nothing saved for later."
  else -> "Nothing archived."
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

@Composable
private fun SwipeRow(
  doc: DocumentEntity,
  list: String,
  colors: com.reader.app.ui.theme.ReaderColors,
  onOpen: () -> Unit,
  onSwiped: (target: String) -> Unit,
) {
  // Tap-vs-swipe arbitration, textbook pattern: stock clickable owns
  // taps (it completes press→up only when no drag consumed the stream);
  // detectHorizontalDragGestures owns horizontal swipes and sets a guard
  // so the up that ends a swipe can't double-fire onClick.
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
    Box(
      Modifier
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .clickable(
          indication = null,
          interactionSource = remember { MutableInteractionSource() },
          onClick = { if (!gestureDrag) onOpen() },
        )
        .pointerInput(list, widthPx) {
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
      ArticleRow(doc, colors, onOpen = onOpen, onMenu = null)
    }
  }
}

@Composable
fun ArticleRow(
  d: DocumentEntity,
  c: com.reader.app.ui.theme.ReaderColors,
  onOpen: () -> Unit,
  onMenu: (() -> Unit)?,
) {
  val complete = d.progressFraction >= 0.999f
  Column(
    Modifier.fillMaxWidth().clickable(
      indication = null,
      interactionSource = remember { MutableInteractionSource() },
    ) { onOpen() }.padding(vertical = 12.dp),
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
      // Archive rows (the only callers passing onMenu) previously had no
      // affordance at all: onMenu was never invoked — the overflow dialog
      // was unreachable. Tap opens; ⋮ opens the menu.
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
