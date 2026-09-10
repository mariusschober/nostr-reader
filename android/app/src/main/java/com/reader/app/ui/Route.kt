package com.reader.app.ui

import com.reader.app.cursor.SemanticCursor

@kotlinx.serialization.Serializable
sealed interface Route {
  @kotlinx.serialization.Serializable data object Archive : Route
  @kotlinx.serialization.Serializable data object Inbox : Route
  @kotlinx.serialization.Serializable data class Reader(
    val id: String,
    val highlightId: String? = null,
    /** Search landing: open at this cursor with a fading match tint. */
    val at: SemanticCursor? = null,
    /** Rendered-text end offset of the search match (tint span). */
    val atEnd: Int? = null,
  ) : Route
  @kotlinx.serialization.Serializable data class Rsvp(val id: String, val from: SemanticCursor) : Route
  @kotlinx.serialization.Serializable data object Pairing : Route
  @kotlinx.serialization.Serializable data object Settings : Route
  @kotlinx.serialization.Serializable data object Review : Route
}
