package com.reader.app.data

import com.reader.app.core.RenderedProjection
import com.reader.app.core.TextKind
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal const val FIND_PAGE_SIZE = 100
internal data class MatchPosition(val part: Int = 0, val offset: Int = 0)
internal data class MatchPage(val matches: List<ArticleMatch>, val next: MatchPosition?)

/** Hold one page of snippets. The continuation identifies the first unconsumed match. */
internal suspend fun scanMatches(
  id: String, needle: String, parts: Int, start: MatchPosition, firstOnly: Boolean = false,
  projection: suspend (Int) -> RenderedProjection,
): MatchPage = withContext(Dispatchers.Default) {
  val matches = ArrayList<ArticleMatch>()
  if (needle.isNotEmpty()) for (part in start.part until parts) {
    val p = projection(part)
    var from = if (part == start.part) start.offset else 0
    while (from < p.text.length) {
      currentCoroutineContext().ensureActive()
      val at = p.text.indexOf(needle, from, ignoreCase = true)
      if (at < 0) break
      if (matches.size == FIND_PAGE_SIZE) return@withContext MatchPage(matches, MatchPosition(part, at))
      val end = at + needle.length
      val window = matchSnippet(p.text, at, end)
      matches.add(ArticleMatch(part, p.cursor(id, at), window.text, end, window.matchStart, window.matchEnd))
      if (firstOnly) return@withContext MatchPage(matches, null)
      from = end
    }
  }
  MatchPage(matches, null)
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
  internal suspend fun matchPage(id: String, query: String, start: MatchPosition = MatchPosition(), firstOnly: Boolean = false): MatchPage {
    val needle = query.trim()
    if (needle.isEmpty()) return MatchPage(emptyList(), null)
    val index = articles.index(id)
    return scanMatches(id, needle, index.sections.size, start, firstOnly) { articles.section(id, it).projection }
  }
  suspend fun fragment(id: String, raw: String): ArticleLocation? {
    val target = runCatching { java.net.URLDecoder.decode(raw.removePrefix("#").replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    val index = articles.index(id)
    return withContext(Dispatchers.Default) {
      for ((part, section) in index.sections.withIndex()) {
        val p = articles.section(id, part).projection
        val canonical = db.documents().contentRange(id, section.startUtf16, section.endUtf16)
        for (block in p.blocks) {
          currentCoroutineContext().ensureActive()
          val title = p.text.substring(block.bodyStart, block.bodyEnd)
          val slug = if (block.kind == TextKind.HEADING)
            title.lowercase().replace(slugPunctuation, "").trim().replace(slugSpaces, "-") else null
          val source = block.canonical?.let { range ->
            val start = (range.startUtf16 - section.startUtf16).coerceIn(0, canonical.length)
            val end = (range.endUtf16 - section.startUtf16).coerceIn(start, minOf(start + 500, canonical.length))
            canonical.substring(start, end)
          }.orEmpty()
          val footnote = footnotePattern.find(source)?.groupValues?.get(1)
          val aliases = if (footnote == null) emptySet() else setOf("^$footnote", "fn-$footnote", "fn:$footnote", footnote)
          val anchors = anchorPattern.findAll(source).map { it.groupValues[1] }.toList()
          if ((block.kind == TextKind.HEADING && slug == target) || target in aliases || target in anchors)
            return@withContext ArticleLocation(part, p.cursor(id, block.start), title)
        }
      }
      null
    }
  }

  private companion object {
    val slugPunctuation = Regex("""[^\p{L}\p{N} _-]""")
    val slugSpaces = Regex("""\s+""")
    val footnotePattern = Regex("""\[\^([^]]+)]:""")
    val anchorPattern = Regex("""(?:id|name)=["']([^"']+)["']""")
  }
}
