package com.reader.app.core

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans

/** Versioned semantic article model. The renderer consumes this, never raw markdown. */
const val ARTICLE_PARSER_VERSION = 2

data class CanonicalRange(val startUtf16: Int, val endUtf16: Int)
data class ParsedArticle(val blocks: List<ArticleBlock>, val canonicalRanges: Map<String, CanonicalRange>)

sealed interface ArticleBlock {
  val id: String
  data class Paragraph(override val id: String, val inlines: List<Inline>) : ArticleBlock
  data class Heading(override val id: String, val level: Int, val inlines: List<Inline>) : ArticleBlock
  data class BulletList(override val id: String, val items: List<ListItem>) : ArticleBlock
  data class OrderedList(override val id: String, val start: Int, val items: List<ListItem>) : ArticleBlock
  data class Quote(override val id: String, val blocks: List<ArticleBlock>) : ArticleBlock
  data class CodeBlock(override val id: String, val language: String?, val code: String) : ArticleBlock
  data class Table(override val id: String, val header: List<String>, val rows: List<List<String>>) : ArticleBlock
  data class Image(override val id: String, val url: String, val alt: String) : ArticleBlock
  data class Divider(override val id: String) : ArticleBlock
  data class Footnotes(override val id: String, val items: List<String>) : ArticleBlock
}

data class ListItem(val blocks: List<ArticleBlock>)

sealed interface Inline {
  data class Text(val text: String) : Inline
  data class Strong(val inlines: List<Inline>) : Inline
  data class Emphasis(val inlines: List<Inline>) : Inline
  data class Strike(val inlines: List<Inline>) : Inline
  data class InlineCode(val code: String) : Inline
  data class Link(val inlines: List<Inline>, val url: String) : Inline
  data class FootnoteRef(val label: String) : Inline
}

