package com.reader.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Offline full-text search over the local library.
 *
 * The FTS5 table is intentionally NOT a Room @Entity: Room cannot express
 * per-column UNINDEXED, and the table must exist with one exact DDL string.
 * [SearchSchema.TABLE_DDL] is the single source of truth, executed both by
 * [MIGRATION_11_12] and by the fresh-install database callback. Compile-time
 * checking is traded for schema exactness; every query below is pinned by
 * instrumented tests instead. FTS reads never return bodies (CursorWindow):
 * only metadata, snippets and offsets.
 */
object SearchSchema {
  const val TABLE_DDL =
    "CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING FTS5(" +
      "title, body, documentId UNINDEXED, " +
      "tokenize='unicode61 \"remove_diacritics=1\"')"
  const val DELETE_TRIGGER_DDL =
    "CREATE TRIGGER IF NOT EXISTS trg_documents_fts_del AFTER DELETE ON documents " +
      "BEGIN DELETE FROM documents_fts WHERE documentId=old.documentId; END"
}

/**
 * Device capability probe. Some OEM builds (verified: TCL T807D, stock
 * Android 16) ship framework SQLite WITHOUT the FTS5 module — table creation
 * then throws `no such module: FTS5` and would crash database open. Every
 * FTS touchpoint goes through [ensureTable]/[supported] so those devices get
 * the LIKE+Kotlin fallback path instead of a crash. The flag is device-level
 * (the module is either compiled in or not) and latches false permanently.
 */
object SearchFtsSupport {
  @Volatile var supported: Boolean = true
    private set

  /** Best-effort table creation. Returns false when FTS5 is unavailable. */
  fun ensureTable(db: androidx.sqlite.db.SupportSQLiteDatabase): Boolean {
    if (!supported) return false
    return try {
      db.execSQL(SearchSchema.TABLE_DDL)
      db.execSQL(SearchSchema.DELETE_TRIGGER_DDL)
      true
    } catch (e: android.database.SQLException) {
      if (e.message?.contains("no such module", ignoreCase = true) == true) supported = false
      false
    }
  }

  @androidx.annotation.VisibleForTesting
  fun resetForTests() {
    supported = true
  }

  @androidx.annotation.VisibleForTesting
  fun setSupportedForTests(value: Boolean) {
    supported = value
  }
}
/** One ranked search hit. Field names must match the SELECT aliases below. */
data class SearchRow(
  val documentId: String,
  val title: String,
  val sourceType: String,
  val sourceName: String?,
  val sourceUrl: String?,
  val wordCount: Int,
  val list: String,
  val progressFraction: Float,
  val createdAt: Long,
  val rank: Double,
  val titleHit: Boolean,
  val bodySnippet: String?,
)

@Dao
interface SearchDao {
  @RawQuery(observedEntities = [DocumentEntity::class, DocumentContentEntity::class, DocumentLabelEntity::class, LabelEntity::class])
  fun searchFts(query: SupportSQLiteQuery): Flow<List<SearchRow>>

  @RawQuery(observedEntities = [DocumentEntity::class])
  suspend fun ftsOffsets(query: SupportSQLiteQuery): List<String>

  @RawQuery(observedEntities = [DocumentEntity::class])
  suspend fun ftsBodies(query: SupportSQLiteQuery): List<String>

  @RawQuery(observedEntities = [DocumentEntity::class])
  suspend fun execFts(query: SupportSQLiteQuery): Int

  /** Second-tier recall net (German compounds, CJK phrases): unindexed scan. */
  @Query(
    "SELECT DISTINCT d.documentId AS documentId, d.title AS title, d.sourceType AS sourceType, " +
      "d.sourceName AS sourceName, d.sourceUrl AS sourceUrl, d.wordCount AS wordCount, d.list AS list, " +
      "d.progressFraction AS progressFraction, d.createdAt AS createdAt, " +
      "1.0 AS rank, (d.title LIKE :pattern ESCAPE '\\') AS titleHit, NULL AS bodySnippet " +
      "FROM documents d LEFT JOIN document_content c ON c.documentId = d.documentId " +
      "WHERE (d.title LIKE :pattern ESCAPE '\\' OR c.text LIKE :pattern ESCAPE '\\') " +
      "AND (:list IS NULL OR d.list = :list) " +
      "ORDER BY titleHit DESC, d.createdAt DESC LIMIT :limit",
  )
  suspend fun likeSupplement(pattern: String, list: String?, limit: Int): List<SearchRow>

  companion object {
    fun ftsSearchQuery(ftsQuery: String, likePattern: String, list: String?, limit: Int): SimpleSQLiteQuery {
      val sql = buildString {
        append(
          "SELECT d.documentId AS documentId, d.title AS title, d.sourceType AS sourceType, " +
            "d.sourceName AS sourceName, d.sourceUrl AS sourceUrl, d.wordCount AS wordCount, d.list AS list, " +
            "d.progressFraction AS progressFraction, d.createdAt AS createdAt, " +
            "bm25(documents_fts, 10.0, 1.0) AS rank, " +
            "(d.title LIKE ? ESCAPE '\\') AS titleHit, " +
            "snippet(documents_fts, 1, '<b>', '</b>', '…', 24) AS bodySnippet " +
            "FROM documents_fts JOIN documents d ON d.documentId = documents_fts.documentId " +
            "WHERE documents_fts MATCH ? ",
        )
        if (list != null) append("AND d.list = ? ")
        append(
          "ORDER BY titleHit DESC, bm25(documents_fts, 10.0, 1.0) ASC, d.createdAt DESC, d.documentId ASC " +
            "LIMIT ?",
        )
      }
      val args = if (list != null) arrayOf<Any?>(likePattern, ftsQuery, list, limit)
      else arrayOf(likePattern, ftsQuery, limit)
      return SimpleSQLiteQuery(sql, args)
    }

    fun offsetsQuery(documentId: String, ftsQuery: String): SimpleSQLiteQuery =
      SimpleSQLiteQuery(
        "SELECT offsets(documents_fts) FROM documents_fts " +
          "WHERE documents_fts MATCH ? AND documentId = ?",
        arrayOf<Any?>(ftsQuery, documentId),
      )

    fun bodyQuery(documentId: String): SimpleSQLiteQuery =
      SimpleSQLiteQuery(
        "SELECT body FROM documents_fts WHERE documentId = ?",
        arrayOf<Any?>(documentId),
      )
  }
}
