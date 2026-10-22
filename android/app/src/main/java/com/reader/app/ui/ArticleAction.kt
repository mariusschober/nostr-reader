package com.reader.app.ui

/**
 * One authoritative triage mapping for list rows, open-article swipes, and the
 * reader overflow menu. The list and the reader must never disagree about what
 * a gesture does.
 */
enum class ArticleAction(val label: String, val releaseLabel: String, val threshold: Float, val target: String?) {
  Inbox("Move to Inbox", "Release to move to Inbox", .30f, Triage.INBOX),
  Priority("Move to Priority", "Release to move to Priority", .30f, Triage.PRIORITY),
  Later("Move to Later", "Release to move to Later", .30f, Triage.LATER),
  Archive("Archive", "Release to archive", .30f, Triage.ARCHIVED),
  Unarchive("Unarchive", "Release to unarchive", .30f, Triage.INBOX),
  Delete("Delete", "Release to delete", .60f, null);

  companion object {
    /** Swipe target for an open article, mirroring [Triage.swipeTarget] exactly. */
    fun forSwipe(list: String?, right: Boolean): ArticleAction? = when (list) {
      Triage.ARCHIVED -> if (right) Unarchive else Delete
      Triage.INBOX, Triage.PRIORITY, Triage.LATER ->
        Triage.swipeTarget(list, if (right) Triage.Swipe.RIGHT else Triage.Swipe.LEFT)?.let(::forTarget)
      else -> null
    }

    fun forTarget(target: String): ArticleAction? = when (target) {
      Triage.INBOX -> Inbox
      Triage.PRIORITY -> Priority
      Triage.LATER -> Later
      Triage.ARCHIVED -> Archive
      else -> null
    }

    /** Explicit menu choices; the current list is present but disabled by the caller. */
    fun forMenu(list: String?): List<ArticleAction> = when (list) {
      Triage.ARCHIVED -> listOf(Unarchive, Delete)
      Triage.INBOX, Triage.PRIORITY, Triage.LATER -> listOf(Inbox, Priority, Later, Archive)
      else -> emptyList()
    }

    fun swipeHint(list: String?): String? {
      val left = forSwipe(list, right = false) ?: return null
      val right = forSwipe(list, right = true) ?: return null
      return "Swipe inside the page: ← ${left.label} · ${right.label} →"
    }

    fun commits(action: ArticleAction?, distance: Float, width: Float): Boolean =
      action != null && width > 0 && kotlin.math.abs(distance) >= width * action.threshold
  }
}
