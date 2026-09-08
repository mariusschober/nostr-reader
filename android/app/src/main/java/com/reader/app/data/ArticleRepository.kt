package com.reader.app.data

import com.reader.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ArticleIndex(val documentId: String, val length: Int, val sections: List<ArticleSection>) {
  fun sectionFor(blockId: String?, fraction: Float = 0f): Int {
    val sourceStart = blockId?.substringBefore('/')?.removePrefix("p")?.toIntOrNull()
    if (sourceStart != null) return sections.indexOfFirst { it.startUtf16 == sourceStart }.coerceAtLeast(0)
    if (blockId?.startsWith("b") == true) return 0
    val offset = (length * fraction.coerceIn(0f, 1f)).toInt()
    return sections.indexOfLast { it.startUtf16 <= offset }.coerceAtLeast(0)
  }
}
data class PreparedSection(val index: ArticleIndex, val section: Int, val projection: RenderedProjection) {
  fun fraction(offset: Int): Float {
    val part = index.sections[section]
    val inPart = offset.toFloat() / projection.text.length.coerceAtLeast(1)
    return ((part.startUtf16 + (part.endUtf16 - part.startUtf16) * inPart) / index.length.coerceAtLeast(1)).coerceIn(0f, 1f)
  }
}

/** Immutable-document cache: at most three bounded sections and eight tiny indexes. */
class ArticleRepository(private val db: ReaderDb) {
  private val mutex = Mutex()
  private val indexes = LinkedHashMap<String, ArticleIndex>()
  private val sections = LinkedHashMap<String, PreparedSection>()

  suspend fun index(id: String): ArticleIndex = mutex.withLock {
    indexes[id]?.let { return@withLock it }
    val value = withContext(Dispatchers.IO) {
      check(db.documents().exists(id)) { "Source article is unavailable" }
      val planner = ArticleSectionPlanner()
      val count = db.documents().contentPartCount(id)
      for (part in 0 until count) planner.append(checkNotNull(db.documents().contentPart(id, part)) { "Article content is incomplete" })
      ArticleIndex(id, db.documents().contentLength(id), planner.finish())
    }
    if (indexes.size >= 8) indexes.remove(indexes.keys.first())
    indexes[id] = value
    value
  }

  suspend fun section(id: String, requested: Int): PreparedSection {
    val index = index(id)
    check(index.sections.isNotEmpty()) { "This article has no readable content" }
    val n = requested.coerceIn(index.sections.indices)
    val key = "$id:$n:$RENDERED_PROJECTION_VERSION"
    return mutex.withLock {
      sections[key]?.let { return@withLock it }
      val part = index.sections[n]
      val source = withContext(Dispatchers.IO) { db.documents().contentRange(id, part.startUtf16, part.endUtf16) }
      val prepared = withContext(Dispatchers.Default) {
        val prefix = if (index.sections.size == 1) "" else "p${part.startUtf16}/"
        val parsed = ArticleParser.parseWithSources(part.prefix + source + part.suffix, prefix, part.startUtf16 - part.prefix.length)
        val clamped = parsed.copy(canonicalRanges = parsed.canonicalRanges.mapValues { (_, range) ->
          CanonicalRange(range.startUtf16.coerceIn(part.startUtf16, part.endUtf16), range.endUtf16.coerceIn(part.startUtf16, part.endUtf16))
        })
        PreparedSection(index, n, RenderedText.project(clamped).also { it.graphemes })
      }
      if (sections.size >= 3) sections.remove(sections.keys.first())
      sections[key] = prepared
      prepared
    }
  }
}
