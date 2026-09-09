package com.reader.app.core

import com.reader.app.cursor.SemanticCursor
import java.text.BreakIterator
import java.util.Locale

const val RENDERED_PROJECTION_VERSION = 2

enum class TextKind { PARAGRAPH, HEADING, CODE, TABLE, FOOTNOTE, DIVIDER }
enum class TextStyle { BOLD, ITALIC, STRIKE, CODE, LINK }
data class TableCell(val start: Int, val end: Int)
data class ProjectedTable(val rows: List<List<TableCell>>)
data class ProjectedStyle(val start: Int, val end: Int, val style: TextStyle, val value: String? = null)
data class ProjectedBlock(
  val id: String, val start: Int, val end: Int, val bodyStart: Int, val bodyEnd: Int,
  val kind: TextKind, val depth: Int = 0, val level: Int = 0, val quote: Boolean = false,
  val canonical: CanonicalRange? = null,
)

/** All offsets are UTF-16 in rendered text. Markdown source ranges are separate. */
data class RenderedProjection(
  val text: String,
  val blocks: List<ProjectedBlock>,
  val styles: List<ProjectedStyle>,
  val sourceRanges: Map<String, CanonicalRange>,
  val tables: List<ProjectedTable> = emptyList(),
) {
  private val byId = blocks.associateBy { it.id }
  val graphemes by lazy(LazyThreadSafetyMode.NONE) { Graphemes(text) }

  fun offset(blockId: String?, localOffset: Int): Int {
    var block = byId[blockId]
    if (block == null) sourceRanges[blockId]?.let { range ->
      block = blocks.firstOrNull { it.canonical?.startUtf16?.let { start -> start >= range.startUtf16 && start <= range.endUtf16 } == true }
    }
    return block?.let { it.start + localOffset.coerceIn(0, it.end - it.start) } ?: 0
  }

  fun cursor(documentId: String, at: Int): SemanticCursor {
    if (blocks.isEmpty()) return SemanticCursor.start(documentId)
    val offset = at.coerceIn(0, text.length)
    var lo = 0; var hi = blocks.lastIndex
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (blocks[mid].start <= offset) lo = mid else hi = mid - 1
    }
    val block = blocks[lo]
    return SemanticCursor(documentId, block.id, (offset - block.start).coerceIn(0, block.end - block.start))
  }

  fun range(start: Int, end: Int): IntRange? {
    val a = graphemes.floor(minOf(start, end).coerceIn(0, text.length))
    val b = graphemes.ceil(maxOf(start, end).coerceIn(0, text.length))
    return if (a < b && text.substring(a, b).isNotBlank()) a until b else null
  }
}

object RenderedText {
  fun project(article: ParsedArticle): RenderedProjection = project(article.blocks, article.canonicalRanges)

