package com.reader.app.ui

/** Completion of an Important command belongs to the quote that issued it. */
internal suspend fun <T> completeReviewCommand(
  id: String,
  load: suspend (String) -> T,
  currentId: () -> String?,
  apply: (T) -> Unit,
) {
  val result = load(id)
  if (currentId() == id) apply(result)
}
