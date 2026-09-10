package com.reader.app.data

import android.content.Context
import android.content.SharedPreferences
import com.reader.app.core.SearchQuery
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield

enum class SearchScope { ALL, TITLES, THIS_LIST }

data class SearchResults(val rows: List<SearchRow>, val tier1Count: Int)

/** Resolved “open at match” target. All offsets are rendered-text UTF-16. */
data class SearchHit(
  val documentId: String,
  val part: Int,
  val cursor: SemanticCursor,
  val endRendered: Int,
  val term: String,
)

/** Local-only recent searches. Never leaves the device. */
class RecentsStore(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences("reader_search", Context.MODE_PRIVATE)

  fun recents(): List<String> =
    prefs.getString(KEY_RECENTS, "").orEmpty().split(UNIT_SEP).filter { it.isNotBlank() }.take(MAX_RECENTS)

  fun record(raw: String) {
    val q = raw.trim().take(100).replace("\u001F", "")
    if (q.length < 2) return
    val rest = recents().filterNot { it.equals(q, ignoreCase = true) }.take(MAX_RECENTS - 1)
    prefs.edit().putString(KEY_RECENTS, (listOf(q) + rest).joinToString(UNIT_SEP.toString())).apply()
  }

  fun remove(raw: String) {
    prefs.edit().putString(KEY_RECENTS, recents().filterNot { it == raw }.joinToString(UNIT_SEP.toString())).apply()
  }

  fun clear() {
    prefs.edit().remove(KEY_RECENTS).apply()
  }

  internal fun backfillDone(): Boolean = prefs.getBoolean(KEY_BACKFILL, false)
  internal fun markBackfillDone() {
    prefs.edit().putBoolean(KEY_BACKFILL, true).apply()
  }

  companion object {
    const val MAX_RECENTS = 8
    private const val KEY_RECENTS = "recent_queries"
    private const val KEY_BACKFILL = "fts_backfill_done"
    private const val UNIT_SEP = "\u001F"
  }
}

