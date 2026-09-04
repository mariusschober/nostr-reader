package com.reader.app.cursor

/** One semantic reading position shared by scroll, TTS, and RSVP. */
data class SemanticCursor(
  val documentId: String,
  val blockId: String,
  val charOffset: Int,
) {
  companion object {
    fun start(documentId: String) = SemanticCursor(documentId, "b0", 0)
  }
}
