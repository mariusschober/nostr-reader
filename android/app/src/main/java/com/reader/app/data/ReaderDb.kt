package com.reader.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents", indices = [Index(value = ["list", "createdAt", "documentId"])])
data class DocumentEntity(
  @PrimaryKey val documentId: String,
  val title: String,
  val sourceType: String,
  val sourceName: String?,
  val sourceUrl: String?,
  val author: String?,
  val publishedAt: Long?,
  val capturedAt: Long,
  val language: String?,
  val canonicalMarkdown: String,
  val wordCount: Int,
  val parserVersion: Int,
  val state: String, // unread|reading (progress bucket; list membership lives in `list`)
  @ColumnInfo(defaultValue = "'inbox'") val list: String = "inbox", // inbox|priority|later|archived
  val progressBlockId: String?,
  val progressCharOffset: Int,
  val progressFraction: Float,
  val lastOpenedAt: Long,
  val createdAt: Long,
  val updatedAt: Long,
)

/** List rows never contain article bodies or parser trees. */
data class DocumentSummary(
  val documentId: String, val title: String, val sourceType: String, val sourceName: String?,
  val wordCount: Int, val state: String, val list: String, val progressFraction: Float,
  val createdAt: Long, val updatedAt: Long,
)

/** Bounded rows avoid Android CursorWindow limits, while preserving Room atomicity. */
@Entity(
  tableName = "document_content", primaryKeys = ["documentId", "part"],
  foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["documentId"],
    childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
)
data class DocumentContentEntity(val documentId: String, val part: Int, val text: String, val startUtf16: Int, val endUtf16: Int)

const val CONTENT_PART_CHARS = 32768