class SearchRepository(
  private val db: ReaderDb,
  private val articles: ArticleRepository,
  private val recents: RecentsStore,
) {
  /** One-time normalized backfill for pre-v12 documents. Idempotent. */
  suspend fun ensureIndexed() {
    if (!SearchFtsSupport.ensureTable(db.openHelper.writableDatabase)) return
    if (recents.backfillDone()) return
    val metas = db.documents().allIdTitles()
    var n = 0
    for (meta in metas) {
      val length = db.documents().contentLength(meta.documentId)
      val canonical = if (length > 0) db.documents().contentRange(meta.documentId, 0, length) else ""
      db.search().execFts(
        androidx.sqlite.db.SimpleSQLiteQuery(
          "DELETE FROM documents_fts WHERE documentId = ?",
          arrayOf<Any?>(meta.documentId),
        ),
      )
      db.search().execFts(
        androidx.sqlite.db.SimpleSQLiteQuery(
          "INSERT INTO documents_fts(documentId, title, body) VALUES (?, ?, ?)",
          arrayOf<Any?>(meta.documentId, SearchQuery.ftsNormalize(meta.title), SearchQuery.ftsNormalize(canonical)),
        ),
      )
      if (++n % 25 == 0) yield()
    }
    recents.markBackfillDone()
  }

  fun observeResults(queryText: String, scope: SearchScope, list: String?): Flow<SearchResults> = flow {
    val tokens = SearchQuery.surfaceTokens(queryText)
    if (tokens.isEmpty()) {
      emit(SearchResults(emptyList(), 0))
      return@flow
    }
    ensureIndexed()
    val listFilter = if (scope == SearchScope.THIS_LIST) list else null
    if (SearchFtsSupport.supported) {
      val fts = SearchQuery.buildFtsQuery(queryText)
      if (fts == null) {
        emit(SearchResults(emptyList(), 0))
        return@flow
      }
      val match = if (scope == SearchScope.TITLES) titleOnlyMatch(fts) else fts
      val tier1 = db.search().searchFts(SearchDao.ftsSearchQuery(match, SearchQuery.likePattern(queryText), listFilter, 50)).first()
      val seen = tier1.mapTo(HashSet()) { it.documentId }
      val rows = tier1.toMutableList()
      if (SearchQuery.wantsSupplement(queryText, tier1.size)) {
        db.search().likeSupplement(SearchQuery.likePattern(queryText), listFilter, 20)
          .filterNotTo(rows) { it.documentId in seen }
      }
      emit(SearchResults(rows, tier1.size))
    } else {
      // No FTS5 on this device: LIKE variants with Kotlin title boost and
      // computed snippets. Same row shape, same ordering contract. Exact
      // spellings rank before umlaut variants (round index breaks ties).
      val seen = HashSet<String>()
      val merged = mutableListOf<Pair<SearchRow, Int>>()
      for ((round, spelling) in SearchQuery.umlautVariants(queryText).withIndex()) {
        if (merged.size >= 30) break
        for (row in db.search().likeSupplement(SearchQuery.likePattern(spelling), listFilter, 20)) {
          if (seen.add(row.documentId)) merged += row to round
        }
      }
      if (scope == SearchScope.TITLES) merged.retainAll { (row, _) -> row.titleHit }
      val foldedQuery = SearchQuery.foldForMatch(tokens.joinToString(" "))
      val ordered = merged.sortedWith(
        compareByDescending<Pair<SearchRow, Int>> { (row, _) -> row.titleHit }
          .thenBy { (_, round) -> round }
          .thenByDescending { (row, _) -> titleFold(row.title).contains(foldedQuery) }
          .thenByDescending { (row, _) -> row.createdAt },
      ).map { (row, _) -> row }
      // Snippets cost a full body read each: compute for the first 20 rows
      // only (cancellable via mapLatest); the rest still list correctly.
      // Title hits get snippets too when the body also matches.
      val withSnippets = ordered.take(50).mapIndexed { i, row ->
        if (row.bodySnippet != null || i >= 20) row
        else row.copy(bodySnippet = snippetForRow(row.documentId, tokens))
      }
      emit(SearchResults(withSnippets, withSnippets.size))
    }
  }.flowOn(Dispatchers.IO)

  /** Title fold for the fallback path (mirrors index-time folding). */
  private fun titleFold(title: String): String =
    SearchQuery.ftsNormalize(title).lowercase()

  /** Kotlin-computed snippet for the fallback path (first 20 rows share it). */
  private suspend fun snippetForRow(documentId: String, tokens: List<String>): String? {
    return try {
      val length = db.documents().contentLength(documentId)
      if (length <= 0 || length > 400_000) return null
      val canonical = db.documents().contentRange(documentId, 0, length)
      val term = tokens.maxByOrNull { token ->
        if (canonical.contains(token, ignoreCase = token.any { it.isLetter() && it < '\u0080' })) token.length else -1
      } ?: return null
      SearchQuery.snippetFor(canonical, term)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Resolve a document hit to a rendered reader position. Returns null when
   * anything is missing — callers open at the article start instead.
   */
  suspend fun matchOffset(documentId: String, queryText: String): SearchHit? {
    val terms = com.reader.app.core.SearchTerms.parse(queryText)
    for (term in terms) {
      val hit = ArticleNavigation(articles, db).find(documentId, term).firstOrNull() ?: continue
      return SearchHit(documentId, hit.part, hit.cursor, hit.end ?: 0, term)
    }
    return null
  }

  /** Shared tail: canonical offset + surface term → rendered cursor. */
  private suspend fun resolveCursor(documentId: String, term: String, canonicalIdx: Int): SearchHit? {
    val length = db.documents().contentLength(documentId)
    if (length <= 0) return null
    val canonical = db.documents().contentRange(documentId, 0, length)
    val idx = canonical.indexOf(term, (canonicalIdx - 512).coerceAtLeast(0)).takeIf { it >= 0 }
      ?: canonicalIdx.coerceIn(0, canonical.length)
    val index = articles.index(documentId)
    if (index.sections.isEmpty()) return null
    val part = index.sections.indexOfFirst { idx in it.startUtf16..it.endUtf16 }
      .takeIf { it >= 0 } ?: index.sections.indexOfLast { it.startUtf16 <= idx }.coerceAtLeast(0)
    val prepared = articles.section(documentId, part)
    val projection = prepared.projection
    val renderedEst = ((idx.toDouble() / length.coerceAtLeast(1)) * projection.text.length).toInt()
      .coerceIn(0, projection.text.length)
    val renderedIdx = projection.text.indexOf(term, (renderedEst - 512).coerceAtLeast(0)).takeIf { it >= 0 }
      ?: renderedEst
    val cursor = projection.cursor(documentId, renderedIdx)
    return SearchHit(documentId, part, cursor, (renderedIdx + term.length).coerceAtMost(projection.text.length), term)
  }

  private suspend fun ftsBodyFallback(documentId: String): String? {
    val length = db.documents().contentLength(documentId)
    if (length <= 0) return null
    return SearchQuery.ftsNormalize(db.documents().contentRange(documentId, 0, length))
  }

  private data class OffsetHit(val col: Int, val byteOffset: Int, val byteLength: Int)

  private fun parseOffsets(raw: String): List<OffsetHit> {
    val nums = raw.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    val out = mutableListOf<OffsetHit>()
    var i = 0
    var parsed = 0
    while (i + 3 < nums.size && parsed < 128) {
      out += OffsetHit(nums[i], nums[i + 2], nums[i + 3])
      i += 4
      parsed++
    }
    return out
  }

  private fun utf8PrefixLength(ftsBody: String, byteOffset: Int): Int {
    val bytes = ftsBody.toByteArray(Charsets.UTF_8)
    val end = byteOffset.coerceIn(0, bytes.size)
    return bytes.decodeToString(0, end).length
  }

  private fun utf8Slice(ftsBody: String, byteOffset: Int, byteLength: Int): String {
    val bytes = ftsBody.toByteArray(Charsets.UTF_8)
    val start = byteOffset.coerceIn(0, bytes.size)
    val end = (byteOffset + byteLength).coerceIn(start, bytes.size)
    return bytes.decodeToString(start, end)
  }

  private fun titleOnlyMatch(fts: String): String {
    // Column filter per top-level phrase: {title} : "foo" AND {title} : "bar".
    // Split only on ANDs outside quotes (a phrase may itself contain AND).
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < fts.length) {
      val c = fts[i]
      if (c == '"') {
        // FTS escapes a literal quote as ""; only a lone quote toggles.
        if (inQuotes && i + 1 < fts.length && fts[i + 1] == '"') {
          current.append("\"\"")
          i += 2
          continue
        }
        inQuotes = !inQuotes
        current.append(c)
        i++
        continue
      }
      if (!inQuotes && fts.startsWith(" AND ", i)) {
        parts += current.toString()
        current.clear()
        i += 5
        continue
      }
      current.append(c)
      i++
    }
    parts += current.toString()
    return parts.filter { it.isNotBlank() }.joinToString(" AND ") { part ->
      val core = part.trim().removeSuffix("*")
      val star = if (part.trim().endsWith("*")) "*" else ""
      "{title} : $core$star"
    }
  }
}
