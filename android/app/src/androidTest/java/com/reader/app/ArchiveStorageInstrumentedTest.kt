package com.reader.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.data.*
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveStorageInstrumentedTest {
  @Test fun deletionDropsContentAndCacheButPreservesQuoteAndReviewMetadata() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReaderDb::class.java).build()
    try {
      val doc = DocumentEntity("archive-smoke", "Disposable archive smoke", "text", null, null, null, null,
        1, null, "A saved quotation survives article deletion.", 7, 1, "reading", "archived", null, 0, 0f, 1, 1, 1)
      db.documents().insert(doc)
      val articles = ArticleRepository(db)
      val section = articles.section(doc.documentId, 0)
      val quote = HighlightAnchors.create("archive-smoke-quote", doc, section.projection, 0, 7, 1).copy(reviewCount = 3, lastReviewedAt = 2)
      db.highlights().insert(quote)
      val progress = ProgressWriter(db)
      progress.offer(SemanticCursor(doc.documentId, "b0", 2), .2f)
      progress.discard(doc.documentId)
      articles.delete(doc.documentId)
      progress.flush()
      assertFalse(db.documents().exists(doc.documentId))
      assertEquals(0, db.documents().contentPartCount(doc.documentId))
      assertEquals(quote, db.highlights().byId(quote.id))
      assertTrue(runCatching { articles.section(doc.documentId, 0) }.isFailure)
      db.documents().insert(doc.copy(documentId = "inbox-smoke", list = "inbox"))
      assertTrue(runCatching { articles.delete("inbox-smoke") }.isFailure)
      assertTrue(db.documents().exists("inbox-smoke"))
    } finally { db.close() }
  }
}