@Entity(tableName = "channels")
data class ChannelEntity(
  @PrimaryKey val channelId: String,
  val receiverPubkey: String,
  val trustedSenderPubkey: String,
  val createdAt: Long,
  val revokedAt: Long?,
  val relaysJson: String,
  @ColumnInfo(defaultValue = "2") val protocolVersion: Int = 2,
  @ColumnInfo(defaultValue = "'pending_response'") val state: String = "pending_response",
  val sessionId: String? = null,
  val pairingPubkey: String? = null,
  val pairingNonce: String? = null,
  val pairingRequestJson: String? = null,
  val relaySetDigest: String? = null,
  val pendingExpiresAt: Long? = null,
  val acceptedRelaysJson: String? = null,
  @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
  val nextAttemptAt: Long? = null,
  val lastErrorCode: String? = null,
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(tableName = "incoming_chunks", primaryKeys = ["transferId", "index"])
data class ChunkEntity(
  val transferId: String,
  val index: Int,
  val manifestId: String,
  val documentId: String,
  val compressedSha256: String,
  val count: Int,
  val bytesB64: String,
  val receivedAt: Long,
  val expiresAt: Long,
)

@Entity(tableName = "incoming_manifests")
data class ManifestEntity(
  @PrimaryKey val transferId: String,
  val manifestId: String,
  val documentId: String,
  val title: String,
  val sourceType: String,
  val sourceName: String?,
  val sourceUrl: String?,
  val author: String?,
  val publishedAt: Long?,
  val capturedAt: Long,
  val language: String?,
  val mime: String,
  val compression: String,
  val wordCount: Int,
  val uncompressedBytes: Int,
  val compressedBytes: Int,
  val compressedSha256: String,
  val documentSha256: String,
  val chunkCount: Int,
  val senderDevicePubkey: String,
  val recipientChannelPubkey: String,
  val expiresAt: Long,
  val receivedAt: Long,
)

@Entity(tableName = "ack_intents", primaryKeys = ["channelId", "transferId"])
data class AckIntentEntity(
  val channelId: String,
  val transferId: String,
  val manifestId: String,
  val documentId: String,
  val recipientDevicePubkey: String,
  val status: String,
  val receivedAt: Long,
  val expiresAt: Long,
  val acceptedRelaysJson: String,
  val attemptCount: Int,
  val nextAttemptAt: Long?,
  val completedAt: Long?,
  val failedAt: Long?,
  val lastErrorCode: String?,
  @ColumnInfo(defaultValue = "0") val refreshCount: Int = 0,
)

@Entity(tableName = "processed_events")
data class ProcessedEventEntity(
  @PrimaryKey val eventId: String,
  val channelId: String,
  val transferId: String,
  val processedAt: Long,
  val expiresAt: Long,
)

@Dao
abstract class DocumentDao {
  @Query("SELECT CAST(COUNT(*) AS TEXT) || ':' || CAST(COALESCE(MAX(updatedAt), 0) AS TEXT) FROM documents")
  abstract fun observeRevision(): Flow<String>

  @Query("SELECT documentId, title, sourceType, sourceName, wordCount, state, list, progressFraction, createdAt, updatedAt FROM documents ORDER BY createdAt DESC, documentId ASC")
  abstract fun observeSummaries(): Flow<List<DocumentSummary>>

  @Query("SELECT * FROM documents WHERE documentId = :id LIMIT 1")
  abstract fun observeMetadata(id: String): Flow<DocumentEntity?>

  @Query("SELECT * FROM documents WHERE list = 'inbox' ORDER BY createdAt DESC")
  abstract suspend fun inbox(): List<DocumentEntity>

  @Query("SELECT * FROM documents WHERE list = 'archived' ORDER BY updatedAt DESC")
  abstract suspend fun archived(): List<DocumentEntity>

  @Query("SELECT * FROM documents WHERE list = :list ORDER BY createdAt DESC")
  abstract suspend fun byList(list: String): List<DocumentEntity>

  @Query("UPDATE documents SET list = :list, updatedAt = :now WHERE documentId = :id")
  abstract suspend fun setList(id: String, list: String, now: Long)

  @Query("DELETE FROM documents WHERE documentId = :id AND list = 'archived'")
  abstract suspend fun deleteArchivedById(id: String): Int

  @Query("DELETE FROM documents WHERE documentId = :id")
  abstract suspend fun deleteById(id: String)

  @Query("SELECT * FROM documents WHERE documentId = :id LIMIT 1")
  abstract suspend fun metadataById(id: String): DocumentEntity?

  @Query("SELECT * FROM document_content WHERE documentId = :id ORDER BY part")
  abstract suspend fun contentParts(id: String): List<DocumentContentEntity>

  @Query("SELECT text FROM document_content WHERE documentId = :id AND part = :part")
  abstract suspend fun contentPart(id: String, part: Int): String?

  @Query("SELECT COUNT(*) FROM document_content WHERE documentId = :id")
  abstract suspend fun contentPartCount(id: String): Int

  @Query("SELECT COALESCE(MAX(endUtf16), 0) FROM document_content WHERE documentId = :id")
  abstract suspend fun contentLength(id: String): Int

  @Query("SELECT * FROM document_content WHERE documentId = :id AND startUtf16 < :end AND endUtf16 > :start ORDER BY part")
  abstract suspend fun contentRangeParts(id: String, start: Int, end: Int): List<DocumentContentEntity>

  @Transaction
  open suspend fun contentRange(id: String, start: Int, end: Int): String = buildString {
    for (part in contentRangeParts(id, start, end)) {
      append(part.text.substring((start - part.startUtf16).coerceAtLeast(0), (end - part.startUtf16).coerceAtMost(part.text.length)))
    }
  }

  @Transaction
  open suspend fun byId(id: String): DocumentEntity? {
    val metadata = metadataById(id) ?: return null
    val count = contentPartCount(id)
    return metadata.copy(canonicalMarkdown = buildString {
      for (part in 0 until count) append(checkNotNull(contentPart(id, part)) { "Article content is incomplete" })
    })
  }

  @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE documentId = :id)")
  abstract suspend fun exists(id: String): Boolean

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  protected abstract suspend fun insertMetadata(doc: DocumentEntity): Long

  @Insert(onConflict = OnConflictStrategy.ABORT)
  protected abstract suspend fun insertContent(part: DocumentContentEntity)

  /** The surrounding intake transaction commits content, metadata and ACK intent together. */
  @Transaction
  open suspend fun insert(doc: DocumentEntity): Long {
    val row = insertMetadata(doc.copy(canonicalMarkdown = ""))
    if (row == -1L) return row
    var start = 0
    var part = 0
    var bytes = 0
    while (start < doc.canonicalMarkdown.length) {
      var end = (start + CONTENT_PART_CHARS).coerceAtMost(doc.canonicalMarkdown.length)
      if (end < doc.canonicalMarkdown.length && Character.isHighSurrogate(doc.canonicalMarkdown[end - 1])) end--
      val text = doc.canonicalMarkdown.substring(start, end)
      bytes += text.toByteArray(Charsets.UTF_8).size
      require(bytes <= com.reader.app.core.ReaderCore.MAX_EXPANDED_BYTES) { "Article exceeds 20 MiB" }
      insertContent(DocumentContentEntity(doc.documentId, part++, text, start, end))
      start = end
    }
    return row
  }

  @Update
  protected abstract suspend fun updateMetadata(doc: DocumentEntity)

  open suspend fun update(doc: DocumentEntity) = updateMetadata(doc.copy(canonicalMarkdown = ""))

  @Query("UPDATE documents SET progressBlockId = :blockId, progressCharOffset = :charOffset, progressFraction = :fraction, state = 'reading', lastOpenedAt = :now, updatedAt = :now WHERE documentId = :id")
  abstract suspend fun setProgress(id: String, blockId: String, charOffset: Int, fraction: Float, now: Long)

  @Query("UPDATE documents SET state = :state, updatedAt = :now WHERE documentId = :id")
  abstract suspend fun setState(id: String, state: String, now: Long)

  @Query("SELECT COALESCE(SUM(wordCount),0) FROM documents WHERE state IN ('unread','reading')")
  abstract suspend fun unreadWords(): Long

  @Query("SELECT COALESCE(SUM(wordCount),0) FROM documents WHERE list = :list")
  abstract suspend fun wordsInList(list: String): Long
}

