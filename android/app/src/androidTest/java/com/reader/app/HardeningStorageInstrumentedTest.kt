package com.reader.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.data.*
import com.reader.app.core.ReaderCore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class HardeningStorageInstrumentedTest {
  @Test fun portableSnapshotIncludesProvenanceAndOrphanQuotesAndVerifiesEveryComponent() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    val directory = File(context.cacheDir, "hardening-export-test").apply { mkdirs() }
    try {
      val text = ReaderCore.canonicalize("# Synthetic\n\nA retained quote.\n")
      val doc = DocumentEntity(ReaderCore.documentId(text), "Synthetic", "web", "Example", "https://example.org/source", "Author", 1,
        2, "en", text, ReaderCore.wordCount(text), 2, "reading", "archived", "b0", 0, 0f, 1, 1, 1)
      db.documents().insert(doc)
      val section = ArticleRepository(db).section(doc.documentId, 0)
      val quote = HighlightAnchors.create("retained", doc, section.projection, 0, 5, 1).copy(reviewCount = 3)
      db.highlights().insert(quote)
      val exported = ArchiveExporter(db).prepare(directory)
      ArchiveExporter.verify(exported)
      ZipFile(exported).use { zip ->
        assertEquals(text, zip.getInputStream(zip.getEntry("articles/${doc.documentId}.md")).bufferedReader().readText())
        val metadata = zip.getInputStream(zip.getEntry("articles/${doc.documentId}.json")).bufferedReader().readText()
        assertTrue(metadata.contains("https://example.org/source"))
        assertTrue(metadata.contains("Author"))
        assertFalse(zip.entries().toList().any { it.name.contains("key", true) || it.name.contains("channel", true) })
      }
      exported.delete()
      ArticleRepository(db).delete(doc.documentId)
      val orphan = ArchiveExporter(db).prepare(directory)
      ZipFile(orphan).use { zip ->
        assertNull(zip.getEntry("articles/${doc.documentId}.md"))
        assertTrue(zip.getInputStream(zip.getEntry("highlights.jsonl")).bufferedReader().readText().contains("retained"))
      }
      orphan.delete()
      assertEquals(quote, db.highlights().byId(quote.id))
    } finally { db.close(); directory.deleteRecursively() }
  }

  @Test fun journalRecoversSelectionAfterItsPersistenceOwnerIsLost() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val directory = File(context.cacheDir, "hardening-journal-test").apply { deleteRecursively(); mkdirs() }
    val draft = HighlightEntity("synthetic", "source", "café 🌱", "Synthetic", null, 1, 1,
      "b0", 0, "b0", 8, 1, 0, 8, "", "", color = "CYAN")
    val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val blocked = CompletableDeferred<Unit>()
    try {
      withContext(Dispatchers.Main) {
        ReadingSession(firstScope, directory) { blocked.await(); HighlightMutation(null, it) }.submitSelection(draft) { }
      }
      withTimeout(5000) { while (directory.listFiles()?.none { it.name.endsWith(".json") } != false) delay(10) }
      firstScope.cancel()
      val recovered = CompletableDeferred<HighlightEntity>()
      val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      try {
        val second = withContext(Dispatchers.Main) { ReadingSession(secondScope, directory) { recovered.complete(it); HighlightMutation(null, it) } }
        assertEquals(draft, withTimeout(5000) { recovered.await() })
        withContext(Dispatchers.Main) { second.flush() }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
      } finally { secondScope.cancel() }
    } finally { firstScope.cancel(); directory.deleteRecursively() }
  }
}
