package com.reader.app.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import org.jsoup.Jsoup

/** Local ingestion: share / process-text / file. Always ends in rendered state. */
object Ingest {
  private val strongMarkers = listOf(
    Regex("^#{1,4}\\s+\\S"),
    Regex("^```"),
    Regex("^\\s*[-*+]\\s+\\S"),
    Regex("^\\s*\\d+[.)]\\s+\\S"),
  )
  private val tableRow = Regex("^\\s*\\|.*\\|\\s*$")
  private val mdLink = Regex("\\[[^\\]]+\\]\\([^)]+\\)")

  /**
   * Markdown auto-detect for pasted content. Strong structural markers
   * (heading / fence / list) at a line start always count; weaker signals
   * (tables, links) need a blank line for structure. Ties resolve to
   * literal text: never surprise-render structure.
   */
  fun looksLikeMarkdown(raw: String): Boolean {
    val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    if (lines.any { l -> strongMarkers.any { it.containsMatchIn(l) } }) return true
    val hasBlank = raw.lines().any { it.isBlank() } && lines.size > 1
    if (!hasBlank) return false
    if (lines.any { tableRow.matches(it) }) return true
    return mdLink.containsMatchIn(raw)
  }

  /** Single paste entry: markdown stays markdown, anything else is literal text. */
  suspend fun importPasted(ctx: Context, rawText: String): String {
    val text = rawText.trim()
    require(text.isNotEmpty()) { "empty paste" }
    return if (looksLikeMarkdown(text)) {
      commit(ctx, text, "paste", null)
    } else {
      importPlainText(ctx, text, "paste", null)
    }
  }

  suspend fun importPlainText(ctx: Context, rawText: String, sourceType: String, title: String? = null): String {
    val escaped = ReaderCore.escapePlainText(rawText.trim())
    // Preserve the sender line structure: single newlines become hard breaks
    // (canonicalize runs first and trims trailing whitespace, so add breaks after).
    val canonical = ReaderCore.canonicalize(escaped + "\n")
    val lines = canonical.lines()
    val rebuilt = buildString {
      for ((i, line) in lines.withIndex()) {
        append(line)
        if (line.isNotBlank() && i + 1 < lines.size && lines[i + 1].isNotBlank()) append("\\")
        if (i + 1 < lines.size) append("\n")
      }
    }
    return commit(ctx, rebuilt, sourceType, title ?: firstLineTitle(rawText))
  }

  suspend fun importHtml(ctx: Context, html: String, sourceType: String): String {
    val doc = Jsoup.parseBodyFragment(html)
    doc.select("script,style,nav,header,footer").remove()
    val md = StringBuilder()
    for (el in doc.body().children()) {
      val t = el.text().trim()
      if (t.isEmpty()) continue
      when (el.tagName()) {
        "h1", "h2", "h3" -> md.append("#".repeat(el.tagName()[1].digitToInt())).append(' ').append(t).append("\n\n")
        "li" -> md.append("- ").append(t).append("\n")
        "pre", "code" -> md.append("```\n").append(el.text()).append("\n```\n\n")
        else -> md.append(t).append("\n\n")
      }
    }
    return commit(ctx, ReaderCore.canonicalize(md.toString()), sourceType, null)
  }

  suspend fun importFile(ctx: Context, uri: Uri, name: String): String {
    val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: throw IllegalArgumentException("unreadable file")
    return if (name.endsWith(".txt", ignoreCase = true)) {
      importPlainText(ctx, text, "file", name.substringAfterLast('/'))
    } else {
      commit(ctx, text, "file", name.substringAfterLast('/'))
    }
  }

  fun handleIntentText(intent: Intent): Pair<String, String?>? {
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
      ?: intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT")?.toString()
      ?: return null
    if (text.isBlank()) return null
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
    val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
    return if (html != null) "__HTML__$html" to subject else text to subject
  }

  private suspend fun commit(ctx: Context, markdown: String, sourceType: String, title: String?): String {
    val canonical = ReaderCore.canonicalize(markdown)
    val id = ReaderCore.documentId(canonical)
    val db = ReaderDb.get(ctx)
    if (db.documents().byId(id) != null) return id // dedupe: same hash
    val blocks = ArticleParser.parse(canonical)
    val resolvedTitle = title?.take(500)
      ?: canonical.lines().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.take(500)
      ?: "Untitled"
    val now = System.currentTimeMillis()
    db.documents().insert(
      DocumentEntity(
        documentId = id, title = resolvedTitle, sourceType = sourceType,
        sourceName = null, sourceUrl = null, author = null, publishedAt = null,
        capturedAt = now, language = null, canonicalMarkdown = canonical,
        wordCount = ReaderCore.wordCount(canonical), parserVersion = 1,
        state = "unread", progressBlockId = blocks.firstOrNull()?.id,
        progressCharOffset = 0, progressFraction = 0f,
        lastOpenedAt = 0L, createdAt = now, updatedAt = now,
      ),
    )
    return id
  }

  private fun firstLineTitle(t: String): String =
    t.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Shared text"
}
