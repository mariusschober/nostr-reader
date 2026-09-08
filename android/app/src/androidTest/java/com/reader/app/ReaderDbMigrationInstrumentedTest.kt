package com.reader.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.data.AckIntentEntity
import com.reader.app.data.MIGRATION_3_4
import com.reader.app.data.MIGRATION_5_6
import com.reader.app.data.MIGRATION_6_7
import com.reader.app.data.MIGRATION_8_9
import com.reader.app.data.MIGRATION_9_10
import com.reader.app.data.MIGRATION_7_8
import com.reader.app.data.MIGRATION_4_5
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ProcessedEventEntity
import com.reader.app.data.ReaderDb
import com.reader.app.security.KeystoreWrap
import com.reader.app.sync.PairingCoordinator
import com.reader.app.sync.openActiveChannelKeyOrRevoke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDbMigrationInstrumentedTest {
  @org.junit.Rule @JvmField
  val migrationHelper = androidx.room.testing.MigrationTestHelper(
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), ReaderDb::class.java)

  @Test fun versionEightAckEvidenceMigratesWithoutInventingReceiptTime() {
    val name = "hardening-v8-v9"
    migrationHelper.createDatabase(name, 8).apply {
      execSQL("""INSERT INTO ack_intents(channelId, transferId, manifestId, documentId,
        recipientDevicePubkey, status, receivedAt, expiresAt, acceptedRelaysJson,
        attemptCount, nextAttemptAt, completedAt, failedAt, lastErrorCode, refreshCount)
        VALUES ('channel','transfer','manifest','deleted-source','sender','stored',123,999,'[]',0,NULL,NULL,NULL,NULL,0)""")
      close()
    }
    migrationHelper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { migrated ->
      migrated.query("SELECT status, receivedAt, deletedAt FROM transfer_outcomes").use {
        assertTrue(it.moveToFirst())
        assertEquals("stored", it.getString(0))
        assertEquals(123L, it.getLong(1))
        assertEquals(123000L, it.getLong(2))
        assertFalse(it.moveToNext())
      }
    }
  }

  @Test fun versionNineToTenAddsBindingColumnsAndDocumentIndex() {
    val name = "hardening-v9-v10"
    migrationHelper.createDatabase(name, 9).apply {
      // v9 table without binding columns (as created by MIGRATION_8_9).
      execSQL("""INSERT INTO transfer_outcomes(channelId, transferId, manifestId, documentId,
        recipientDevicePubkey, status, receivedAt, expiresAt, deletedAt)
        VALUES ('channel','transfer','manifest','doc','sender','stored',10,99,NULL)""")
      close()
    }
    migrationHelper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10).use { migrated ->
      migrated.query("SELECT compressedSha256, chunkCount FROM transfer_outcomes").use {
        assertTrue(it.moveToFirst())
        assertEquals("", it.getString(0))
        assertEquals(0, it.getInt(1))
      }
      migrated.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_transfer_outcomes_documentId'").use {
        assertTrue(it.moveToFirst())
      }
    }
  }

  private fun channel(id: String, state: String, createdAt: Long): ChannelEntity = ChannelEntity(
    channelId = id,
    receiverPubkey = "11".repeat(32),
    trustedSenderPubkey = "22".repeat(32),
    createdAt = createdAt,
    revokedAt = null,
    relaysJson = "[\"wss://nos.lol\"]",
    state = state,
    updatedAt = createdAt,
  )

  @Test
  fun physicalDiagnosticV3AdvancesToV5WithoutLosingDocuments() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "reader-migration-3-4-test.db"
    context.deleteDatabase(name)
    val helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(name)
        .callback(object : SupportSQLiteOpenHelper.Callback(3) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              """CREATE TABLE documents (
                documentId TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                sourceName TEXT,
                sourceUrl TEXT,
                author TEXT,
                publishedAt INTEGER,
                capturedAt INTEGER NOT NULL,
                language TEXT,
                canonicalMarkdown TEXT NOT NULL,
                wordCount INTEGER NOT NULL,
                parserVersion INTEGER NOT NULL,
                state TEXT NOT NULL,
                list TEXT NOT NULL DEFAULT 'inbox',
                progressBlockId TEXT,
                progressCharOffset INTEGER NOT NULL,
                progressFraction REAL NOT NULL,
                lastOpenedAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
              )""".trimIndent(),
            )
            db.execSQL(
              """CREATE TABLE channels (
                channelId TEXT NOT NULL PRIMARY KEY,
                receiverPubkey TEXT NOT NULL,
                trustedSenderPubkey TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                revokedAt INTEGER,
                relaysJson TEXT NOT NULL,
                protocolVersion INTEGER NOT NULL DEFAULT 2,
                state TEXT NOT NULL DEFAULT 'pending_response',
                sessionId TEXT,
                pairingPubkey TEXT,
                pairingNonce TEXT,
                pairingRequestJson TEXT,
                relaySetDigest TEXT,
                pendingExpiresAt INTEGER,
                acceptedRelaysJson TEXT,
                attemptCount INTEGER NOT NULL DEFAULT 0,
                nextAttemptAt INTEGER,
                lastErrorCode TEXT,
                updatedAt INTEGER NOT NULL DEFAULT 0
              )""".trimIndent(),
            )
            db.execSQL(
              """CREATE TABLE incoming_chunks (
                transferId TEXT NOT NULL,
                `index` INTEGER NOT NULL,
                documentId TEXT NOT NULL,
                count INTEGER NOT NULL,
                bytesB64 TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL,
                PRIMARY KEY(transferId, `index`)
              )""".trimIndent(),
            )
            db.execSQL(
              """INSERT INTO documents VALUES (
                'doc-preserved','Preserved','web',NULL,NULL,NULL,NULL,1,NULL,
                'Preserved\n',1,1,'unread','inbox',NULL,0,0.0,0,1,1
              )""".trimIndent(),
            )
            db.execSQL(
              """INSERT INTO incoming_chunks VALUES (
                'old-transfer',0,'old-doc',1,'b2xk',1,9999999999999
              )""".trimIndent(),
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build(),
    )
    helper.writableDatabase
    helper.close()

    val migrated = Room.databaseBuilder(context, ReaderDb::class.java, name)
      .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
      .build()
    try {
      migrated.openHelper.writableDatabase
      assertEquals("Preserved", migrated.documents().byId("doc-preserved")!!.title)
      assertTrue(migrated.chunks().forTransfer("old-transfer").isEmpty())
      assertNull(migrated.manifests().byTransfer("old-transfer"))
      val columns = mutableSetOf<String>()
      migrated.openHelper.readableDatabase.query("PRAGMA table_info(incoming_chunks)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
      }
      assertTrue(columns.containsAll(setOf("manifestId", "compressedSha256")))
      assertTrue(migrated.ackIntents().due("none", Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 1).isEmpty())
      assertNull(migrated.processedEvents().byId("none"))
      val intent = AckIntentEntity(
        channelId = "channel",
        transferId = "00".repeat(16),
        manifestId = "11".repeat(32),
        documentId = "22".repeat(32),
        recipientDevicePubkey = "33".repeat(32),
        status = "stored",
        receivedAt = 10,
        expiresAt = 100,
        acceptedRelaysJson = "[]",
        attemptCount = 0,
        nextAttemptAt = null,
        completedAt = null,
        failedAt = null,
        lastErrorCode = null,
      )
      migrated.ackIntents().insert(intent)
      assertEquals(intent, migrated.ackIntents().byTransfer("channel", intent.transferId))
      val processed = ProcessedEventEntity("44".repeat(32), "channel", intent.transferId, 10, 100)
      assertTrue(migrated.processedEvents().insert(processed) >= 0)
      assertEquals(processed, migrated.processedEvents().byId(processed.eventId))
    } finally {
      migrated.close()
      context.deleteDatabase(name)
    }
  }

  @Test
  fun validatedReplacementBecomesTheOnlyActiveChannel() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    try {
      db.channels().upsert(channel("old-active", "active", 1))
      db.channels().upsert(channel("replacement", "ack_validated", 2))
      db.channels().upsert(channel("unrelated-pending", "awaiting_ack", 3))
      db.channels().upsert(
        channel("orphan-provisioning", "provisioning", 4).copy(pendingExpiresAt = 5),
      )

      val promoted = db.withTransaction {
        val result = db.channels().promoteAfterValidatedAck("replacement", 10)
        if (result == 1) db.channels().revokeOtherActive("replacement", 10)
        result
      }

      assertEquals(1, promoted)
      assertEquals(listOf("replacement"), db.channels().active().map { it.channelId })
      assertNull(db.channels().byId("old-active"))
      assertEquals("awaiting_ack", db.channels().byId("unrelated-pending")?.state)
      assertFalse(db.channels().pendingPairings().any { it.channelId == "orphan-provisioning" })
      assertTrue(db.channels().expiredPending(5).any { it.channelId == "orphan-provisioning" })
      db.openHelper.readableDatabase.query(
        "SELECT state, revokedAt FROM channels WHERE channelId = 'old-active'",
      ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("revoked", cursor.getString(0))
        assertEquals(10L, cursor.getLong(1))
      }
    } finally {
      db.close()
    }
  }

  @Test
  fun missingWrappedKeyRevokesFalseConnectedChannel() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    val channelId = "missing-key-${System.nanoTime()}"
    val keys = KeystoreWrap(context)
    keys.deleteChannelKey(channelId)
    try {
      val active = channel(channelId, "active", 1)
      db.channels().upsert(active)

      assertNull(openActiveChannelKeyOrRevoke(db, keys, active, nowMillis = 25))
      assertTrue(db.channels().active().isEmpty())
      assertNull(db.channels().byId(channelId))
      db.openHelper.readableDatabase.query(
        "SELECT state, revokedAt FROM channels WHERE channelId = ?",
        arrayOf(channelId),
      ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        assertEquals("revoked", cursor.getString(0))
        assertEquals(25L, cursor.getLong(1))
      }
    } finally {
      keys.deleteChannelKey(channelId)
      db.close()
    }
  }

  @Test
  fun futurePendingPairingKeepsRecoveryWorkRetryable() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    val nowSecs = System.currentTimeMillis() / 1000
    val pending = channel("pending-future", "awaiting_ack", System.currentTimeMillis()).copy(
      sessionId = "00".repeat(16),
      pairingPubkey = "11".repeat(32),
      pairingNonce = "22".repeat(32),
      pairingRequestJson = "{}",
      relaySetDigest = "33".repeat(32),
      pendingExpiresAt = nowSecs + 600,
      nextAttemptAt = nowSecs + 60,
    )
    try {
      db.channels().upsert(pending)
      val coordinator = PairingCoordinator(context, db)

      assertTrue(coordinator.processDue(collectSecs = 0))
      db.channels().revoke(pending.channelId, System.currentTimeMillis())
      assertFalse(coordinator.processDue(collectSecs = 0))
    } finally {
      db.close()
    }
  }
}
