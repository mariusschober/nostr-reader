package com.reader.app.data

import androidx.room.withTransaction
import com.reader.app.core.ReaderCore
import com.reader.app.ui.Triage

/** Receipt describes only the fields changed by this deliberate action. */
data class FinishReceipt(val documentId: String, val previousList: String, val previousFinishedAt: Long?,
                         val finishedAt: Long, val changedAt: Long, val day: String, val creditedMinutes: Int, val revision: Long)

class ArticleCompletion(private val db: ReaderDb) {
  suspend fun finish(id: String): FinishReceipt = db.withTransaction {
    val doc = checkNotNull(db.documents().metadataById(id)) { "Article is unavailable" }
    val now = maxOf(System.currentTimeMillis(), doc.updatedAt + 1)
    val day = java.time.LocalDate.now().toString()
    val minutes = ReaderCore.readingMinutes(doc.wordCount)
    val credited = db.readingStats().recordFinish(id, minutes, day, now)
    db.documents().setList(id, Triage.ARCHIVED, now)
    FinishReceipt(id, doc.list, doc.finishedAt, doc.finishedAt ?: now, now, day, if (credited) minutes else 0, ArticleMutationClock.advance(id))
  }

  suspend fun undo(receipt: FinishReceipt): Boolean = db.withTransaction {
    val current = db.documents().metadataById(receipt.documentId) ?: return@withTransaction false
    // Progress may advance while the end card remains open. A different list
    // or finish timestamp is a later deliberate decision and always wins.
    if (ArticleMutationClock.current(receipt.documentId) != receipt.revision || current.list != Triage.ARCHIVED || current.finishedAt != receipt.finishedAt) return@withTransaction false
    db.readingStats().restoreFinish(current.documentId, receipt.previousFinishedAt, receipt.previousList, System.currentTimeMillis())
    if (receipt.creditedMinutes > 0) db.readingStats().addToDay(receipt.day, -receipt.creditedMinutes, -1)
    ArticleMutationClock.advance(receipt.documentId)
    true
  }
}

/** Deliberate organization changes invalidate old receipts; position writes do not. */
internal object ArticleMutationClock {
  private val revisions = java.util.concurrent.ConcurrentHashMap<String, Long>()
  fun current(id: String): Long = revisions[id] ?: 0L
  fun advance(id: String): Long = revisions.merge(id, 1L, Long::plus)!!
}