@Dao
interface ChannelDao {
  @Query("SELECT * FROM channels WHERE revokedAt IS NULL AND protocolVersion = 2 AND state = 'active'")
  suspend fun active(): List<ChannelEntity>

  @Query("SELECT * FROM channels WHERE revokedAt IS NULL AND protocolVersion = 2 AND state IN ('pending_response','awaiting_ack','ack_validated','completion_pending')")
  suspend fun pendingPairings(): List<ChannelEntity>

  @Query("SELECT * FROM channels WHERE sessionId = :sessionId AND revokedAt IS NULL LIMIT 1")
  suspend fun bySession(sessionId: String): ChannelEntity?

  @Query("SELECT * FROM channels WHERE channelId = :channelId AND revokedAt IS NULL LIMIT 1")
  suspend fun byId(channelId: String): ChannelEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(channel: ChannelEntity)

  @Query("UPDATE channels SET state = :state, acceptedRelaysJson = :acceptedRelaysJson, attemptCount = :attemptCount, nextAttemptAt = :nextAttemptAt, lastErrorCode = :lastErrorCode, updatedAt = :updatedAt WHERE channelId = :id")
  suspend fun updatePairingState(
    id: String,
    state: String,
    acceptedRelaysJson: String?,
    attemptCount: Int,
    nextAttemptAt: Long?,
    lastErrorCode: String?,
    updatedAt: Long,
  )

  @Query("UPDATE channels SET state = 'active', nextAttemptAt = NULL, lastErrorCode = NULL, updatedAt = :updatedAt WHERE channelId = :id AND protocolVersion = 2 AND state IN ('ack_validated','completion_pending')")
  suspend fun promoteAfterValidatedAck(id: String, updatedAt: Long): Int

  @Query("UPDATE channels SET state = 'revoked', revokedAt = :now, updatedAt = :now WHERE channelId = :id")
  suspend fun revoke(id: String, now: Long)

  @Query("UPDATE channels SET state = 'revoked', revokedAt = :now, updatedAt = :now WHERE channelId != :keepId AND revokedAt IS NULL AND state = 'active'")
  suspend fun revokeOtherActive(keepId: String, now: Long): Int

  @Query("SELECT * FROM channels WHERE revokedAt IS NULL AND protocolVersion = 2 AND state != 'active' AND pendingExpiresAt IS NOT NULL AND pendingExpiresAt <= :nowSecs")
  suspend fun expiredPending(nowSecs: Long): List<ChannelEntity>
}

@Dao
interface ChunkDao {
  @Query("SELECT COUNT(*) FROM (SELECT transferId FROM incoming_chunks UNION SELECT transferId FROM incoming_manifests)")
  suspend fun stagedTransferCount(): Int

  @Query("SELECT EXISTS(SELECT 1 FROM incoming_chunks WHERE transferId = :id UNION SELECT 1 FROM incoming_manifests WHERE transferId = :id)")
  suspend fun hasStagedTransfer(id: String): Boolean

