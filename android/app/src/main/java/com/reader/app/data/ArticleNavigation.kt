package com.reader.app.data

import com.reader.app.core.TextKind
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class ArticleLocation(val part: Int, val cursor: SemanticCursor, val label: String, val end: Int? = null)

/**
 * A Find result with everything the sheet needs to render and to jump:
 * the exact location, a context snippet, the snippet-relative match range for
 * emphasised styling, and the absolute end offset for the landing tint.
 */
data class ArticleMatch(
  val part: Int,
  val cursor: SemanticCursor,
  val snippet: String,
  val textEnd: Int?,
  val matchStart: Int,
  val matchEnd: Int,
) {
  fun toLocation(): ArticleLocation = ArticleLocation(part, cursor, snippet, textEnd)
}

/** Snippet text plus the match range measured inside that snippet. */
data class SnippetWindow(val text: String, val matchStart: Int, val matchEnd: Int)

/**
 * Builds the context snippet for a match at [at]..[end] in [text]. Newlines
 * become spaces one-for-one, so the returned match range stays valid.
 */
internal fun matchSnippet(text: String, at: Int, end: Int): SnippetWindow {
  val start = (at - 40).coerceAtLeast(0)
  val stop = (end + 80).coerceAtMost(text.length)
  val snippet = text.substring(start, stop).replace('\n', ' ')
  return SnippetWindow(snippet, at - start, end - start)
}

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
    return matches(id, query).map { it.toLocation() }
  }
  suspend fun matches(id: String, query: String): List<ArticleMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val index = articles.index(id)
    return buildList {
      index.sections.indices.forEach { part ->
        val p = articles.section(id, part).projection
        withContext(Dispatchers.Default) {
          var from = 0
          while (from < p.text.length) {
            val at = p.text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            val end = at + needle.length
            val window = matchSnippet(p.text, at, end)
            add(ArticleMatch(
              part = part,
              cursor = p.cursor(id, at),
              snippet = window.text,
              textEnd = end,
              matchStart = window.matchStart,
              matchEnd = window.matchEnd,
            ))
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
