package com.reader.app.ui

/** Article actions share their release thresholds across the archive and reader. */
enum class ArticleAction(val label: String, val releaseLabel: String, val threshold: Float, val target: String?) {
  Later("Later", "Release to move to Later", .30f, Triage.LATER),
  Archive("Archive", "Release to archive", .30f, Triage.ARCHIVED),
  Unarchive("Unarchive", "Release to unarchive", .30f, Triage.INBOX),
  Delete("Delete permanently", "Release to delete", .60f, null);
  companion object {
    fun forArticle(list: String?, right: Boolean): ArticleAction? = when {
      list == Triage.ARCHIVED -> if (right) Unarchive else Delete
      right && list == Triage.LATER -> null
      right -> Later
      else -> Archive
    }
    fun commits(action: ArticleAction?, distance: Float, width: Float): Boolean =
      action != null && width > 0 && kotlin.math.abs(distance) >= width * action.threshold
  }
}
