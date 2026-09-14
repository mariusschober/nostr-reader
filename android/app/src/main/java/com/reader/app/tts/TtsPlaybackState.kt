package com.reader.app.tts

import com.reader.app.cursor.SemanticCursor

/**
 * Compact, observable playback state. It carries metadata, transport state and
 * a semantic spoken position — never narration units or article text — so the
 * UI and the media session observe it cheaply without copying whole articles.
 */
data class TtsPlaybackState(
  val documentId: String? = null,
  val sourceTitle: String = "",
  val sourceUrl: String? = null,
  val playing: Boolean = false,
  val loading: Boolean = false,
  val ended: Boolean = false,
  val error: String? = null,
  val requiresNetwork: Boolean? = null,
  val speed: Float = 1f,
  val cursor: SemanticCursor? = null,
  val index: Int = 0,
  val total: Int = 0,
  /** Set when a section load failed and the reader can retry without restarting. */
  val retryable: Boolean = false,
) {
  /** A narration exists for some article and has not finished. */
  val active: Boolean get() = documentId != null && !ended
}

