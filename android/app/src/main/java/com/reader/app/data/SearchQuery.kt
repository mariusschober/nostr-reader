package com.reader.app.data

/** SQLite LIKE pattern with user wildcards escaped so literal text stays literal. */
object SearchQuery {
  fun pattern(raw: String): String? {
    val query = raw.trim()
    if (query.isEmpty()) return null
    val escaped = query
      .replace("\\", "\\\\")
      .replace("%", "\\%")
      .replace("_", "\\_")
    return "%$escaped%"
  }
}
