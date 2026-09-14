package com.reader.app.data

import androidx.room.withTransaction
import com.reader.app.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One resumable, article-scoped review round. Membership is snapshotted at
 * round start in source-passage order (rotated so the chosen quote is first);
 * advancing credits each quote at most once per round. It never touches the
 * global round.
 */
@kotlinx.serialization.Serializable
data class ArticleRound(
  val documentId: String,
  val order: List<String>,
  val cursor: Int = 0,
  val consumed: Set<String> = emptySet(),
  val completed: Boolean = false,
) {
  /** The quote currently presented, or null when the round is finished/empty. */
  val presentedId: String? get() = if (completed) null else order.getOrNull(cursor)
  val position: Int get() = (cursor + 1).coerceIn(1, order.size.coerceAtLeast(1))
  val total: Int get() = order.size
  val atLast: Boolean get() = cursor >= order.size - 1
}

/**
 * Passage-ordered membership rotated so [startHighlightId] (when it is a member)
 * is presented first; the rest continue to the end, then wrap so every member
 * appears exactly once. Pure, so the scoped round is unit-testable without Room.
 */
fun articleOrder(ids: List<String>, startHighlightId: String?): List<String> {
  val distinct = ids.distinct()
  return when {
    startHighlightId != null && startHighlightId in distinct ->
      distinct.dropWhile { it != startHighlightId } + distinct.takeWhile { it != startHighlightId }
    else -> distinct
  }
}

/**
 * Pure advance for a scoped round: credit [expectedId] once, then move to the
 * next member that still exists in [present], skipping deleted rows. Reaching
 * the end (or the last present member) completes the round. An expected-id
 * mismatch is a no-op so a stale or rapid repeat tap cannot double-advance.
 */
fun ArticleRound.advance(expectedId: String, present: Set<String>): ArticleRound {
  if (presentedId != expectedId) return this
  val consumed = if (expectedId in consumed) consumed else consumed + expectedId
  if (cursor >= order.size - 1) return copy(consumed = consumed, completed = true)
  var next = cursor + 1
  while (next < order.size && order[next] !in present) next++
  return if (next >= order.size) copy(consumed = consumed, completed = true)
  else copy(consumed = consumed, cursor = next)
}

/**
 * Pure step back to an earlier member without crediting it. Repeated Previous
 * stays on the first member; it never wraps forward. Deleted members are
 * skipped so the reader lands on a quote that still exists.
 */
fun ArticleRound.previous(expectedId: String, present: Set<String>): ArticleRound {
  if (presentedId != expectedId) return this
  var prev = (cursor - 1).coerceAtLeast(0)
  while (prev > 0 && order[prev] !in present) prev--
  return copy(cursor = prev, completed = false)
}

/**
 * Versioned JSON envelope for the chunked `review_state` storage. It carries
 * the global round and at most one article round side by side so neither scope
 * can overwrite the other inside the same Room transaction. A legacy bare
 * `ReviewState` blob decodes losslessly into [global] via [decodeReviewEnvelope].
 */
@kotlinx.serialization.Serializable
data class ReviewEnvelope(
  val version: Int = 2,
  val global: ReviewState? = null,
  val article: ArticleRound? = null,
)

/**
 * Central decoder for the persisted review blob. Accepts both the versioned
 * envelope and a legacy bare global `ReviewState`; every caller uses this so
 * legacy compatibility is not reimplemented differently.
 */
fun decodeReviewEnvelope(json: String): ReviewEnvelope = runCatching {
  val element = Json.parseToJsonElement(json)
  val obj = element as? JsonObject
  if (obj != null && (obj.containsKey("global") || obj.containsKey("version") || obj.containsKey("article"))) {
    Json.decodeFromString(ReviewEnvelope.serializer(), json)
  } else {
    ReviewEnvelope(global = Json.decodeFromString(ReviewState.serializer(), json))
  }
}.getOrDefault(ReviewEnvelope())

