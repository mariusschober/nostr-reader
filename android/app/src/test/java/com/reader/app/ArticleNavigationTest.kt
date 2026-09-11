package com.reader.app

import com.reader.app.data.matchSnippet
import org.junit.Assert.*
import org.junit.Test

class ArticleNavigationTest {
  private val needle = "workersAll"

  @Test fun matchRangePointsAtTheNeedle() {
    val text = "a".repeat(50) + needle + "b".repeat(100)
    val at = text.indexOf(needle)
    val window = matchSnippet(text, at, at + needle.length)
    assertEquals(needle, window.text.substring(window.matchStart, window.matchEnd))
  }

  @Test fun newlineInSnippetKeepsOffsetsStable() {
    val text = "first line\nsecond line " + needle + " and the rest of the sentence."
    val at = text.indexOf(needle)
    val window = matchSnippet(text, at, at + needle.length)
    assertFalse(window.text.contains('\n'))
    assertEquals(needle, window.text.substring(window.matchStart, window.matchEnd))
  }

  @Test fun matchAtStartClampsToZero() {
    val text = needle + " " + "tail ".repeat(30)
    val window = matchSnippet(text, 0, needle.length)
    assertEquals(0, window.matchStart)
    assertEquals(needle.length, window.matchEnd)
    assertEquals(needle, window.text.substring(window.matchStart, window.matchEnd))
  }

  @Test fun matchNearEndClampsStopToTextLength() {
    val text = "head ".repeat(30) + needle
    val at = text.indexOf(needle)
    val window = matchSnippet(text, at, text.length)
    assertTrue(at + needle.length <= text.length)
    assertEquals(needle, window.text.substring(window.matchStart, window.matchEnd))
  }
}
