package com.reader.app

import com.reader.app.core.*
import com.reader.app.rsvp.RsvpModel
import org.junit.Assert.*
import org.junit.Test

class RenderedProjectionTest {
  @Test fun ordinaryTextHasUsableCharacterBoundaries() {
    val boundaries = Graphemes("Reader text")
    for (offset in 0..11) {
      assertEquals(offset, boundaries.floor(offset))
      assertEquals(offset, boundaries.ceil(offset))
    }
  }

  @Test fun emojiAndCombiningSequencesRemainWhole() {
    for (text in listOf("e\u0301", "🌱", "👩🏽‍💻", "🇩🇪", "👨‍👩‍👧‍👦")) {
      val boundaries = Graphemes(text)
      for (offset in 1 until text.length) {
        assertEquals("floor $text at $offset", 0, boundaries.floor(offset))
        assertEquals("ceil $text at $offset", text.length, boundaries.ceil(offset))
      }
    }
  }

  @Test fun articleProjectionCanBeSelectedAndSpeedRead() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("# Heading\n\nRead **these** words 🌱.\n\nAnother paragraph.\n"))
    val start = projection.text.indexOf("Read")
    val end = projection.text.indexOf("Another") + "Another".length
    val selected = projection.range(start, end)!!
    assertTrue(projection.text.substring(selected).contains("Read these words 🌱."))
    assertTrue(RsvpModel.tokens(projection).any { it.text == "these" })
  }
}
