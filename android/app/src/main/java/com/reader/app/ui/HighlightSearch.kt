package com.reader.app.ui

import com.reader.app.data.HighlightSummary

/** Case-insensitive quote/source filter for the highlights tab. */
object HighlightSearch {
  fun matches(quote: HighlightSummary, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return quote.preview.contains(needle, ignoreCase = true) ||
      quote.sourceTitle.contains(needle, ignoreCase = true)
  }
}
