package com.reader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.ReaderCore
import com.reader.app.core.SearchQuery
import com.reader.app.data.MIGRATION_11_12
import com.reader.app.data.MIGRATION_12_13
import com.reader.app.data.ReaderDb
import com.reader.app.data.RecentsStore
import com.reader.app.data.SearchDao
import com.reader.app.data.SearchRepository
import com.reader.app.data.SearchScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-run search verification (Room FTS5 needs the platform SQLite).
 * Deferred to the device pass; host gates cover the query builder.
 */
@RunWith(AndroidJUnit4::class)
class SearchInstrumentedTest {
  private lateinit var context: Context
  private lateinit var db: ReaderDb
  private lateinit var repo: SearchRepository

  @org.junit.Rule @JvmField
  val helper = androidx.room.testing.MigrationTestHelper(
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
    ReaderDb::class.java,
  )

  @Before fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    repo = SearchRepository(db, com.reader.app.data.ArticleRepository(db), RecentsStore(context))
  }

  @After fun tearDown() {
    db.close()
  }

  private suspend fun seedDoc(id: String, title: String, body: String) {
    val canonical = com.reader.app.core.ReaderCore.canonicalize("# $title\n\n$body\n")
    db.insertDocumentIndexed(
      com.reader.app.data.DocumentEntity(
        documentId = ReaderCore.documentId(canonical), title = title, sourceType = "web",
        sourceName = null, sourceUrl = null, author = null, publishedAt = null,
        capturedAt = 1, language = null, canonicalMarkdown = canonical,
        wordCount = ReaderCore.effectiveWords(canonical), parserVersion = 2,
        state = "unread", progressBlockId = null, progressCharOffset = 0, progressFraction = 0f,
        lastOpenedAt = 0L, createdAt = 1, updatedAt = 1,
      ),
    )
  }

  @Test fun ftsFindsTitleAndBodyWithSnippets() = runBlocking {
    seedDoc("a", "Nostr relays", "Relays forward encrypted messages between devices every day. ".repeat(10))
    seedDoc("b", "Gardening notes", "Relays have nothing to do with soil and seeds here. ".repeat(10))
    val results = repo.observeResults("relays", SearchScope.ALL, null).first()
    assertTrue(results.rows.isNotEmpty())
    // Title hit outranks the body-only hit.
    assertTrue(results.rows.first().title.contains("Nostr"))
    assertTrue(results.rows.first().titleHit)
    assertFalse(results.rows.first().bodySnippet.isNullOrBlank())
  }

  @Test fun umlautFoldingAndCompoundRecall() = runBlocking {
    seedDoc("c", "Müller report", "Herr Müller misst die Lesegeschwindigkeit jeden Morgen. ".repeat(8))
    val folded = repo.observeResults("muller", SearchScope.ALL, null).first()
    assertTrue(folded.rows.any { it.title.contains("Müller") })
    val compound = repo.observeResults("Geschwindigkeit", SearchScope.ALL, null).first()
    assertTrue(compound.rows.any { it.documentId.isNotBlank() })
  }

  @Test fun titlesScopeStaysInTitles() = runBlocking {
    seedDoc("d", "Unrelated title", "Zebra stripes appear only in this body text here. ".repeat(10))
    seedDoc("e", "Zebra facts", "Completely different body about savannas and grass. ".repeat(10))
    val results = repo.observeResults("zebra", SearchScope.TITLES, null).first()
    assertEquals(listOf("Zebra facts"), results.rows.map { it.title })
  }

  @Test fun listScopeFilters() = runBlocking {
    seedDoc("f", "Scoped note", "Scoped body words for filtering checks. ".repeat(10))
    db.documents().setList(
      db.documents().observeSummaries().first().first { it.title == "Scoped note" }.documentId,
      "later", 2,
    )
    val inbox = repo.observeResults("scoped", SearchScope.THIS_LIST, "inbox").first()
    assertTrue(inbox.rows.isEmpty())
    val later = repo.observeResults("scoped", SearchScope.THIS_LIST, "later").first()
    assertEquals(1, later.rows.size)
  }

  @Test fun deletesLeaveNoGhostHits() = runBlocking {
    seedDoc("g", "Ephemeral", "This paragraph will be deleted shortly after. ".repeat(10))
    assertTrue(repo.observeResults("ephemeral", SearchScope.ALL, null).first().rows.isNotEmpty())
    val id = db.documents().observeSummaries().first().first { it.title == "Ephemeral" }.documentId
    db.documents().update(db.documents().byId(id)!!.copy(list = "archived"))
    com.reader.app.data.ArticleRepository(db).delete(id)
    assertTrue(repo.observeResults("ephemeral", SearchScope.ALL, null).first().rows.isEmpty())
  }

  @Test fun matchOffsetResolvesToRenderedCursor() = runBlocking {
    val body = "Opening line here. " + "Filler sentence number nine. ".repeat(30) +
      "The needle phrase sleeps here quietly. " + "Closing filler sentence. ".repeat(30)
    seedDoc("h", "Needle article", body)
    val id = db.documents().observeSummaries().first().first { it.title == "Needle article" }.documentId
    val hit = repo.matchOffset(id, "needle phrase")!!
    assertEquals(id, hit.cursor.documentId)
    val prepared = com.reader.app.data.ArticleRepository(db).section(id, hit.part)
    val around = prepared.projection.text.substring(
      (hit.cursor.let { prepared.projection.offset(it.blockId, it.charOffset) } - 40).coerceAtLeast(0),
      (hit.endRendered + 40).coerceAtMost(prepared.projection.text.length),
    )
    assertTrue(around.contains("needle", ignoreCase = true))
  }

  @Test fun versionElevenToTwelveBackfillsAndServes() {
    // Backfill flag persists per app prefs: reset so this test owns its run.
    context.getSharedPreferences("reader_search", Context.MODE_PRIVATE).edit().clear().apply()
    val name = "search-v11-v12"
    helper.createDatabase(name, 11).apply {
      execSQL(
        """INSERT INTO documents(documentId, title, sourceType, capturedAt, canonicalMarkdown,
          wordCount, parserVersion, state, list, progressCharOffset, progressFraction, lastOpenedAt, createdAt, updatedAt)
          VALUES ('kept-doc','Kept Title','web',1,'Kept Title\n',2,2,'unread','inbox',0,0.0,0,1,1)""",
      )
      execSQL(
        """INSERT INTO document_content(documentId, part, text, startUtf16, endUtf16)
          VALUES ('kept-doc',0,'Kept Title\n\nBackfilled body mentions turnips often. Turnips everywhere.',0,80)""",
      )
      close()
    }
    helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).close()
    val migrated = Room.databaseBuilder(context, ReaderDb::class.java, name)
      .addMigrations(MIGRATION_11_12, MIGRATION_12_13)
      .build()
    try {
      runBlocking {
        // Pre-backfill the migrated row is invisible to search but intact.
        assertEquals("Kept Title", migrated.documents().byId("kept-doc")!!.title)
        val search = SearchRepository(migrated, com.reader.app.data.ArticleRepository(migrated), RecentsStore(context))
        search.ensureIndexed()
        val results = search.observeResults("turnips", SearchScope.ALL, null).first()
        assertEquals(1, results.rows.size)
        assertEquals("kept-doc", results.rows.single().documentId)
        // Second ensure is a no-op via the persisted flag.
        search.ensureIndexed()
      }
    } finally {
      migrated.close()
      context.deleteDatabase(name)
    }
  }

  @Test fun queryBuilderContractHolds() {
    // Host-covered too (SearchQueryTest); pinned here against the real schema path.
    assertNull(SearchQuery.buildFtsQuery("🎉"))
    assertNotNull(SearchQuery.buildFtsQuery("nostr relays"))
  }

  @Test fun fallbackPathServesWithoutFtsModule() {
    // Simulates TCL-class devices whose SQLite lacks FTS5: same row shape,
    // Kotlin snippets, working open-at-match — via LIKE + indexOf.
    com.reader.app.data.SearchFtsSupport.setSupportedForTests(false)
    try {
      runBlocking {
        seedDoc("z", "Fallback probe", "The fallback path finds this exact turnip sentence here. ".repeat(10))
        val results = repo.observeResults("turnip", SearchScope.ALL, null).first()
        assertTrue(results.rows.isNotEmpty())
        assertTrue(results.rows.any { it.bodySnippet?.contains("turnip", ignoreCase = true) == true })
        val id = db.documents().observeSummaries().first().first { it.title == "Fallback probe" }.documentId
        val hit = repo.matchOffset(id, "turnip")!!
        assertEquals(id, hit.cursor.documentId)
      }
    } finally {
      com.reader.app.data.SearchFtsSupport.resetForTests()
    }
  }
}
