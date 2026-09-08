package com.reader.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

const val MAX_QUOTE_BYTES = 128 * 1024

/** Exact occurrence anchors use rendered UTF-16 offsets, with separate canonical ranges. */
@Serializable
@Entity(tableName = "highlights", indices = [
  Index(value = ["documentId", "projectionVersion", "startBlockId", "startOffset", "endBlockId", "endOffset"], unique = true),
  Index(value = ["createdAt", "id"]),
])
data class HighlightEntity(
  @PrimaryKey val id: String,
  val documentId: String, val quote: String, val sourceTitle: String, val sourceUrl: String?,
  val createdAt: Long, val updatedAt: Long,
  val startBlockId: String, val startOffset: Int, val endBlockId: String, val endOffset: Int,
  val projectionVersion: Int, val canonicalStart: Int, val canonicalEnd: Int,
  val prefixContext: String, val suffixContext: String,
  val color: String = "YELLOW", val important: Boolean = false,
  val reviewCount: Int = 0, val lastReviewedAt: Long? = null, val revision: Long = 1,
)

data class HighlightSummary(
  val id: String, val documentId: String, val preview: String, val quoteLength: Int,
  val sourceTitle: String, val createdAt: Long, val color: String, val important: Boolean,
)
data class ReviewCandidate(val id: String, val important: Boolean)
data class HighlightMark(
  val id: String, val documentId: String, val color: String, val createdAt: Long,
  val startBlockId: String, val startOffset: Int, val endBlockId: String, val endOffset: Int,
  val projectionVersion: Int,
)

/** Review JSON is split into small rows too; a 10,000-quote session stays readable. */
@Entity(tableName = "review_state")
data class ReviewStatePartEntity(@PrimaryKey val part: Int, val json: String)

@Entity(tableName = "sync_health")
data class SyncHealthEntity(
  @PrimaryKey val id: Int = 1, val checkedAt: Long, val successfulAt: Long?,
  val healthyRelays: Int, val failedRelays: Int, val pendingTransfers: Int,
  val pendingReceipts: Int, val error: String?,
)

@Dao
interface HighlightDao {
  @Query("SELECT id, documentId, substr(quote, 1, 800) AS preview, length(quote) AS quoteLength, sourceTitle, createdAt, color, important FROM highlights ORDER BY createdAt DESC, id")
  fun observeSummaries(): Flow<List<HighlightSummary>>

  @Query("SELECT * FROM highlights WHERE documentId = :id ORDER BY createdAt, id")
  fun observeForDocument(id: String): Flow<List<HighlightEntity>>

  @Query("SELECT id, documentId, color, createdAt, startBlockId, startOffset, endBlockId, endOffset, projectionVersion FROM highlights WHERE documentId = :id ORDER BY createdAt, id")
  fun observeMarks(id: String): Flow<List<HighlightMark>>

  @Query("SELECT * FROM highlights WHERE id = :id")
  fun observeById(id: String): Flow<HighlightEntity?>

  @Query("SELECT * FROM highlights WHERE id = :id")
  suspend fun byId(id: String): HighlightEntity?

  @Query("SELECT * FROM highlights WHERE documentId = :documentId AND projectionVersion = :version AND startBlockId = :startBlock AND startOffset = :start AND endBlockId = :endBlock AND endOffset = :end LIMIT 1")
  suspend fun byRange(documentId: String, version: Int, startBlock: String, start: Int, endBlock: String, end: Int): HighlightEntity?

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(value: HighlightEntity)
  @Update suspend fun update(value: HighlightEntity)
  @Query("DELETE FROM highlights WHERE id = :id AND revision = :revision")
  suspend fun deleteAtRevision(id: String, revision: Long): Int
  @Query("SELECT id, important FROM highlights ORDER BY id")
  suspend fun reviewCandidates(): List<ReviewCandidate>
  @Query("SELECT * FROM highlights ORDER BY id LIMIT :limit OFFSET :offset")
  suspend fun exportPage(limit: Int, offset: Int): List<HighlightEntity>
  @Query("UPDATE highlights SET reviewCount = reviewCount + 1, lastReviewedAt = :now, updatedAt = :now, revision = revision + 1 WHERE id = :id")
  suspend fun recordReview(id: String, now: Long)
}

@Dao
interface ReviewDao {
  @Query("SELECT * FROM review_state ORDER BY part") suspend fun parts(): List<ReviewStatePartEntity>
  @Query("DELETE FROM review_state") suspend fun clear()
  @Insert suspend fun insert(parts: List<ReviewStatePartEntity>)
}

@Dao
interface SyncHealthDao {
  @Query("SELECT COUNT(*) FROM incoming_manifests") suspend fun pendingTransfers(): Int
  @Query("SELECT COUNT(*) FROM ack_intents WHERE completedAt IS NULL AND failedAt IS NULL") suspend fun pendingReceipts(): Int
  @Query("SELECT * FROM sync_health WHERE id = 1") fun observe(): Flow<SyncHealthEntity?>
  @Query("SELECT * FROM sync_health WHERE id = 1") suspend fun get(): SyncHealthEntity?
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: SyncHealthEntity)
}
