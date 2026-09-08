package com.reader.app.ui

import com.reader.app.cursor.SemanticCursor

@kotlinx.serialization.Serializable
sealed interface Route {
  @kotlinx.serialization.Serializable data object Inbox : Route
  @kotlinx.serialization.Serializable data class Reader(val id: String, val highlightId: String? = null) : Route
  @kotlinx.serialization.Serializable data class Rsvp(val id: String, val from: SemanticCursor) : Route
  @kotlinx.serialization.Serializable data object Pairing : Route
  @kotlinx.serialization.Serializable data object Settings : Route
  @kotlinx.serialization.Serializable data object Review : Route
}
