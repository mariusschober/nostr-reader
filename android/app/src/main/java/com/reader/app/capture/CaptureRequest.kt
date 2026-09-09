package com.reader.app.capture

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Durable URL-capture request, separate from [com.reader.app.data.DocumentEntity].
 *
 * Lifecycle: pending -> fetching -> completed | link_only | failed | cancelled.
 * `generation` invalidates stale workers: cancel/retry bump it, and the
 * commit transaction only succeeds when the generation still matches.
 */
@Entity(
  tableName = "capture_requests",
  indices = [
    Index(value = ["normalizedUrl"]),
    Index(value = ["state", "nextAttemptAt"]),
  ],
)
data class CaptureRequestEntity(
  @androidx.room.PrimaryKey val requestId: String,
  val originalUrl: String,
  val normalizedUrl: String,
  val subjectTitle: String?,
  val sourceType: String,
  val state: String,
  val documentId: String?,
  val resolvedUrl: String?,
  val title: String?,
  val errorCode: String?,
  val errorMessage: String?,
  val attemptCount: Int = 0,
  val nextAttemptAt: Long?,
  val generation: Int = 0,
  val createdAt: Long,
  val updatedAt: Long,
)

@Dao
interface CaptureRequestDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(request: CaptureRequestEntity)

  @Query("SELECT * FROM capture_requests WHERE requestId = :id LIMIT 1")
  suspend fun byId(id: String): CaptureRequestEntity?

  @Query("SELECT * FROM capture_requests WHERE normalizedUrl = :normalized AND state IN ('pending','fetching') ORDER BY createdAt LIMIT 1")
  suspend fun pendingByNormalized(normalized: String): CaptureRequestEntity?

  @Query("SELECT * FROM capture_requests WHERE state = 'pending' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :nowMillis) ORDER BY createdAt LIMIT :limit")
  suspend fun due(nowMillis: Long, limit: Int): List<CaptureRequestEntity>

  @Query("SELECT * FROM capture_requests WHERE state = 'pending' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :nowMillis) ORDER BY createdAt LIMIT :limit OFFSET :offset")
  suspend fun duePaged(nowMillis: Long, limit: Int, offset: Int): List<CaptureRequestEntity>

  @Query("SELECT * FROM capture_requests WHERE state = 'fetching' ORDER BY createdAt LIMIT :limit OFFSET :offset")
  suspend fun fetchingPaged(limit: Int, offset: Int): List<CaptureRequestEntity>

  @Query("SELECT * FROM capture_requests WHERE requestId = :id AND state IN ('pending','fetching') AND (nextAttemptAt IS NULL OR nextAttemptAt <= :nowMillis) LIMIT 1")
  suspend fun dueById(id: String, nowMillis: Long): CaptureRequestEntity?

  /** Atomic claim: exactly one worker wins the fetch for this request. */
  @Query("UPDATE capture_requests SET state = 'fetching', attemptCount = attemptCount + 1, nextAttemptAt = NULL, updatedAt = :now WHERE requestId = :id AND state IN ('pending','fetching')")
  suspend fun claimForFetch(id: String, now: Long): Int

  /** Atomic park: only while attempts remain (callers handle exhaustion). */
  @Query("UPDATE capture_requests SET state = 'pending', errorCode = :code, errorMessage = :message, nextAttemptAt = :nextAt, updatedAt = :now WHERE requestId = :id AND state IN ('pending','fetching') AND attemptCount < :maxAttempts")
  suspend fun parkForRetry(id: String, code: String, message: String, nextAt: Long, now: Long, maxAttempts: Int): Int

  /** Atomic cancel: bumps generation so a stale worker cannot commit. */
  @Query("UPDATE capture_requests SET state = 'cancelled', generation = generation + 1, updatedAt = :now WHERE requestId = :id AND state IN ('pending','fetching')")
  suspend fun cancelActive(id: String, now: Long): Int

  /** Atomic reopen for manual retry. */
  @Query("UPDATE capture_requests SET state = 'pending', errorCode = NULL, errorMessage = NULL, nextAttemptAt = NULL, generation = generation + 1, updatedAt = :now WHERE requestId = :id AND state IN ('failed','link_only','pending')")
  suspend fun reopenForRetry(id: String, now: Long): Int

  @Query("SELECT * FROM capture_requests WHERE state IN ('pending','fetching') ORDER BY createdAt LIMIT :limit")
  suspend fun active(limit: Int = 50): List<CaptureRequestEntity>

  @Update
  suspend fun update(request: CaptureRequestEntity)

  @Query("UPDATE capture_requests SET state = :state, documentId = :documentId, resolvedUrl = :resolvedUrl, title = :title, errorCode = NULL, errorMessage = NULL, updatedAt = :now WHERE requestId = :id AND generation = :generation AND state IN ('pending','fetching')")
  suspend fun completeIfGeneration(
    id: String, generation: Int, state: String, documentId: String?, resolvedUrl: String?, title: String?, now: Long,
  ): Int

  /** Terminal history is bounded: active requests are never touched. */
  @Query("DELETE FROM capture_requests WHERE state IN ('completed','link_only','failed','cancelled') AND updatedAt < :olderThanMillis")
  suspend fun purgeTerminalOlderThan(olderThanMillis: Long): Int

  @Query("DELETE FROM capture_requests WHERE requestId IN (SELECT requestId FROM capture_requests WHERE state IN ('completed','link_only','failed','cancelled') ORDER BY updatedAt ASC LIMIT :excess)")
  suspend fun purgeOldestTerminal(excess: Int): Int

  @Query("SELECT COUNT(*) FROM capture_requests WHERE state IN ('completed','link_only','failed','cancelled')")
  suspend fun terminalCount(): Int
}