data class HighlightMutation(val before: HighlightEntity?, val after: HighlightEntity?)

/**
 * Read-only view of the persisted review cycle for the Highlights entry card.
 * It never starts, advances or repairs a session. [phase] is the scheduler's
 * own "base"/"bonus"/"done" phase and [remaining] counts the current quote plus
 * its queued peers in that phase, so no fabricated "X of Y" is shown while
 * Important bonus items may still follow.
 */
data class ReviewSummary(
  val exists: Boolean = false,
  val phase: String = "done",
  val remaining: Int = 0,
) {
  val finished: Boolean get() = !exists || phase == "done"
}

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
    // Idempotent retry: the journal re-invokes saveDraft after Room success
    // but before journal deletion (crash, delete failure, onSaved failure).
    // If the stored record already equals this draft, return it without
    // bumping revision or emitting a duplicate Undo mutation.
    if (editing != null && editing.startBlockId == draft.startBlockId && editing.startOffset == draft.startOffset &&
      editing.endBlockId == draft.endBlockId && editing.endOffset == draft.endOffset &&
      editing.quote == draft.quote && editing.color == draft.color &&
      editing.projectionVersion == draft.projectionVersion && editing.documentId == draft.documentId) {
      return@withTransaction HighlightMutation(editing, editing)
    }
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

  suspend fun toggleImportant(id: String): HighlightMutation? = db.withTransaction {
    val before = db.highlights().byId(id) ?: return@withTransaction null
    val after = before.copy(important = !before.important, updatedAt = System.currentTimeMillis(), revision = before.revision + 1)
    db.highlights().update(after); HighlightMutation(before, after)
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
  /** Live, read-only summary for the Highlights entry. Never writes. */
  fun observeSummary(): Flow<ReviewSummary> = db.review().observeParts().map { parts -> summarize(envelopeOf(parts).global) }

  private fun envelopeOf(parts: List<ReviewStatePartEntity>): ReviewEnvelope {
    if (parts.isEmpty()) return ReviewEnvelope()
    return runCatching {
      check(parts.map { it.part } == parts.indices.toList()) { "Review state is incomplete" }
      decodeReviewEnvelope(parts.joinToString("") { it.json })
    }.getOrDefault(ReviewEnvelope())
  }

  private fun summarize(state: ReviewState?): ReviewSummary {
    state ?: return ReviewSummary()
    if (state.members.isEmpty()) return ReviewSummary()
    val presented = ReviewScheduler.presentedId(state)
    val phase = if (presented != null) state.phase else "done"
    return ReviewSummary(exists = true, phase = phase, remaining = ReviewScheduler.presentationCount(state))
  }

  private suspend fun readEnvelope(): ReviewEnvelope {
    val parts = db.review().parts()
    if (parts.isEmpty()) return ReviewEnvelope()
    check(parts.map { it.part } == parts.indices.toList()) { "Review state is incomplete" }
    return decodeReviewEnvelope(parts.joinToString("") { it.json })
  }
  private suspend fun writeEnvelope(envelope: ReviewEnvelope) {
    val json = Json.encodeToString(envelope)
    db.review().clear()
    db.review().insert(json.chunked(CONTENT_PART_CHARS).mapIndexed { i, part -> ReviewStatePartEntity(i, part) })
  }
  private suspend fun read(): ReviewState? = readEnvelope().global
  private suspend fun write(state: ReviewState) = writeEnvelope(readEnvelope().copy(global = state))
  private suspend fun candidates() = db.highlights().reviewCandidates()

  suspend fun resume(chosen: String? = null, restart: Boolean = false): ReviewState = db.withTransaction {
    val eligible = candidates()
    val old = if (restart) runCatching { read() }.getOrNull() else read()
    val state = if (restart || chosen != null || old == null) ReviewScheduler.start(
      eligible.map { it.id }, java.security.SecureRandom().nextLong(), old?.currentId ?: old?.lastPresentedId, chosen,
    ) else ReviewScheduler.refresh(old, eligible.map { it.id }, eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id })
    write(state); state
  }

  /**
   * Present `chosen` without discarding an unfinished round. Used by the
   * quote-tap path; entering Review records nothing.
   */
  suspend fun focus(chosen: String): ReviewState = db.withTransaction {
    val eligible = candidates()
    val state = ReviewScheduler.focus(
      read(), eligible.map { it.id }, java.security.SecureRandom().nextLong(),
      eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id }, chosen,
    )
    write(state); state
  }

  suspend fun advance(expectedId: String): ReviewState? = db.withTransaction {
    val old = read() ?: return@withTransaction null
    if (ReviewScheduler.presentedId(old) != expectedId) return@withTransaction old
    val credited = if (old.focusedId != null) old.focusedReviewed else old.currentReviewed
    if (!credited) db.highlights().recordReview(expectedId, System.currentTimeMillis())
    val eligible = candidates()
    val marked = if (old.focusedId != null) old.copy(focusedReviewed = true) else old.copy(currentReviewed = true)
    val next = ReviewScheduler.advance(marked, eligible.map { it.id }, eligible.filter { it.important }.mapTo(mutableSetOf()) { it.id })
    write(next); next
  }
  suspend fun openedSource(expectedId: String) = db.withTransaction {
    val old = read() ?: return@withTransaction
    if (ReviewScheduler.presentedId(old) != expectedId) return@withTransaction
    val credited = if (old.focusedId != null) old.focusedReviewed else old.currentReviewed
    if (!credited) {
      db.highlights().recordReview(expectedId, System.currentTimeMillis())
      write(if (old.focusedId != null) old.copy(focusedReviewed = true) else old.copy(currentReviewed = true))
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

  // ---- Article-scoped rounds (brief section C). Kept beside the global scope. ----

  /** Read the persisted article round for one document, or null. Read-only. */
  suspend fun readArticleRound(documentId: String): ArticleRound? =
    readEnvelope().article?.takeIf { it.documentId == documentId }

  /**
   * Start a fresh article round from the passage-ordered ids. The order is
   * rotated so [startHighlightId] (when present) is presented first, then the
   * rest continue to the end and wrap so every member appears once. Replaces
   * any prior article round; the global round is untouched.
   */
  suspend fun startArticleRound(documentId: String, orderedIds: List<String>, startHighlightId: String? = null): ArticleRound =
    db.withTransaction {
      val round = ArticleRound(documentId = documentId, order = articleOrder(orderedIds, startHighlightId))
      writeEnvelope(readEnvelope().copy(article = round))
      round
    }

  /**
   * Credit the presented quote once for this round and advance, or finish on
   * the last card. An expected-id guard makes repeated rapid taps idempotent and
   * a stale tap a no-op. Deleted members are skipped at presentation.
   */
  suspend fun advanceArticle(expectedId: String): ArticleRound? = db.withTransaction {
    val envelope = readEnvelope()
    val round = envelope.article ?: return@withTransaction null
    if (round.presentedId != expectedId) return@withTransaction round
    if (expectedId !in round.consumed && db.highlights().byId(expectedId) != null) {
      db.highlights().recordReview(expectedId, System.currentTimeMillis())
    }
    val present = db.highlights().idsForDocument(round.documentId).toSet()
    val advanced = round.advance(expectedId, present)
    writeEnvelope(envelope.copy(article = advanced))
    advanced
  }

  /**
   * Step back to an earlier member without crediting it. Repeated Previous
   * stays on the first member; it never wraps forward.
   */
  suspend fun previousArticle(expectedId: String): ArticleRound? = db.withTransaction {
    val envelope = readEnvelope()
    val round = envelope.article ?: return@withTransaction null
    val present = db.highlights().idsForDocument(round.documentId).toSet()
    val stepped = round.previous(expectedId, present)
    writeEnvelope(envelope.copy(article = stepped))
    stepped
  }
}
