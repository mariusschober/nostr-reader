package com.reader.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reader.app.ReaderApp
import com.reader.app.core.RenderedProjection
import com.reader.app.core.RENDERED_PROJECTION_VERSION
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.*
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads metadata independently from the library and parses only the visible bounded part. */
@Composable
fun PreparedReaderScreen(id: String, highlightId: String?, settings: ReaderSettings,
                         onSettingsChange: (ReaderSettings) -> Unit, onBack: () -> Unit,
                         onListen: (RenderedProjection, SemanticCursor) -> Unit,
                         onSpeedRead: (SemanticCursor) -> Unit,
                         onLater: () -> Unit, onArchive: () -> Unit,
                         player: @Composable () -> Unit = {}) {
  val context = LocalContext.current
  val app = context.applicationContext as ReaderApp
  val db = remember { ReaderDb.get(context) }
  val highlights = remember { HighlightRepository(db) }
  val review = remember { ReviewRepository(db) }
  val doc by remember(id) { db.documents().observeMetadata(id) }.collectAsState(initial = null)
  val marks by remember(id) { db.highlights().observeMarks(id) }.collectAsState(initial = emptyList())
  val scope = rememberCoroutineScope()
  val colors = readerColors(settings.background)
  val dark = colors.background.luminance() < .5f
  var part by rememberSaveable(id) { mutableIntStateOf(-1) }
  var prepared by remember(id) { mutableStateOf<PreparedSection?>(null) }
  var content by remember { mutableStateOf<CharSequence?>(null) }
  var failure by remember(id) { mutableStateOf<String?>(null) }
  var initial by remember(id) { mutableStateOf(SemanticCursor.start(id)) }
  var view by remember { mutableStateOf<NativeArticleView?>(null) }
  var pen by rememberSaveable(id) { mutableStateOf(false) }
  var selectedColor by rememberSaveable { mutableStateOf("YELLOW") }
  var appearance by remember { mutableStateOf(false) }
  var actions by remember { mutableStateOf<List<String>>(emptyList()) }
  var activeQuote by remember { mutableStateOf<HighlightEntity?>(null) }
  var undo by remember { mutableStateOf<HighlightMutation?>(null) }
  val selections = remember(id) { Mutex() }
  val sessions = remember(id) { mutableMapOf<String, Pair<Long, HighlightMutation>>() }
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(id, doc?.documentId) {
    val source = doc ?: return@LaunchedEffect
    if (part >= 0) return@LaunchedEffect
    try {
      val quote = highlightId?.let { db.highlights().byId(it) }?.takeIf { it.documentId == id }
      initial = SemanticCursor(id, quote?.startBlockId ?: source.progressBlockId ?: "b0", quote?.startOffset ?: source.progressCharOffset)
      val index = app.articles.index(id)
      part = index.sectionFor(initial.blockId, source.progressFraction)
    } catch (e: Exception) { failure = e.message ?: "Couldn’t open article" }
  }
  LaunchedEffect(id, part) {
    if (part < 0) return@LaunchedEffect
    prepared = null
    content = null
    try {
      val ready = app.articles.section(id, part)
      val quote = highlightId?.let { db.highlights().byId(it) }?.takeIf { it.documentId == id }
      if (quote != null && ready.index.sectionFor(quote.startBlockId) == part) {
        val range = withContext(Dispatchers.Default) { HighlightAnchors.resolve(quote, ready.projection) }
        if (range != null) initial = ready.projection.cursor(id, range.first)
        else {
          initial = SemanticCursor.start(id)
          scope.launch { snackbar.showSnackbar("Source position unavailable. Your saved quote is unchanged.") }
        }
      }
      prepared = ready
    }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { failure = e.message ?: "Couldn’t load article" }
  }
  LaunchedEffect(prepared, colors.link) {
    val value = prepared ?: return@LaunchedEffect
    content = withContext(Dispatchers.Default) { nativeArticleText(value.projection, colors.link.toArgb()) }
  }
  LaunchedEffect(actions) { activeQuote = actions.firstOrNull()?.let { db.highlights().byId(it) } }

  fun leave() {
    if (view?.clearSelection() == true) return
    view?.reportCursor()
    scope.launch {
      try { app.progress.flush(); onBack() }
      catch (_: Exception) { snackbar.showSnackbar("Couldn’t save reading position. Try again.") }
    }
  }
  BackHandler { leave() }
  DisposableEffect(id) {
    onDispose { view?.reportCursor() }
  }
  fun share(value: HighlightEntity) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value.quote)
    }, "Share quote"))
  }

  ReaderTheme(if (dark) com.reader.app.prefs.ThemeMode.DARK else com.reader.app.prefs.ThemeMode.LIGHT) {
  Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
    Column {
      Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { leave() }) { Text("Back") }
        TextButton(onClick = { pen = !pen }) { Text(if (pen) "Pen on" else "Highlight") }
        TextButton(onClick = { appearance = true }) { Text("Appearance") }
      }
      if (pen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        HighlightColor.entries.forEach { color ->
          FilterChip(selected = selectedColor == color.name, onClick = { selectedColor = color.name }, label = { Text(color.label) })
        }
      }
    }
  }, bottomBar = {
    Column {
      if (undo != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Highlight saved", modifier = Modifier.padding(12.dp))
        TextButton(onClick = {
          val change = undo ?: return@TextButton
          view?.clearSelection()
          undo = null
          scope.launch { if (!highlights.undo(change)) snackbar.showSnackbar("Highlight changed since saving; Undo is unavailable.") }
        }) { Text("Undo") }
      }
      player()
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { prepared?.let { onListen(it.projection, view?.currentCursor() ?: initial) } }) { Text("Listen") }
        TextButton(onClick = { onSpeedRead(view?.currentCursor() ?: initial) }) { Text("Speed") }
        TextButton(onClick = onLater) { Text("Later") }
        TextButton(onClick = onArchive) { Text("Archive") }
      }
    }
  }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      Text(doc?.title.orEmpty(), color = colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 4.dp))
      val ready = prepared
      val text = content
      if (failure != null) Text(failure!!, modifier = Modifier.padding(24.dp), color = colors.error)
      else if (ready == null || text == null) CircularProgressIndicator(Modifier.padding(24.dp))
      else {
        if (ready.index.sections.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(enabled = part > 0, onClick = { view?.reportCursor(); initial = SemanticCursor.start(id); part-- }) { Text("Previous part") }
          Text("${part + 1} / ${ready.index.sections.size}", modifier = Modifier.padding(12.dp))
          TextButton(enabled = part < ready.index.sections.lastIndex, onClick = { view?.reportCursor(); initial = SemanticCursor.start(id); part++ }) { Text("Next part") }
        }
        val nativeMarks = remember(marks, ready, dark) {
          val blocks = ready.projection.blocks.mapTo(hashSetOf()) { it.id }
          marks.filter { it.projectionVersion == RENDERED_PROJECTION_VERSION && it.startBlockId in blocks && it.endBlockId in blocks }.map {
            NativeMark(it.id, ready.projection.offset(it.startBlockId, it.startOffset), ready.projection.offset(it.endBlockId, it.endOffset),
              it.createdAt, HighlightColor.parse(it.color).background(dark).toArgb(), HighlightColor.text(dark).toArgb())
          }
        }
        val styleKey = listOf(ready, text, settings.font, settings.fontSizeSp, settings.margin, colors)
        AndroidView(factory = { NativeArticleView(it).also { view = it } }, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(), update = { native ->
          if (native.tag != styleKey) {
            native.display(id, ready.projection, text, settings, colors.text.toArgb(), colors.background.toArgb(), marginDp(settings.margin, false), initial)
            native.tag = styleKey
          }
          native.setPenMode(pen)
          native.setMarks(nativeMarks)
          native.onCursor = { cursor, _ -> app.progress.offer(cursor, ready.fraction(ready.projection.offset(cursor.blockId, cursor.charOffset))) }
          native.onMark = { actions = it }
          native.onLink = { url ->
            if (Uri.parse(url).scheme in listOf("http", "https")) {
              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
          }
          native.onSelection = { session, sequence, range ->
            val source = doc
            if (source != null) scope.launch {
              try {
                selections.withLock {
                  if ((sessions[session]?.first ?: -1) >= sequence) return@withLock
                  val draft = withContext(Dispatchers.Default) {
                    HighlightAnchors.create(session, source, ready.projection, range.first, range.last + 1, System.currentTimeMillis()).copy(color = selectedColor)
                  }
                  val change = highlights.saveSelection(draft)
                  val aggregate = HighlightMutation(if (sessions.containsKey(session)) sessions.getValue(session).second.before else change.before, change.after)
                  sessions[session] = sequence to aggregate
                  while (sessions.size > 16) sessions.remove(sessions.keys.first())
                  undo = aggregate.takeIf { it.before != null || it.after != null }
                }
              } catch (e: CancellationException) { throw e }
              catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Couldn’t save highlight") }
            }
          }
        })
      }
    }
  }
  if (appearance) AppearanceSheet(settings, onSettingsChange) { appearance = false }
  activeQuote?.let { quote ->
    AlertDialog(onDismissRequest = { actions = emptyList(); activeQuote = null }, title = { Text("Highlight") }, text = {
      Column {
        if (actions.size > 1) TextButton(onClick = { actions = actions.drop(1) + actions.first() }) { Text("Next overlapping highlight") }
        HighlightColor.entries.forEach { color -> TextButton(onClick = { scope.launch {
          undo = highlights.recolor(quote.id, color.name); actions = emptyList(); activeQuote = null
        } }) { Text(color.label) } }
        TextButton(onClick = { scope.launch { activeQuote = review.toggleImportant(quote.id) } }) { Text(if (quote.important) "Remove importance" else "Mark important") }
        TextButton(onClick = { share(quote) }) { Text("Share quote") }
        TextButton(onClick = { scope.launch { undo = highlights.remove(quote.id); actions = emptyList(); activeQuote = null } }) { Text("Delete highlight") }
      }
    }, confirmButton = { TextButton(onClick = { actions = emptyList(); activeQuote = null }) { Text("Done") } })
  }
  }
}
