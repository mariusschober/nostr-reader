package com.reader.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.ReaderDb
import com.reader.app.data.ArticleMoves
import com.reader.app.data.ArticleMove
import com.reader.app.nostr.READER_DEFAULT_RELAYS
import com.reader.app.nostr.READER_RELAY_WRITE_QUORUM
import com.reader.app.prefs.Prefs
import com.reader.app.prefs.ArticleBackground
import com.reader.app.prefs.ReaderSettings
import com.reader.app.rsvp.RsvpModel
import com.reader.app.security.KeystoreWrap
import com.reader.app.signer.AmberSigner
import com.reader.app.sync.Ingest
import com.reader.app.sync.PairingCoordinator
import com.reader.app.sync.ReaderSyncSession
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.reader.app.sync.SyncWorker
import com.reader.app.sync.TransferManager
import com.reader.app.tts.AndroidTtsEngine
import com.reader.app.tts.Narration
import com.reader.app.tts.TtsController
import com.reader.app.ui.Route
import com.reader.app.ui.RouteStack
import com.reader.app.ui.Triage
import com.reader.app.ui.screens.*
import com.reader.app.ui.theme.colorsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
  private lateinit var db: ReaderDb
  private lateinit var prefs: Prefs
  private lateinit var keys: KeystoreWrap
  private var ttsEngine: AndroidTtsEngine? = null
  private var ttsController: TtsController? = null
  private var refreshTick = androidx.compose.runtime.mutableStateOf(0)
  private val reviewMutex = kotlinx.coroutines.sync.Mutex()
  private val exportMutex = kotlinx.coroutines.sync.Mutex()

  private val exportDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    if (uri != null) lifecycleScope.launch { exportArchive(uri) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    db = ReaderDb.get(this)
    prefs = Prefs(this)
    keys = KeystoreWrap(this)
    SyncWorker.schedule(this)
    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        val receiving = ReaderSyncSession(applicationContext)
        while (true) {
          receiving.runOnce()
          kotlinx.coroutines.delay(1000)
        }
      }
    }
    handleIncomingIntent(intent)
    window.setBackgroundDrawableResource(android.R.color.black)
    setContent {
      val loadedSettings by remember { prefs.flow }.collectAsState(initial = null)
      val settings = loadedSettings ?: return@setContent
      com.reader.app.ui.theme.ReaderTheme(settings.themeMode) {
      var savedRoutes by rememberSaveable { mutableStateOf("[]") }
      val stack = remember { RouteStack(runCatching { Json.decodeFromString<List<Route>>(savedRoutes) }.getOrDefault(emptyList())) }
      var tick by remember { mutableIntStateOf(0) }
      fun go(r: Route) { stack.push(r); tick++ }
      // Pop returns to the exact prior destination (Inbox tab, Archive,
      // Review, Reader) with its preserved scroll state. Settings, Pairing,
      // Review and Reader all use this so round trips never reset
      // indiscriminately to Inbox.
      fun pop() { stack.pop(); tick++ }
      // NB: tick MUST be read here (via remember key). RouteStack is a plain
      // mutable list, not observable state: pushing without reading tick
      // schedules no recomposition, so navigation silently never renders
      // (row taps, settings, back all "did nothing" — bug #1, 2026-09-04).
      val route = remember(tick) { stack.current() }
      SideEffect { savedRoutes = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Route.serializer()), stack.snapshot()) }
      var lists by remember { mutableStateOf(mapOf<String, List<com.reader.app.data.DocumentSummary>>()) }
      var libraryLoaded by remember { mutableStateOf(false) }
      var selectedTab by rememberSaveable { mutableStateOf(Triage.INBOX) }
      val mainScrollStates = mapOf(
        Triage.INBOX to androidx.compose.foundation.lazy.rememberLazyListState(),
        Triage.PRIORITY to androidx.compose.foundation.lazy.rememberLazyListState(),
        Triage.LATER to androidx.compose.foundation.lazy.rememberLazyListState(),
      )
      val archiveScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
      val highlightsScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
      val reviewScrollState = androidx.compose.foundation.rememberScrollState()
      var reviewScrollId by rememberSaveable { mutableStateOf<String?>(null) }
      var highlightSeed by rememberSaveable { mutableLongStateOf(java.security.SecureRandom().nextLong()) }
      var highlightsNewest by rememberSaveable { mutableStateOf(false) }
      val highlightSummaries by remember { db.highlights().observeSummaries() }.collectAsState(initial = emptyList())
      var channels by remember { mutableStateOf(listOf<com.reader.app.data.ChannelEntity>()) }
      var minutesByList by remember { mutableStateOf(mapOf<String, Int>()) }
      var pairingError by remember { mutableStateOf<String?>(null) }
      var pairingStatus by remember { mutableStateOf<String?>(null) }
      var pairingChannelId by remember { mutableStateOf<String?>(null) }
      var ttsState by remember { mutableStateOf<TtsController.State?>(null) }
      var readerMove by remember { mutableStateOf<MoveNotice?>(null) }
      val articleMoves = remember { ArticleMoves(db) }
      var readerMoving by remember { mutableStateOf(false) }
      var ttsDocId by remember { mutableStateOf<String?>(null) }
      // Library search: text + scope survive rotation; results re-query.
      // Blank query always means "no search" (never MATCH '').
      var searchActive by rememberSaveable { mutableStateOf(false) }
      var searchText by rememberSaveable { mutableStateOf("") }
      // Scope persisted as a name (enums need explicit savers to survive process death).
      var searchScopeName by rememberSaveable { mutableStateOf(com.reader.app.data.SearchScope.ALL.name) }
      val searchScope = com.reader.app.data.SearchScope.valueOf(searchScopeName)
      var recentsTick by remember { mutableIntStateOf(0) }
      val recentsStore = remember { com.reader.app.data.RecentsStore(this@MainActivity) }
      // Labels: counts stream live; the doc→norms map reloads on library or
      // assignment change. Selection is session state (a persisted label that
      // later empties out simply shows an empty state, never an error).
      val labelCounts by remember { db.labels().observeLabels() }.collectAsState(initial = emptyList())
      var selectedLabelNorm by rememberSaveable { mutableStateOf<String?>(null) }
      var labelsTick by remember { mutableIntStateOf(0) }
      var labelsByDoc by remember { mutableStateOf(mapOf<String, Set<String>>()) }
      LaunchedEffect(lists, labelsTick) {
        labelsByDoc = withContext(Dispatchers.IO) {
          val normById = db.labels().allLabels().associate { it.labelId to it.normalized }
          db.labels().allPairs().groupBy({ it.documentId }, { normById[it.labelId] ?: "?" })
            .mapValues { (_, norms) -> norms.toSet() }
        }
      }
      fun toggleLabel(ids: Set<String>, raw: String) {
        val norm = com.reader.app.data.LabelNorm.normalize(raw) ?: return
        val have = ids.associateWith { id -> labelsByDoc[id]?.contains(norm) == true }
        lifecycleScope.launch {
          withContext(Dispatchers.IO) {
            if (have.values.all { it }) {
              val labelId = db.labels().idForNormalized(norm) ?: return@withContext
              ids.forEach { id -> db.labels().unassign(id, labelId) }
            } else {
              val now = System.currentTimeMillis()
              ids.forEach { id -> db.labels().assignNorm(id, raw, now) }
            }
          }
          labelsTick++
        }
      }
      val searchRepo = remember { com.reader.app.data.SearchRepository(db, (application as com.reader.app.ReaderApp).articles, recentsStore) }
      // Debounced, conflated: every input restarts the collection, stale
      // queries cancel when typing advances, and the previous results stay
      // visible until the next set lands (no flicker, no reset).
      var searchResults by remember { mutableStateOf(com.reader.app.data.SearchResults(emptyList(), 0)) }
      LaunchedEffect(searchActive, searchText, searchScopeName, selectedTab, route) {
        snapshotFlow {
          listOf(
            searchActive.toString(),
            searchText,
            searchScopeName,
            if (route == Route.Archive) Triage.ARCHIVED else selectedTab,
          )
        }
          .debounce(150)
          .distinctUntilChanged()
          .mapLatest { (active, text, scopeName, list) ->
            val scope = com.reader.app.data.SearchScope.valueOf(scopeName)
            if (active != "true" || text.isBlank()) com.reader.app.data.SearchResults(emptyList(), 0)
            else searchRepo.observeResults(text, scope, list).first()
          }
          .flowOn(Dispatchers.Default)
          .catch { emit(com.reader.app.data.SearchResults(emptyList(), 0)) }
          .collect { searchResults = it }
      }
      fun openSearchResult(documentId: String, query: String = searchText) {
        lifecycleScope.launch {
          recentsStore.record(query)
          recentsTick++
          val hit = withContext(Dispatchers.IO) {
            runCatching { searchRepo.matchOffset(documentId, query) }.getOrNull()
          }
          if (hit != null) go(Route.Reader(documentId, at = hit.cursor, atEnd = hit.endRendered))
          else go(Route.Reader(documentId))
        }
      }
      // Room invalidation keeps a visible inbox truthful when a background
      // relay sync commits a document after onResume's initial refresh.
      val syncHealth by remember { db.syncHealth().observe() }.collectAsState(initial = null)
      var syncing by remember { mutableStateOf(false) }
      var libraryStats by remember { mutableStateOf<com.reader.app.ui.screens.LibraryStats?>(null) }
      var finishNotice by remember { mutableStateOf<String?>(null) }
      // Finishes surface once, quietly, and only in the library — never in
      // the reader (the inline card covers that) and never twice.
      LaunchedEffect(Unit) {
        (application as com.reader.app.ReaderApp).progress.finished.collect { docId ->
          try {
            if (route !is Route.Inbox && route !is Route.Archive) return@collect
            val doc = withContext(Dispatchers.IO) { db.documents().metadataById(docId) } ?: return@collect
            val mins = com.reader.app.core.ReaderCore.readingMinutes(doc.wordCount)
            val total = withContext(Dispatchers.IO) { db.readingStats().finishedTotal() }
            val byDay = withContext(Dispatchers.IO) {
              db.readingStats().recentDayStats(400).associate { it.day to it }
            }
            val run = com.reader.app.data.ReadingRun.computeRun(byDay, com.reader.app.data.ReadingRun.today())
            val milestone = when {
              total >= 100 && prefs.takeMilestone("m100") -> "Quiet milestone — 100 articles finished. Remarkable."
              total >= 50 && prefs.takeMilestone("m50") -> "Quiet milestone — 50 articles finished."
              total >= 10 && prefs.takeMilestone("m10") -> "Quiet milestone — 10 articles finished. Your shelf is working."
              run >= 7 && prefs.takeMilestone("run7") -> "A week of steady reading. No streak to protect — just a nice rhythm."
              else -> null
            }
            finishNotice = milestone ?: "Finished “${doc.title.take(60)}” · $mins min"
          } catch (_: Exception) { /* best-effort celebration only */ }
        }
      }


      val windowColors = if (route is Route.Reader || route is Route.Rsvp) com.reader.app.ui.theme.readerColors(settings.background) else com.reader.app.ui.theme.appColors()
      SideEffect {
        val bg = windowColors.background
        window.statusBarColor = bg.toArgb()
        window.navigationBarColor = bg.toArgb()
        WindowCompat.getInsetsController(window, window.decorView)
          .isAppearanceLightStatusBars =
          windowColors.text == com.reader.app.ui.theme.Flexoki.Black
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = windowColors.text == com.reader.app.ui.theme.Flexoki.Black
      }

      suspend fun computeLibraryStats(summaries: List<com.reader.app.data.DocumentSummary>): com.reader.app.ui.screens.LibraryStats =
        withContext(Dispatchers.IO) {
          val weekStart = com.reader.app.data.ReadingRun.daysAgo(6)
          val byDay = db.readingStats().recentDayStats(400).associate { it.day to it }
          com.reader.app.ui.screens.LibraryStats(
            total = summaries.size,
            weekMinutes = db.readingStats().minutesSince(weekStart),
            weekFinished = db.readingStats().finishedSince(weekStart),
            runDays = com.reader.app.data.ReadingRun.computeRun(byDay, com.reader.app.data.ReadingRun.today()),
          )
        }

      suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) {
          db.documents().observeSummaries().first().groupBy { it.list }
        }
        lists = loaded
        libraryLoaded = true
        channels = withContext(Dispatchers.IO) { db.channels().active() }
        val mins = withContext(Dispatchers.IO) {
          Triage.TABS.associateWith { l ->
            maxOf(0, ((db.documents().wordsInList(l) + 224) / 225).toInt())
          }
        }
        minutesByList = mins
        libraryStats = computeLibraryStats(loaded.values.flatten())
        // First-save warmth: exactly once, only when the library was empty.
        // The sample teaches triage, highlights and offline in 3 minutes.
        // The .qa package holds destructive fixtures that need a controllable
        // empty library — the welcome sample stays a real-package behavior.
        if (loaded.values.all { it.isEmpty() } && !prefs.isWelcomeShown() &&
          !this@MainActivity.packageName.endsWith(".qa")) {
          try {
            Ingest.importPasted(this@MainActivity, WELCOME_MARKDOWN)
            prefs.setWelcomeShown()
            Toast.makeText(this@MainActivity, "First save — welcome in. It’s yours, offline.", Toast.LENGTH_LONG).show()
            val reloaded = withContext(Dispatchers.IO) {
              db.documents().observeSummaries().first().groupBy { it.list }
            }
            lists = reloaded
            libraryLoaded = true
          } catch (_: Exception) { /* next refresh retries; never block startup */ }
        }
      }
      LaunchedEffect(refreshTick.value, route) {
        if (route == Route.Inbox || route == Route.Archive) {
          db.documents().observeSummaries().collect { summaries ->
            lists = summaries.groupBy { it.list }
            minutesByList = lists.mapValues { (_, values) -> ((values.sumOf { it.wordCount.toLong() } + 224) / 225).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            libraryLoaded = true
            try { libraryStats = computeLibraryStats(summaries) } catch (_: Exception) { /* stats are decoration */ }
          }
        } else if (route == Route.Settings || route == Route.Pairing) {
          channels = withContext(Dispatchers.IO) { db.channels().active() }
        }
      }

      val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        lifecycleScope.launch {
          try {
            val id = Ingest.importFile(this@MainActivity, uri, uri.lastPathSegment ?: "file")
            Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
            refresh()
            go(Route.Reader(id))
          } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Import failed: ${e.message?.take(120)}", Toast.LENGTH_LONG).show()
          }
        }
      }

      fun openTts(docId: String, projection: com.reader.app.core.RenderedProjection, from: SemanticCursor) {
        ttsController?.pause()
        lifecycleScope.launch {
          val audioRepo = (application as com.reader.app.ReaderApp).articles
          val audioIndex = audioRepo.index(docId)
          val audioSection = audioRepo.section(docId, audioIndex.sectionFor(from.blockId))
          val units = withContext(Dispatchers.Default) { Narration.sentences(projection) }
          val eng = ttsEngine ?: AndroidTtsEngine(this@MainActivity).also {
            ttsEngine = it
            // Refresh the network-required banner if TTS init completes after load.
            it.onReady = { ttsController?.refreshVoice() }
          }
          val ctl = ttsController ?: TtsController(eng).also { ttsController = it }
          eng.onFocusLost = { ctl.pause() }
          ctl.onPosition = { blockId, offset ->
            val cursor = SemanticCursor(docId, blockId, offset)
            (application as com.reader.app.ReaderApp).progress.offer(cursor,
              audioSection.fraction(projection.offset(blockId, offset)))
          }
          ctl.onState = { ttsState = it }
          ctl.load(units, from.blockId, settings.ttsSpeed, from.charOffset)
          ttsDocId = docId
          ctl.play()
        }
      }

      fun moveReader(id: String, target: String) {
        if (readerMoving) return
        readerMoving = true
        lifecycleScope.launch {
          try {
            (application as com.reader.app.ReaderApp).progress.flush()
            val previous = db.documents().observeMetadata(id).first()?.list ?: return@launch
            if (previous == target) return@launch
            val changes = articleMoves.move(setOf(id), target)
            if (changes.isNotEmpty()) readerMove = MoveNotice(changes)
            ttsController?.pause(); ttsState = null; ttsDocId = null
            refresh(); stack.pop(); tick++
          } catch (_: Exception) {
            Toast.makeText(this@MainActivity, "Couldn’t move that just now. Nothing was moved — try again.", Toast.LENGTH_LONG).show()
          } finally { readerMoving = false }
        }
      }

      val deleting = remember { mutableStateListOf<String>() }
      fun deleteArticle(id: String) {
        if (id in deleting) return
        deleting.add(id)
        lifecycleScope.launch {
          try {
            if (ttsDocId == id) { ttsController?.pause(); ttsState = null; ttsDocId = null }
            (application as com.reader.app.ReaderApp).deleteArticle(id)
            if ((stack.current() as? Route.Reader)?.id == id) { stack.pop(); tick++ }
          } catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t delete article. Try again.", Toast.LENGTH_LONG).show() }
          finally { deleting.remove(id) }
        }
      }
      fun moveArticles(ids: Set<String>, target: String) {
        lifecycleScope.launch {
          try {
            val changes = articleMoves.move(ids, target)
            if (changes.isNotEmpty()) readerMove = MoveNotice(changes)
          } catch (error: kotlinx.coroutines.CancellationException) { throw error }
          catch (error: Exception) {
            android.util.Log.e("ReaderMove", "batch move failed", error)
            Toast.makeText(this@MainActivity, "Couldn’t move articles. Nothing was moved. Try again.", Toast.LENGTH_LONG).show()
          }
        }
      }
      fun undoMoves(moves: List<ArticleMove>) {
        lifecycleScope.launch {
          try {
            if (articleMoves.undo(moves) < moves.size) {
              Toast.makeText(this@MainActivity, "Restored available articles. Later changes were kept.", Toast.LENGTH_LONG).show()
            }
          } catch (error: kotlinx.coroutines.CancellationException) { throw error }
          catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t undo the move. Try again.", Toast.LENGTH_LONG).show() }
        }
      }

      when (val r = route) {
          is Route.Inbox, is Route.Archive -> InboxScreen(
            archiveMode = r == Route.Archive,
            onArchiveOpen = { go(Route.Archive) }, onArchiveBack = { stack.pop(); tick++ },
            listState = if (r == Route.Archive) archiveScrollState else mainScrollStates[selectedTab] ?: mainScrollStates.getValue(Triage.PRIORITY),
            lists = lists, minutes = minutesByList, settings = settings,
            readerMove = readerMove, onReaderMoveConsumed = { noticeId -> if (readerMove?.id == noticeId) readerMove = null },
            loaded = libraryLoaded, selectedTab = selectedTab, onSelectTab = { selectedTab = it },
            highlights = { HighlightsFeed(highlightSummaries, highlightSeed, highlightsNewest, {
            highlightsNewest = it
            if (!it) {
              val ids = highlightSummaries.map { quote -> quote.id }
              fun order(seed: Long) = ids.sortedWith(compareBy<String> { id -> com.reader.app.core.ReviewScheduler.feedKey(seed, id) }.thenBy { id -> id })
              val previous = order(highlightSeed)
              val random = java.security.SecureRandom()
              var candidate = random.nextLong()
              var attempts = 0
              while (ids.size > 1 && order(candidate) == previous && attempts++ < 128) candidate = random.nextLong()
              highlightSeed = candidate
            }
            lifecycleScope.launch { highlightsScrollState.scrollToItem(0) }
          },
            listState = highlightsScrollState,
            onOpenLatest = lists.values.flatten().maxByOrNull { it.createdAt }?.let { d -> ({ go(Route.Reader(d.documentId)) }) },
            onOpenSource = { quoteId -> lifecycleScope.launch {
              // The quote is a door back to the essay. TOCTOU-guarded like
              // the Review path; deleted sources never strand the reader.
              val entity = try { db.highlights().byId(quoteId) } catch (_: Exception) { null }
              if (entity != null && db.documents().exists(entity.documentId)) go(Route.Reader(entity.documentId, entity.id))
              else Toast.makeText(this@MainActivity, "Source article was deleted. Your quote is still saved.", Toast.LENGTH_LONG).show()
            } },
            onReview = { chosen -> lifecycleScope.launch {
              try { com.reader.app.data.ReviewRepository(db).resume(chosen); go(Route.Review) }
              catch (e: Exception) { Toast.makeText(this@MainActivity, "Couldn’t open review: ${e.message?.take(100)}", Toast.LENGTH_LONG).show() }
            } },
            onToggleImportant = { id -> lifecycleScope.launch {
              // Feed toggles never run while the Review route is visible, and
              // toggleImportant is one transaction: no review mutex needed.
              try { com.reader.app.data.ReviewRepository(db).toggleImportant(id) }
              catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t save importance. Try again.", Toast.LENGTH_LONG).show() }
            } },
            onRemoveHighlights = { ids -> lifecycleScope.launch {
              try {
                val repo = com.reader.app.data.HighlightRepository(db)
                ids.forEach { repo.remove(it) }
              } catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t remove highlights. Try again.", Toast.LENGTH_LONG).show() }
            } },
          ) },
          onOpen = { go(Route.Reader(it)) },
          onMove = ::moveArticles,
          onUndoMove = ::undoMoves,
          onDelete = ::deleteArticle,
          onImportFile = { filePicker.launch(arrayOf("text/plain", "text/markdown", "*/*")) },
          onPasteText = { text ->
            lifecycleScope.launch {
              try {
                when (val kind = com.reader.app.capture.ShareIntentClassifier.classify(text, null, Ingest::looksLikeMarkdown)) {
                  is com.reader.app.capture.ShareClassification.SingleUrl,
                  is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> {
                    val url = when (kind) {
                      is com.reader.app.capture.ShareClassification.SingleUrl -> kind.url
                      is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> kind.url
                      else -> text.trim()
                    }
                    val titleHint = when (kind) {
                      is com.reader.app.capture.ShareClassification.SingleUrl -> kind.titleHint
                      is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> kind.titleHint
                      else -> null
                    }
                    val request = try {
                      com.reader.app.capture.CaptureRepository(db).getOrCreate(url, titleHint, "paste")
                    } catch (e: IllegalArgumentException) {
                      val id = Ingest.importPasted(this@MainActivity, text)
              Toast.makeText(this@MainActivity, "That link didn’t open, so we kept the text.", Toast.LENGTH_LONG).show()
                      refresh()
                      go(Route.Reader(id))
                      return@launch
                    }
                    com.reader.app.capture.CaptureWorker.scheduleById(this@MainActivity, request.requestId)
                    Toast.makeText(this@MainActivity, "Link saved — fetching article", Toast.LENGTH_SHORT).show()
                    refresh()
                    observeCaptureForTruthfulToast(request.requestId)
                  }
                  else -> {
                    val id = Ingest.importPasted(this@MainActivity, text)
                    Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
                    refresh()
                    go(Route.Reader(id))
                  }
                }
              } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Paste failed: " + (e.message?.take(120) ?: "unknown"), Toast.LENGTH_LONG).show()
              }
            }
          },
          onPair = { go(Route.Pairing) },
          onSettings = { go(Route.Settings) },
          highlightCount = highlightSummaries.size,
          sort = settings.sort,
          onSort = { s -> lifecycleScope.launch { prefs.save(prefs.load().copy(sort = s)) } },
          age = settings.age,
          onAge = { a -> lifecycleScope.launch { prefs.save(prefs.load().copy(age = a)) } },
          labelCounts = labelCounts,
          selectedLabelNorm = selectedLabelNorm,
          onLabelSelect = { selectedLabelNorm = it },
          labelsByDoc = labelsByDoc,
          onToggleLabel = { ids, label -> toggleLabel(ids, label) },
          searchActive = searchActive,
          onToggleSearch = {
            searchActive = !searchActive
            if (!searchActive) searchText = ""
          },
          searchText = searchText,
          onSearchText = { searchText = it },
          onSubmitSearch = {
            if (searchText.isNotBlank()) {
              recentsStore.record(searchText)
              recentsTick++
            }
          },
          searchScope = searchScope,
          onSearchScope = { searchScopeName = it.name },
          searchResults = searchResults.takeIf { searchActive && searchText.isNotBlank() }?.let { results ->
            // Labels AND age AND search text AND list scope: one predicate.
            val norm = selectedLabelNorm
            val withLabels = if (norm == null) results.rows
            else results.rows.filter { labelsByDoc[it.documentId]?.contains(norm) == true }
            val withAge = withLabels.filter { Triage.ageMatches(it.createdAt, settings.age) }
            results.copy(rows = withAge)
          },
          searchRecents = remember(searchActive, recentsTick) { recentsStore.recents() },
          onRecentTap = { tapped -> searchText = tapped },
          onRecentRemove = { removed -> recentsStore.remove(removed); recentsTick++ },
          onRecentsClear = { recentsStore.clear(); recentsTick++ },
          onOpenResult = { id -> openSearchResult(id) },
          finishNotice = finishNotice,
          onFinishNoticeConsumed = { finishNotice = null },
          onFinishNoticeAction = { go(Route.Archive) },
          onArchiveCoachDone = {
            lifecycleScope.launch {
              if (!prefs.load().archiveCoachShown) prefs.save(prefs.load().copy(archiveCoachShown = true))
            }
          },
        )
        is Route.Review -> {
          val review = remember { com.reader.app.data.ReviewRepository(db) }
          var reviewState by remember { mutableStateOf<com.reader.app.core.ReviewState?>(null) }
          var quote by remember { mutableStateOf<com.reader.app.data.HighlightEntity?>(null) }
          var busy by remember { mutableStateOf(true) }
          var reviewError by remember { mutableStateOf<String?>(null) }
          suspend fun loadReview(restart: Boolean = false) {
            busy = true
            try {
              reviewState = review.resume(restart = restart)
              quote = reviewState?.currentId?.let { db.highlights().byId(it) }
              reviewError = null
            } catch (e: Exception) { reviewError = "Couldn’t load review: ${e.message?.take(100)}" }
            finally { busy = false }
          }
          LaunchedEffect(Unit) { loadReview() }
          val sourceDocument by remember(quote?.documentId) { db.documents().observeMetadata(quote?.documentId ?: "") }.collectAsState(initial = null)
          LaunchedEffect(quote?.id) {
            val current = quote?.id ?: return@LaunchedEffect
            if (reviewScrollId != current) { reviewScrollState.scrollTo(0); reviewScrollId = current }
          }
          ReviewScreen(reviewState, quote, busy, reviewError, sourceAvailable = sourceDocument != null,
            scrollState = reviewScrollState,
            onBack = { stack.pop(); tick++ },
            onNext = {
              val id = quote?.id
              if (!busy && id != null) {
                busy = true
                lifecycleScope.launch {
                  // Serialize with Important/Source: delayed Next must not
                  // overwrite a quote the user already moved away from.
                  if (!reviewMutex.tryLock()) { busy = false; return@launch }
                  try {
                    val issued = id
                    val next = review.advance(issued)
                    // Apply only if the user is still on the issued quote;
                    // Next intends to move, so a stale response is discarded.
                    if (quote?.id == issued) {
                      reviewState = next
                      quote = next?.currentId?.let { db.highlights().byId(it) }
                    }
                  } catch (e: Exception) { reviewError = "Couldn’t save review: ${e.message?.take(100)}" }
                  finally { reviewMutex.unlock(); busy = false }
                }
              }
            },
            onImportant = {
              val id = quote?.id
              if (!busy && id != null) lifecycleScope.launch {
                if (!reviewMutex.tryLock()) { reviewError = "Saving… please wait."; return@launch }
                try { completeReviewCommand(id, review::toggleImportant, { quote?.id }) { quote = it } }
                catch (error: Exception) { reviewError = "Couldn’t save importance. Try again." }
                finally { reviewMutex.unlock() }
              }
            },
            onSource = {
              quote?.let { selected -> lifecycleScope.launch {
                if (!reviewMutex.tryLock()) return@launch
                try {
                  // Re-check existence inside the serialized section (TOCTOU).
                  if (db.documents().exists(selected.documentId)) {
                    review.openedSource(selected.id)
                    go(Route.Reader(selected.documentId, selected.id))
                  } else Toast.makeText(this@MainActivity, "Source article was deleted. Your quote is still saved.", Toast.LENGTH_LONG).show()
                } finally { reviewMutex.unlock() }
              } }
            },
            onShare = {
              quote?.let { selected ->
                val source = selected.sourceUrl?.takeIf { it.isNotBlank() }?.let { " (${it.take(200)})" }.orEmpty()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_TEXT, "“${selected.quote}”\n— ${selected.sourceTitle}$source")
                }, "Share quote"))
              }
            },
            onRestart = { lifecycleScope.launch {
              if (!reviewMutex.tryLock()) return@launch
              try { loadReview(restart = true) } finally { reviewMutex.unlock() }
            } },
          )
        }
        is Route.Reader -> PreparedReaderScreen(
          id = r.id, highlightId = r.highlightId, settings = settings,
          at = r.at, atEnd = r.atEnd,
          docLabels = labelsByDoc[r.id]?.mapNotNull { norm ->
            labelCounts.firstOrNull { it.normalized == norm }?.name
          }.orEmpty(),
          labelSuggestions = labelCounts.map { it.name },
          onToggleLabel = { label -> toggleLabel(setOf(r.id), label) },
          onSettingsChange = { lifecycleScope.launch { prefs.save(it) } },
          onBack = {
            ttsController?.pause(); ttsState = null; ttsDocId = null
            stack.pop(); tick++
          },
          onListen = { projection, cursor -> openTts(r.id, projection, cursor) },
          onSpeedRead = { cursor -> ttsController?.pause(); go(Route.Rsvp(r.id, cursor)) },
          onArticleAction = { action ->
            if (action == ArticleAction.Delete) deleteArticle(r.id) else action.target?.let { moveReader(r.id, it) }
          },
          onPauseAudio = { ttsController?.pause() },
          speechPlaying = ttsDocId == r.id && ttsState?.playing == true,
          playerVisible = ttsDocId == r.id && ttsState != null,
          player = {
            if (ttsDocId == r.id) ttsState?.let { state ->
              TtsBar(state, com.reader.app.ui.theme.readerColors(settings.background),
                onPrev = { ttsController?.prev() },
                onToggle = { if (state.playing) ttsController?.pause() else ttsController?.play() },
                onNext = { ttsController?.next() },
                onSpeed = { speed ->
                  ttsController?.setSpeed(speed)
                  lifecycleScope.launch { prefs.save(prefs.load().copy(ttsSpeed = speed)) }
                },
                onClose = { ttsController?.pause(); ttsState = null; ttsDocId = null },
              )
            }
          },
        )
        is Route.Rsvp -> {
          var section by remember(r.id, r.from) { mutableStateOf<com.reader.app.data.PreparedSection?>(null) }
          var tokens by remember(r.id, r.from) { mutableStateOf<List<com.reader.app.rsvp.RsvpToken>?>(null) }
          var error by remember { mutableStateOf<String?>(null) }
          LaunchedEffect(r.id, r.from) {
            try {
              val repo = (application as com.reader.app.ReaderApp).articles
              val index = repo.index(r.id)
              val ready = repo.section(r.id, index.sectionFor(r.from.blockId))
              section = ready
              tokens = withContext(Dispatchers.Default) { RsvpModel.tokens(ready.projection) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn’t open speed reader" }
          }
          if (error != null || tokens == null) {
            androidx.activity.compose.BackHandler { stack.pop(); tick++ }
            val colors = com.reader.app.ui.theme.readerColors(settings.background)
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = colors.background, contentColor = colors.text) {
              androidx.compose.foundation.layout.Column(Modifier.padding(androidx.compose.ui.unit.Dp(24f))) {
                if (error != null) androidx.compose.material3.Text(error!!, color = colors.error)
                else androidx.compose.material3.CircularProgressIndicator(color = colors.text)
                androidx.compose.material3.TextButton(onClick = { stack.pop(); tick++ }) {
                  androidx.compose.material3.Text("Back to article", color = colors.text)
                }
              }
            }
          } else tokens?.let { ready ->
            val candidates = ready.indices.filter { ready[it].blockId == r.from.blockId }
            val start = candidates.lastOrNull { ready[it].start <= r.from.charOffset } ?: candidates.firstOrNull() ?: 0
            RsvpScreen(tokens = ready, settings = settings, startIndex = start,
              onWpm = { w -> lifecycleScope.launch { prefs.save(prefs.load().copy(rsvpWpm = w)) } },
              onExit = { cursor -> lifecycleScope.launch {
                val actual = cursor.copy(documentId = r.id)
                val prepared = section
                val writer = (application as com.reader.app.ReaderApp).progress
                writer.offer(actual, prepared?.fraction(prepared.projection.offset(actual.blockId, actual.charOffset)) ?: 0f)
                writer.flush(); stack.pop(); tick++
              } },
            )
          }
        }
        is Route.Pairing -> {
          LaunchedEffect(pairingChannelId) {
            val channelId = pairingChannelId ?: return@LaunchedEffect
            while (true) {
              delay(2000)
              val channel = withContext(Dispatchers.IO) { db.channels().byId(channelId) }
              when (channel?.state) {
                "active" -> {
                  pairingStatus = null
                  pairingError = null
                  Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
                  refresh()
                  pop()
                  break
                }
                "revoked" -> {
                  pairingStatus = null
                  pairingError = "Pairing expired or was cancelled. Create a new code in Chrome."
                  break
                }
                null -> {
                  // byId excludes revoked rows, so a missing pending channel is terminal.
                  pairingStatus = null
                  pairingError = "Pairing expired or was cancelled. Create a new code in Chrome."
                  pairingChannelId = null
                  break
                }
              }
            }
          }
          PairingScreen(
            settings = settings,
            error = pairingError,
            status = pairingStatus,
            onCancel = {
              val channelId = pairingChannelId
              if (channelId != null) lifecycleScope.launch { PairingCoordinator(this@MainActivity).cancel(channelId) }
              pairingChannelId = null
              pairingStatus = null
              pairingError = null
              pop()
            },
            onScanned = { qrText ->
              lifecycleScope.launch {
                try {
                  pairingError = null
                  pairingStatus = "Sending an encrypted response through the agreed relays…"
                  val result = completePairing(qrText)
                  pairingChannelId = result.channelId
                  if (result.connected) {
                    pairingStatus = null
                    Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
                    refresh()
                    pop()
                  } else {
                    pairingStatus = if (result.acceptedRelays == 0) {
                      "${result.retryReason ?: "No relay confirmed the encrypted response yet."} Reader will retry until this pairing code expires…"
                    } else {
                      "Reply accepted by ${result.acceptedRelays} relay${if (result.acceptedRelays == 1) "" else "s"}. Waiting for Chrome's authenticated confirmation…"
                    }
                  }
                } catch (e: Exception) {
                  pairingStatus = null
                  pairingError = e.message?.take(200) ?: "Pairing failed"
                }
              }
            },
          )
        }
        is Route.Settings -> SettingsScreen(
          settings = settings, channels = channels,
          libraryStats = libraryStats,
          syncHealth = syncHealth, syncing = syncing, onSync = {
            if (!syncing) { syncing = true; lifecycleScope.launch {
              try { withContext(Dispatchers.IO) { ReaderSyncSession(applicationContext).runOnce() } }
              finally { syncing = false }
            } }
          },
          onSettingsChange = { lifecycleScope.launch { prefs.save(it) } },
          signerLabel = "Private device key (Recommended). " + AmberSigner(this).status(),
          relaySummary = (channels.firstOrNull()?.relaysJson
            ?: "${READER_DEFAULT_RELAYS.size} default public relays (${READER_RELAY_WRITE_QUORUM} required per payload).") +
            "\n\nAdd up to 2 custom relays in Chrome Settings, then re-pair so both devices authenticate the same relay set.",
          onBack = { pop(); lifecycleScope.launch { refresh() } },
          onRevokeChannel = { id ->
            lifecycleScope.launch {
              withContext(Dispatchers.IO) { db.channels().revoke(id, System.currentTimeMillis()) }
              keys.deleteChannelKey(id)
              refresh()
            }
          },
          onExport = { exportDestination.launch("reader-archive.zip") },
          onSignerInfo = {
            Toast.makeText(this, "Random local keys by default. External signers only add provenance, never transport.", Toast.LENGTH_LONG).show()
          },
        )
      }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIncomingIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    SyncWorker.runNow(this)
    refreshTick.value++
  }


  private fun handleIncomingIntent(intent: Intent) {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_PROCESS_TEXT) return
    val sourceType = if (intent.action == Intent.ACTION_PROCESS_TEXT) "android-process-text" else "android-share"
    lifecycleScope.launch {
      try {
        val (text, subject) = Ingest.handleIntentText(intent) ?: return@launch
        if (text.startsWith("__HTML__")) {
          val id = Ingest.importHtml(this@MainActivity, text.removePrefix("__HTML__"), "android-share")
          Toast.makeText(this@MainActivity, "Saved. Find it in Inbox.", Toast.LENGTH_SHORT).show()
          refreshTick.value++
          return@launch
        }
        when (val kind = com.reader.app.capture.ShareIntentClassifier.classify(text, subject, Ingest::looksLikeMarkdown)) {
          is com.reader.app.capture.ShareClassification.SingleUrl,
          is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> {
            val url = when (kind) {
              is com.reader.app.capture.ShareClassification.SingleUrl -> kind.url
              is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> kind.url
              else -> text.trim()
            }
            val titleHint = when (kind) {
              is com.reader.app.capture.ShareClassification.SingleUrl -> kind.titleHint
              is com.reader.app.capture.ShareClassification.SubjectPlusUrl -> kind.titleHint
              else -> subject
            }
            // Durable commit before network and before saying saved.
            val request = try {
              com.reader.app.capture.CaptureRepository(com.reader.app.data.ReaderDb.get(this@MainActivity))
                .getOrCreate(url, titleHint, sourceType)
            } catch (e: IllegalArgumentException) {
              // Structurally invalid URL: fall back to existing text import,
              // and say so — the link was kept as text, not fetched.
              val id = Ingest.importSharedText(this@MainActivity, text, sourceType, subject)
              Toast.makeText(this@MainActivity, "Link not valid — saved as text instead", Toast.LENGTH_LONG).show()
              refreshTick.value++
              return@launch
            }
            com.reader.app.capture.CaptureWorker.scheduleById(this@MainActivity, request.requestId)
            Toast.makeText(this@MainActivity, "Link saved — fetching article", Toast.LENGTH_SHORT).show()
            refreshTick.value++
            observeCaptureForTruthfulToast(request.requestId)
          }
          else -> {
            val id = Ingest.importSharedText(this@MainActivity, text, sourceType, subject)
            Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
            refreshTick.value++
          }
        }
      } catch (e: Exception) {
        Toast.makeText(this@MainActivity, "Import failed", Toast.LENGTH_LONG).show()
      }
    }
  }

  /** Foreground-only truthful completion toast. Never interrupts reading beyond a toast. */
  private fun observeCaptureForTruthfulToast(requestId: String) {
    lifecycleScope.launch {
      try {
        val db = com.reader.app.data.ReaderDb.get(this@MainActivity)
        // Poll briefly while foreground; background completions surface as new library rows.
        repeat(60) {
          kotlinx.coroutines.delay(2000)
          val row = withContext(Dispatchers.IO) { db.captureRequests().byId(requestId) } ?: return@launch
          when (row.state) {
            "completed" -> {
              Toast.makeText(this@MainActivity, "Ready to read.", Toast.LENGTH_SHORT).show()
              refreshTick.value++
              return@launch
            }
            "link_only" -> {
              val detail = row.errorMessage?.take(120) ?: "article text unavailable"
              Toast.makeText(this@MainActivity, "Link saved — $detail", Toast.LENGTH_LONG).show()
              refreshTick.value++
              return@launch
            }
            "failed" -> {
              val detail = row.errorMessage?.take(120) ?: row.errorCode ?: "fetch failed"
              Toast.makeText(this@MainActivity, "Link saved — $detail", Toast.LENGTH_LONG).show()
              refreshTick.value++
              return@launch
            }
            "cancelled" -> return@launch
            else -> { /* keep waiting while pending/fetching */ }
          }
        }
      } catch (_: Exception) { /* best-effort toast only */ }
    }
  }

  private suspend fun completePairing(qrText: String): PairingCoordinator.BeginResult =
    PairingCoordinator(this).begin(qrText)

  /** Manual archive export: ZIP of md + metadata JSON. Never keys. Single-flight. */
  private suspend fun exportArchive(destination: Uri) {
    if (!exportMutex.tryLock()) {
      Toast.makeText(this, "Export already running. Please wait.", Toast.LENGTH_SHORT).show()
      return
    }
    var temporary: java.io.File? = null
    try {
      val app = application as com.reader.app.ReaderApp
      try { app.reading.flush() } finally { app.progress.flush() }
      val startedAt = System.currentTimeMillis()
      val archive = com.reader.app.data.ArchiveExporter(db).prepare(cacheDir)
      temporary = archive
      android.util.Log.i("NostrReaderExport", "prepared bytes=${archive.length()} millis=${System.currentTimeMillis() - startedAt}")
      withContext(Dispatchers.IO) {
        checkNotNull(contentResolver.openOutputStream(destination, "wt")) { "Destination is unavailable" }.use { output ->
          archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              kotlin.coroutines.coroutineContext.ensureActive()
              val n = input.read(buffer); if (n < 0) break
              output.write(buffer, 0, n)
            }
          }
        }
        // Read back the selected destination, including providers outside Reader.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun hash(input: java.io.InputStream): ByteArray = input.use {
          digest.reset(); val buffer = ByteArray(64 * 1024)
          while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
          digest.digest()
        }
        check(hash(archive.inputStream()).contentEquals(hash(checkNotNull(contentResolver.openInputStream(destination))))) { "Destination verification failed" }
      }
      Toast.makeText(this, "Export checked and complete. It holds plain text — keep it somewhere safe.", Toast.LENGTH_LONG).show()
    } catch (error: Exception) {
      if (error is kotlinx.coroutines.CancellationException) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
          runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, destination) }
          runCatching { temporary?.delete() }
        }
        throw error
      }
      val removed = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, destination) }.getOrDefault(false)
      }
      // MediaStore/Downloads providers reject DocumentsContract.deleteDocument;
      // the warning tells the user to remove the partial file manually.
      Toast.makeText(this, if (removed) "Export did not complete; destination removed." else "Export did not complete. Delete the incomplete destination file.", Toast.LENGTH_LONG).show()
    } finally {
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { temporary?.delete() } }
      exportMutex.unlock()
    }
  }

  override fun onStop() {
    ttsController?.pause()
    val app = application as com.reader.app.ReaderApp
    app.persistenceScope.launch {
      try {
        try { app.reading.flush() } finally { app.progress.flush() }
      } catch (_: kotlinx.coroutines.CancellationException) { /* scope cancelled */ }
      catch (_: Exception) { /* failure.value surfaces Retry on next launch */ }
    }
    super.onStop()
  }

  override fun onDestroy() {
    try {
      ttsEngine?.shutdown()
    } catch (e: Exception) {
    }
    super.onDestroy()
  }
}