object ArticleParser {
  private val extensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    FootnotesExtension.create(),
    AutolinkExtension.create(),
  )
  private val parser: Parser = Parser.builder().extensions(extensions)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

  fun parse(markdown: String): List<ArticleBlock> = parseWithSources(markdown).blocks

  fun parseWithSources(markdown: String, idPrefix: String = "", canonicalOffset: Int = 0): ParsedArticle {
    val doc = parser.parse(markdown)
    val out = mutableListOf<ArticleBlock>()
    var n = 0
    val ranges = linkedMapOf<String, CanonicalRange>()
    fun nextId(node: Node): String {
      val id = "${idPrefix}b${n++}"
      val spans = node.sourceSpans
      val start = spans.minOfOrNull { it.inputIndex } ?: 0
      val end = spans.maxOfOrNull { it.inputIndex + it.length } ?: start
      ranges[id] = CanonicalRange(canonicalOffset + start, canonicalOffset + end)
      return id
    }
    var c = doc.firstChild
    while (c != null) {
      blockOf(c, ::nextId)?.let { out.add(it) }
      c = c.next
    }
    return ParsedArticle(out, ranges)
  }

  private fun blockOf(node: Node, nextId: (Node) -> String): ArticleBlock? = when (node) {
    is Paragraph -> ArticleBlock.Paragraph(nextId(node), inlinesOf(node))
    is Heading -> ArticleBlock.Heading(nextId(node), node.level, inlinesOf(node))
    is BulletList -> ArticleBlock.BulletList(
      nextId(node),
      node.children().filterIsInstance<org.commonmark.node.ListItem>().map {
        ListItem(it.children().mapNotNull { b -> blockOf(b, nextId) })
      },
    )
    is OrderedList -> ArticleBlock.OrderedList(
      nextId(node), node.markerStartNumber,
      node.children().filterIsInstance<org.commonmark.node.ListItem>().map {
        ListItem(it.children().mapNotNull { b -> blockOf(b, nextId) })
      },
    )
    is BlockQuote -> ArticleBlock.Quote(nextId(node), node.children().mapNotNull { blockOf(it, nextId) })
    is FencedCodeBlock -> ArticleBlock.CodeBlock(nextId(node), node.info.takeIf { it.isNotBlank() }, node.literal)
    is IndentedCodeBlock -> ArticleBlock.CodeBlock(nextId(node), null, node.literal)
    is org.commonmark.ext.gfm.tables.TableBlock -> {
      val rows = node.children().flatMap { it.children() }.filterIsInstance<org.commonmark.ext.gfm.tables.TableRow>()
        .map { row -> row.children().map { textOf(it) } }
      ArticleBlock.Table(nextId(node), rows.firstOrNull().orEmpty(), rows.drop(1))
    }
    is org.commonmark.ext.gfm.tables.TableHead -> null
    is org.commonmark.ext.gfm.tables.TableBody -> null
    is ThematicBreak -> ArticleBlock.Divider(nextId(node))
    is HtmlBlock -> {
      val t = node.literal.trim().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
      if (t.isEmpty()) null else ArticleBlock.Paragraph(nextId(node), listOf(Inline.Text(t)))
    }
    else -> {
      // Tables and any other container: degrade to readable text, never markup.
      val t = textOf(node).trim()
      if (t.isEmpty()) null else ArticleBlock.Paragraph(nextId(node), listOf(Inline.Text(t)))
    }
  }

  private fun Node.children(): List<Node> {
    val out = mutableListOf<Node>()
    var c = firstChild
    while (c != null) {
      out.add(c)
      c = c.next
    }
    return out
  }

  private fun textOf(node: Node): String {
    val sb = StringBuilder()
    fun walk(n: Node) {
      when (n) {
        is Text -> sb.append(n.literal)
        is Code -> sb.append(n.literal)
        else -> {
          var c = n.firstChild
          while (c != null) {
            walk(c)
            c = c.next
          }
        }
      }
    }
    walk(node)
    return sb.toString()
  }

  private fun inlinesOf(parent: Node): List<Inline> {
    val out = mutableListOf<Inline>()
    var c = parent.firstChild
    while (c != null) {
      when (c) {
        is Text -> out.add(Inline.Text(c.literal))
        is Emphasis -> out.add(Inline.Emphasis(inlinesOf(c)))
        is StrongEmphasis -> out.add(Inline.Strong(inlinesOf(c)))
        is org.commonmark.ext.gfm.strikethrough.Strikethrough -> out.add(Inline.Strike(inlinesOf(c)))
        is Code -> out.add(Inline.InlineCode(c.literal))
        is Link -> {
          val url = sanitizeUrl(c.destination)
          if (url != null) out.add(Inline.Link(inlinesOf(c), url))
          else out.addAll(inlinesOf(c))
        }
        is Image -> {
          // Images surface as alt text inline; block-level images handled by renderer separately.
          val alt = textOf(c).ifBlank { "image" }
          out.add(Inline.Text("[$alt]"))
        }
        is HtmlInline -> { /* stripped: never execute */ }
        is HardLineBreak -> out.add(Inline.Text("\n"))
        is SoftLineBreak -> out.add(Inline.Text("\n"))
        else -> {
          if (c.firstChild != null) out.addAll(inlinesOf(c))
          else {
            val t = textOf(c)
            if (t.isNotBlank()) out.add(Inline.Text(t))
          }
        }
      }
      c = c.next
    }
    return out
  }

  /** Only http/https links survive; javascript:/data: become plain text. */
  fun sanitizeUrl(url: String): String? {
    val t = url.trim()
    return runCatching {
      val uri = java.net.URI(t)
      t.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }.getOrNull()
  }

  /** Readable plain text of the whole article (TTS/RSVP/reading-time source). */
  fun readableText(blocks: List<ArticleBlock>): String = buildString {
    fun inlineText(inlines: List<Inline>) {
      for (i in inlines) when (i) {
        is Inline.Text -> append(i.text)
        is Inline.Strong -> inlineText(i.inlines)
        is Inline.Emphasis -> inlineText(i.inlines)
        is Inline.Strike -> inlineText(i.inlines)
        is Inline.InlineCode -> {} // skipped for narration
        is Inline.Link -> {
          if (!i.url.startsWith("http")) inlineText(i.inlines)
          else {
            inlineText(i.inlines)
          }
        }
        is Inline.FootnoteRef -> {}
      }
    }
    for (b in blocks) when (b) {
      is ArticleBlock.Paragraph -> {
        inlineText(b.inlines)
        append("\n\n")
      }
      is ArticleBlock.Heading -> {
        inlineText(b.inlines)
        append("\n\n")
      }
      is ArticleBlock.BulletList -> for (item in b.items) {
        for (sb in item.blocks) {
          if (sb is ArticleBlock.Paragraph) inlineText(sb.inlines)
        }
        append("\n")
      }
      is ArticleBlock.OrderedList -> for (item in b.items) {
        for (sb in item.blocks) {
          if (sb is ArticleBlock.Paragraph) inlineText(sb.inlines)
        }
        append("\n")
      }
      is ArticleBlock.Quote -> {
        append(readableText(b.blocks))
      }
      is ArticleBlock.CodeBlock -> {} // skipped
      is ArticleBlock.Table -> {
        append(b.header.joinToString(" "))
        append("\n")
        for (r in b.rows) append(r.joinToString(" ")).append("\n")
      }
      is ArticleBlock.Image -> append(if (b.alt.isNotBlank()) "[${b.alt}]\n" else "")
      is ArticleBlock.Divider -> {}
      is ArticleBlock.Footnotes -> {}
    }
  }
}
