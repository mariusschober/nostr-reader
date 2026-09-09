package com.reader.app.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.reader.app.core.BoundedText
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local ingestion: share / process-text / file. Always ends in rendered state. */
object Ingest {
  /** Recognize parsed Markdown structure, including compact tables and inline formatting. */
  fun looksLikeMarkdown(raw: String): Boolean {
    if (raw.isBlank()) return false
    return com.reader.app.core.ArticleParser.parse(raw).any { block ->
      when (block) {
        is com.reader.app.core.ArticleBlock.Paragraph -> block.inlines.any { it !is com.reader.app.core.Inline.Text }
        else -> true
      }
    }
  }

  /** Single paste entry: markdown stays markdown, anything else is literal text. */
  suspend fun importPasted(ctx: Context, rawText: String): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(rawText)
    val text = rawText.trim()
    require(text.isNotEmpty()) { "empty paste" }
    if (com.reader.app.core.HtmlMarkdown.looksLikeHtml(text)) {
      importHtml(ctx, text, "paste")
    } else if (looksLikeMarkdown(text)) {
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
    commit(ctx, com.reader.app.core.HtmlMarkdown.convert(html), sourceType, null)
  }

  suspend fun importFile(ctx: Context, uri: Uri, name: String): String = withContext(Dispatchers.IO) {
    val text = ctx.contentResolver.openInputStream(uri)?.use { BoundedText.readUtf8(it) }
      ?: throw IllegalArgumentException("Unreadable file")
    val displayName = runCatching {
      ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }
    }.getOrNull() ?: name
    val mimeType = ctx.contentResolver.getType(uri)
    if (mimeType == "text/html" || displayName.endsWith(".html", ignoreCase = true) || displayName.endsWith(".htm", ignoreCase = true)) {
      importHtml(ctx, text, "file")
    } else if (displayName.endsWith(".txt", ignoreCase = true)) {
      importPlainText(ctx, text, "file", displayName.substringAfterLast('/'))
    } else {
      commit(ctx, text, "file", displayName.substringAfterLast('/'))
    }
  }

  suspend fun importSharedText(ctx: Context, text: String, sourceType: String, title: String? = null): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(text)
    when {
      com.reader.app.core.HtmlMarkdown.looksLikeHtml(text) -> importHtml(ctx, text, sourceType)
      looksLikeMarkdown(text) -> commit(ctx, text, sourceType, title)
      else -> importPlainText(ctx, text, sourceType, title)
    }
  }

  fun handleIntentText(intent: Intent): Pair<String, String?>? {
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
    val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
    if (!html.isNullOrBlank()) return "__HTML__$html" to subject
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
      ?: intent.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT")?.toString()
      ?: return null
    return text.takeIf { it.isNotBlank() }?.let { it to subject }
  }

  private suspend fun commit(ctx: Context, markdown: String, sourceType: String, title: String?): String = withContext(Dispatchers.Default) {
    BoundedText.requireSize(markdown)
    val canonical = ReaderCore.canonicalize(markdown)
    BoundedText.requireSize(canonical)
    val id = ReaderCore.documentId(canonical)
    val db = ReaderDb.get(ctx)
    if (db.documents().exists(id)) return@withContext id // dedupe: same hash
    val resolvedTitle = title?.take(500)
      ?: canonical.lineSequence().firstOrNull { Regex("^#{1,6}\\s+").containsMatchIn(it) }?.replaceFirst(Regex("^#{1,6}\\s+"), "")?.trim()?.take(500)
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
