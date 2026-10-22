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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.ReaderDb
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
import kotlinx.coroutines.flow.first
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
  private val pendingShare = androidx.compose.runtime.mutableStateOf<Ingest.SharedContent?>(null)
  private val shareTimes = ArrayDeque<Long>()

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
    if (savedInstanceState == null) handleIncomingIntent(intent)
    window.setBackgroundDrawableResource(android.R.color.black)
    setContent {
      val loadedSettings by remember { prefs.flow }.collectAsState(initial = null)
      val settings = loadedSettings ?: return@setContent
      com.reader.app.ui.theme.ReaderTheme(settings.themeMode) {
      var savedRoutes by rememberSaveable { mutableStateOf("[]") }
      val stack = remember { RouteStack(runCatching { Json.decodeFromString<List<Route>>(savedRoutes) }.getOrDefault(emptyList())) }
      var tick by remember { mutableIntStateOf(0) }
      fun go(r: Route) { stack.push(r); tick++ }
      fun backToInbox() { stack.reset(); tick++ }
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
      val highlightSeed = rememberSaveable { java.security.SecureRandom().nextLong() }
      var highlightsNewest by rememberSaveable { mutableStateOf(false) }
      val highlightSummaries by remember { db.highlights().observeSummaries() }.collectAsState(initial = emptyList())
      var channels by remember { mutableStateOf(listOf<com.reader.app.data.ChannelEntity>()) }
      var minutesByList by remember { mutableStateOf(mapOf<String, Int>()) }
      var pairingError by remember { mutableStateOf<String?>(null) }
      var pairingStatus by remember { mutableStateOf<String?>(null) }
      var pairingChannelId by remember { mutableStateOf<String?>(null) }
      var ttsState by remember { mutableStateOf<TtsController.State?>(null) }
      var readerMove by remember { mutableStateOf<Triple<String, String, String>?>(null) }
      var readerMoving by remember { mutableStateOf(false) }
      var deletedUndo by remember { mutableStateOf<String?>(null) }
      var ttsDocId by remember { mutableStateOf<String?>(null) }
      // Room invalidation keeps a visible inbox truthful when a background
      // relay sync commits a document after onResume's initial refresh.
      val syncHealth by remember { db.syncHealth().observe() }.collectAsState(initial = null)
      var syncing by remember { mutableStateOf(false) }


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

      fun launchUiAction(message: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
          try { block() }
          catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
        }
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
      }
      LaunchedEffect(refreshTick.value, route) {
        if (route == Route.Inbox || route == Route.Archive) {
          db.documents().observeSummaries().collect { summaries ->
            lists = summaries.groupBy { it.list }
            minutesByList = lists.mapValues { (_, values) -> ((values.sumOf { it.wordCount.toLong() } + 224) / 225).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            libraryLoaded = true
          }
        } else if (route == Route.Settings || route == Route.Pairing) {
          channels = withContext(Dispatchers.IO) { db.channels().active() }
        }
      }

      var exporting by remember { mutableStateOf(false) }
      val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        lifecycleScope.launch {
          exporting = true
          try {
            exportArchive(uri)
            Toast.makeText(this@MainActivity, "Archive exported. The ZIP contains plaintext article text.", Toast.LENGTH_LONG).show()
          } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Export failed: ${e.message?.take(120)}", Toast.LENGTH_LONG).show()
          } finally {
            exporting = false
          }
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
        launchUiAction("Couldn’t start listening. Try again.") {
          val audioRepo = (application as com.reader.app.ReaderApp).articles
          val audioIndex = audioRepo.index(docId)
          val audioSection = audioRepo.section(docId, audioIndex.sectionFor(from.blockId))
          val units = withContext(Dispatchers.Default) { Narration.sentences(projection) }
          val eng = ttsEngine ?: AndroidTtsEngine(this@MainActivity).also { ttsEngine = it }
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
            moveToListDb(id, target)
            readerMove = Triple(id, previous, target)
            ttsController?.pause(); ttsState = null; ttsDocId = null
            refresh(); stack.pop(); tick++
          } catch (_: Exception) {
            Toast.makeText(this@MainActivity, "Couldn’t move article. Try again.", Toast.LENGTH_LONG).show()
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
            deletedUndo = id
            if ((stack.current() as? Route.Reader)?.id == id) { stack.pop(); tick++ }
          } catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t delete article. Try again.", Toast.LENGTH_LONG).show() }
          finally { deleting.remove(id) }
        }
      }
      fun unarchive(id: String) {
        lifecycleScope.launch {
          try { moveToListDb(id, Triage.INBOX); readerMove = Triple(id, Triage.ARCHIVED, Triage.INBOX) }
          catch (_: Exception) { Toast.makeText(this@MainActivity, "Couldn’t unarchive article. Try again.", Toast.LENGTH_LONG).show() }
        }
      }

      when (val r = route) {
        is Route.Inbox, is Route.Archive -> InboxScreen(
          archiveMode = r == Route.Archive,
          onArchiveOpen = { go(Route.Archive) }, onArchiveBack = { stack.pop(); tick++ },
          listState = if (r == Route.Archive) archiveScrollState else mainScrollStates[selectedTab] ?: mainScrollStates.getValue(Triage.PRIORITY),
          lists = lists, minutes = minutesByList, settings = settings,
          readerMove = readerMove, onReaderMoveConsumed = { readerMove = null },
          loaded = libraryLoaded, selectedTab = selectedTab, onSelectTab = { selectedTab = it },
          highlights = { HighlightsFeed(highlightSummaries, highlightSeed, highlightsNewest, { highlightsNewest = it }) { chosen -> lifecycleScope.launch {
            try { com.reader.app.data.ReviewRepository(db).resume(chosen); go(Route.Review) }
            catch (e: Exception) { Toast.makeText(this@MainActivity, "Couldn’t open review: ${e.message?.take(100)}", Toast.LENGTH_LONG).show() }
          } } },
          onOpen = { go(Route.Reader(it)) },
          onMove = { id, target -> launchUiAction("Couldn’t move article. Try again.") { moveToListDb(id, target); refresh() } },
          onUndoMove = { id, previous -> launchUiAction("Couldn’t undo the move. Try again.") { moveToListDb(id, previous); refresh() } },
          onUnarchive = ::unarchive,
          onDelete = ::deleteArticle,
          deletedUndo = deletedUndo,
          onDeletedUndoConsumed = { deletedUndo = null },
          onRestoreDelete = { id ->
            lifecycleScope.launch {
              try {
                (application as com.reader.app.ReaderApp).restoreArticle(id)
                Toast.makeText(this@MainActivity, "Article restored", Toast.LENGTH_SHORT).show()
                refresh()
              } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Couldn’t restore article. It may have expired.", Toast.LENGTH_LONG).show()
              }
            }
          },
          onImportFile = { filePicker.launch(arrayOf("text/plain", "text/markdown", "*/*")) },
          onPasteText = { text ->
            lifecycleScope.launch {
              try {
                val id = Ingest.importPasted(this@MainActivity, text)
                Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
                refresh()
                go(Route.Reader(id))
              } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Paste failed: " + (e.message?.take(120) ?: "unknown"), Toast.LENGTH_LONG).show()
              }
            }
          },
          onPair = { go(Route.Pairing) },
          onSettings = { go(Route.Settings) },
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
          ReviewScreen(reviewState, quote, busy, reviewError, sourceAvailable = sourceDocument != null,
            onBack = { stack.pop(); tick++ },
            onNext = {
              val id = quote?.id
              if (!busy && id != null) {
                busy = true
                lifecycleScope.launch {
                  try {
                    reviewState = review.advance(id)
                    quote = reviewState?.currentId?.let { db.highlights().byId(it) }
                  } catch (e: Exception) { reviewError = "Couldn’t save review: ${e.message?.take(100)}" }
                  finally { busy = false }
                }
              }
            },
            onImportant = {
              val id = quote?.id
              if (!busy && id != null) launchUiAction("Couldn’t update importance. Try again.") { quote = review.toggleImportant(id) }
            },
            onSource = {
              quote?.let { selected -> launchUiAction("Couldn’t open the source article. Try again.") {
                if (db.documents().exists(selected.documentId)) {
                  review.openedSource(selected.id)
                  go(Route.Reader(selected.documentId, selected.id))
                } else Toast.makeText(this@MainActivity, "Source article was deleted. Your quote is still saved.", Toast.LENGTH_LONG).show()
              } }
            },
            onShare = {
              quote?.let { selected ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_TEXT, selected.quote)
                }, "Share quote"))
              }
            },
            onRestart = { lifecycleScope.launch { loadReview(restart = true) } },
          )
        }
        is Route.Reader -> PreparedReaderScreen(
          id = r.id, highlightId = r.highlightId, settings = settings,
          onSettingsChange = { launchUiAction("Couldn’t save settings. Try again.") { prefs.save(it) } },
          onBack = {
            ttsController?.pause(); ttsState = null; ttsDocId = null
            stack.pop(); tick++
          },
          onListen = { projection, cursor -> openTts(r.id, projection, cursor) },
          onSpeedRead = { cursor -> go(Route.Rsvp(r.id, cursor)) },
          onArticleAction = { action ->
            if (action == ArticleAction.Delete) deleteArticle(r.id) else action.target?.let { moveReader(r.id, it) }
          },
          playerVisible = ttsDocId == r.id && ttsState != null,
          player = {
            if (ttsDocId == r.id) ttsState?.let { state ->
              TtsBar(state, com.reader.app.ui.theme.readerColors(settings.background),
                onPrev = { ttsController?.prev() },
                onToggle = { if (state.playing) ttsController?.pause() else ttsController?.play() },
                onNext = { ttsController?.next() },
                onSpeed = { speed ->
                  ttsController?.setSpeed(speed)
                  launchUiAction("Couldn’t save speech speed.") { prefs.save(prefs.load().copy(ttsSpeed = speed)) }
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
              onWpm = { w -> launchUiAction("Couldn’t save reading speed.") { prefs.save(prefs.load().copy(rsvpWpm = w)) } },
              onExit = { cursor -> launchUiAction("Couldn’t save your place. Try again.") {
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
                  backToInbox()
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
              if (channelId != null) launchUiAction("Couldn’t cancel pairing. Try again.") { PairingCoordinator(this@MainActivity).cancel(channelId) }
              pairingChannelId = null
              pairingStatus = null
              pairingError = null
              backToInbox()
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
                    backToInbox()
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
          syncHealth = syncHealth, syncing = syncing, onSync = {
            if (!syncing) {
              syncing = true
              launchUiAction("Sync could not finish. Reader will retry.") {
                try { withContext(Dispatchers.IO) { ReaderSyncSession(applicationContext).runOnce() } }
                finally { syncing = false }
              }
            }
          },
          onSettingsChange = { launchUiAction("Couldn’t save settings. Try again.") { prefs.save(it) } },
          signerLabel = "Private device key (Recommended). " + AmberSigner(this).status(),
          relaySummary = (channels.firstOrNull()?.relaysJson
            ?: "${READER_DEFAULT_RELAYS.size} default public relays (${READER_RELAY_WRITE_QUORUM} required per payload).") +
            "\n\nAdd up to 2 custom relays in Chrome Settings, then re-pair so both devices authenticate the same relay set.",
          onBack = { backToInbox(); launchUiAction("Couldn’t refresh library.") { refresh() } },
          onRevokeChannel = { id ->
            launchUiAction("Couldn’t revoke the device. Try again.") {
              try {
                val channel = withContext(Dispatchers.IO) { db.channels().byId(id) }
                val channelKey = runCatching { keys.openChannelKey(id) }.getOrNull()
                if (channel != null && channelKey != null) {
                  withContext(Dispatchers.IO) {
                    com.reader.app.sync.DisconnectDispatcher((application as com.reader.app.ReaderApp).relayClient)
                      .publish(channel, channelKey)
                  }
                }
              } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Couldn’t notify Chrome; revoking locally.", Toast.LENGTH_LONG).show()
              }
              withContext(Dispatchers.IO) { db.channels().revoke(id, System.currentTimeMillis()) }
              keys.deleteChannelKey(id)
              refresh()
              Toast.makeText(
                this@MainActivity,
                "Revoked on this phone. If Chrome was offline, also choose Disconnect in Chrome.",
                Toast.LENGTH_LONG,
              ).show()
            }
          },
          exporting = exporting,
          onExport = { if (!exporting) exportPicker.launch(com.reader.app.data.ArchiveExport.filename(System.currentTimeMillis())) },
          onSignerInfo = {
            Toast.makeText(this, "Random local keys by default. External signers only add provenance, never transport.", Toast.LENGTH_LONG).show()
          },
        )
      }
      pendingShare.value?.let { shared ->
        AlertDialog(
          onDismissRequest = { pendingShare.value = null },
          containerColor = com.reader.app.ui.theme.appColors().background,
          title = { Text("Add shared content?") },
          text = {
            Column {
              Text("Another app sent this to Reader. It will be stored locally until you delete it.")
              Spacer(Modifier.height(8.dp))
              Text(
                shared.text.replace(Regex("\\s+"), " ").take(280) + if (shared.text.length > 280) "…" else "",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          },
          confirmButton = {
            TextButton(onClick = { pendingShare.value = null; importShared(shared, "android-share") }) {
              Text("Add to Reader")
            }
          },
          dismissButton = {
            TextButton(onClick = { pendingShare.value = null }) { Text("Cancel") }
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

  private suspend fun moveToListDb(id: String, target: String) {
    withContext(Dispatchers.IO) { db.documents().setList(id, target, System.currentTimeMillis()) }
  }

  private fun handleIncomingIntent(intent: Intent) {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_PROCESS_TEXT) return
    val shared = Ingest.handleIntentText(intent) ?: return
    if (intent.action == Intent.ACTION_PROCESS_TEXT) {
      // The user explicitly chose Reader from the selection menu; keep it quick.
      importShared(shared, "android-process-text")
      return
    }
    // An exported share target can be launched by any app. Bound repeated
    // imports and require one explicit confirmation before touching the library.
    val now = android.os.SystemClock.elapsedRealtime()
    while (shareTimes.isNotEmpty() && now - shareTimes.first() > 60_000) shareTimes.removeFirst()
    if (shareTimes.size >= 5) {
      Toast.makeText(this, "Too many shared items. Try again in a minute.", Toast.LENGTH_LONG).show()
      return
    }
    shareTimes.addLast(now)
    pendingShare.value = shared
  }

  private fun importShared(shared: Ingest.SharedContent, sourceType: String) {
    lifecycleScope.launch {
      try {
        val id = if (shared.isHtml) {
          Ingest.importHtml(this@MainActivity, shared.text, sourceType)
        } else {
          Ingest.importPlainText(this@MainActivity, shared.text, sourceType, shared.subject)
        }
        Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
        refreshTick.value++
      } catch (e: Exception) {
        Toast.makeText(this@MainActivity, "Import failed: ${e.message?.take(120) ?: "unknown"}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private suspend fun completePairing(qrText: String): PairingCoordinator.BeginResult =
    PairingCoordinator(this).begin(qrText)

  /** Manual archive export to a user-chosen SAF location: ZIP of md + metadata JSON. Never keys. */
  private suspend fun exportArchive(uri: Uri) {
    val list = withContext(Dispatchers.IO) { db.documents().observeSummaries().first() }
    val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open the selected file")
    withContext(Dispatchers.IO) {
      out.use { stream ->
        ZipOutputStream(stream).use { zip ->
          for (summary in list) {
            val d = db.documents().observeMetadata(summary.documentId).first() ?: continue
            zip.putNextEntry(ZipEntry("${d.documentId}.md"))
            for (part in 0 until db.documents().contentPartCount(d.documentId)) {
              val text = checkNotNull(db.documents().contentPart(d.documentId, part)) { "Article content is incomplete" }
              zip.write(text.toByteArray(Charsets.UTF_8))
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${d.documentId}.json"))
            val meta = buildJsonObject {
              put("documentId", d.documentId); put("title", d.title)
              put("sourceType", d.sourceType); put("wordCount", d.wordCount)
              put("capturedAt", d.capturedAt); put("state", d.state); put("list", d.list)
            }.toString()
            zip.write(meta.toByteArray())
            zip.closeEntry()
          }
          zip.putNextEntry(ZipEntry("highlights.jsonl"))
          var offset = 0
          while (true) {
            val page = db.highlights().exportPage(20, offset)
            if (page.isEmpty()) break
            for (quote in page) {
              zip.write(Json.encodeToString(com.reader.app.data.HighlightEntity.serializer(), quote).toByteArray(Charsets.UTF_8))
              zip.write(10)
            }
            offset += page.size
          }
          zip.closeEntry()
        }
      }
    }
  }

  override fun onStop() {
    ttsController?.pause()
    // Never block the main thread on rotation/backgrounding; the app scope owns
    // the write and retries coalesced progress on the next checkpoint.
    (application as com.reader.app.ReaderApp).flushProgressAsync()
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