  @Query("SELECT COALESCE(SUM(length(CAST(bytesB64 AS BLOB)) / 4 * 3 - CASE WHEN substr(bytesB64, -2) = '==' THEN 2 WHEN substr(bytesB64, -1) = '=' THEN 1 ELSE 0 END), 0) FROM incoming_chunks")
  suspend fun stagedBytes(): Long

  @Query("SELECT COALESCE(SUM(length(CAST(bytesB64 AS BLOB)) / 4 * 3 - CASE WHEN substr(bytesB64, -2) = '==' THEN 2 WHEN substr(bytesB64, -1) = '=' THEN 1 ELSE 0 END), 0) FROM incoming_chunks WHERE transferId = :id")
  suspend fun transferBytes(id: String): Long

  @Query("SELECT * FROM incoming_chunks WHERE transferId = :id AND `index` = :index LIMIT 1")
  suspend fun byIndex(id: String, index: Int): ChunkEntity?

  @Query("SELECT * FROM incoming_chunks WHERE transferId = :id LIMIT 1")
  suspend fun first(id: String): ChunkEntity?

  @Query("SELECT COUNT(*) FROM incoming_chunks WHERE transferId = :id")
  suspend fun count(id: String): Int
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(chunk: ChunkEntity)

  @Query("SELECT * FROM incoming_chunks WHERE transferId = :t ORDER BY `index`")
  suspend fun forTransfer(t: String): List<ChunkEntity>

  @Query("DELETE FROM incoming_chunks WHERE transferId = :t")
  suspend fun clearTransfer(t: String)

  @Query("DELETE FROM incoming_chunks WHERE expiresAt < :now")
  suspend fun purgeExpired(now: Long)
}

@Dao
interface ManifestDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(manifest: ManifestEntity): Long

  @Query("SELECT * FROM incoming_manifests WHERE transferId = :transferId LIMIT 1")
  suspend fun byTransfer(transferId: String): ManifestEntity?

  @Query("DELETE FROM incoming_manifests WHERE transferId = :transferId")
  suspend fun clearTransfer(transferId: String)

  @Query("DELETE FROM incoming_manifests WHERE expiresAt <= :nowSecs")
  suspend fun purgeExpired(nowSecs: Long)
}

@Dao
interface AckIntentDao {
  @Query("SELECT * FROM ack_intents WHERE channelId = :channelId AND transferId = :transferId LIMIT 1")
  suspend fun byTransfer(channelId: String, transferId: String): AckIntentEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(intent: AckIntentEntity)

  @Update
  suspend fun update(intent: AckIntentEntity)

  @Query("SELECT * FROM ack_intents WHERE channelId = :channelId AND completedAt IS NULL AND failedAt IS NULL AND expiresAt > :nowSecs AND (nextAttemptAt IS NULL OR nextAttemptAt <= :nowMillis) ORDER BY receivedAt LIMIT :limit")
  suspend fun due(channelId: String, nowSecs: Long, nowMillis: Long, limit: Int): List<AckIntentEntity>

  @Query("SELECT COUNT(*) FROM ack_intents WHERE channelId = :channelId AND completedAt IS NULL AND failedAt IS NULL AND expiresAt > :nowSecs")
  suspend fun pendingCount(channelId: String, nowSecs: Long): Int

  @Query("DELETE FROM ack_intents WHERE expiresAt <= :nowSecs")
  suspend fun purgeExpired(nowSecs: Long)
}

@Dao
interface ProcessedEventDao {
  @Query("SELECT COUNT(*) FROM processed_events")
  suspend fun count(): Int
  @Query("SELECT * FROM processed_events WHERE eventId = :eventId LIMIT 1")
  suspend fun byId(eventId: String): ProcessedEventEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(event: ProcessedEventEntity): Long

