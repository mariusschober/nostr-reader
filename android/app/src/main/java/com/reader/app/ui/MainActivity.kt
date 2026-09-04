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
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.Secp256k1
import com.reader.app.prefs.Prefs
import com.reader.app.prefs.ArticleBackground
import com.reader.app.prefs.ReaderSettings
import com.reader.app.rsvp.RsvpModel
import com.reader.app.security.KeystoreWrap
import com.reader.app.signer.AmberSigner
import com.reader.app.sync.Ingest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
  private lateinit var db: ReaderDb
  private lateinit var prefs: Prefs
  private lateinit var keys: KeystoreWrap
  private var ttsEngine: AndroidTtsEngine? = null
  private var ttsController: TtsController? = null
  private var refreshTick = androidx.compose.runtime.mutableStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    db = ReaderDb.get(this)
    prefs = Prefs(this)
    keys = KeystoreWrap(this)
    SyncWorker.schedule(this)
    handleIncomingIntent(intent)
    setContent {
      val stack = remember { RouteStack() }
      var tick by remember { mutableIntStateOf(0) }
      fun go(r: Route) { stack.push(r); tick++ }
      fun backToInbox() { stack.reset(); tick++ }
      val route = stack.current()
      var settings by remember { mutableStateOf(ReaderSettings()) }
      var lists by remember { mutableStateOf(mapOf<String, List<com.reader.app.data.DocumentEntity>>()) }
      var channels by remember { mutableStateOf(listOf<com.reader.app.data.ChannelEntity>()) }
      var minutesByList by remember { mutableStateOf(mapOf<String, Int>()) }
      var pairingError by remember { mutableStateOf<String?>(null) }
      var ttsState by remember { mutableStateOf<TtsController.State?>(null) }
      var ttsDocId by remember { mutableStateOf<String?>(null) }

      SideEffect {
        val bg = colorsFor(settings.background).background
        window.statusBarColor = bg.toArgb()
        window.navigationBarColor = bg.toArgb()
        WindowCompat.getInsetsController(window, window.decorView)
          .isAppearanceLightStatusBars =
          settings.background == ArticleBackground.PAPER ||
            settings.background == ArticleBackground.SOFT
      }

      suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) {
          Triage.TABS.associateWith { db.documents().byList(it) }
        }
        lists = loaded
        channels = withContext(Dispatchers.IO) { db.channels().active() }
        val mins = withContext(Dispatchers.IO) {
          Triage.TABS.associateWith { l ->
            maxOf(0, ((db.documents().wordsInList(l) + 224) / 225).toInt())
          }
        }
        minutesByList = mins
        settings = prefs.load()
      }
      LaunchedEffect(refreshTick.value) { refresh() }

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

      fun openTts(docId: String, from: SemanticCursor) {
        lifecycleScope.launch {
          val doc = withContext(Dispatchers.IO) { db.documents().byId(docId) } ?: return@launch
          val blocks = withContext(Dispatchers.Default) { ArticleParser.parse(doc.canonicalMarkdown) }
          val units = Narration.sentences(blocks)
          val eng = (ttsEngine ?: AndroidTtsEngine(this@MainActivity).also { ttsEngine = it })
          val ctl = (ttsController ?: TtsController(eng).also { ttsController = it })
          ctl.onCursor = { blockId ->
            lifecycleScope.launch {
              val d = withContext(Dispatchers.IO) { db.documents().byId(docId) } ?: return@launch
              withContext(Dispatchers.IO) {
                db.documents().update(d.copy(progressBlockId = blockId, state = "reading", lastOpenedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
              }
              refresh()
            }
          }
          ctl.onState = { ttsState = it }
          val s = prefs.load()
          ctl.load(units, from.blockId, s.ttsSpeed)
          ttsDocId = docId
          ctl.play()
          go(Route.Reader(docId))
        }
      }

      when (val r = route) {
        is Route.Inbox -> InboxScreen(
          lists = lists, minutes = minutesByList, settings = settings,
          onOpen = { go(Route.Reader(it)) },
          onMove = { id, target -> lifecycleScope.launch { moveToListDb(id, target); refresh() } },
          onUndoMove = { id, previous -> lifecycleScope.launch { moveToListDb(id, previous); refresh() } },
          onUnarchive = { id -> lifecycleScope.launch { moveToListDb(id, Triage.INBOX); refresh() } },
          onDelete = { id ->
            lifecycleScope.launch {
              withContext(Dispatchers.IO) { db.documents().deleteById(id) }
              refresh()
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
        is Route.Reader -> {
          val doc = Triage.TABS.firstNotNullOfOrNull { lists[it]?.find { d -> d.documentId == r.id } }
          if (doc == null) {
            LaunchedEffect(Unit) { backToInbox() }
          } else {
            val blocks = remember(doc.documentId, doc.canonicalMarkdown) { ArticleParser.parse(doc.canonicalMarkdown) }
            ReaderWithTts(
              docId = doc.documentId, blocks = blocks, settings = settings,
              docState = doc,
              ttsState = if (ttsDocId == doc.documentId) ttsState else null,
              onSettingsChange = { lifecycleScope.launch { prefs.save(it); settings = it } },
              onBack = {
                ttsController?.pause()
                ttsState = null
                ttsDocId = null
                backToInbox()
                lifecycleScope.launch { refresh() }
              },
              onCursor = { cursor, frac ->
                lifecycleScope.launch {
                  withContext(Dispatchers.IO) {
                    db.documents().byId(doc.documentId)?.let {
                      db.documents().update(it.copy(progressBlockId = cursor.blockId, progressCharOffset = cursor.charOffset, progressFraction = frac, state = "reading", lastOpenedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
                    }
                  }
                }
              },
              onEnterTts = { cursor -> openTts(doc.documentId, cursor) },
              onEnterRsvp = { cursor -> go(Route.Rsvp(doc.documentId, cursor)) },
              onReadLater = {
                lifecycleScope.launch {
                  moveToListDb(doc.documentId, Triage.LATER)
                  refresh()
                  Toast.makeText(this@MainActivity, "Saved for later", Toast.LENGTH_SHORT).show()
                  backToInbox()
                }
              },
              onArchive = { lifecycleScope.launch { moveToListDb(doc.documentId, Triage.ARCHIVED); refresh(); backToInbox() } },
              onTtsPrev = { ttsController?.prev() },
              onTtsToggle = {
                val st = ttsState
                if (st?.playing == true) ttsController?.pause() else ttsController?.play()
              },
              onTtsNext = { ttsController?.next() },
              onTtsSpeed = { sp -> lifecycleScope.launch { prefs.save(prefs.load().copy(ttsSpeed = sp)); ttsController?.let { ttsState = it.state } } },
              onTtsClose = { ttsController?.pause(); ttsState = null; ttsDocId = null },
            )
          }
        }
        is Route.Rsvp -> {
          val doc = Triage.TABS.firstNotNullOfOrNull { lists[it]?.find { d -> d.documentId == r.id } }
          if (doc == null) {
            LaunchedEffect(Unit) { backToInbox() }
          } else {
            val blocks = remember(doc.documentId) { ArticleParser.parse(doc.canonicalMarkdown) }
            val tokens = remember(doc.documentId) { RsvpModel.tokens(blocks) }
            val startIdx = tokens.indexOfFirst { it.blockId == r.from.blockId }.takeIf { it >= 0 } ?: 0
            RsvpScreen(
              tokens = tokens, settings = settings, startIndex = startIdx,
              onWpm = { w -> lifecycleScope.launch { prefs.save(prefs.load().copy(rsvpWpm = w)) } },
              onExit = { cursor ->
                lifecycleScope.launch {
                  withContext(Dispatchers.IO) {
                    db.documents().byId(doc.documentId)?.let {
                      db.documents().update(it.copy(progressBlockId = cursor.blockId, progressCharOffset = cursor.charOffset, state = "reading", updatedAt = System.currentTimeMillis()))
                    }
                  }
                  go(Route.Reader(doc.documentId))
                }
              },
            )
          }
        }
        is Route.Pairing -> PairingScreen(
          settings = settings,
          error = pairingError,
          onCancel = { backToInbox() },
          onScanned = { qrText ->
            lifecycleScope.launch {
              try {
                completePairing(qrText)
                pairingError = null
                Toast.makeText(this@MainActivity, "Connected", Toast.LENGTH_SHORT).show()
                refresh()
                backToInbox()
              } catch (e: Exception) {
                pairingError = e.message?.take(200) ?: "Pairing failed"
              }
            }
          },
        )
        is Route.Settings -> SettingsScreen(
          settings = settings, channels = channels,
          signerLabel = "Private device key (Recommended). " + AmberSigner(this).status(),
          relaySummary = channels.firstOrNull()?.relaysJson ?: "Default public relays (2-of-3 quorum).",
          onBack = { backToInbox(); lifecycleScope.launch { refresh() } },
          onRevokeChannel = { id ->
            lifecycleScope.launch {
              withContext(Dispatchers.IO) { db.channels().revoke(id, System.currentTimeMillis()) }
              keys.deleteChannelKey(id)
              refresh()
            }
          },
          onExport = { lifecycleScope.launch { exportArchive() } },
          onSignerInfo = {
            Toast.makeText(this, "Random local keys by default. External signers only add provenance, never transport.", Toast.LENGTH_LONG).show()
          },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIncomingIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    refreshTick.value++
  }

  private suspend fun moveToListDb(id: String, target: String) {
    withContext(Dispatchers.IO) { db.documents().setList(id, target, System.currentTimeMillis()) }
  }

  private fun handleIncomingIntent(intent: Intent) {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_PROCESS_TEXT) return
    lifecycleScope.launch {
      try {
        val (text, subject) = Ingest.handleIntentText(intent) ?: return@launch
        val id = if (text.startsWith("__HTML__")) {
          Ingest.importHtml(this@MainActivity, text.removePrefix("__HTML__"), "android-share")
        } else {
          Ingest.importPlainText(this@MainActivity, text, if (intent.action == Intent.ACTION_PROCESS_TEXT) "android-process-text" else "android-share", subject)
        }
        Toast.makeText(this@MainActivity, "Added to Reader", Toast.LENGTH_SHORT).show()
        refreshTick.value++
      } catch (e: Exception) {
        Toast.makeText(this@MainActivity, "Import failed", Toast.LENGTH_LONG).show()
      }
    }
  }

  /** Pairing completion: validate QR, mint channel keypair, encrypted nonce-echo reply. */
  private suspend fun completePairing(qrText: String) {
    val o = parsePairingQr(qrText, System.currentTimeMillis() / 1000)
    val pairingPubkey = o["pairingPubkey"]!!.jsonPrimitive.content
    val chromePubkey = o["chromeDevicePubkey"]!!.jsonPrimitive.content
    val nonce = o["nonce"]!!.jsonPrimitive.content
    val relays = o["relays"]!!.jsonArray.map { it.jsonPrimitive.content }
    val channelSeckey = Secp256k1.randomPrivateKey()
    val channelPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(channelSeckey))
    val channelId = UUID.randomUUID().toString()
    keys.sealChannelKey(channelId, channelSeckey)
    withContext(Dispatchers.IO) {
      db.channels().upsert(
        com.reader.app.data.ChannelEntity(
          channelId = channelId, receiverPubkey = channelPubkey,
          trustedSenderPubkey = chromePubkey, createdAt = System.currentTimeMillis(),
          revokedAt = null, relaysJson = JsonArray(relays.map { JsonPrimitive(it) }).toString(),
        ),
      )
    }
    // Encrypted pairing response with nonce echo (prefer ephemeral 21059).
    val payload = buildJsonObject {
      put("protocol", "reader/1"); put("type", "pair-response")
      put("nonce", nonce); put("channelPubkey", channelPubkey)
      put("appVersion", "0.1.0")
    }.toString()
    val wrap = withContext(Dispatchers.IO) {
      try {
        NostrCodec.sealAndWrap(channelSeckey, pairingPubkey, payload, com.reader.app.nostr.WRAP_KIND_EPHEMERAL, 600).second
      } catch (e: Exception) {
        NostrCodec.sealAndWrap(channelSeckey, pairingPubkey, payload, com.reader.app.nostr.WRAP_KIND, 600).second
      }
    }
    withContext(Dispatchers.IO) {
      val client = RelayClient()
      var ok = 0
      for (r in relays) {
        if (runCatching { client.publish(r, wrap, 12) }.getOrDefault(false)) ok++
      }
      if (ok == 0) throw IllegalStateException("No relay accepted the pairing reply. Check connection and retry.")
    }
  }

  /** Manual archive export: ZIP of md + metadata JSON. Never keys. */
  private suspend fun exportArchive() {
    try {
      val list = withContext(Dispatchers.IO) { Triage.TABS.flatMap { db.documents().byList(it) } }
      val out = getExternalFilesDir(null)?.resolve("reader-archive.zip") ?: throw IllegalStateException("no storage")
      withContext(Dispatchers.IO) {
        ZipOutputStream(out.outputStream()).use { zip ->
          for (d in list) {
            zip.putNextEntry(ZipEntry("${d.documentId}.md"))
            zip.write(d.canonicalMarkdown.toByteArray())
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
        }
      }
      Toast.makeText(this, "Exported ${list.size} articles. Note: exported Markdown is plaintext.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
      Toast.makeText(this, "Export failed: ${e.message?.take(120)}", Toast.LENGTH_LONG).show()
    }
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
