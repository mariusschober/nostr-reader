package com.reader.app

import com.reader.app.ui.RelaySummary
import com.reader.app.ui.SourceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLabelsTest {
  @Test
  fun protocolSourceIdsBecomeReaderFacingLabels() {
    assertEquals("Web article", SourceLabels.label("web"))
    assertEquals("Chrome selection", SourceLabels.label("selection"))
    assertEquals("Shared from Android", SourceLabels.label("android-share"))
    assertEquals("Android selection", SourceLabels.label("android-process-text"))
    assertEquals("ChatGPT", SourceLabels.label("chatgpt"))
    assertEquals("Imported file", SourceLabels.label("file"))
  }

  @Test
  fun unknownSourceIdsAreStillReadableInsteadOfRawProtocolValues() {
    assertEquals("Future Capture", SourceLabels.label("future-capture"))
  }

  @Test
  fun relaySummaryFormatsDefaultsAndCustomRelaysWithoutJson() {
    val defaults = listOf("wss://nos.lol", "wss://relay.primal.net")
    val text = RelaySummary.format(
      "[\"wss://nos.lol\",\"wss://relay.primal.net\",\"wss://custom.example\"]",
      defaults, quorum = 2,
    )
    assertTrue(text.contains("3 paired relays"))
    assertTrue(text.contains("1. wss://nos.lol (default)"))
    assertTrue(text.contains("3. wss://custom.example (custom)"))
    assertTrue(!text.contains("[\""))
  }

  @Test
  fun missingRelayStateExplainsDefaultsInsteadOfShowingEmptyJson() {
    val text = RelaySummary.format(null, List(6) { "wss://relay$it.example" }, quorum = 2)
    assertTrue(text.contains("6 default public relays"))
    assertTrue(text.contains("2 required"))
  }
}
