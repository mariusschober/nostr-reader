package com.reader.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.ui.viewinterop.AndroidView
import com.reader.app.ui.ArticleAction
import com.reader.app.ui.ReaderSearchField
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

/** One row entry for the four reading actions. */
private data class ReadingActionItem(
  val label: String,
  val icon: ImageVector,
  val selected: Boolean,
  val enabled: Boolean,
  val onClick: () -> Unit,
)

/**
 * Equal-width action with a 20–22dp icon above a short label and a 48dp-minimum
 * touch target. Used for Highlight · Contents · Listen · Speed.
 */
@Composable
private fun ReadingActionButton(item: ReadingActionItem, colors: ReaderColors, modifier: Modifier = Modifier) {
  val tint = when {
    !item.enabled -> colors.secondary.copy(alpha = .4f)
    item.selected -> colors.text
    else -> colors.secondary
  }
  Column(
    modifier = modifier
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable(enabled = item.enabled, onClick = item.onClick)
      .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    Spacer(Modifier.height(2.dp))
    Text(item.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = tint, maxLines = 1)
  }
}

/** Loads metadata independently from the library and parses only the visible bounded part. */
/**
 * The compact continuous-highlighting dock. One bottom-centred capsule with the
 * current colour, an explicit focus control and Done highlighting. Long-pressing
 * the colour control reveals a small transient palette above the dock; choosing
 * a colour closes it. Touch targets are at least 48dp and the capsule is about
 * 56dp tall, so it never reserves the old control stack's height.
 */
@Composable
private fun PenDock(
  colors: ReaderColors,
  monochrome: Boolean,
  dark: Boolean,
  selectedColor: String,
  paletteOpen: Boolean,
  onTogglePalette: () -> Unit,
  onSelectColor: (String) -> Unit,
  immersed: Boolean,
  onToggleFocus: () -> Unit,
  onDone: () -> Unit,
) {
  val current = highlightPresentation(selectedColor, monochrome, dark)
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (paletteOpen) {
      Surface(
        color = colors.surface,
        contentColor = colors.text,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.padding(bottom = 8.dp),
      ) {
        Row(
          Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          HighlightColor.entries.forEach { color ->
            val pres = highlightPresentation(color.name, monochrome, dark)
            val selected = selectedColor == color.name
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              IconToggleButton(
                selected,
                { onSelectColor(color.name) },
                modifier = Modifier.semantics { contentDescription = "Highlight color ${color.label}" },
              ) {
                Box(
                  Modifier.size(32.dp)
                    .background(if (selected && monochrome) colors.text else if (monochrome) pres.fill else color.background(dark), CircleShape)
                    .border(2.dp, colors.text, CircleShape),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    pres.shortId,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected && monochrome) colors.background else if (monochrome) colors.text else HighlightColor.text(dark),
                  )
                }
              }
              Text(pres.label, style = MaterialTheme.typography.labelSmall, color = colors.text, maxLines = 1)
            }
          }
        }
      }
    }
    Surface(
      color = colors.surface,
      contentColor = colors.text,
      shape = RoundedCornerShape(28.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, colors.divider),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Row(
        Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = onTogglePalette,
          modifier = Modifier.semantics { contentDescription = "Highlight color ${current.label}" },
        ) {
          Box(
            Modifier.size(26.dp)
              .background(if (monochrome) current.fill else HighlightColor.parse(selectedColor).background(dark), CircleShape)
              .border(2.dp, colors.text, CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              current.shortId,
              style = MaterialTheme.typography.labelLarge,
              color = if (monochrome) colors.text else HighlightColor.text(dark),
            )
          }
        }
        IconButton(
          onClick = onToggleFocus,
          modifier = Modifier.semantics { contentDescription = if (immersed) "Exit focus" else "Enter focus" },
        ) {
          Icon(
            if (immersed) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            contentDescription = null,
            tint = colors.text,
          )
        }
        TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
      }
    }
  }
}

