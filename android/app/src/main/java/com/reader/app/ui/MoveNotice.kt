package com.reader.app.ui

import com.reader.app.data.ArticleMove
import java.util.UUID

data class MoveNotice(val moves: List<ArticleMove>, val id: String = UUID.randomUUID().toString()) {
  init { require(moves.isNotEmpty()) }
  val message: String get() {
    val target = moves.first().target
    if (moves.size == 1) return Triage.movedLabel(target)
    return when (target) {
      Triage.ARCHIVED -> "Archived ${moves.size} articles"
      Triage.INBOX -> "Moved ${moves.size} articles to Inbox"
      else -> "Moved ${moves.size} articles to ${Triage.tabLabel(target)}"
    }
  }
}