  @Query("DELETE FROM processed_events WHERE expiresAt <= :nowSecs")
  suspend fun purgeExpired(nowSecs: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE documents ADD COLUMN list TEXT NOT NULL DEFAULT 'inbox'")
    // `finished` meant read: archive is anything read or discarded.
    db.execSQL("UPDATE documents SET list = 'archived' WHERE state IN ('archived', 'finished')")
  }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE channels ADD COLUMN protocolVersion INTEGER NOT NULL DEFAULT 2")
    db.execSQL("ALTER TABLE channels ADD COLUMN state TEXT NOT NULL DEFAULT 'pending_response'")
    db.execSQL("ALTER TABLE channels ADD COLUMN sessionId TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN pairingPubkey TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN pairingNonce TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN pairingRequestJson TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN relaySetDigest TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN pendingExpiresAt INTEGER")
    db.execSQL("ALTER TABLE channels ADD COLUMN acceptedRelaysJson TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE channels ADD COLUMN nextAttemptAt INTEGER")
    db.execSQL("ALTER TABLE channels ADD COLUMN lastErrorCode TEXT")
    db.execSQL("ALTER TABLE channels ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    // v1 never completed authenticated two-endpoint pairing. Preserve the row
    // for explicit cleanup, but never silently promote it into the v2 trust set.
    db.execSQL("UPDATE channels SET protocolVersion = 1, state = 'legacy_repair_required', updatedAt = createdAt")
  }
}