  fun project(blocks: List<ArticleBlock>, sourceRanges: Map<String, CanonicalRange> = emptyMap()): RenderedProjection {
    val text = StringBuilder()
    val projected = mutableListOf<ProjectedBlock>()
    val styles = mutableListOf<ProjectedStyle>()
    val tables = mutableListOf<ProjectedTable>()
    fun inlines(items: List<Inline>) {
      for (item in items) {
        val start = text.length
        when (item) {
          is Inline.Text -> text.append(item.text)
          is Inline.Strong -> { inlines(item.inlines); styles += ProjectedStyle(start, text.length, TextStyle.BOLD) }
          is Inline.Emphasis -> { inlines(item.inlines); styles += ProjectedStyle(start, text.length, TextStyle.ITALIC) }
          is Inline.Strike -> { inlines(item.inlines); styles += ProjectedStyle(start, text.length, TextStyle.STRIKE) }
          is Inline.InlineCode -> { text.append(item.code); styles += ProjectedStyle(start, text.length, TextStyle.CODE) }
          is Inline.Link -> { inlines(item.inlines); styles += ProjectedStyle(start, text.length, TextStyle.LINK, item.url) }
          is Inline.FootnoteRef -> text.append("[${item.label}]")
        }
      }
    }
    fun walk(items: List<ArticleBlock>, depth: Int = 0, quoted: Boolean = false, marker: String = "") {
      for ((index, block) in items.withIndex()) {
        val prefix = if (index == 0) marker else ""
        when (block) {
          is ArticleBlock.BulletList -> block.items.forEach { walk(it.blocks, depth + 1, quoted, "•  ") }
          is ArticleBlock.OrderedList -> block.items.forEachIndexed { i, item -> walk(item.blocks, depth + 1, quoted, "${block.start + i}.  ") }
          is ArticleBlock.Quote -> walk(block.blocks, depth + 1, true, prefix)
          else -> {
            val start = text.length
            text.append(prefix)
            val bodyStart = text.length
            val kind = when (block) {
              is ArticleBlock.Paragraph -> { inlines(block.inlines); TextKind.PARAGRAPH }
              is ArticleBlock.Heading -> { inlines(block.inlines); TextKind.HEADING }
              is ArticleBlock.CodeBlock -> { text.append(block.code.trimEnd('\n')); TextKind.CODE }
              is ArticleBlock.Table -> {
                val cells = mutableListOf<List<TableCell>>()
                for ((rowIndex, row) in (listOf(block.header) + block.rows).withIndex()) {
                  if (rowIndex > 0) text.append('\n')
                  val rowStart = text.length
                  cells += row.mapIndexed { column, cell ->
                    if (column > 0) text.append("  │  ")
                    val cellStart = text.length
                    text.append(cell)
                    TableCell(cellStart, text.length)
                  }
                  if (rowIndex == 0) styles += ProjectedStyle(rowStart, text.length, TextStyle.BOLD)
                }
                tables += ProjectedTable(cells)
                TextKind.TABLE
              }
              is ArticleBlock.Image -> { text.append(block.alt.ifBlank { "Image omitted" }); TextKind.PARAGRAPH }
              is ArticleBlock.Footnotes -> { text.append(block.items.mapIndexed { i, value -> "[${i + 1}] $value" }.joinToString("\n")); TextKind.FOOTNOTE }
              is ArticleBlock.Divider -> { text.append('—'); TextKind.DIVIDER }
              else -> error("Container handled above")
            }
            val bodyEnd = text.length
            text.append("\n\n")
            projected += ProjectedBlock(block.id, start, text.length, bodyStart, bodyEnd, kind, depth,
              (block as? ArticleBlock.Heading)?.level ?: 0, quoted, sourceRanges[block.id])
          }
        }
      }
    }
    walk(blocks)
    return RenderedProjection(text.toString(), projected, styles.filter { it.end > it.start }, sourceRanges, tables)
  }
}

/** Platform character boundaries plus emoji sequences older BreakIterators split. */
class Graphemes(private val text: String) {
  private val boundaries: IntArray
  init {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val out = ArrayList<Int>(); out += 0
    var at = 0; var regionalRun = 0
    while (at < text.length) {
      val cp = text.codePointAt(at)
      regionalRun = if (cp in 0x1F1E6..0x1F1FF) regionalRun + 1 else 0
      at += Character.charCount(cp)
      if (at >= text.length) break
      val next = text.codePointAt(at)
      val type = Character.getType(next)
      val joins = cp == 0x200D || next == 0x200D ||
        next in 0x1F3FB..0x1F3FF || next in 0xFE00..0xFE0F || next in 0xE0100..0xE01EF ||
        next in 0xE0020..0xE007F || type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
        (regionalRun % 2 == 1 && next in 0x1F1E6..0x1F1FF) || (cp == 13 && next == 10)
      if (!joins && iterator.isBoundary(at)) out += at
    }
    if (out.last() != text.length) out += text.length
    boundaries = out.toIntArray()
  }
  fun floor(offset: Int): Int {
    val x = offset.coerceIn(0, text.length); val i = boundaries.binarySearch(x)
    return if (i >= 0) x else boundaries[(-i - 2).coerceAtLeast(0)]
  }
  fun ceil(offset: Int): Int {
    val x = offset.coerceIn(0, text.length); val i = boundaries.binarySearch(x)
    return if (i >= 0) x else boundaries[(-i - 1).coerceAtMost(boundaries.lastIndex)]
  }
}
