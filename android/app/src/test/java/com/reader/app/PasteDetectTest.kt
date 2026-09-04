package com.reader.app

import com.reader.app.sync.Ingest
import org.junit.Assert.*
import org.junit.Test

class PasteDetectTest {
  @Test
  fun plainTextStaysLiteral() {
    assertFalse(Ingest.looksLikeMarkdown("Just a few words here"))
    assertFalse(Ingest.looksLikeMarkdown("First line.\nSecond line."))
    assertFalse(Ingest.looksLikeMarkdown(""))
    assertFalse(Ingest.looksLikeMarkdown("   "))
  }

  @Test
  fun trickyProseStaysLiteral() {
    // '#' mid-sentence and '1.' mid-line are not structure.
    assertFalse(Ingest.looksLikeMarkdown("I give it #1 priority for sure"))
    assertFalse(Ingest.looksLikeMarkdown("Meet at 5. Bring version 1.2 of the doc"))
    assertFalse(Ingest.looksLikeMarkdown("Use a #hashtag and 3.5 stars"))
  }

  @Test
  fun headingsListsFencesCount() {
    assertTrue(Ingest.looksLikeMarkdown("# Title\nSome body"))
    assertTrue(Ingest.looksLikeMarkdown("Intro\n\n- one\n- two"))
    assertTrue(Ingest.looksLikeMarkdown("Steps:\n1. first\n2. second"))
    assertTrue(Ingest.looksLikeMarkdown("Code:\n```\nx = 1\n```"))
  }

  @Test
  fun linksAndTablesNeedBlankLine() {
    assertTrue(Ingest.looksLikeMarkdown("Read this.\n\nSee [the docs](https://example.com) for more."))
    assertFalse(Ingest.looksLikeMarkdown("See [the docs](https://example.com) ok"))
    assertTrue(Ingest.looksLikeMarkdown("Data:\n\n| A | B |\n|---|---|\n| 1 | 2 |"))
    assertFalse(Ingest.looksLikeMarkdown("a | b"))
  }
}
