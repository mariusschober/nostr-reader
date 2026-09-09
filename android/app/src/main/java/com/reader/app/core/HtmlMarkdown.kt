package com.reader.app.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Semantic HTML import. No scripts, CSS, network loads, or executable URLs survive. */
object HtmlMarkdown {
  fun looksLikeHtml(text: String): Boolean = Regex(
    "(?is)<(?:!doctype\\s+html|html|body|article|section|div|p|h[1-6]|table|ul|ol|blockquote|pre|strong|em|a)(?:\\s|>)"
  ).containsMatchIn(text)

  fun convert(html: String): String {
    val doc = Jsoup.parseBodyFragment(html)
    doc.select("script,style,noscript,iframe,object,embed,nav").remove()
    return children(doc.body()).trim() + "\n"
  }

  private fun escape(text: String) = buildString {
    for (character in text) {
      if (character in "\\`*_{}[]<>#|+-.!()") append('\\')
      append(character)
    }
  }
  private fun children(node: Node): String = node.childNodes().joinToString("") { render(it) }
  private fun render(node: Node): String {
    if (node is TextNode) return escape(node.text())
    if (node !is Element) return ""
    val content by lazy { children(node).trim() }
    return when (node.normalName()) {
      "h1", "h2", "h3", "h4", "h5", "h6" -> "\n\n${"#".repeat(node.normalName()[1].digitToInt())} $content\n\n"
      "p", "div", "article", "section", "main", "figure", "figcaption" -> "\n\n$content\n\n"
      "br" -> "\\\n"
      "hr" -> "\n\n---\n\n"
      "strong", "b" -> "**$content**"
      "em", "i" -> "*$content*"
      "s", "del", "strike" -> "~~$content~~"
      "a" -> ArticleParser.sanitizeUrl(node.attr("href"))?.let { "[$content](<${it.replace(">", "%3E")}>)" } ?: content
      "img" -> escape(node.attr("alt")).takeIf { it.isNotBlank() }?.let { "[$it]" }.orEmpty()
      "pre" -> {
        val code = node.wholeText().trimEnd('\n')
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1))
        "\n\n$fence\n$code\n$fence\n\n"
      }
      "code" -> {
        val code = node.wholeText()
        val fence = "`".repeat((Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1)
        "$fence $code $fence"
      }
      "blockquote" -> "\n\n" + content.lines().joinToString("\n") { "> $it" } + "\n\n"
      "ul", "ol" -> {
        var number = node.attr("start").toIntOrNull() ?: 1
        "\n\n" + node.children().filter { it.normalName() == "li" }.joinToString("\n") {
          val marker = if (node.normalName() == "ol") "${number++}. " else "- "
          val lines = children(it).trim().lines()
          marker + lines.firstOrNull().orEmpty() + lines.drop(1).joinToString("") { line -> "\n" + " ".repeat(marker.length) + line }
        } + "\n\n"
      }
      "table" -> table(node)
      else -> children(node)
    }
  }

  private fun table(table: Element): String {
    val rows = table.select("tr").filter { it.parents().firstOrNull { p -> p.normalName() == "table" } === table }
      .map { row -> row.children().filter { it.normalName() in setOf("th", "td") }
        .map { children(it).trim().replace(Regex("\\s*\n\\s*"), " ") } }
      .filter { it.isNotEmpty() }
    if (rows.isEmpty()) return ""
    val columns = rows.maxOf { it.size }
    fun line(row: List<String>) = "| " + (0 until columns).joinToString(" | ") { row.getOrElse(it) { "" } } + " |\n"
    return "\n\n" + line(rows.first()) + line(List(columns) { "---" }) + rows.drop(1).joinToString("") { line(it) } + "\n"
  }
}