/** Loads metadata independently from the library and parses only the visible bounded part. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PreparedReaderScreen(id: String, highlightId: String?, settings: ReaderSettings,
                         onSettingsChange: (ReaderSettings) -> Unit, onBack: () -> Unit,
                         onListen: (RenderedProjection, SemanticCursor) -> Unit,
                         onSpeedRead: (SemanticCursor) -> Unit,
                         onArticleAction: (ArticleAction) -> Unit,
                         onPauseAudio: () -> Unit = {},
                         onResumeAudio: () -> Unit = {},
                         onHighlightRemoved: ((HighlightMutation) -> Unit)? = null,
                         speechPlaying: Boolean = false,
                         playerVisible: Boolean = false,
                         // Search landing: open at this cursor with a fading
                         // match tint; null means normal (quote/progress) entry.
                          at: SemanticCursor? = null,
                          atEnd: Int? = null,
                          docLabels: List<String> = emptyList(),
                          labelSuggestions: List<String> = emptyList(),
                         onToggleLabel: (String) -> Unit = {},
                         onOpenArticleHighlights: () -> Unit = {},
                         player: @Composable () -> Unit = {},
                          // Finish-card bridge: pop back to the library so the
                          // moment of completion leads somewhere.
                          onReadAnother: () -> Unit = {}) {
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
  val monochrome = com.reader.app.ui.theme.LocalDisplayPolicy.current.monochrome
  val effectiveReducedMotion = com.reader.app.ui.theme.LocalDisplayPolicy.current.reducedMotion
  val keyboard = LocalSoftwareKeyboardController.current
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
  // Live reading fraction for the progress footer (also offered to storage).
  var liveFraction by remember { mutableStateOf<Float?>(null) }
  var atEndOfPart by remember(id, part) { mutableStateOf(false) }
  var openingVisible by remember(id) { mutableStateOf(true) }
  val completion = remember { ArticleCompletion(db) }
  var finishReceipt by remember(id) { mutableStateOf<FinishReceipt?>(null) }
  val navigation = remember { ArticleNavigation(app.articles, db) }
  var contents by remember(id) { mutableStateOf<List<ArticleLocation>>(emptyList()) }
  var navigationError by remember(id) { mutableStateOf<String?>(null) }
  var navigationSheet by remember { mutableStateOf<String?>(null) }
  var findText by rememberSaveable(id) { mutableStateOf("") }
  var findResults by remember(id) { mutableStateOf<List<ArticleMatch>>(emptyList()) }
  var findBusy by remember { mutableStateOf(false) }
  var findIndex by rememberSaveable(id) { mutableIntStateOf(0) }
  var inspectionOrigin by rememberSaveable(id, stateSaver = androidx.compose.runtime.saveable.Saver<SemanticCursor?, List<Any>>(
    save = { if (it == null) emptyList() else listOf(it.documentId, it.blockId, it.charOffset) },
    restore = { if (it.isEmpty()) null else SemanticCursor(it[0] as String, it[1] as String, (it[2] as Number).toInt()) }
  )) { mutableStateOf<SemanticCursor?>(null) }
  var inspectionFraction by rememberSaveable(id) { mutableFloatStateOf(0f) }
  var jumpMatch by remember(id) { mutableStateOf<ArticleLocation?>(null) }
  var confirmDelete by remember { mutableStateOf(false) }
  var highlightHelp by remember { mutableStateOf(false) }
  LaunchedEffect(id, navigationSheet) {
    if (navigationSheet != "contents") return@LaunchedEffect
    try { contents = navigation.contents(id); navigationError = null }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { navigationError = "Contents couldn’t load. Try again." }
  }
  LaunchedEffect(id, findText) {
    findResults = emptyList(); findIndex = 0; navigationError = null
    if (findText.isBlank()) { findBusy = false; return@LaunchedEffect }
    findBusy = true
    delay(150)
    try { findResults = navigation.matches(id, findText); navigationError = null }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { findResults = emptyList(); navigationError = "Search couldn’t load. Try again." }
    finally { findBusy = false }
  }
  // Search-landing bookkeeping: re-entry into the same article with a new
  // match must re-resolve even when the part is already loaded, and force a
  // re-display when the part doesn't change (otherwise the new cursor and
  // tint are silently swallowed by the up-to-date display).
  var lastAt by remember(id) { mutableStateOf<SemanticCursor?>(null) }
  var displayRequest by remember(id) { mutableIntStateOf(0) }
  var view by remember { mutableStateOf<NativeArticleView?>(null) }
  var pen by rememberSaveable(id) { mutableStateOf(false) }
  // Immersive reading: a clean tap anywhere toggles the chrome. The reader
  // enters a two-hour state, not an app with bars bolted on.
  var immersed by rememberSaveable(id) { mutableStateOf(false) }
  var selectedColor by rememberSaveable { mutableStateOf("YELLOW") }
  var menu by remember { mutableStateOf(false) }
  // Transient highlighter palette above the pen dock; closes after a choice.
  var paletteOpen by rememberSaveable(id) { mutableStateOf(false) }
  var showLabels by remember { mutableStateOf(false) }
  var appearance by remember { mutableStateOf(false) }
  var expandedTable by remember(id, part) { mutableStateOf<Int?>(null) }
  var actions by remember { mutableStateOf<List<String>>(emptyList()) }
  var activeQuote by remember { mutableStateOf<HighlightEntity?>(null) }
  // Search-landing tint: visible until the user scrolls away from the hit.
  var matchTint by remember(id, at) { mutableStateOf(at != null) }
  // "Back to top" bubble: a single boolean flipped on threshold crossings,
  // so scroll frames never recompose the screen — only showing/hiding does.
  var showTopBubbleRaw by remember { mutableStateOf(false) }
  var undo by remember { mutableStateOf<HighlightMutation?>(null) }
  val sessions = remember(id) { mutableMapOf<String, Pair<Long, HighlightMutation>>() }
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(id, doc?.documentId, at) {
    val source = doc ?: return@LaunchedEffect
    if (part >= 0 && at == lastAt) return@LaunchedEffect
    val hadPart = part >= 0
    lastAt = at
    try {
      val quote = highlightId?.let { db.highlights().byId(it) }?.takeIf { it.documentId == id }
      if (!hadPart && (at != null || quote != null)) {
        inspectionOrigin = SemanticCursor(id, source.progressBlockId ?: "b0", source.progressCharOffset)
        inspectionFraction = source.progressFraction
      }
      initial = at ?: SemanticCursor(id, quote?.startBlockId ?: source.progressBlockId ?: "b0", quote?.startOffset ?: source.progressCharOffset)
      val index = app.articles.index(id)
      val newPart = if (at != null) index.sectionFor(at.blockId, 0f) else index.sectionFor(initial.blockId, source.progressFraction)
      if (hadPart && newPart == part) displayRequest++
      part = newPart
    } catch (e: Exception) { failure = e.message ?: "Couldn’t open article" }
  }
  LaunchedEffect(id, part) {
    if (part < 0) return@LaunchedEffect
    prepared = null
    content = null
    liveFraction = null
    try {
      val ready = app.articles.section(id, part)
      val quote = highlightId?.let { db.highlights().byId(it) }?.takeIf { it.documentId == id }
      if (quote != null && displayRequest == 0 && ready.index.sectionFor(quote.startBlockId) == part) {
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
  var transitioning by remember(id) { mutableStateOf(false) }
  fun transition(action: suspend () -> Unit) {
    if (transitioning) {
      scope.launch { snackbar.showSnackbar("Saving… please wait.") }
      return
    }
    view?.flushSelection()
    onPauseAudio()
    val cursor = inspectionOrigin ?: liveCursor ?: view?.currentCursor() ?: initial
    if (inspectionOrigin == null) initial = cursor
    prepared?.let {
      try { app.progress.offer(cursor, if (inspectionOrigin != null) inspectionFraction else it.fraction(it.projection.offset(cursor.blockId, cursor.charOffset))) }
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
    if (paletteOpen) { paletteOpen = false; return }
    if (menu) { menu = false; return }
    if (navigationSheet != null) { navigationSheet = null; return }
    if (appearance) { appearance = false; return }
    if (highlightHelp) { highlightHelp = false; return }
    if (showLabels) { showLabels = false; return }
    if (activeQuote != null) { actions = emptyList(); activeQuote = null; return }
    view?.flushSelection()
    if (view?.clearSelection() == true) return
    // Back dismisses the palette/sheet first, then cancels an active selection
    // gesture, then exits fullscreen focus, then exits highlighting, and only
    // then leaves the article.
    if (immersed) { immersed = false; return }
    if (pen) { paletteOpen = false; pen = false; return }
    transition(onBack)
  }
  BackHandler(enabled = !transitioning) { leave() }
  // Fullscreen focus reports its state to the Activity-owned window coordinator,
  // which recomputes the decor/bars/icon contrast from the current surface. The
  // reader copies no captured bar colors, so exiting focus or switching theme
  // never restores a stale light strip.
  SideEffect {
    com.reader.app.ui.theme.ReaderWindow.readerFocused = immersed
    com.reader.app.ui.theme.ReaderWindow.readerSurface = colors.background
  }
  DisposableEffect(Unit) {
    onDispose {
      com.reader.app.ui.theme.ReaderWindow.readerFocused = false
      com.reader.app.ui.theme.ReaderWindow.readerSurface = null
    }
  }
  // Reading sessions run long: never let the lock screen interrupt
  // mid-paragraph. Scoped strictly to the reader and speed-read surfaces.
  DisposableEffect(id) {
    val window = (context as? android.app.Activity)?.window
    window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }
  val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
  DisposableEffect(id, lifecycle) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        view?.flushSelection()
        (inspectionOrigin ?: liveCursor ?: view?.currentCursor())?.let { initial = it }
        view?.reportCursor()
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      view?.flushSelection()
      (inspectionOrigin ?: liveCursor ?: view?.currentCursor())?.let { initial = it }
      view?.reportCursor()
      lifecycle.removeObserver(observer)
    }
  }
  fun share(value: HighlightEntity) {
    // Quotes travel with their source: a shared passage stays attributed
    // even outside the library.
    val source = value.sourceUrl?.takeIf { it.isNotBlank() }?.let { " (${it.take(200)})" }.orEmpty()
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "“${value.quote}”\n— ${value.sourceTitle}$source")
    }, "Share quote"))
  }

  fun jump(location: ArticleLocation, temporary: Boolean = true) {
    val origin = liveCursor ?: view?.currentCursor() ?: initial
    transition {
      if (temporary && inspectionOrigin == null) { inspectionOrigin = origin; inspectionFraction = liveFraction ?: doc?.progressFraction ?: 0f }
      view?.clearSelection()
      initial = location.cursor; liveCursor = location.cursor; jumpMatch = location
      displayRequest++
      navigationSheet = null
      if (part == location.part) view?.jumpTo(location.cursor) else part = location.part
    }
  }
  fun returnToReading() {
    val origin = inspectionOrigin ?: return
    transition {
      val index = app.articles.index(id)
      val destination = index.sectionFor(origin.blockId)
      initial = origin; liveCursor = origin; liveFraction = inspectionFraction
      inspectionOrigin = null; jumpMatch = null; findText = ""; navigationSheet = null
      displayRequest++
      if (part == destination) view?.jumpTo(origin) else part = destination
    }
  }
  fun finish() { transition { finishReceipt = completion.finish(id) } }
  fun undoFinish() {
    val receipt = finishReceipt ?: return
    transition {
      if (!completion.undo(receipt)) scope.launch { snackbar.showSnackbar("Later changes were kept. This finish can no longer be undone.") }
      finishReceipt = null
    }
  }
  fun openWeb(raw: String) {
    val resolved = runCatching { java.net.URI(doc?.sourceUrl.orEmpty()).resolve(raw).toString() }.getOrDefault(raw)
    val uri = Uri.parse(resolved)
    val sameSourceFragment = uri.fragment != null && resolved.substringBefore('#') == doc?.sourceUrl?.substringBefore('#')
    if (raw.startsWith("#") || sameSourceFragment) {
      scope.launch {
        val target = navigation.fragment(id, if (raw.startsWith("#")) raw else "#${uri.fragment}")
        if (target != null) jump(target) else snackbar.showSnackbar("That section wasn’t captured. Open the original to follow this link.")
      }
      return
    }
    if (uri.scheme?.lowercase() !in setOf("http", "https")) { scope.launch { snackbar.showSnackbar("This link can’t be opened here.") }; return }
    runCatching { androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
      .onFailure { scope.launch { snackbar.showSnackbar("No browser is available to open this link.") } }
  }

  ReaderTheme(if (dark) com.reader.app.prefs.ThemeMode.DARK else com.reader.app.prefs.ThemeMode.LIGHT) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
  val dockMaxHeight = maxHeight * 0.45f
  Scaffold(containerColor = colors.background, contentColor = colors.text, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
    // Focus hides the top bar whether pen mode is on or off.
    if (!immersed) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = ::leave, enabled = !transitioning) { Icon(Icons.Default.ArrowBack, "Back") }
      Spacer(Modifier.weight(1f))
      IconButton(onClick = { appearance = true }, enabled = !transitioning) { Icon(Icons.Default.TextFields, "Appearance") }
      Box {
        IconButton(onClick = { menu = true }, enabled = !transitioning) { Icon(Icons.Default.MoreVert, "Reading tools") }
        DropdownMenu(menu, { menu = false }) {
          DropdownMenuItem(text = { Text("Find in article") }, onClick = { menu = false; navigationSheet = "find" })
          DropdownMenuItem(text = { Text("Contents") }, onClick = { menu = false; navigationSheet = "contents" })
          DropdownMenuItem(text = { Text("Listen") }, enabled = prepared != null && !playerVisible,
            onClick = { menu = false; prepared?.let { val cursor = liveCursor ?: view?.currentCursor() ?: initial; transition { onListen(it.projection, cursor) } } })
          DropdownMenuItem(text = { Text("Speed") }, enabled = prepared != null,
            onClick = { menu = false; val cursor = liveCursor ?: view?.currentCursor() ?: initial; transition { onSpeedRead(cursor) } })
          DropdownMenuItem(text = { Text("Labels") }, onClick = { menu = false; showLabels = true })
          DropdownMenuItem(text = { Text("Article highlights · ${marks.size}") }, onClick = { menu = false; onOpenArticleHighlights() })
          if (!doc?.sourceUrl.isNullOrBlank()) DropdownMenuItem(text = { Text("Open original") }, onClick = { menu = false; openWeb(doc!!.sourceUrl!!) })
          DropdownMenuItem(text = { Text(if (immersed) "Show reading controls" else "Focus mode") }, onClick = { menu = false; view?.retainPassageOnLayout(); immersed = !immersed })
          if (prepared?.projection?.tables?.isNotEmpty() == true) DropdownMenuItem(text = { Text("View tables") }, onClick = { menu = false; expandedTable = 0 })
          HorizontalDivider()
          if (doc?.finishedAt == null || doc?.list != com.reader.app.ui.Triage.ARCHIVED) DropdownMenuItem(text = { Text("Finish & archive") }, enabled = doc != null && doc?.sourceType != "link", onClick = { menu = false; finish() })
          val archived = doc?.list == com.reader.app.ui.Triage.ARCHIVED
          (if (archived) listOf(ArticleAction.Unarchive, ArticleAction.Delete) else listOf(ArticleAction.Later, ArticleAction.Archive)).forEach { action ->
            DropdownMenuItem(text = { Text(action.label) }, enabled = action.target == null || doc?.list != action.target, onClick = {
              menu = false
              if (action == ArticleAction.Delete) confirmDelete = true else transition { onArticleAction(action) }
            })
          }
        }
      }
    }
  }, bottomBar = {
    // Focus hides the normal stack; with highlighting active it leaves only the
    // compact dock, which never reserves the old control stack's height.
    if (!immersed) Column(Modifier.navigationBarsPadding()) {
      if (inspectionOrigin != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = ::returnToReading, modifier = Modifier.weight(1f)) { Text("Return to reading position") }
        if (findResults.isNotEmpty()) {
          IconButton(enabled = findIndex > 0, onClick = { findIndex--; jump(findResults[findIndex].toLocation()) }) { Icon(Icons.Default.ChevronLeft, "Previous match") }
          Text("${findIndex+1} of ${findResults.size}", style = MaterialTheme.typography.labelSmall)
          IconButton(enabled = findIndex < findResults.lastIndex, onClick = { findIndex++; jump(findResults[findIndex].toLocation()) }) { Icon(Icons.Default.ChevronRight, "Next match") }
        }
      }
      if (finishReceipt != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Finished and archived", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = ::undoFinish, enabled = !transitioning) { Text("Undo") }
      } else if (atEndOfPart && part == prepared?.index?.sections?.lastIndex && doc?.finishedAt == null && doc?.sourceType != "link") {
        Surface(color = colors.surface) {
          FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !transitioning, onClick = ::finish) { Text("Finish & archive") }
            TextButton(enabled = !transitioning, onClick = ::leave) { Text("Back to Reader") }
          }
        }
      }
      Column(Modifier.heightIn(max = dockMaxHeight).verticalScroll(rememberScrollState())) {
        val ready = prepared
        if (ready != null) {
          val fraction = if (atEndOfPart && part == ready.index.sections.lastIndex) 1f else (liveFraction ?: doc?.progressFraction ?: 0f).coerceIn(0f, 1f)
          val mins = kotlin.math.ceil(((1f-fraction) * com.reader.app.core.ReaderCore.readingMinutes(doc?.wordCount ?: 0)).toDouble()).toInt().coerceAtLeast(1)
          Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${(fraction*100).toInt()}%" + (if (fraction >= 1f) " · End of article" else " · about $mins min left") +
              (if (ready.index.sections.size > 1) " · Part ${part+1}/${ready.index.sections.size}" else ""),
              style = MaterialTheme.typography.labelSmall, color = colors.secondary, modifier = Modifier.weight(1f))
            if (undo != null) TextButton(onClick = {
              val change = undo ?: return@TextButton
              transition { view?.clearSelection(); if (highlights.undo(change)) undo = null else snackbar.showSnackbar("Later highlight changes were kept.") }
            }) { Text("Undo highlight", style = MaterialTheme.typography.labelSmall) }
          }
        }
        player()
        if (pen) {
          // One compact dock; the palette opens above it and closes on choice.
          Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            PenDock(
              colors = colors, monochrome = monochrome, dark = dark,
              selectedColor = selectedColor, paletteOpen = paletteOpen,
              onTogglePalette = { paletteOpen = !paletteOpen },
              onSelectColor = { selectedColor = it; paletteOpen = false },
              immersed = immersed,
              onToggleFocus = { view?.retainPassageOnLayout(); immersed = !immersed },
              onDone = { view?.flushSelection(); paletteOpen = false; pen = false },
            )
          }
        } else {
          // Four equally weighted reading actions: Highlight · Contents ·
          // Listen · Speed. Labels wrap to a 2x2 grid at enlarged text rather
          // than scrolling or shrinking.
          val cursorNow = liveCursor ?: view?.currentCursor() ?: initial
          val readingActions = listOf(
            ReadingActionItem("Highlight", Icons.Default.BorderColor, pen, ready != null) {
              if (!settings.highlightCoachSeen) {
                highlightHelp = true
                onSettingsChange(settings.copy(highlightCoachSeen = true))
              } else {
                view?.retainPassageOnLayout()
                pen = true
              }
            },
            ReadingActionItem("Contents", Icons.AutoMirrored.Filled.FormatListBulleted, false, ready != null) {
              navigationSheet = "contents"
            },
            ReadingActionItem(if (speechPlaying) "Pause" else "Listen",
              if (speechPlaying) Icons.Default.Pause else Icons.AutoMirrored.Filled.VolumeUp, speechPlaying, ready != null) {
              if (speechPlaying) onPauseAudio()
              else if (playerVisible) onResumeAudio()
              else prepared?.let { transition { onListen(it.projection, cursorNow) } }
            },
            ReadingActionItem("Speed", Icons.Default.Speed, false, ready != null) {
              transition { onSpeedRead(cursorNow) }
            },
          )
          if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            readingActions.chunked(2).forEach { pair ->
              Row(Modifier.fillMaxWidth()) { pair.forEach { item -> ReadingActionButton(item, colors, Modifier.weight(1f)) } }
            }
          } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            readingActions.forEach { item -> ReadingActionButton(item, colors, Modifier.weight(1f)) }
          }
        }
      }
    } else if (pen) {
      // Fullscreen focus + highlighting: only the compact dock, bottom-centred,
      // so the reading surface keeps its edges and the last line stays clear.
      Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
      ) {
        PenDock(
          colors = colors, monochrome = monochrome, dark = dark,
          selectedColor = selectedColor, paletteOpen = paletteOpen,
          onTogglePalette = { paletteOpen = !paletteOpen },
          onSelectColor = { selectedColor = it; paletteOpen = false },
          immersed = immersed,
          onToggleFocus = { view?.retainPassageOnLayout(); immersed = !immersed },
          onDone = { view?.flushSelection(); paletteOpen = false; pen = false },
        )
      }
    }
  }) { padding ->
    // Return-to-beginning bubble: only after a library-search landing, once
    // the opening is scrolled away, with no selection/highlight/sheet in
    // flight. `at` is the search cursor (the legacy provenance field), so
    // restored search routes stay compatible. Focus and overlays hide it
    // temporarily without consuming it.
    val showTopBubble = at != null && showTopBubbleRaw &&
      !pen && actions.isEmpty() && !transitioning && !immersed &&
      navigationSheet == null && !highlightHelp && activeQuote == null
    Box(Modifier.padding(padding).fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      // Link-only fallback: honestly labeled, with Open original + Retry +
      // selected-text guidance. The underlying Markdown already carries the
      // same notice; this banner makes the actions one tap away.
      if (doc?.sourceType == "link" && !doc?.sourceUrl.isNullOrBlank()) {
        Surface(color = colors.surface, contentColor = colors.text) {
          Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Article text is unavailable for this link.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(doc!!.sourceUrl))) }
              }) { Text("Open original") }
              TextButton(onClick = {
                val sourceUrl = doc!!.sourceUrl ?: return@TextButton
                scope.launch {
                  try {
                    val repo = com.reader.app.capture.CaptureRepository(db)
                    val req = repo.getOrCreate(sourceUrl, doc!!.title, "retry")
                    com.reader.app.capture.CaptureWorker.scheduleById(context, req.requestId)
                    snackbar.showSnackbar("Link saved — fetching article")
                  } catch (_: Exception) { snackbar.showSnackbar("Couldn’t retry this link.") }
                }
              }) { Text("Retry") }
            }
            Text("Tip: for login or JavaScript pages, open the original, select text, then Share to Reader.",
              style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      // Display-only duplicate-title suppression: when the metadata title and
      // the first rendered heading genuinely match (normalized), the heading in
      // the article body carries the title and the redundant header line is
      // hidden. Extraction, canonical Markdown, hashes and anchors untouched.
      val ready = prepared
      val text = content
      val titleDuplicate = part == 0 && ready != null && isDuplicateTitle(doc?.title.orEmpty(), ready.projection)
      if (part == 0 && openingVisible && !immersed && !doc?.title.isNullOrBlank()) {
        Column {
          if (!titleDuplicate) Text(doc?.title.orEmpty(), color = colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 0.dp))
          // Same truthful source line as the library rows — never a raw
          // "android-share" or bare host.
          Text(
            "${com.reader.app.core.ReaderCore.shortDisplaySource(doc?.sourceType.orEmpty(), doc?.sourceName, doc?.sourceUrl)} · ${com.reader.app.core.ReaderCore.readingMinutes(doc?.wordCount ?: 0)} min",
            style = MaterialTheme.typography.bodySmall, color = colors.secondary,
            modifier = Modifier.padding(16.dp, 2.dp, 16.dp, 0.dp),
          )
          Spacer(Modifier.height(4.dp))
        }
      }
      if (saveError != null) TextButton(onClick = { transition { } }) { Text("Save failed — Retry", color = colors.error) }
      if (failure != null) Text(failure!!, modifier = Modifier.padding(24.dp), color = colors.error)
      else if (ready == null || text == null) {
        if (com.reader.app.ui.theme.LocalDisplayPolicy.current.monochrome) Text("Opening article…", Modifier.padding(24.dp), color = colors.text)
        else CircularProgressIndicator(Modifier.padding(24.dp))
      }
      else {
        if (ready.index.sections.size > 1) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          // Per-part positions: leaving a part bookmarks it, returning restores
          // it — across rotation AND process death (Saveable, not remember).
          val partPositions = rememberSaveable(
            id,
            saver = androidx.compose.runtime.saveable.listSaver(
              save = { map -> map.entries.map { listOf(it.key, it.value.documentId, it.value.blockId, it.value.charOffset) } },
              restore = { list ->
                @Suppress("UNCHECKED_CAST")
                buildMap {
                  for (row in list) {
                    row as List<Any>
                    put((row[0] as Number).toInt(), SemanticCursor(row[1] as String, row[2] as String, (row[3] as Number).toInt()))
                  }
                }.toMutableMap()
              },
            ),
          ) { mutableMapOf<Int, SemanticCursor>() }
          TextButton(enabled = part > 0 && !transitioning, onClick = { transition {
            (liveCursor ?: view?.currentCursor())?.let { partPositions[part] = it }
            initial = partPositions[part - 1] ?: SemanticCursor.start(id); liveCursor = null; part--
          } }) { Text("Previous part") }
          Text("${part + 1} / ${ready.index.sections.size}", modifier = Modifier.padding(12.dp))
          TextButton(enabled = part < ready.index.sections.lastIndex && !transitioning, onClick = { transition {
            (liveCursor ?: view?.currentCursor())?.let { partPositions[part] = it }
            initial = partPositions[part + 1] ?: SemanticCursor.start(id); liveCursor = null; part++
          } }) { Text("Next part") }
        }
        val nativeMarks = remember(marks, ready, dark, monochrome, at, atEnd, matchTint, jumpMatch) {
          val blocks = ready.projection.blocks.mapTo(hashSetOf()) { it.id }
          buildList {
            addAll(marks.filter { it.projectionVersion == RENDERED_PROJECTION_VERSION && it.startBlockId in blocks && it.endBlockId in blocks }.map {
              val pres = highlightPresentation(it.color, monochrome, dark)
              val edge = if (monochrome) when (pres.edge) {
                MonoEdge.SOLID -> "solid"; MonoEdge.DOUBLE -> "double"; MonoEdge.DASHED -> "dashed"; MonoEdge.DOTTED -> "dotted"
              } else "none"
              NativeMark(it.id, ready.projection.offset(it.startBlockId, it.startOffset), ready.projection.offset(it.endBlockId, it.endOffset),
                it.createdAt, pres.fill.toArgb(), pres.text.toArgb(), edge)
            })
            jumpMatch?.takeIf { it.part == part && it.end != null }?.let { match ->
              val start = ready.projection.offset(match.cursor.blockId, match.cursor.charOffset)
              add(NativeMark("search-hit", start, match.end!!, 0, colors.link.copy(alpha = .25f).toArgb(), colors.text.toArgb()))
            }
            // Search-landing tint: one fading span over the matched text.
            // Cleared on the first real scroll (see onCursor below).
            if (matchTint && at != null && atEnd != null) {
              val start = ready.projection.offset(at.blockId, at.charOffset)
              if (atEnd > start) add(NativeMark("search-hit", start, atEnd.coerceAtMost(ready.projection.text.length),
                System.currentTimeMillis(), colors.link.copy(alpha = 0.28f).toArgb(), colors.text.toArgb()))
            }
          }
        }
        // displayRequest forces a re-display (and scroll restore) when a new
        // match lands on the already-loaded part.
        val styleKey = listOf(ready, text, settings.font, settings.fontSizeSp, settings.margin, settings.bold, settings.lineSpacing, colors, displayRequest)
        // Wide screens cap the measure for comfortable line lengths.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
          val wide = maxWidth > 700.dp
          val capWidth = if (wide) 680.dp else maxWidth
          Box(
            Modifier.fillMaxSize().semantics {
              // Focus mode keeps no visible reveal control; expose the reveal
              // as an accessibility action on the reading surface instead.
              if (immersed) customActions = listOf(CustomAccessibilityAction("Show reading controls") { immersed = false; true })
            },
            contentAlignment = Alignment.TopCenter,
          ) {
            AndroidView(factory = { NativeArticleView(it).also { view = it } },
              modifier = Modifier.widthIn(max = capWidth).fillMaxHeight().clipToBounds(),
              update = { native ->
          if (native.tag != styleKey) {
            native.display(id, ready.projection, text, settings, colors.text.toArgb(), colors.background.toArgb(), marginDp(settings.margin, wide), initial)
            native.tag = styleKey
          }
          native.deleteTint = colors.error.toArgb()
          native.monoEdgeColor = colors.text.toArgb()
          native.reducedMotion = effectiveReducedMotion
          native.articleList = null
          native.onArticleSwipe = { action -> transition { onArticleAction(action) } }
          native.setPenMode(pen)
          native.setMarks(nativeMarks)
          // Immersive toggle: any clean tap (no pen, no selection) flips the
          // chrome. Drag physics and selection are untouched.
          native.onTap = { view?.retainPassageOnLayout(); immersed = !immersed }
          native.onCursor = { cursor, fraction ->
            liveCursor = cursor
            // Whole-document fraction for the footer: the raw callback value
            // is part-local and would visibly jump backwards when a new part
            // starts. One offset computation feeds both the footer and the
            // durable progress offer.
            if (native.tag == styleKey) {
              val whole = ready.fraction(ready.projection.offset(cursor.blockId, cursor.charOffset))
              liveFraction = whole
              if (!speechPlaying && inspectionOrigin == null) app.progress.offer(cursor, whole)
            }
            // The match tint survives the landing settle (cursor == hit) and
            // clears on the first real scroll away from it.
            if (matchTint) {
              val a = at
              if (a == null || cursor.blockId != a.blockId || kotlin.math.abs(cursor.charOffset - a.charOffset) > 40) {
                matchTint = false
              }
            }
          }
          native.onAtEnd = { end ->
            if (!native.gestureActive && atEndOfPart != end) {
              native.retainPassageOnLayout(pinEnd = end)
              atEndOfPart = end
            }
          }
          native.onViewport = { scrollY, viewportHeight ->
            val atOpening = scrollY <= 8
            if (!native.gestureActive && openingVisible != atOpening) { native.retainPassageOnLayout(); openingVisible = atOpening }
            val show = viewportHeight > 0 && scrollY > viewportHeight
            if (show != showTopBubbleRaw) showTopBubbleRaw = show
          }
          native.onMark = { actions = it }
          native.onTable = { expandedTable = it }
          native.onLink = ::openWeb
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
                    // The mark updates immediately. A stable dock Undo avoids
                    // covering the passage on every native handle adjustment.
                    if (sequence == 1L) view?.announceForAccessibility("Highlight saved")
                  }
                }
              } catch (error: Exception) { scope.launch { snackbar.showSnackbar(error.message ?: "Couldn’t prepare highlight") } }
            }
          }
          // Final-commit bridge: a completed pen gesture persists its final
          // half-open range once and acknowledges, which is what settles the
          // native selection. A failure keeps the range on screen for a retry.
          native.onSettle = { session, range, ack ->
            val source = doc
            if (source == null) ack(false) else {
              val frozenColor = selectedColor
              try {
                val draft = HighlightAnchors.create(session, source, ready.projection, range.first, range.last + 1,
                  System.currentTimeMillis()).copy(color = frozenColor)
                app.reading.submitSelection(draft) { change ->
                  val before = sessions[session]?.second?.before ?: change.before
                  val aggregate = HighlightMutation(before, change.after)
                  sessions[session] = System.currentTimeMillis() to aggregate
                  while (sessions.size > 16) sessions.remove(sessions.keys.first())
                  undo = aggregate.takeIf { it.before != null || it.after != null }
                  ack(true)
                }
              } catch (_: Exception) { ack(false) }
            }
          }
        })
          } // inner centering Box
        } // BoxWithConstraints
      }
    }
      if (showTopBubble) {
        SmallFloatingActionButton(
          onClick = {
            matchTint = false
            // Beginning of part zero, not merely the current part.
            if (part == 0) view?.smoothScrollToTop() else transition {
              initial = SemanticCursor.start(id); liveCursor = null
              displayRequest++
              part = 0
            }
          },
          modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
          containerColor = colors.surface,
          contentColor = colors.text,
        ) {
          Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Go to article beginning")
        }
      }
    }
  }
  }
  expandedTable?.let { selected ->
    prepared?.projection?.let { projection ->
      TableViewer(projection, selected, settings, colors, onSelect = { expandedTable = it }, onDismiss = { expandedTable = null })
    }
  }
  if (appearance) AppearanceSheet(settings, onSettingsChange) { appearance = false }
  if (showLabels) {
    val assigned = remember(docLabels) { docLabels.map { it to 1 }.toMap() }
    LabelsDialog(
      title = "Labels",
      assignedCounts = assigned,
      totalDocs = 1,
      suggestions = labelSuggestions,
      onToggle = onToggleLabel,
      onDismiss = { showLabels = false },
      colors = colors,
    )
  }
  activeQuote?.let { quote -> HighlightActionsSheet(quote, actions.size > 1,
    onNextOverlap = { actions = actions.drop(1) + actions.first() },
    onColor = { color -> scope.launch { try { undo = highlights.recolor(quote.id, color); activeQuote = db.highlights().byId(quote.id) } catch (_: Exception) { snackbar.showSnackbar("Couldn’t change color. Try again.") } } },
    onImportant = { scope.launch { try { undo = highlights.toggleImportant(quote.id); activeQuote = db.highlights().byId(quote.id) } catch (_: Exception) { snackbar.showSnackbar("Couldn’t save importance. Try again.") } } },
    onShare = { share(quote) },
    onRemove = { scope.launch {
      try { val removed = highlights.remove(quote.id); if (removed != null && onHighlightRemoved != null) { undo = null; onHighlightRemoved(removed) } else undo = removed; actions = emptyList(); activeQuote = null }
      catch (_: Exception) { snackbar.showSnackbar("Couldn’t remove highlight. Try again.") }
    } },
    onArticleHighlights = { actions = emptyList(); activeQuote = null; onOpenArticleHighlights() },
    onDismiss = { actions = emptyList(); activeQuote = null }) }
  if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete article?") },
    text = { Text("This permanently removes the saved article. Your highlights and their attribution stay saved.") },
    confirmButton = { TextButton(onClick = { confirmDelete = false; transition { onArticleAction(ArticleAction.Delete) } }) { Text("Delete article", color = colors.error) } },
    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep article") } })
  if (highlightHelp) ModalBottomSheet(onDismissRequest = { highlightHelp = false }, containerColor = colors.background) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Keep a passage", style = MaterialTheme.typography.titleLarge)
      Text("Press and hold a word, drag to extend over the text, then release. Your selection is highlighted and saved automatically. Choose a color in the dock; tap Done to leave highlighting.")
      Text("Copy, Share and your device’s text actions stay available outside continuous highlighting.", color = colors.secondary)
      Button(onClick = { highlightHelp = false; pen = true }) { Text("Start highlighting") }
    }
  }
  if (navigationSheet != null) ModalBottomSheet(onDismissRequest = { navigationSheet = null }, containerColor = colors.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    val partCount = prepared?.index?.sections?.size ?: 1
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val isFind = navigationSheet == "find"
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val sheetHeight = screenH * (if (imeVisible || largeText) 0.9f else 0.55f)
    Column(
      Modifier.fillMaxWidth()
        .then(if (isFind) Modifier.height(sheetHeight) else Modifier.heightIn(max = 560.dp))
        .imePadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)
    ) {
      if (isFind) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Find in article", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
          IconButton(onClick = { navigationSheet = null }) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        ReaderSearchField(
          value = findText,
          onValueChange = { findText = it },
          placeholder = "Word or phrase",
          clearLabel = "Clear search",
          modifier = Modifier.fillMaxWidth(),
          surfaceColor = colors.divider.copy(alpha = .4f),
          textColor = colors.text,
          hintColor = colors.secondary,
          cursorColor = colors.link,
          keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
            val target = findResults.getOrNull(findIndex) ?: findResults.firstOrNull()
            target?.let { keyboard?.hide(); jump(it.toLocation()) }
          }),
        )
        when {
          findText.isBlank() -> Text("Search the article text stored on this device.", style = MaterialTheme.typography.bodySmall, color = colors.secondary, modifier = Modifier.padding(top = 8.dp))
          navigationError != null -> {
            Text(navigationError!!, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { val q = findText; findText = ""; findText = q }) { Text("Retry") }
          }
          findBusy -> Text("Searching all parts…", style = MaterialTheme.typography.bodySmall, color = colors.secondary, modifier = Modifier.padding(top = 8.dp))
          findResults.isEmpty() -> Text("No matches for “$findText”.", style = MaterialTheme.typography.bodySmall, color = colors.secondary, modifier = Modifier.padding(top = 8.dp))
          else -> {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
              Text("${findIndex + 1} of ${findResults.size}", style = MaterialTheme.typography.labelLarge, color = colors.secondary, modifier = Modifier.weight(1f))
              IconButton(enabled = findIndex > 0, onClick = { findIndex--; jump(findResults[findIndex].toLocation()) }) { Icon(Icons.Default.ChevronLeft, "Previous match") }
              IconButton(enabled = findIndex < findResults.lastIndex, onClick = { findIndex++; jump(findResults[findIndex].toLocation()) }) { Icon(Icons.Default.ChevronRight, "Next match") }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
              items(findResults.size) { i ->
                val match = findResults[i]
                val isSelected = i == findIndex
                Surface(
                  color = colors.background,
                  shape = RoundedCornerShape(12.dp),
                  border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, colors.text) else null,
                  modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { findIndex = i; keyboard?.hide(); jump(match.toLocation()) }
                    .semantics { selected = isSelected },
                ) {
                  Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) Box(
                      Modifier.width(4.dp).height(36.dp).background(colors.text, RoundedCornerShape(2.dp))
                        .semantics { contentDescription = "Selected result" },
                    )
                    if (isSelected) Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                    if (partCount > 1) Text("Part ${match.part + 1}", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
                    val snippet = match.snippet
                    val ms = match.matchStart.coerceIn(0, snippet.length)
                    val me = match.matchEnd.coerceIn(ms, snippet.length)
                    Text(
                      buildAnnotatedString {
                        append(snippet.substring(0, ms))
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, background = colors.link.copy(alpha = .18f))) { append(snippet.substring(ms, me)) }
                        append(snippet.substring(me))
                      },
                      style = MaterialTheme.typography.bodyMedium,
                      color = colors.text,
                      maxLines = 3,
                      overflow = TextOverflow.Ellipsis,
                    )
                    }
                  }
                }
              }
            }
          }
        }
      } else {
        Text("Contents", style = MaterialTheme.typography.titleLarge)
        navigationError?.let { Text(it, color = colors.error) }
        androidx.compose.foundation.lazy.LazyColumn {
        val index = prepared?.index
        if (index != null) index.sections.indices.forEach { n ->
          if (index.sections.size > 1 || contents.none { it.part == n }) item {
            TextButton(onClick = { scope.launch { val ready = app.articles.section(id, n); jump(ArticleLocation(n, ready.projection.cursor(id, 0), "Part ${n+1}")) } }) {
              Text(if (index.sections.size == 1) "Article opening" else "Part ${n+1}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
          }
          val current = contents.filter { it.part <= part }.lastOrNull { location ->
            location.part < part || (prepared?.projection?.offset(location.cursor.blockId, location.cursor.charOffset) ?: 0) <=
              (prepared?.projection?.offset(liveCursor?.blockId ?: initial.blockId, liveCursor?.charOffset ?: initial.charOffset) ?: 0)
          }
          contents.filter { it.part == n }.forEach { location -> item {
            TextButton(onClick = { jump(location) }) { Text((if (current == location) "• " else "") + location.label,
              fontWeight = if (current == location) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) }
          } }
        }
        }
      }
    }
  }
  }
}
