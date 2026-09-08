package com.reader.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
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
import com.reader.app.ui.ArticleAction
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreparedReaderScreen(id: String, highlightId: String?, settings: ReaderSettings,
                         onSettingsChange: (ReaderSettings) -> Unit, onBack: () -> Unit,
                         onListen: (RenderedProjection, SemanticCursor) -> Unit,
                         onSpeedRead: (SemanticCursor) -> Unit,
                         onArticleAction: (ArticleAction) -> Unit,
                         onPauseAudio: () -> Unit = {},
                         speechPlaying: Boolean = false,
                         playerVisible: Boolean = false,
                         player: @Composable () -> Unit = {}) {
  val context = LocalContext.current
  val app = context.applicationContext as ReaderApp
  val hints = remember { context.getSharedPreferences("reader_hints", android.content.Context.MODE_PRIVATE) }
  var showSwipeHint by remember { mutableStateOf(!hints.getBoolean("article_swipe_seen", false)) }
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
  var initial by rememberSaveable(id, stateSaver = androidx.compose.runtime.saveable.Saver<SemanticCursor, List<Any>>(
    save = { listOf(it.documentId, it.blockId, it.charOffset) },
    restore = {
      check(it.size == 3 && it[0] is String && it[1] is String && (it[2] is Int || it[2] is Long)) { "invalid cursor" }
      SemanticCursor(it[0] as String, it[1] as String, (it[2] as Number).toInt())
    }
  )) { mutableStateOf(SemanticCursor.start(id)) }
  // Live scroll cursor (not Saveable): updated on every scroll without parcel
  // churn. The Saveable `initial` is only written on transition/dispose and
  // part changes, keeping recreation coherent without per-scroll overhead.
  var liveCursor by remember { mutableStateOf<SemanticCursor?>(null) }
  var view by remember { mutableStateOf<NativeArticleView?>(null) }
  var pen by rememberSaveable(id) { mutableStateOf(false) }
  var selectedColor by rememberSaveable { mutableStateOf("YELLOW") }
  var menu by remember { mutableStateOf(false) }
  var appearance by remember { mutableStateOf(false) }
  var actions by remember { mutableStateOf<List<String>>(emptyList()) }
  var activeQuote by remember { mutableStateOf<HighlightEntity?>(null) }
  var undo by remember { mutableStateOf<HighlightMutation?>(null) }
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

  val saveError by app.reading.error.collectAsState()
  // Survives rotation: a transition in-flight across recreation stays guarded
  // so its action() cannot run twice. UI is disabled while true.
  var transitioning by rememberSaveable { mutableStateOf(false) }
  fun transition(action: suspend () -> Unit) {
    if (transitioning) {
      scope.launch { snackbar.showSnackbar("Saving… please wait.") }
      return
    }
    view?.flushSelection()
    onPauseAudio()
    val cursor = liveCursor ?: view?.currentCursor() ?: initial
    initial = cursor
    prepared?.let {
      try { app.progress.offer(cursor, it.fraction(it.projection.offset(cursor.blockId, cursor.charOffset))) }
      catch (_: Exception) { /* offer is coalesced; flush below surfaces failures */ }
    }
    transitioning = true
    scope.launch {
      try {
        try { app.reading.flush() }
        finally { app.progress.flush() }
        action()
      }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { snackbar.showSnackbar("Couldn’t save reading changes. Retry before leaving.") }
      finally { transitioning = false }
    }
  }
  fun leave() {
    view?.flushSelection()
    if (view?.clearSelection() == true) return
    transition(onBack)
  }
  BackHandler(enabled = !transitioning) { leave() }
  val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
  DisposableEffect(id, lifecycle) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        view?.flushSelection()
        (liveCursor ?: view?.currentCursor())?.let { initial = it }
        view?.reportCursor()
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      view?.flushSelection()
      (liveCursor ?: view?.currentCursor())?.let { initial = it }
      view?.reportCursor()
      lifecycle.removeObserver(observer)
    }
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
        TextButton(onClick = { leave() }, enabled = !transitioning) { Text("Back") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { appearance = true }, enabled = !transitioning) { Text("Appearance") }
        Box {
          IconButton(onClick = { menu = true }, enabled = !transitioning) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val archived = doc?.list == com.reader.app.ui.Triage.ARCHIVED
            val menuActions = if (archived) listOf(ArticleAction.Unarchive, ArticleAction.Delete) else listOf(ArticleAction.Later, ArticleAction.Archive)
            menuActions.forEach { action ->
              DropdownMenuItem(enabled = action.target == null || doc?.list != action.target,
                text = { Text(action.label, color = if (action == ArticleAction.Delete) colors.error else colors.text) },
                onClick = { menu = false; transition { onArticleAction(action) } })
            }
            if (undo != null) DropdownMenuItem(text = { Text("Undo highlight change") }, onClick = {
              menu = false
              transition {
                view?.clearSelection()
                val change = undo ?: return@transition
                try { if (highlights.undo(change)) undo = null else scope.launch { snackbar.showSnackbar("Highlight changed since saving; Undo is unavailable.") } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { scope.launch { snackbar.showSnackbar("Couldn’t undo highlight change. Try again.") } }
              }
            })
          }
        }
      }

    }
  }, bottomBar = {
    Column {
      player()
      if (pen) {
        // Wrapping swatch row: actual highlight fills plus accessible names
        // and a check indicator. Logical color identity (YELLOW/GREEN/CYAN/
        // PURPLE) is persisted as a name, independently of theme.
        FlowRow(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          horizontalArrangement = Arrangement.Center,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          HighlightColor.entries.forEach { color ->
            val selected = selectedColor == color.name
            FilterChip(
              selected = selected,
              onClick = { selectedColor = color.name },
              modifier = Modifier.padding(horizontal = 4.dp).semantics {
                contentDescription = "Highlight color ${color.label}"
                stateDescription = if (selected) "Selected" else "Not selected"
              },
              leadingIcon = {
                Box(
                  Modifier.size(16.dp)
                    .background(color.background(dark), CircleShape)
                    .border(1.dp, colors.text.copy(alpha = 0.4f), CircleShape),
                  contentAlignment = Alignment.Center,
                ) {
                  if (selected) Icon(
                    Icons.Default.Check, contentDescription = null,
                    tint = HighlightColor.text(dark), modifier = Modifier.size(12.dp),
                  )
                }
              },
              label = { Text(color.label) },
            )
          }
        }
      }
      Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { prepared?.let { val cursor = liveCursor ?: view?.currentCursor() ?: initial; transition { onListen(it.projection, cursor) } } }, enabled = prepared != null && !playerVisible && !transitioning) {
          Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Listen")
        }
        // Calm dock: the Highlight control matches Listen/Speed weight when
        // inactive (no elevation, no color fill). The active state keeps the
        // selected highlight fill plus an explicit on/off label. The 48 dp
        // touch target is unchanged.
        val activeHighlight = HighlightColor.parse(selectedColor)
        FilterChip(selected = pen, onClick = {
          pen = !pen
          if (pen && !hints.getBoolean("highlight_seen", false)) {
            hints.edit().putBoolean("highlight_seen", true).apply()
            scope.launch { snackbar.showSnackbar("Select text to highlight. Drag the handles to adjust.") }
          }
        }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (pen) "Highlighting on" else "Highlighting off" },
          leadingIcon = { Icon(Icons.Default.BorderColor, null, Modifier.size(20.dp)) },
          label = {
            if (pen) Text("Highlight on", color = HighlightColor.text(dark),
              modifier = Modifier.background(activeHighlight.background(dark), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
            else Text("Highlight")
          })
        TextButton(onClick = { val cursor = liveCursor ?: view?.currentCursor() ?: initial; transition { onSpeedRead(cursor) } }, enabled = prepared != null && !transitioning) {
          Icon(Icons.Default.Speed, "Speed reading", Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Speed")
        }
      }
    }
  }) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      if (showSwipeHint) Surface(color = colors.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(if (doc?.list == com.reader.app.ui.Triage.ARCHIVED) "Swipe inside the page: ← Delete · Unarchive →" else "Swipe inside the page: ← Archive · Later →", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
          IconButton(onClick = { showSwipeHint = false; hints.edit().putBoolean("article_swipe_seen", true).apply() }) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss swipe hint", modifier = Modifier.size(18.dp))
          }
        }
      }
      Text(doc?.title.orEmpty(), color = colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 4.dp))
      val ready = prepared
      val text = content
      if (saveError != null) TextButton(onClick = { transition { } }) { Text("Save failed — Retry", color = colors.error) }
      if (failure != null) Text(failure!!, modifier = Modifier.padding(24.dp), color = colors.error)
      else if (ready == null || text == null) CircularProgressIndicator(Modifier.padding(24.dp))
      else {
        if (ready.index.sections.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(enabled = part > 0 && !transitioning, onClick = { transition { initial = SemanticCursor.start(id); liveCursor = null; part-- } }) { Text("Previous part") }
          Text("${part + 1} / ${ready.index.sections.size}", modifier = Modifier.padding(12.dp))
          TextButton(enabled = part < ready.index.sections.lastIndex && !transitioning, onClick = { transition { initial = SemanticCursor.start(id); liveCursor = null; part++ } }) { Text("Next part") }
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
          native.deleteTint = colors.error.toArgb()
          native.articleList = doc?.list
          native.onArticleSwipe = { action -> transition { onArticleAction(action) } }
          native.setPenMode(pen)
          native.setMarks(nativeMarks)
          native.onCursor = { cursor, _ ->
            liveCursor = cursor
            // Guard stale ready closures after a part switch: only offer
            // progress for the currently displayed section.
            if (native.tag == styleKey) {
              if (!speechPlaying) app.progress.offer(cursor, ready.fraction(ready.projection.offset(cursor.blockId, cursor.charOffset)))
            }
          }
          native.onMark = { actions = it }
          native.onLink = { url ->
            if (Uri.parse(url).scheme in listOf("http", "https")) {
              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
          }
          native.onSelection = { session, sequence, range ->
            val source = doc
            val frozenColor = selectedColor
            if (source != null) {
              try {
                // Freeze exact offsets, projection version, provenance and color
                // while this native view still owns the selection.
                val draft = HighlightAnchors.create(session, source, ready.projection, range.first, range.last + 1,
                  System.currentTimeMillis()).copy(color = frozenColor)
                app.reading.submitSelection(draft) { change ->
                  if ((sessions[session]?.first ?: -1) < sequence) {
                    val aggregate = HighlightMutation(if (sessions.containsKey(session)) sessions.getValue(session).second.before else change.before, change.after)
                    sessions[session] = sequence to aggregate
                    while (sessions.size > 16) sessions.remove(sessions.keys.first())
                    undo = aggregate.takeIf { it.before != null || it.after != null }
                  }
                }
              } catch (error: Exception) { scope.launch { snackbar.showSnackbar(error.message ?: "Couldn’t prepare highlight") } }
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
        // Recolor uses the same actual swatches, accessible names and check
        // indicator as the pen row. The stored value remains the logical
        // color name, independent of theme.
        HighlightColor.entries.forEach { color ->
          val selected = quote.color == color.name
          TextButton(onClick = { scope.launch {
            try { undo = highlights.recolor(quote.id, color.name); actions = emptyList(); activeQuote = null }
            catch (_: Exception) { snackbar.showSnackbar("Couldn’t save highlight color. Try again.") }
          } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                Modifier.size(16.dp)
                  .background(color.background(dark), CircleShape)
                  .border(1.dp, colors.text.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                if (selected) Icon(
                  Icons.Default.Check, contentDescription = null,
                  tint = HighlightColor.text(dark), modifier = Modifier.size(12.dp),
                )
              }
              Spacer(Modifier.width(8.dp))
              Text(color.label + if (selected) " — selected" else "")
            }
          }
        }
        TextButton(onClick = { scope.launch { activeQuote = review.toggleImportant(quote.id) } }) { Text(if (quote.important) "Remove importance" else "Mark important") }
        TextButton(onClick = { share(quote) }) { Text("Share quote") }
        TextButton(onClick = { scope.launch { undo = highlights.remove(quote.id); actions = emptyList(); activeQuote = null } }) { Text("Delete highlight") }
      }
    }, confirmButton = { TextButton(onClick = { actions = emptyList(); activeQuote = null }) { Text("Done") } })
  }
  }
}
