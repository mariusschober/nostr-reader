package com.reader.app.data

import androidx.room.withTransaction
import com.reader.app.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class HighlightMutation(val before: HighlightEntity?, val after: HighlightEntity?)

object HighlightAnchors {
  fun create(id: String, doc: DocumentEntity, projection: RenderedProjection, first: Int, last: Int, now: Long): HighlightEntity {
    val range = checkNotNull(projection.range(first, last)) { "Select some text first" }
    val start = range.first; val end = range.last + 1
    val quote = projection.text.substring(start, end)
    require(quote.toByteArray(Charsets.UTF_8).size <= MAX_QUOTE_BYTES) { "Select a shorter quote (up to 128 KiB)." }
    val a = projection.cursor(doc.documentId, start)
    val b = projection.cursor(doc.documentId, end)
    val startBlock = projection.blocks.first { it.id == a.blockId }
    val endBlock = projection.blocks.first { it.id == b.blockId }
    return HighlightEntity(id, doc.documentId, quote, doc.title, doc.sourceUrl, now, now,
      a.blockId, a.charOffset, b.blockId, b.charOffset, RENDERED_PROJECTION_VERSION,
      startBlock.canonical?.startUtf16 ?: 0, endBlock.canonical?.endUtf16 ?: 0,
      projection.text.substring(projection.graphemes.floor((start - 64).coerceAtLeast(0)), start),
      projection.text.substring(end, projection.graphemes.ceil((end + 64).coerceAtMost(projection.text.length))))
  }

  /** Never attach repeated text to the first match. Context fallback must be unique. */
  fun resolve(value: HighlightEntity, projection: RenderedProjection): IntRange? {
    if (value.projectionVersion == RENDERED_PROJECTION_VERSION &&
      projection.blocks.any { it.id == value.startBlockId } && projection.blocks.any { it.id == value.endBlockId }) {
      val start = projection.offset(value.startBlockId, value.startOffset)
      val end = projection.offset(value.endBlockId, value.endOffset)
      if (end > start && projection.text.substring(start, end) == value.quote) return start until end
    }
    var from = 0
    var resolved: IntRange? = null
    while (from <= projection.text.length - value.quote.length) {
      val at = projection.text.indexOf(value.quote, from)
      if (at < 0) break
      val end = at + value.quote.length
      val prefix = projection.text.substring((at - value.prefixContext.length).coerceAtLeast(0), at)
      val suffix = projection.text.substring(end, (end + value.suffixContext.length).coerceAtMost(projection.text.length))
      if (prefix == value.prefixContext && suffix == value.suffixContext) {
        if (resolved != null) return null
        resolved = at until end
      }
      from = at + 1
    }
    return resolved
  }
}

class HighlightRepository(private val db: ReaderDb) {
  suspend fun saveSelection(draft: HighlightEntity): HighlightMutation = db.withTransaction {
    val sameRange = db.highlights().byRange(draft.documentId, draft.projectionVersion,
      draft.startBlockId, draft.startOffset, draft.endBlockId, draft.endOffset)
    val editing = db.highlights().byId(draft.id)
    if (sameRange != null && sameRange.id != draft.id) {
      // Preserve differently colored overlaps; only exactly identical ranges reuse a record.
      if (editing != null) {
        db.highlights().deleteAtRevision(editing.id, editing.revision)
        return@withTransaction HighlightMutation(editing, null)
      }
      return@withTransaction HighlightMutation(sameRange, sameRange)
    }
    val before = editing ?: sameRange
    val after = if (before == null) draft else draft.copy(
      id = before.id, createdAt = before.createdAt, color = before.color, important = before.important,
      reviewCount = before.reviewCount, lastReviewedAt = before.lastReviewedAt, revision = before.revision + 1)
    if (before == null) db.highlights().insert(after) else db.highlights().update(after)
    HighlightMutation(before, after)
  }

