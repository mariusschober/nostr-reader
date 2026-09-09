package com.reader.app.data

import androidx.room.withTransaction
import com.reader.app.ui.Triage

data class ArticleMove(val documentId: String, val previous: String, val target: String)

/** One committed group is one Undo. A failed transaction never advertises success. */
class ArticleMoves(private val db: ReaderDb) {
  suspend fun move(ids: Set<String>, target: String): List<ArticleMove> = db.withTransaction {
    require(target in Triage.TABS)
    val moves = ids.mapNotNull { id ->
      val doc = checkNotNull(db.documents().metadataById(id)) { "An article is no longer available. Try again." }
      if (doc.list == target) null else ArticleMove(id, doc.list, target)
    }
    val now = System.currentTimeMillis()
    moves.forEach { db.documents().setList(it.documentId, it.target, now) }
    moves
  }

  /** Never resurrect a deleted article or overwrite a later deliberate move. */
  suspend fun undo(moves: List<ArticleMove>): Int = db.withTransaction {
    val now = System.currentTimeMillis()
    var restored = 0
    moves.forEach { move ->
      if (db.documents().metadataById(move.documentId)?.list == move.target) {
        db.documents().setList(move.documentId, move.previous, now)
        restored++
      }
    }
    restored
  }
}
