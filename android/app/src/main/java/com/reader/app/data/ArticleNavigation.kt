package com.reader.app.data

import com.reader.app.core.TextKind
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class ArticleLocation(val part: Int, val cursor: SemanticCursor, val label: String, val end: Int? = null)

/** Builds small navigation metadata from bounded parts; never changes stored text. */
class ArticleNavigation(private val articles: ArticleRepository, private val db: ReaderDb) {
  suspend fun contents(id: String): List<ArticleLocation> {
    val index = articles.index(id)
    return buildList {
      index.sections.indices.forEach { part ->
        val p = articles.section(id, part).projection
        p.blocks.filter { it.kind == TextKind.HEADING }.forEach { block ->
          add(ArticleLocation(part, p.cursor(id, block.start), p.text.substring(block.bodyStart, block.bodyEnd).trim()))
        }
        yield()
      }
    }
  }
  suspend fun find(id: String, query: String): List<ArticleLocation> {
    if (query.isBlank()) return emptyList()
    val index = articles.index(id)
    return buildList {
      index.sections.indices.forEach { part ->
        val p = articles.section(id, part).projection
        withContext(Dispatchers.Default) {
          var from = 0
          while (from < p.text.length) {
            val at = p.text.indexOf(query.trim(), from, ignoreCase = true)
            if (at < 0) break
            val end = at + query.trim().length
            add(ArticleLocation(part, p.cursor(id, at), p.text.substring((at-40).coerceAtLeast(0), (end+80).coerceAtMost(p.text.length)).replace('\n', ' '), end))
            from = end
          }
        }
        yield()
      }
    }
  }
  suspend fun fragment(id: String, raw: String): ArticleLocation? {
    val target = java.net.URLDecoder.decode(raw.removePrefix("#"), "UTF-8")
    val index = articles.index(id)
    for (part in index.sections.indices) {
      val p = articles.section(id, part).projection
      for (block in p.blocks) {
        val title = p.text.substring(block.bodyStart, block.bodyEnd)
        val slug = title.lowercase().replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(Regex("\\s+"), "-")
        val range = block.canonical
        val source = if (range != null) withContext(Dispatchers.IO) {
          db.documents().contentRange(id, range.startUtf16, minOf(range.endUtf16, range.startUtf16 + 500))
        } else ""
        val footnote = Regex("\\[\\^([^]]+)]:").find(source)?.groupValues?.get(1)
        val aliases = if (footnote == null) emptySet() else setOf("^$footnote", "fn-$footnote", "fn:$footnote", footnote)
        val anchors = Regex("(?:id|name)=[\"']([^\"']+)[\"']").findAll(source).map { it.groupValues[1] }.toList()
        if ((block.kind == TextKind.HEADING && slug == target) || target in aliases || target in anchors)
          return ArticleLocation(part, p.cursor(id, block.start), title)
      }
    }
    return null
  }
}
