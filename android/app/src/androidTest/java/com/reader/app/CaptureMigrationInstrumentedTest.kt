package com.reader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.data.MIGRATION_10_11
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureMigrationInstrumentedTest {
  @org.junit.Rule @JvmField
  val helper = androidx.room.testing.MigrationTestHelper(
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
    com.reader.app.data.ReaderDb::class.java,
  )

  @Test fun versionTenToElevenCreatesCaptureRequestsWithoutTouchingDocuments() {
    val name = "capture-v10-v11"
    helper.createDatabase(name, 10).apply {
      execSQL(
        """INSERT INTO documents(documentId, title, sourceType, capturedAt, canonicalMarkdown,
          wordCount, parserVersion, state, list, progressCharOffset, progressFraction, lastOpenedAt, createdAt, updatedAt)
          VALUES ('doc-keep','Keep','web',1,'Keep\n',1,2,'unread','inbox',0,0.0,0,1,1)""",
      )
      close()
    }
    helper.runMigrationsAndValidate(name, 11, true, MIGRATION_10_11).use { migrated ->
      migrated.query("SELECT documentId, title FROM documents").use {
        assertTrue(it.moveToFirst())
        assertEquals("doc-keep", it.getString(0))
      }
      migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='capture_requests'").use {
        assertTrue(it.moveToFirst())
      }
      migrated.query("SELECT requestId FROM capture_requests").use {
        assertFalse(it.moveToFirst())
      }
      migrated.execSQL(
        """INSERT INTO capture_requests(requestId, originalUrl, normalizedUrl, sourceType, state,
          attemptCount, createdAt, updatedAt) VALUES ('00','https://example.com/a','https://example.com/a','paste','pending',0,1,1)""",
      )
      migrated.query("SELECT state FROM capture_requests WHERE requestId='00'").use {
        assertTrue(it.moveToFirst())
        assertEquals("pending", it.getString(0))
      }
    }
  }
}