  suspend fun recolor(id: String, color: String): HighlightMutation? = db.withTransaction {
    require(color in setOf("YELLOW", "GREEN", "CYAN", "PURPLE"))
    val before = db.highlights().byId(id) ?: return@withTransaction null
    val after = before.copy(color = color, updatedAt = System.currentTimeMillis(), revision = before.revision + 1)
    db.highlights().update(after); HighlightMutation(before, after)
  }
  suspend fun remove(id: String): HighlightMutation? = db.withTransaction {
    val before = db.highlights().byId(id) ?: return@withTransaction null
    if (db.highlights().deleteAtRevision(id, before.revision) == 1) HighlightMutation(before, null) else null
  }
  suspend fun undo(change: HighlightMutation): Boolean = db.withTransaction {
    val id = change.after?.id ?: change.before?.id ?: return@withTransaction false
    val current = db.highlights().byId(id)
    if (change.after == null) {
      val before = change.before ?: return@withTransaction false
      if (current != null || db.highlights().byRange(before.documentId, before.projectionVersion,
          before.startBlockId, before.startOffset, before.endBlockId, before.endOffset) != null) return@withTransaction false
      db.highlights().insert(before.copy(revision = before.revision + 1)); return@withTransaction true
    }
    if (current?.revision != change.after.revision) return@withTransaction false
    if (change.before == null) db.highlights().deleteAtRevision(id, current.revision)
    else db.highlights().update(change.before.copy(updatedAt = System.currentTimeMillis(), revision = current.revision + 1))
    true
  }
}

class ReviewRepository(private val db: ReaderDb) {
  private suspend fun read(): ReviewState? {
    val parts = db.review().parts()
    if (parts.isEmpty()) return null
    check(parts.map { it.part } == parts.indices.toList()) { "Review state is incomplete" }
    return Json.decodeFromString(parts.joinToString("") { it.json })
  }
  private suspend fun write(state: ReviewState) {
    val json = Json.encodeToString(state)
    db.review().clear()
    db.review().insert(json.chunked(CONTENT_PART_CHARS).mapIndexed { i, part -> ReviewStatePartEntity(i, part) })
  }
  private suspend fun candidates() = db.highlights().reviewCandidates()

  suspend fun resume(chosen: String? = null, restart: Boolean = false): ReviewState = db.withTransaction {
    val eligible = candidates()
    val old = if (restart) runCatching { read() }.getOrNull() else read()
    val state = if (restart || chosen != null || old == null) ReviewScheduler.start(
      eligible.map { it.id }, java.security.SecureRandom().nextLong(), old?.currentId ?: old?.lastPresentedId, chosen,
    ) else ReviewScheduler.refresh(old, eligible.map { it.id }, eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id })
    write(state); state
  }
  suspend fun advance(expectedId: String): ReviewState? = db.withTransaction {
    val old = read() ?: return@withTransaction null
    if (old.currentId != expectedId) return@withTransaction old
    if (!old.currentReviewed) db.highlights().recordReview(expectedId, System.currentTimeMillis())
    val eligible = candidates()
    val next = ReviewScheduler.advance(old, eligible.map { it.id }, eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id })
    write(next); next
  }
  suspend fun openedSource(expectedId: String) = db.withTransaction {
    val old = read() ?: return@withTransaction
    if (old.currentId == expectedId && !old.currentReviewed) {
      db.highlights().recordReview(expectedId, System.currentTimeMillis())
      write(old.copy(currentReviewed = true))
    }
  }
  suspend fun toggleImportant(id: String): HighlightEntity? = db.withTransaction {
    val old = db.highlights().byId(id) ?: return@withTransaction null
    val updated = old.copy(important = !old.important, updatedAt = System.currentTimeMillis(), revision = old.revision + 1)
    db.highlights().update(updated)
    val state = read()
    if (state != null) {
      val eligible = candidates()
      write(ReviewScheduler.refresh(state, eligible.map { it.id }, eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id }))
    }
    updated
  }
}
