package com.reader.app.ui

import com.reader.app.cursor.SemanticCursor

sealed interface Route {
  data object Inbox : Route
  data class Reader(val id: String) : Route
  data class Rsvp(val id: String, val from: SemanticCursor) : Route
  data object Pairing : Route
  data object Settings : Route
}
