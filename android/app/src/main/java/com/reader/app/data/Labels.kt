package com.reader.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Topic facets — explicitly NOT folders or lists. A label with no articles
 * simply stops existing (no tombstones, no empty-label persistence).
 */
object LabelNorm {
  const val MAX_LEN = 50
  const val MAX_PER_DOC = 20

  /** Canonical lookup key, or null when the input cannot be a label. */
  fun normalize(raw: String): String? {
    var s = raw.trim().removePrefix("#").trim()
    if (s.isEmpty() || s.length > MAX_LEN) return null
    s = s.replace(Regex("[\\s\\p{Z}]+"), " ")
    if (s.isEmpty()) return null
    if (s.any { it.isISOControl() || it in ",;\"\\" }) return null
    return s.lowercase(java.util.Locale.ROOT)
  }

  /** Display form preserves the creator's casing. */
  fun display(raw: String): String? {
    if (normalize(raw) == null) return null
    return raw.trim().removePrefix("#").trim().replace(Regex("[\\s\\p{Z}]+"), " ")
  }
}

@Entity(
  tableName = "labels",
  indices = [Index(value = ["normalized"], unique = true)],
)
data class LabelEntity(
  @androidx.room.PrimaryKey val labelId: String,
  val name: String,
  val normalized: String,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "document_labels",
  primaryKeys = ["documentId", "labelId"],
  foreignKeys = [
    ForeignKey(entity = DocumentEntity::class, parentColumns = ["documentId"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LabelEntity::class, parentColumns = ["labelId"], childColumns = ["labelId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index(value = ["labelId", "documentId"]), Index(value = ["documentId"])],
)
data class DocumentLabelEntity(
  val documentId: String,
  val labelId: String,
  val createdAt: Long,
)

/** One row per local day with finished-article credit. Ephemeral encouragement, never exported. */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
  @androidx.room.PrimaryKey val day: String, // YYYY-MM-DD, device zone
  val minutes: Int,
  val finished: Int,
)

data class LabelCount(val labelId: String, val name: String, val normalized: String, val count: Int)

data class DocLabelPair(val documentId: String, val labelId: String)

@Dao
interface LabelDao {
  @Query("SELECT l.labelId AS labelId, l.name AS name, l.normalized AS normalized, COUNT(a.documentId) AS count FROM labels l JOIN document_labels a ON a.labelId = l.labelId GROUP BY l.labelId ORDER BY count DESC, name COLLATE NOCASE")
  fun observeLabels(): Flow<List<LabelCount>>
  @Query("SELECT l.name FROM labels l JOIN document_labels a ON a.labelId = l.labelId WHERE a.documentId = :documentId ORDER BY l.name COLLATE NOCASE")
  suspend fun labelsFor(documentId: String): List<String>

  @Query("SELECT labelId FROM labels WHERE normalized = :normalized LIMIT 1")
  suspend fun idForNormalized(normalized: String): String?

  @Query("SELECT documentId, labelId FROM document_labels")
  suspend fun allPairs(): List<DocLabelPair>

  @Query("SELECT labelId, name, normalized, createdAt, updatedAt FROM labels ORDER BY name COLLATE NOCASE")
  suspend fun allLabels(): List<LabelEntity>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertLabel(label: LabelEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun assign(row: DocumentLabelEntity): Long

  @Query("DELETE FROM document_labels WHERE documentId = :documentId AND labelId = :labelId")
  suspend fun unassign(documentId: String, labelId: String)

  @Query("UPDATE labels SET name = :name, normalized = :normalized, updatedAt = :now WHERE labelId = :labelId")
  suspend fun rename(labelId: String, name: String, normalized: String, now: Long)

  @Query("DELETE FROM labels WHERE labelId = :labelId")
  suspend fun deleteLabel(labelId: String)

  @Transaction
  open suspend fun assignNorm(documentId: String, rawLabel: String, now: Long): Boolean {
    val norm = LabelNorm.normalize(rawLabel) ?: return false
    val display = LabelNorm.display(rawLabel) ?: return false
    var id = idForNormalized(norm)
    if (id == null) {
      id = java.util.UUID.randomUUID().toString()
      insertLabel(LabelEntity(id, display, norm, now, now))
      id = idForNormalized(norm) ?: return false
    }
    val existing = labelsFor(documentId)
    if (existing.size >= LabelNorm.MAX_PER_DOC && existing.none { it.equals(display, ignoreCase = true) }) return false
    assign(DocumentLabelEntity(documentId, id, now))
    return true
  }

  @Transaction
  open suspend fun mergeInto(fromLabelId: String, toLabelId: String) {
    if (fromLabelId == toLabelId) return
    mergeRows(fromLabelId, toLabelId)
    deleteLabel(fromLabelId)
  }

  @Query("INSERT OR IGNORE INTO document_labels(documentId, labelId, createdAt) SELECT documentId, :toId, :now FROM document_labels WHERE labelId = :fromId")
  suspend fun mergeRows(fromId: String, toId: String, now: Long = System.currentTimeMillis()): Long
}

@Dao
interface ReadingStatsDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertDay(day: ReadingDayEntity): Long

  @Query("UPDATE reading_days SET minutes = minutes + :minutes, finished = finished + :finished WHERE day = :day")
  suspend fun addToDay(day: String, minutes: Int, finished: Int)

  @Query("SELECT COALESCE(SUM(minutes), 0) FROM reading_days WHERE day >= :sinceDay")
  suspend fun minutesSince(sinceDay: String): Int

  @Query("SELECT COALESCE(SUM(finished), 0) FROM reading_days WHERE day >= :sinceDay")
  suspend fun finishedSince(sinceDay: String): Int

  @Query("SELECT day FROM reading_days ORDER BY day DESC LIMIT :limit")
  suspend fun recentDays(limit: Int): List<String>

  @Query("SELECT * FROM reading_days ORDER BY day DESC LIMIT :limit")
  suspend fun recentDayStats(limit: Int): List<ReadingDayEntity>

  @Query("DELETE FROM reading_days WHERE day < :keepSinceDay")
  suspend fun pruneOlderThan(keepSinceDay: String): Int

  /**
   * Finish credit: sticky finishedAt plus one full-article minute credit,
   * counted once. Returns true when this call actually finished the article.
   */
  @Transaction
  open suspend fun recordFinish(documentId: String, minutes: Int, day: String, now: Long): Boolean {
    val marked = markFinished(documentId, now)
    if (marked != 1) return false
    insertDay(ReadingDayEntity(day, 0, 0))
    addToDay(day, minutes, 1)
    return true
  }

  @Query("UPDATE documents SET finishedAt = :now, updatedAt = :now WHERE documentId = :id AND finishedAt IS NULL")
  suspend fun markFinished(id: String, now: Long): Int

  @Query("SELECT wordCount FROM documents WHERE documentId = :id")
  suspend fun wordCountOf(id: String): Int?

  @Query("SELECT COUNT(*) FROM documents WHERE finishedAt IS NOT NULL")
  suspend fun finishedTotal(): Int
}
