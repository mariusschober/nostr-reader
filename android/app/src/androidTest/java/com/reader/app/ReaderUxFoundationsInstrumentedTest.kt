package com.reader.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.data.*
import com.reader.app.ui.Triage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Three bounded counterexamples for new contracts; never touches the app library. */
@RunWith(AndroidJUnit4::class)
class ReaderUxFoundationsInstrumentedTest {
  private fun db() = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ReaderDb::class.java).build()
  private fun doc(id: String, title: String = id, text: String = "A quiet reading habit", list: String = Triage.INBOX) =
    DocumentEntity(id, title, "web", null, "https://example.org/$id", null, null, 1, null, text, 4, 1, "unread", list, "b0", 0, 0f, 0, 1, 1)

  @Test fun completionIsExplicitOnceAndUndoProtectsLaterDecisions() = runBlocking {
    val db = db()
    try {
      db.documents().insert(doc("a"))
      db.documents().setProgress("a", "b0", 10, 1f, 2)
      assertNull(db.documents().metadataById("a")!!.finishedAt)
      val completion = ArticleCompletion(db)
      val receipt = completion.finish("a")
      assertEquals(Triage.ARCHIVED, db.documents().metadataById("a")!!.list)
      assertEquals(1, db.readingStats().finishedTotal())
      assertEquals(1, db.readingStats().finishedSince("0000"))
      db.documents().setProgress("a", "b0", 11, .95f, 3)
      assertTrue(completion.undo(receipt))
      assertFalse(completion.undo(receipt))
      assertEquals(0, db.readingStats().finishedSince("0000"))
      assertEquals(Triage.INBOX, db.documents().metadataById("a")!!.list)
      val second = completion.finish("a")
      completion.finish("a")
      assertEquals(1, db.readingStats().finishedSince("0000"))
      ArticleMoves(db).move(setOf("a"), Triage.LATER)
      ArticleMoves(db).move(setOf("a"), Triage.ARCHIVED)
      assertFalse(completion.undo(second))
    } finally { db.close() }
  }

  @Test fun phraseScopeAndStableAndLabelsAreAppliedTogether() = runBlocking {
    val db = db()
    try {
      db.documents().insert(doc("a", "Good habit", "A quiet reading habit", Triage.ARCHIVED))
      db.documents().insert(doc("b", "Quiet reading", "An ordinary habit"))
      db.documents().insert(doc("c", "Interrupted", "quiet scattered reading habit"))
      db.labels().assignNorm("a", "Long reads", 1)
      db.labels().assignNorm("a", "Practice", 1)
      db.labels().assignNorm("b", "Practice", 1)
      val long = db.labels().idForNormalized("long reads")!!
      val practice = db.labels().idForNormalized("practice")!!
      suspend fun ids(r: LibrarySearchRequest) = LibrarySearch(db).observe(r).first().rows.map { it.documentId }.toSet()
      assertEquals(setOf("a", "b"), ids(LibrarySearchRequest("\"quiet reading\" habit")))
      assertEquals(setOf("b"), ids(LibrarySearchRequest("\"quiet reading\"", titlesOnly = true)))
      assertEquals(setOf("b"), ids(LibrarySearchRequest("\"quiet reading\"", shelf = Triage.INBOX)))
      assertEquals(setOf("a"), ids(LibrarySearchRequest("", labels = setOf(long, practice))))
      db.labels().rename(long, "Deep reading", "deep reading", 2)
      assertEquals(setOf("a"), ids(LibrarySearchRequest("", labels = setOf(long))))
      db.labels().mergeInto(long, practice)
      assertEquals(2, db.labels().allPairs().size)
      assertEquals(setOf("c"), ids(LibrarySearchRequest("", unlabeled = true)))
    } finally { db.close() }
  }

  @Test fun removalUndoRestoresFullQuoteMetadataAfterSourceDeletion() = runBlocking {
    val db = db()
    try {
      db.documents().insert(doc("a", list = Triage.ARCHIVED))
      val quote = HighlightEntity("h", "a", "quiet reading", "Attribution", "https://example.org/a", 1, 2,
        "b0", 2, "b0", 15, 2, 0, 20, "A ", " habit", "PURPLE", true, 7, 1234, 8)
      db.highlights().insert(quote)
      val repo = HighlightRepository(db)
      val removed = repo.remove("h")!!
      db.documents().deleteArchivedById("a")
      assertTrue(repo.undo(removed))
      assertEquals(quote.copy(revision = 9), db.highlights().byId("h"))
      assertFalse(repo.undo(removed))
    } finally { db.close() }
  }
}
