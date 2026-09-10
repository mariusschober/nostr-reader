package com.reader.app.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.reader.app.core.SearchTerms
import com.reader.app.core.SearchQuery
import com.reader.app.prefs.AgeFilter
import com.reader.app.ui.Triage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn

data class LibrarySearchRequest(
  val text: String, val shelf: String? = null, val titlesOnly: Boolean = false,
  val labels: Set<String> = emptySet(), val unlabeled: Boolean = false,
  val age: AgeFilter = AgeFilter.ANY, val pages: Int = 1,
)

enum class SearchStatus { IDLE, LOADING, READY, INVALID, FAILED }
data class LibrarySearchResult(val rows: List<SearchRow> = emptyList(), val hasMore: Boolean = false,
  val status: SearchStatus = SearchStatus.IDLE, val message: String? = null)

/**
 * One local substring contract on every Android SQLite build, including OEMs
 * without FTS5. Terms, phrases and all facets are applied BEFORE the page limit.
 * Full bodies stay inside SQLite; result cursors contain metadata only.
 */
class LibrarySearch(private val db: ReaderDb) {
  fun observe(request: LibrarySearchRequest): Flow<LibrarySearchResult> {
    val terms = SearchTerms.parse(request.text)
    val args = mutableListOf<Any?>()
    fun pattern(term: String) = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun condition(term: String, field: String): String {
      val variants = (if (term.length > 60) listOf(term) else SearchQuery.umlautVariants(term)).ifEmpty { listOf(term) }
      return variants.joinToString(" OR ", "(", ")") { args += pattern(it); "$field LIKE ? ESCAPE '\\'" }
    }
    val titleHit = terms.joinToString(" AND ") { condition(it, "d.title") }.ifBlank { "1" }
    val where = mutableListOf<String>()
    // Join ordered parts in SQLite so phrases crossing a storage boundary work.
    val body = "(SELECT group_concat(text, '') FROM (SELECT text FROM document_content WHERE documentId = d.documentId ORDER BY part))"
    terms.forEach { term ->
      val title = condition(term, "d.title")
      where += if (request.titlesOnly) title else "($title OR ${condition(term, body)})"
    }
    request.shelf?.let { where += "d.list = ?"; args += it }
    if (request.unlabeled) where += "NOT EXISTS (SELECT 1 FROM document_labels a WHERE a.documentId = d.documentId)"
    else request.labels.sorted().forEach { id ->
      where += "EXISTS (SELECT 1 FROM document_labels a WHERE a.documentId = d.documentId AND a.labelId = ?)"; args += id
    }
    val now = System.currentTimeMillis()
    when (request.age) {
      AgeFilter.ANY -> Unit
      AgeFilter.TODAY -> { where += "d.createdAt >= ?"; args += Triage.startOfTodayMillis(now) }
      AgeFilter.WEEK -> { where += "d.createdAt >= ?"; args += now - 7L * 86400000 }
      AgeFilter.MONTH -> { where += "d.createdAt >= ?"; args += now - 30L * 86400000 }
      AgeFilter.OLDER -> { where += "d.createdAt < ?"; args += now - 30L * 86400000 }
    }
    val limit = request.pages.coerceAtLeast(1) * 50
    args += limit + 1
    val sql = "SELECT d.documentId, d.title, d.sourceType, d.sourceName, d.sourceUrl, d.wordCount, d.list, " +
      "d.progressFraction, d.createdAt, 1.0 AS rank, ($titleHit) AS titleHit, NULL AS bodySnippet " +
      "FROM documents d WHERE ${where.joinToString(" AND ").ifBlank { "1" }} " +
      "ORDER BY titleHit DESC, d.createdAt DESC, d.documentId ASC LIMIT ?"
    return db.search().searchFts(SimpleSQLiteQuery(sql, args.toTypedArray())).map {
      LibrarySearchResult(it.take(limit), it.size > limit, SearchStatus.READY)
    }.flowOn(Dispatchers.IO)
  }
}