/**
 * Version 3 was installed on the physical TCL during pairing diagnostics before
 * durable manifest assembly existed. Keep that upgrade path exact, then add the
 * transfer schema independently so an already-migrated device can advance
 * without clearing documents, settings, or pairing evidence.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // Partial chunks are disposable and cannot be authenticated under reader/2.
    db.execSQL("DROP TABLE IF EXISTS incoming_chunks")
    db.execSQL(
      """CREATE TABLE incoming_chunks (
        transferId TEXT NOT NULL,
        `index` INTEGER NOT NULL,
        manifestId TEXT NOT NULL,
        documentId TEXT NOT NULL,
        compressedSha256 TEXT NOT NULL,
        count INTEGER NOT NULL,
        bytesB64 TEXT NOT NULL,
        receivedAt INTEGER NOT NULL,
        expiresAt INTEGER NOT NULL,
        PRIMARY KEY(transferId, `index`)
      )""".trimIndent(),
    )
    db.execSQL(
      """CREATE TABLE incoming_manifests (
        transferId TEXT NOT NULL,
        manifestId TEXT NOT NULL,
        documentId TEXT NOT NULL,
        title TEXT NOT NULL,
        sourceType TEXT NOT NULL,
        sourceName TEXT,
        sourceUrl TEXT,
        author TEXT,
        publishedAt INTEGER,
        capturedAt INTEGER NOT NULL,
        language TEXT,
        mime TEXT NOT NULL,
        compression TEXT NOT NULL,
        wordCount INTEGER NOT NULL,
        uncompressedBytes INTEGER NOT NULL,
        compressedBytes INTEGER NOT NULL,
        compressedSha256 TEXT NOT NULL,
        documentSha256 TEXT NOT NULL,
        chunkCount INTEGER NOT NULL,
        senderDevicePubkey TEXT NOT NULL,
        recipientChannelPubkey TEXT NOT NULL,
        expiresAt INTEGER NOT NULL,
        receivedAt INTEGER NOT NULL,
        PRIMARY KEY(transferId)
      )""".trimIndent(),
    )
  }
}

/**
 * Persist receiver ACK intent and the wrapper IDs that triggered it. This
 * closes the commit-before-ACK crash window and prevents a retained relay
 * wrapper from causing another ACK batch on every rolling-window catch-up.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS ack_intents (
        channelId TEXT NOT NULL,
        transferId TEXT NOT NULL,
        manifestId TEXT NOT NULL,
        documentId TEXT NOT NULL,
        recipientDevicePubkey TEXT NOT NULL,
        status TEXT NOT NULL,
        receivedAt INTEGER NOT NULL,
        expiresAt INTEGER NOT NULL,
        acceptedRelaysJson TEXT NOT NULL,
        attemptCount INTEGER NOT NULL,
        nextAttemptAt INTEGER,
        completedAt INTEGER,
        failedAt INTEGER,
        lastErrorCode TEXT,
        PRIMARY KEY(channelId, transferId)
      )""".trimIndent(),
    )
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS processed_events (
        eventId TEXT NOT NULL,
        channelId TEXT NOT NULL,
        transferId TEXT NOT NULL,
        processedAt INTEGER NOT NULL,
        expiresAt INTEGER NOT NULL,
        PRIMARY KEY(eventId)
      )""".trimIndent(),
    )
  }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE ack_intents ADD COLUMN refreshCount INTEGER NOT NULL DEFAULT 0")
  }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS document_content (
      documentId TEXT NOT NULL, part INTEGER NOT NULL, text TEXT NOT NULL, startUtf16 INTEGER NOT NULL, endUtf16 INTEGER NOT NULL,
      PRIMARY KEY(documentId, part),
      FOREIGN KEY(documentId) REFERENCES documents(documentId) ON UPDATE NO ACTION ON DELETE CASCADE
    )""".trimIndent())
    // SQLite slices inside the database: no old oversized body crosses a CursorWindow.
    // SQLite substr counts Unicode code points; concatenating the parts is byte-exact.
    db.query("SELECT documentId, length(canonicalMarkdown) FROM documents").use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.getString(0)
        val length = cursor.getLong(1)
        var start = 0L
        var part = 0
        while (start < length) {
          db.execSQL("INSERT INTO document_content(documentId, part, text, startUtf16, endUtf16) SELECT documentId, ?, substr(canonicalMarkdown, ?, ?), 0, 0 FROM documents WHERE documentId = ?",
            arrayOf(part++, start + 1, CONTENT_PART_CHARS, id))
          start += CONTENT_PART_CHARS
        }
        var utf16 = 0
        db.query("SELECT part, text FROM document_content WHERE documentId = ? ORDER BY part", arrayOf(id)).use { parts ->
          while (parts.moveToNext()) {
            val end = utf16 + parts.getString(1).length
            db.execSQL("UPDATE document_content SET startUtf16 = ?, endUtf16 = ? WHERE documentId = ? AND part = ?", arrayOf(utf16, end, id, parts.getInt(0)))
            utf16 = end
          }
        }
      }
    }
    db.execSQL("UPDATE documents SET canonicalMarkdown = ''")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_list_createdAt_documentId ON documents(list, createdAt, documentId)")
  }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TABLE IF NOT EXISTS highlights (
      id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, quote TEXT NOT NULL,
      sourceTitle TEXT NOT NULL, sourceUrl TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
      startBlockId TEXT NOT NULL, startOffset INTEGER NOT NULL, endBlockId TEXT NOT NULL, endOffset INTEGER NOT NULL,
      projectionVersion INTEGER NOT NULL, canonicalStart INTEGER NOT NULL, canonicalEnd INTEGER NOT NULL,
      prefixContext TEXT NOT NULL, suffixContext TEXT NOT NULL, color TEXT NOT NULL, important INTEGER NOT NULL,
      reviewCount INTEGER NOT NULL, lastReviewedAt INTEGER, revision INTEGER NOT NULL
    )""".trimIndent())
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_highlights_documentId_projectionVersion_startBlockId_startOffset_endBlockId_endOffset ON highlights(documentId, projectionVersion, startBlockId, startOffset, endBlockId, endOffset)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_createdAt_id ON highlights(createdAt, id)")
    db.execSQL("CREATE TABLE IF NOT EXISTS review_state (part INTEGER NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
    db.execSQL("""CREATE TABLE IF NOT EXISTS sync_health (
      id INTEGER NOT NULL PRIMARY KEY, checkedAt INTEGER NOT NULL, successfulAt INTEGER,
      healthyRelays INTEGER NOT NULL, failedRelays INTEGER NOT NULL, pendingTransfers INTEGER NOT NULL,
      pendingReceipts INTEGER NOT NULL, error TEXT
    )""".trimIndent())
  }
}

@Database(
  entities = [
    DocumentEntity::class,
    DocumentContentEntity::class,
    ChannelEntity::class,
    ChunkEntity::class,
    ManifestEntity::class,
    AckIntentEntity::class,
    ProcessedEventEntity::class,
    HighlightEntity::class,
    ReviewStatePartEntity::class,
    SyncHealthEntity::class,
  ],
  version = 8,
  exportSchema = true,
)
abstract class ReaderDb : RoomDatabase() {
  abstract fun documents(): DocumentDao
  abstract fun channels(): ChannelDao
  abstract fun chunks(): ChunkDao
  abstract fun manifests(): ManifestDao
  abstract fun ackIntents(): AckIntentDao
  abstract fun processedEvents(): ProcessedEventDao
  abstract fun highlights(): HighlightDao
  abstract fun review(): ReviewDao
  abstract fun syncHealth(): SyncHealthDao

  companion object {
    @Volatile
    private var instance: ReaderDb? = null
    fun get(ctx: Context): ReaderDb = instance ?: synchronized(this) {
      instance ?: Room.databaseBuilder(ctx.applicationContext, ReaderDb::class.java, "reader.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        .build()
        .also { instance = it }
    }
  }
}