@Composable
private fun ReaderWithTts(
  docId: String,
  blocks: List<ArticleBlock>,
  settings: ReaderSettings,
  docState: com.reader.app.data.DocumentEntity,
  ttsState: TtsController.State?,
  onSettingsChange: (ReaderSettings) -> Unit,
  onBack: () -> Unit,
  onCursor: (SemanticCursor, Float) -> Unit,
  onEnterTts: (SemanticCursor) -> Unit,
  onEnterRsvp: (SemanticCursor) -> Unit,
  onReadLater: () -> Unit,
  onArchive: () -> Unit,
  onTtsPrev: () -> Unit,
  onTtsToggle: () -> Unit,
  onTtsNext: () -> Unit,
  onTtsSpeed: (Float) -> Unit,
  onTtsClose: () -> Unit,
) {
  Scaffold(
    containerColor = colorsFor(settings.background).background,
    bottomBar = {
      if (ttsState != null) {
        TtsBar(
          state = ttsState, colors = colorsFor(settings.background),
          onPrev = onTtsPrev, onToggle = onTtsToggle, onNext = onTtsNext,
          onSpeed = onTtsSpeed, onClose = onTtsClose,
        )
      }
    },
  ) { pad ->
    Box(Modifier.padding(pad).fillMaxSize()) {
      ReaderScreen(
        doc = docState, blocks = blocks, settings = settings,
        onSettingsChange = onSettingsChange, onBack = onBack, onCursor = onCursor,
        initialBlockId = docState.progressBlockId, initialOffset = docState.progressCharOffset,
        onEnterTts = onEnterTts, onEnterRsvp = onEnterRsvp,
        onReadLater = onReadLater, onArchive = onArchive,
      )
    }
  }
}

/** First-run sample: teaches triage, highlights and offline in 3 minutes. */
private const val WELCOME_MARKDOWN = """# Welcome to Reader

This is your quiet shelf. Everything you save lives on this phone, readable offline, with no account and no cloud.

- **Inbox** collects everything. Swipe right to prioritize, left to save for later.
- **Listen** reads aloud; **Speed** flies through at your pace.
- Turn on **Highlight**, drag the handles, and keep what matters. Star passages to meet them again in Review.

Try it now: highlight the sentence above, then find it under Highlights.

To save the web: share any page to Reader from your browser, or paste a link with the + button.
"""
