package com.reader.app

import com.reader.app.data.HighlightSummary
import com.reader.app.ui.HighlightSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightSearchTest {
  private val quote = HighlightSummary(
    id = "h1", documentId = "d1", preview = "The quiet cost of speed",
    quoteLength = 23, sourceTitle = "Harbor Lights", createdAt = 1,
    color = "YELLOW", important = false,
  )

  @Test
  fun blankQueryMatchesEverything() {
    assertTrue(HighlightSearch.matches(quote, ""))
    assertTrue(HighlightSearch.matches(quote, "   "))
  }

  @Test
  fun queryMatchesQuoteAndSourceCaseInsensitively() {
    assertTrue(HighlightSearch.matches(quote, "QUIET"))
    assertTrue(HighlightSearch.matches(quote, "harbor"))
    assertFalse(HighlightSearch.matches(quote, "missing"))
  }
}
