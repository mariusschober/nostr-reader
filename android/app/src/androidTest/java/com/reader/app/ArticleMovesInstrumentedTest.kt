package com.reader.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.data.*
import com.reader.app.ui.Triage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleMovesInstrumentedTest {
  private fun document(id: String, list: String) = DocumentEntity(id, id, "text", null, null, null, null,
    1, null, "Synthetic article", 2, 1, "unread", list, "b0", 0, 0f, 0, 1, 1)

  @Test fun batchUndoKeepsEveryPreviousListAndLaterChanges() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ReaderDb::class.java).build()
    try {
      db.documents().insert(document("a", Triage.INBOX))
      db.documents().insert(document("b", Triage.LATER))
      db.documents().insert(document("c", Triage.PRIORITY))
      val repo = ArticleMoves(db)
      val moves = repo.move(setOf("a", "b", "c"), Triage.ARCHIVED)
      assertEquals(3, moves.size)
      db.documents().setList("c", Triage.LATER, 2)
      assertEquals(2, repo.undo(moves))
      assertEquals(Triage.INBOX, db.documents().metadataById("a")!!.list)
      assertEquals(Triage.LATER, db.documents().metadataById("b")!!.list)
      assertEquals(Triage.LATER, db.documents().metadataById("c")!!.list)
      assertEquals(0, repo.undo(moves))
    } finally { db.close() }
  }

  @Test fun failedBatchRollsBackAndDeletedSourceIsNeverRestored() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ReaderDb::class.java).build()
    try {
      db.documents().insert(document("a", Triage.INBOX))
      db.documents().insert(document("b", Triage.INBOX))
      db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second_move BEFORE UPDATE OF list ON documents WHEN NEW.documentId = 'b' BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END")
      val repo = ArticleMoves(db)
      assertTrue(runCatching { repo.move(linkedSetOf("a", "b"), Triage.ARCHIVED) }.isFailure)
      assertEquals(Triage.INBOX, db.documents().metadataById("a")!!.list)
      assertEquals(Triage.INBOX, db.documents().metadataById("b")!!.list)
      db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_move")
      val moves = repo.move(setOf("a"), Triage.ARCHIVED)
      db.documents().deleteArchivedById("a")
      assertEquals(0, repo.undo(moves))
      assertNull(db.documents().metadataById("a"))
      assertTrue(runCatching { repo.move(setOf("b", "missing"), Triage.PRIORITY) }.isFailure)
      assertEquals(Triage.INBOX, db.documents().metadataById("b")!!.list)
    } finally { db.close() }
  }
}
