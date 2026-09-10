package com.reader.app.capture

import androidx.room.withTransaction
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Durable capture-request ownership.
 *
 * - Persists the request before any network activity and before UI says saved.
 * - Deduplicates repeated intents for the same normalized URL while a request
 *   is still active, without preventing deliberate recapture (a new request
 *   after terminal states).
 * - Crash-safe completion: document insert + request update share one Room
 *   transaction guarded by generation + state.
 */
class CaptureRepository(
  private val db: ReaderDb,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  companion object {
    const val MAX_ATTEMPTS = 10
    const val MAX_BACKOFF_MILLIS = 6L * 60L * 60L * 1000L
    /** Terminal history retention: 90 days, newest 2000 rows. */
    const val TERMINAL_RETENTION_MILLIS = 90L * 24L * 60L * 60L * 1000L
    const val MAX_TERMINAL_ROWS = 2000

    fun backoffMillis(attempt: Int, requestId: String): Long {
      val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(10)
      val base = (30_000L * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MILLIS)
      val jitterPercent = 80 + ((requestId.takeLast(2).toIntOrNull(16) ?: 20) % 41)
      return (base * jitterPercent / 100L).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    fun newRequestId(): String {
      val bytes = ByteArray(16)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }
  }

  suspend fun getOrCreate(originalUrl: String, titleHint: String?, sourceType: String): CaptureRequestEntity {
    val trimmed = originalUrl.trim()
    CaptureUrlPolicy.validateStructure(trimmed)
    // DNS is validated in the worker (offline shares must persist first).
    // Here we only enforce structure so the durable commit never stores junk.
    val normalized = CaptureUrlPolicy.normalizeForDedup(trimmed)
    db.captureRequests().pendingByNormalized(normalized)?.let { return it }
    val now = nowMillis()
    val request = CaptureRequestEntity(
      requestId = newRequestId(),
      originalUrl = trimmed,
      normalizedUrl = normalized,
      subjectTitle = titleHint?.trim()?.take(200)?.ifBlank { null },
      sourceType = sourceType.take(32),
      state = "pending",
      documentId = null,
      resolvedUrl = null,
      title = null,
      errorCode = null,
      errorMessage = null,
      attemptCount = 0,
      nextAttemptAt = null,
      generation = 0,
      createdAt = now,
      updatedAt = now,
    )
    try {
      db.captureRequests().insert(request)
    } catch (_: android.database.SQLException) {
      // Lost a creation race: return the winner.
      db.captureRequests().pendingByNormalized(normalized)?.let { return it }
      throw IllegalStateException("capture request unavailable")
    }
    return request
  }

  suspend fun markFetching(id: String): CaptureRequestEntity? {
    if (db.captureRequests().claimForFetch(id, nowMillis()) != 1) return null
    return db.captureRequests().byId(id)
  }

  suspend fun markRetryable(id: String, code: String, message: String): CaptureRequestEntity? {
    val now = nowMillis()
    val current = db.captureRequests().byId(id) ?: return null
    if (current.state != "pending" && current.state != "fetching") return null
    val attempt = current.attemptCount.coerceAtLeast(1)
    val parked = db.captureRequests().parkForRetry(
      id, code, message.take(500), now + backoffMillis(attempt, current.requestId), now, MAX_ATTEMPTS,
    )
    if (parked == 1) return db.captureRequests().byId(id)
    // Not parked: attempts exhausted (or a concurrent cancel won). On
    // exhaustion preserve a useful link; on cancel do nothing.
    val latest = db.captureRequests().byId(id) ?: return null
    if (latest.state != "pending" && latest.state != "fetching") return latest
    completeLinkOnly(
      id = id,
      generation = latest.generation,
      resolvedUrl = latest.resolvedUrl,
      title = latest.title ?: fallbackTitle(latest),
      reason = message,
      errorCode = code,
    )
    return db.captureRequests().byId(id)
  }

  suspend fun cancel(id: String) {
    db.captureRequests().cancelActive(id, nowMillis())
  }

  suspend fun retry(id: String): CaptureRequestEntity? {
    if (db.captureRequests().reopenForRetry(id, nowMillis()) != 1) return null
    return db.captureRequests().byId(id)
  }

  suspend fun retryWithSchedule(context: android.content.Context, id: String): CaptureRequestEntity? {
    if (db.captureRequests().reopenForRetry(id, nowMillis()) != 1) return null
    CaptureWorker.scheduleById(context, id)
    return db.captureRequests().byId(id)
  }

  /**
   * Crash-safe article commit. Inserts the document (or reuses an identical
   * existing one) and marks the request completed in one transaction, only
   * when the generation still matches. Returns the document ID, or null when
   * a stale/cancelled worker lost the race (caller must not report success).
   */
  suspend fun completeWithArticle(
    id: String,
    generation: Int,
    extracted: ArticleExtractor.ExtractedArticle,
    resolvedUrl: String,
  ): String? = db.withTransaction {
    val current = db.captureRequests().byId(id) ?: return@withTransaction null
    if (current.generation != generation) return@withTransaction null
    if (current.state != "pending" && current.state != "fetching") return@withTransaction null
    val now = nowMillis()
    val canonical = ReaderCore.canonicalize(extracted.markdown)
    val documentId = ReaderCore.documentId(canonical)
    val existing = db.documents().exists(documentId)
    if (!existing) {
      val host = CaptureUrlPolicy.hostOf(current.originalUrl)
      val doc = DocumentEntity(
        documentId = documentId,
        title = extracted.title.take(500),
        sourceType = "url",
        sourceName = host.take(200).ifBlank { null },
        sourceUrl = current.originalUrl,
        author = extracted.author?.take(300),
        publishedAt = null,
        capturedAt = current.createdAt,
        language = extracted.language,
        canonicalMarkdown = canonical,
        wordCount = ReaderCore.effectiveWords(canonical),
        parserVersion = 2,
        state = "unread",
        progressBlockId = null,
        progressCharOffset = 0,
        progressFraction = 0f,
        lastOpenedAt = 0L,
        createdAt = now,
        updatedAt = now,
      )
      val row = db.insertDocumentIndexed(doc)
      if (row == -1L && !db.documents().exists(documentId)) {
        throw IllegalStateException("article commit failed")
      }
    }
    val updated = db.captureRequests().completeIfGeneration(
      id, generation, "completed", documentId, resolvedUrl, extracted.title.take(500), now,
    )
    if (updated != 1) {
      // Lost generation race after document insert? The document itself is
      // content-addressed and harmless (dedupe by hash), but the request must
      // not flip to completed from a stale worker. If the request row was
      // concurrently cancelled, the document remains as an orphan identical
      // to a deliberate recapture — acceptable and never resurrected later
      // because completions never delete documents.
      return@withTransaction null
    }
    documentId
  }

  /**
   * Honest link-only fallback: preserves the URL as a readable document that
   * explicitly says article text is unavailable, with Open-original + Retry +
   * selected-text guidance. Never empty, never a login page masquerading as
   * an article.
   */
  suspend fun completeLinkOnly(
    id: String,
    generation: Int,
    resolvedUrl: String?,
    title: String?,
    reason: String,
    errorCode: String = "extraction_failed",
  ): String? = db.withTransaction {
    val current = db.captureRequests().byId(id) ?: return@withTransaction null
    if (current.generation != generation) return@withTransaction null
    if (current.state != "pending" && current.state != "fetching") return@withTransaction null
    val now = nowMillis()
    val host = CaptureUrlPolicy.hostOf(current.originalUrl)
    val safeTitle = (title?.take(500)?.ifBlank { null } ?: current.subjectTitle ?: host.ifBlank { current.originalUrl }.take(500))
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(current.createdAt))
    val markdown = ReaderCore.canonicalize(
      buildString {
        append("# $safeTitle\n\n")
        append("> Article text is unavailable. $reason\n\n")
        append("[Open original](<${current.originalUrl.replace(">", "%3E")}>)\n\n")
        append("What you can do:\n\n")
        append("- Open the original in your browser to read the full page.\n\n")
        append("- To retry fetching, share the link to Reader again or use Retry.\n\n")
        append("- For pages that need login or JavaScript, open the original, select the text you want, then Share the selection to Reader.\n\n")
        append("Original link: ${current.originalUrl}\n\n")
        append("Captured: $date · Source: $host\n")
      },
    )
    val documentId = ReaderCore.documentId(markdown)
    if (!db.documents().exists(documentId)) {
      val doc = DocumentEntity(
        documentId = documentId,
        title = safeTitle,
        sourceType = "link",
        sourceName = host.take(200).ifBlank { null },
        sourceUrl = current.originalUrl,
        author = null,
        publishedAt = null,
        capturedAt = current.createdAt,
        language = null,
        canonicalMarkdown = markdown,
        wordCount = ReaderCore.effectiveWords(markdown),
        parserVersion = 2,
        state = "unread",
        progressBlockId = null,
        progressCharOffset = 0,
        progressFraction = 0f,
        lastOpenedAt = 0L,
        createdAt = now,
        updatedAt = now,
      )
      val row = db.insertDocumentIndexed(doc)
      if (row == -1L && !db.documents().exists(documentId)) throw IllegalStateException("link commit failed")
    }
    val updated = db.captureRequests().completeIfGeneration(
      id, generation, "link_only", documentId, resolvedUrl, safeTitle, now,
    )
    // Persist the actionable error alongside the link_only state for Retry UI.
    if (updated == 1) {
      db.captureRequests().byId(id)?.let { after ->
        db.captureRequests().update(after.copy(errorCode = errorCode.take(64), errorMessage = reason.take(500), updatedAt = now))
      }
    } else return@withTransaction null
    documentId
  }

  suspend fun markFailed(id: String, generation: Int, code: String, message: String) {
    val current = db.captureRequests().byId(id) ?: return
    if (current.generation != generation) return
    if (current.state != "pending" && current.state != "fetching") return
    db.captureRequests().update(
      current.copy(state = "failed", errorCode = code.take(64), errorMessage = message.take(500), updatedAt = nowMillis()),
    )
  }

  /**
   * Bound terminal history. Active (pending/fetching) rows are never touched;
   * documents are never deleted here — only request bookkeeping expires.
   */
  suspend fun purgeHistory(now: Long = nowMillis()): Int {
    var removed = db.captureRequests().purgeTerminalOlderThan(now - TERMINAL_RETENTION_MILLIS)
    val excess = db.captureRequests().terminalCount() - MAX_TERMINAL_ROWS
    if (excess > 0) removed += db.captureRequests().purgeOldestTerminal(excess)
    return removed
  }

  private fun fallbackTitle(request: CaptureRequestEntity): String {
    val host = CaptureUrlPolicy.hostOf(request.originalUrl)
    return request.subjectTitle ?: host.ifBlank { request.originalUrl }.take(500)
  }
}
