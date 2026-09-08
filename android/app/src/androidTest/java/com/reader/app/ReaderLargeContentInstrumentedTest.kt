package com.reader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderLargeContentInstrumentedTest {
  @Test fun durableContentLargerThanCursorWindowIsReadableAfterReopen() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "reader-large-content-${System.nanoTime()}.db"
    val text = ("Exact Unicode: e\u0301, 日本語, 🌱.\n\n").repeat(80_000)
    val hash = ReaderCore.sha256Hex(text.toByteArray(Charsets.UTF_8))
    fun open() = Room.databaseBuilder(context, ReaderDb::class.java, name).build()
    var db = open()
    try {
      val doc = DocumentEntity(hash, "Large synthetic article", "web", null, null, null, null,
        1, null, text, 240_000, 1, "reading", "priority", "b5", 12, .4f, 1, 1, 1)
      assertTrue(db.documents().insert(doc) >= 0)
      db.close(); db = open()
      val restored = checkNotNull(db.documents().byId(hash))
      assertEquals(hash, ReaderCore.sha256Hex(restored.canonicalMarkdown.toByteArray(Charsets.UTF_8)))
      assertEquals("priority", restored.list); assertEquals(12, restored.progressCharOffset)
      assertEquals(-1L, db.documents().insert(doc.copy(list = "inbox", progressCharOffset = 0)))
      assertEquals("priority", db.documents().byId(hash)!!.list)
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
