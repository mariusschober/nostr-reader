package com.reader.app.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.reader.app.core.BoundedText
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import org.jsoup.Jsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val lines = raw.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }
    if (raw.isBlank()) return false
    if (lines.any { l -> strongMarkers.any { it.containsMatchIn(l) } }) return true
    val hasBlank = raw.lineSequence().any { it.isBlank() } && lines.take(2).count() > 1
    if (!hasBlank) return false
    if (lines.any { tableRow.matches(it) }) return true
    return mdLink.containsMatchIn(raw)
  }

  /** Single paste entry: markdown stays markdown, anything else is literal text. */
  suspend fun importPasted(ctx: Context, rawText: String): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(rawText)
    val text = rawText.trim()
    require(text.isNotEmpty()) { "empty paste" }
    if (looksLikeMarkdown(text)) {
      commit(ctx, text, "paste", null)
    } else {
      importPlainText(ctx, text, "paste", null)
    }
  }

  suspend fun importPlainText(ctx: Context, rawText: String, sourceType: String, title: String? = null): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(rawText)
    val escaped = ReaderCore.escapePlainText(rawText.trim())
    // Preserve the sender line structure: single newlines become hard breaks
    // (canonicalize runs first and trims trailing whitespace, so add breaks after).
    val canonical = ReaderCore.canonicalize(escaped + "\n")
    val rebuilt = buildString {
      var previous: String? = null
      for (line in canonical.lineSequence()) {
        previous?.let {
          append(it)
          if (it.isNotBlank() && line.isNotBlank()) append("\\")
          append("\n")
        }
        previous = line
      }
      previous?.let { append(it) }
    }
    commit(ctx, rebuilt, sourceType, title ?: firstLineTitle(rawText))
  }

  suspend fun importHtml(ctx: Context, html: String, sourceType: String): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(html)
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
    commit(ctx, md.toString(), sourceType, null)
  }

  suspend fun importFile(ctx: Context, uri: Uri, name: String): String = withContext(Dispatchers.IO) {
    val text = ctx.contentResolver.openInputStream(uri)?.use { BoundedText.readUtf8(it) }
      ?: throw IllegalArgumentException("Unreadable file")
    if (name.endsWith(".txt", ignoreCase = true)) {
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

  private suspend fun commit(ctx: Context, markdown: String, sourceType: String, title: String?): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(markdown)
    val canonical = ReaderCore.canonicalize(markdown)
    BoundedText.requireSize(canonical)
    val id = ReaderCore.documentId(canonical)
    val db = ReaderDb.get(ctx)
    if (db.documents().exists(id)) return@withContext id // dedupe: same hash
    val resolvedTitle = title?.take(500)
      ?: canonical.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.take(500)
      ?: "Untitled"
    val now = System.currentTimeMillis()
    db.documents().insert(
      DocumentEntity(
        documentId = id, title = resolvedTitle, sourceType = sourceType,
        sourceName = null, sourceUrl = null, author = null, publishedAt = null,
        capturedAt = now, language = null, canonicalMarkdown = canonical,
        wordCount = ReaderCore.wordCount(canonical), parserVersion = 1,
        state = "unread", progressBlockId = "b0",
        progressCharOffset = 0, progressFraction = 0f,
        lastOpenedAt = 0L, createdAt = now, updatedAt = now,
      ),
    )
    id
  }

  private fun firstLineTitle(t: String): String =
    t.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Shared text"
}
