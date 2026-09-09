package com.reader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.capture.ArticleExtractor
import com.reader.app.capture.CaptureFetcher
import com.reader.app.capture.CaptureHttp
import com.reader.app.capture.CaptureRepository
import com.reader.app.capture.CaptureUrlPolicy
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import com.reader.app.data.ReaderDb
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFlowInstrumentedTest {
  private lateinit var db: ReaderDb
  private lateinit var repo: CaptureRepository

  private fun publicResolver(): CaptureUrlPolicy.AddressResolver =
    CaptureUrlPolicy.AddressResolver { _ -> arrayOf(InetAddress.getByName("93.184.216.34")) }

  private fun articleHtml(title: String = "Instrumented Article"): ByteArray =
    ("<!doctype html><html><head><title>$title</title><meta property='og:title' content='$title'></head>" +
      "<body><main><article><h1>$title</h1><p>" + "Wort ".repeat(80) + "</p><p>" + "Satz ".repeat(80) +
      "</p></article></main><nav>Nav</nav><div class='ads'>Ads</div></body></html>").toByteArray()

  private fun fakeSuccess(title: String = "Instrumented Article"): CaptureHttp = object : CaptureHttp {
    override suspend fun get(url: String, totalTimeoutSecs: Long): CaptureHttp.Response =
      CaptureHttp.Response(200, mapOf("Content-Type" to "text/html"), articleHtml(title))
  }

  @Before fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    repo = CaptureRepository(db)
  }

  @After fun tearDown() { db.close() }

  @Test fun unpairedShareAndPasteBothCreateDurableRequestsWithoutPairing() = runBlocking {
    // No channels, no pairing: capture must not require transport state.
    assertTrue(db.channels().active().isEmpty())
    val share = repo.getOrCreate("https://example.com/unpaired-share", "Share Title", "android-share")
    val paste = repo.getOrCreate("https://example.com/unpaired-paste", null, "paste")
    assertEquals("pending", share.state)
    assertEquals("pending", paste.state)
    assertEquals("https://example.com/unpaired-share", share.originalUrl)
    assertEquals("Share Title", share.subjectTitle)
  }

  @Test fun duplicateIntentCreatesOneLogicalRequest() = runBlocking {
    val first = repo.getOrCreate("https://example.com/dup", "T", "android-share")
    val second = repo.getOrCreate("https://EXAMPLE.com:443/dup", "T2", "android-share")
    assertEquals(first.requestId, second.requestId)
    // Deliberate recapture after terminal state creates a new request.
    val fetching = repo.markFetching(first.requestId)!!
    val extracted = ArticleExtractor.extract(articleHtml("Dup"), "https://example.com/dup")
    val docId = repo.completeWithArticle(first.requestId, fetching.generation, extracted, "https://example.com/dup")!!
    assertNotNull(db.documents().byId(docId))
    val recapture = repo.getOrCreate("https://example.com/dup", null, "android-share")
    assertNotEquals(first.requestId, recapture.requestId)
  }

  @Test fun offlineShareSurvivesRestartAndCompletesOnce() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "capture-offline-${System.nanoTime()}.db"
    context.deleteDatabase(name)
    val fileDb = androidx.room.Room.databaseBuilder(context, ReaderDb::class.java, name)
      .addMigrations(
        com.reader.app.data.MIGRATION_1_2, com.reader.app.data.MIGRATION_2_3,
        com.reader.app.data.MIGRATION_3_4, com.reader.app.data.MIGRATION_4_5,
        com.reader.app.data.MIGRATION_5_6, com.reader.app.data.MIGRATION_6_7,
        com.reader.app.data.MIGRATION_7_8, com.reader.app.data.MIGRATION_8_9,
        com.reader.app.data.MIGRATION_9_10, com.reader.app.data.MIGRATION_10_11,
      ).build()
    try {
      val fileRepo = CaptureRepository(fileDb)
      val req = fileRepo.getOrCreate("https://example.com/offline", null, "android-share")
      // Offline: first fetch attempt fails retryable, request persists.
      val fetching = fileRepo.markFetching(req.requestId)!!
      assertEquals(1, fetching.attemptCount)
      fileRepo.markRetryable(req.requestId, "dns_retryable", "You're offline; Reader will retry")
      val persisted = fileDb.captureRequests().byId(req.requestId)!!
      assertEquals("pending", persisted.state)
      assertNotNull(persisted.nextAttemptAt)
      // Simulate restart: close and reopen, request survives, completes once.
      fileDb.close()
      val reopened = androidx.room.Room.databaseBuilder(context, ReaderDb::class.java, name)
        .addMigrations(
          com.reader.app.data.MIGRATION_1_2, com.reader.app.data.MIGRATION_2_3,
          com.reader.app.data.MIGRATION_3_4, com.reader.app.data.MIGRATION_4_5,
          com.reader.app.data.MIGRATION_5_6, com.reader.app.data.MIGRATION_6_7,
          com.reader.app.data.MIGRATION_7_8, com.reader.app.data.MIGRATION_8_9,
          com.reader.app.data.MIGRATION_9_10, com.reader.app.data.MIGRATION_10_11,
        ).build()
      try {
        val survivor = reopened.captureRequests().byId(req.requestId)!!
        assertEquals("pending", survivor.state)
        val repo2 = CaptureRepository(reopened)
        val run2 = repo2.markFetching(survivor.requestId)!!
        val extracted = ArticleExtractor.extract(articleHtml("Offline"), "https://example.com/offline")
        val docId = repo2.completeWithArticle(survivor.requestId, run2.generation, extracted, "https://example.com/offline")!!
        assertNotNull(reopened.documents().byId(docId))
        // Second completion attempt is a no-op (one logical document).
        assertNull(repo2.completeWithArticle(survivor.requestId, run2.generation, extracted, "https://example.com/offline"))
        assertEquals(1, reopened.documents().byList("inbox").count { it.documentId == docId })
      } finally {
        reopened.close()
      }
    } finally {
      context.deleteDatabase(name)
    }
  }

  @Test fun workerDeathDuringFetchRecoversCoherently() = runBlocking {
    val req = repo.getOrCreate("https://example.com/crash", null, "android-share")
    // Worker dies after markFetching but before commit: fetching row remains.
    val run = repo.markFetching(req.requestId)!!
    assertEquals("fetching", db.captureRequests().byId(req.requestId)!!.state)
    // Recovery run picks up the same request (fetching is runnable) and commits once.
    val extracted = ArticleExtractor.extract(articleHtml("Crash"), "https://example.com/crash")
    val docId = repo.completeWithArticle(req.requestId, run.generation, extracted, "https://example.com/crash")
    assertNotNull(docId)
    assertEquals("completed", db.captureRequests().byId(req.requestId)!!.state)
    assertNotNull(db.documents().byId(docId!!))
  }

  @Test fun cancellationDuringFetchPreventsStaleCommit() = runBlocking {
    val req = repo.getOrCreate("https://example.com/cancel", null, "android-share")
    val run = repo.markFetching(req.requestId)!!
    val staleGeneration = run.generation
    repo.cancel(req.requestId)
    assertEquals("cancelled", db.captureRequests().byId(req.requestId)!!.state)
    val extracted = ArticleExtractor.extract(articleHtml("Cancel"), "https://example.com/cancel")
    // Stale worker commit is suppressed.
    assertNull(repo.completeWithArticle(req.requestId, staleGeneration, extracted, "https://example.com/cancel"))
    assertNull(repo.completeLinkOnly(req.requestId, staleGeneration, null, "Cancel", "reason"))
    // No document was smuggled in by the stale worker.
    assertTrue(db.documents().byList("inbox").none { it.sourceUrl == "https://example.com/cancel" })
  }

  @Test fun failurePreservesUsefulLinkWithHonestLabel() = runBlocking {
    val req = repo.getOrCreate("https://example.com/paywalled", null, "android-share")
    val run = repo.markFetching(req.requestId)!!
    val docId = repo.completeLinkOnly(
      req.requestId, run.generation, "https://example.com/paywalled", "Paywalled Story",
      "This page needs a login; Reader does not bypass access controls", "access_denied",
    )!!
    val doc = db.documents().byId(docId)!!
    assertEquals("link", doc.sourceType)
    assertEquals("https://example.com/paywalled", doc.sourceUrl)
    assertTrue(doc.canonicalMarkdown.contains("Article text is unavailable"))
    assertTrue(doc.canonicalMarkdown.contains("Open original"))
    assertTrue(doc.canonicalMarkdown.contains("https://example.com/paywalled"))
    // Never an empty or login page masquerading as an article.
    assertTrue(doc.wordCount > 20)
    assertFalse(ArticleParser.parse(doc.canonicalMarkdown).isEmpty())
  }

  @Test fun noCredentialsAreImported() = runBlocking {
    try {
      repo.getOrCreate("https://user:secret@example.com/", null, "android-share")
      fail("credentials must be rejected at durable-commit time")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun existingTextImportHighlightAndReviewPathsUnaffected() = runBlocking {    // Plain-text classification still preserves text (no capture fetch).
    // Uses only the in-memory DB: never touches production documents.
    val kind = com.reader.app.capture.ShareIntentClassifier.classify(
      "A quiet note worth keeping.", null, com.reader.app.sync.Ingest::looksLikeMarkdown,
    )
    assertTrue(kind is com.reader.app.capture.ShareClassification.SelectedText)
    val text = ReaderCore.canonicalize("# Synthetic\n\nA retained quote for review.\n")
    val docId = ReaderCore.documentId(text)
    db.documents().insert(
      com.reader.app.data.DocumentEntity(docId, "Synthetic", "web", null, null, null, null, 1, null, text,
        ReaderCore.wordCount(text), 2, "unread", "inbox", "b0", 0, 0f, 1, 1, 1),
    )
    val section = com.reader.app.data.ArticleRepository(db).section(docId, 0)
    val quote = com.reader.app.data.HighlightAnchors.create("q1", db.documents().byId(docId)!!, section.projection, 0, 5, 1)
    db.highlights().insert(quote)
    assertNotNull(db.highlights().byId("q1"))
  }

  @Test fun dueGateBlocksPrematureFetchAndCancelWinsRaces() = runBlocking {
    val req = repo.getOrCreate("https://example.com/gate", null, "android-share")
    // Future backoff: worker must not burn an attempt.
    db.captureRequests().update(db.captureRequests().byId(req.requestId)!!.copy(nextAttemptAt = System.currentTimeMillis() + 3600_000))
    assertNull(db.captureRequests().dueById(req.requestId, System.currentTimeMillis()))
    assertNotNull(db.captureRequests().dueById(req.requestId, System.currentTimeMillis() + 3600_001))
    // Cancel is atomic and terminal: later claims fail, commits fail.
    repo.cancel(req.requestId)
    val cancelled = db.captureRequests().byId(req.requestId)!!
    assertEquals("cancelled", cancelled.state)
    assertNull(repo.markFetching(req.requestId))
    // Second cancel is a no-op (generation untouched).
    repo.cancel(req.requestId)
    assertEquals(cancelled.generation, db.captureRequests().byId(req.requestId)!!.generation)
  }

  @Test fun purgeBoundsTerminalHistoryOnly() = runBlocking {
    val old = System.currentTimeMillis() - 100L * 24L * 3600L * 1000L
    val dao = db.captureRequests()
    // Backdated terminal rows with distinct URLs (no dedup collisions).
    val link = repo.getOrCreate("https://example.com/purge-old", null, "paste")
    dao.update(dao.byId(link.requestId)!!.copy(state = "link_only", documentId = "doc-old", updatedAt = old))
    val fresh = repo.getOrCreate("https://example.com/purge-fresh", null, "paste")
    val active = repo.getOrCreate("https://example.com/purge-active", null, "paste")
    // A document must survive its request row's expiry.
    val text = ReaderCore.canonicalize("# Old\n\nKept body.\n")
    val docId = ReaderCore.documentId(text)
    db.documents().insert(
      com.reader.app.data.DocumentEntity(docId, "Old", "link", null, "https://example.com/purge-old", null, null, 1, null, text,
        ReaderCore.wordCount(text), 2, "unread", "inbox", "b0", 0, 0f, 1, 1, 1),
    )
    val removed = repo.purgeHistory()
    assertEquals(1, removed)
    assertNull(dao.byId(link.requestId))
    assertNotNull(dao.byId(fresh.requestId))
    assertNotNull(dao.byId(active.requestId))
    assertNotNull(db.documents().byId(docId))
  }

  @Test fun chineseArticleCommitsWithCjkWordCount() = runBlocking {
    val para = "阅读是人类获取知识的重要途径。通过阅读我们可以了解世界开阔视野增长见识。"
    val html = ("<!doctype html><html><head><title>阅读的意义</title></head><body>" +
      "<main><article><h1>阅读的意义</h1><p>$para</p><p>$para</p><p>$para</p></article></main></body></html>").toByteArray()
    val req = repo.getOrCreate("https://example.com/zh-doc", null, "paste")
    val run = repo.markFetching(req.requestId)!!
    val extracted = ArticleExtractor.extract(html, "https://example.com/zh-doc")
    val docId = repo.completeWithArticle(req.requestId, run.generation, extracted, "https://example.com/zh-doc")!!
    val doc = db.documents().byId(docId)!!
    assertTrue("words=${doc.wordCount}", doc.wordCount >= 60)
    assertTrue(doc.canonicalMarkdown.contains("阅读是人类"))
  }
}
