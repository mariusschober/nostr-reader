package com.reader.app.ui

/** Highlight feed actions share release thresholds with the article lists. */
enum class HighlightAction(val label: String, val releaseLabel: String, val threshold: Float) {
  Important("Important", "Release to mark important", .30f),
  Remove("Remove highlight", "Release to remove", .60f);
  companion object {
    fun forSwipe(right: Boolean): HighlightAction = if (right) Important else Remove
    fun commits(action: HighlightAction?, distance: Float, width: Float): Boolean =
      action != null && width > 0 && kotlin.math.abs(distance) >= width * action.threshold
  }
}
