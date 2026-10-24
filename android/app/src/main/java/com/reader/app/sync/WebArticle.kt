package com.reader.app.sync

import com.reader.app.core.ReaderCore
import com.reader.app.nostr.PairingProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** URL detection and readable extraction for shared links. */
object UrlCapture {
  private val urlPattern = Regex("(?i)https?://[^\\s<>\"']+")

  fun singleHttpUrl(raw: String): String? {
    val matches = urlPattern.findAll(raw.trim()).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.toList()
    if (matches.size != 1) return null
    return normalize(matches.single())
  }

  fun normalize(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > 2048) return null
    // HttpUrl is the parser OkHttp will use for the request, so validate the
    // same canonical host it will connect to rather than java.net.URI's view.
    val httpUrl = value.toHttpUrlOrNull() ?: return null
    if (httpUrl.scheme !in listOf("http", "https")) return null
    if (httpUrl.host.isBlank() || httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return null
    if (PairingProtocol.isProhibitedHost(httpUrl.host)) return null
    return value
  }
}

object WebArticleExtractor {
  data class Article(
    val title: String,
    val markdown: String,
    val author: String?,
    val sourceName: String?,
  )

  fun extract(html: String, sourceUrl: String): Article? {
    val document = Jsoup.parse(html, sourceUrl)
    document.select("script,style,noscript,nav,header,footer,aside,form,iframe,svg").remove()
    val title = (
      document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        ?: document.selectFirst("h1")?.text()?.trim()
        ?: document.title()?.trim()
      )?.take(500)?.takeIf { it.isNotEmpty() } ?: return null
    val author = (
      document.selectFirst("meta[name=author]")?.attr("content")?.trim()
        ?: document.selectFirst("meta[property=article:author]")?.attr("content")?.trim()
      )?.take(300)?.takeIf { it.isNotEmpty() }
    val root = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body() ?: return null
    val markdown = StringBuilder()
    fun appendBlock(text: String, prefix: String = "", suffix: String = "\n\n") {
      val clean = text.replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
      if (clean.isNotEmpty()) markdown.append(prefix).append(clean).append(suffix)
    }
    fun walk(element: Element) {
      for (child in element.children()) {
        when (child.tagName().lowercase()) {
          "h1", "h2", "h3", "h4" -> appendBlock(child.text(), "#".repeat(child.tagName()[1].digitToInt()) + " ")
          "p" -> appendBlock(child.text())
          "blockquote" -> appendBlock(child.text(), "> ")
          "pre" -> {
            val code = child.text().trimEnd()
            if (code.isNotEmpty()) markdown.append("```\n").append(code).append("\n```\n\n")
          }
          "ul", "ol" -> {
            val items = child.children().filter { it.tagName() == "li" }
            if (items.isNotEmpty()) {
              items.forEachIndexed { index, item ->
                val marker = if (child.tagName() == "ol") "${index + 1}. " else "- "
                markdown.append(marker).append(item.text().trim()).append('\n')
              }
              markdown.append('\n')
            }
          }
          "div", "section", "main", "article" -> walk(child)
          else -> if (child.children().isNotEmpty()) walk(child) else appendBlock(child.text())
        }
      }
    }
    walk(root)
    var body = markdown.toString().trim()
    if (body.length < 40) return null
    // The reader renders the document title separately and strips a matching
    // leading H1, keeping the exported Markdown self-describing.
    if (!body.startsWith(title)) body = "# $title\n\n$body"
    val canonical = ReaderCore.canonicalize(body + "\n")
    if (canonical.length < 40) return null
    val host = runCatching { URI(sourceUrl).host }.getOrNull()
    return Article(title, canonical, author, host)
  }
}
