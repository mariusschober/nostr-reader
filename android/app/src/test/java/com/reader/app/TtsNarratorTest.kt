package com.reader.app

import com.reader.app.core.ArticleParser
import com.reader.app.core.ArticleSection
import com.reader.app.core.RenderedText
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.ArticleIndex
import com.reader.app.data.PreparedSection
import com.reader.app.tts.TtsNarrator
import com.reader.app.tts.TtsPlaybackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused checks for the section-progressive narration lifecycle: bounded
 * section advance, Ended without replay, retryable failure that never restarts
 * from the beginning, and stale async loads that must not hijack a newer
 * session. The engine is a synchronous fake, so no real speech is involved.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class TtsNarratorTest {
  private fun section(documentId: String, index: Int, count: Int, text: String): PreparedSection {
    val projection = RenderedText.project(ArticleParser.parse(text))
    val length = projection.text.length.coerceAtLeast(1)
    val sections = (0 until count).map { i ->
      if (i == index) ArticleSection(0, length) else ArticleSection(0, 1)
    }
    return PreparedSection(ArticleIndex(documentId, length, sections), index, projection)
  }

  @Test
  fun narratesThroughTheSectionBoundary() = runTest {
    val engine = FakeEngine()
    val doc = "doc-boundary"
    val first = section(doc, 0, 2, "First sentence here.\n")
    val second = section(doc, 1, 2, "Third sentence now.\n")
    val requested = mutableListOf<Int>()
    val narrator = TtsNarrator(engine, this) { _, _, hint ->
      requested.add(hint)
      if (hint == 0) first else second
    }
    var last: TtsPlaybackState? = null
    narrator.onState = { last = it }

    narrator.start(doc, SemanticCursor(doc, "b0", 0), 1f, "Title", null)
    advanceUntilIdle()
    assertNotNull(last)
    assertTrue("narration starts", last!!.playing)
    assertTrue(engine.spoken.any { it.contains("First sentence") })

    engine.finish()
    advanceUntilIdle()

    assertEquals(listOf(0, 1), requested)
    assertTrue("the next section continues the article", engine.spoken.any { it.contains("Third sentence") })
    assertFalse("advancing is not an error", last!!.error != null)
  }

  @Test
  fun finalSectionEndsWithoutReplaying() = runTest {
    val engine = FakeEngine()
    val doc = "doc-end"
    val only = section(doc, 0, 1, "Only sentence.\n")
    val narrator = TtsNarrator(engine, this) { _, _, _ -> only }
    var last: TtsPlaybackState? = null
    narrator.onState = { last = it }

    narrator.start(doc, SemanticCursor(doc, "b0", 0), 1f, "Title", null)
    advanceUntilIdle()
    engine.finish()
    advanceUntilIdle()

    assertNotNull(last)
    assertTrue("final section reports Ended", last!!.ended)
    assertFalse(last!!.playing)
    assertFalse("Ended is not active", last!!.active)

    val spoken = engine.spoken.size
    narrator.play()
    advanceUntilIdle()
    assertEquals("Ended narration must not replay the final sentence", spoken, engine.spoken.size)
    assertTrue("the ended document is still reported", last!!.documentId == doc)
  }

  @Test
  fun aStaleSectionLoadCannotHijackANewerSession() = runTest {
    val engine = FakeEngine()
    val alpha = section("doc-a", 0, 1, "Alpha sentence.\n")
    val beta = section("doc-b", 0, 1, "Beta sentence.\n")
    val gate = CompletableDeferred<Unit>()
    val narrator = TtsNarrator(engine, this) { id, _, _ ->
      if (id == "doc-a") gate.await()
      if (id == "doc-a") alpha else beta
    }
    var last: TtsPlaybackState? = null
    narrator.onState = { last = it }

    narrator.start("doc-a", SemanticCursor("doc-a", "b0", 0), 1f, "Alpha", null)
    advanceUntilIdle()
    narrator.start("doc-b", SemanticCursor("doc-b", "b0", 0), 1f, "Beta", null)
    advanceUntilIdle()
    // The first article's load completes only now, after a newer Listen.
    gate.complete(Unit)
    advanceUntilIdle()

    assertNotNull(last)
    assertEquals("doc-b", last!!.documentId)
    assertTrue("the replaced narratation never speaks", engine.spoken.none { it.contains("Alpha") })
  }

  @Test
  fun aFailedSectionLoadRetriesThatSectionOnly() = runTest {
    val engine = FakeEngine()
    val doc = "doc-retry"
    val opening = section(doc, 0, 2, "Opening sentence.\n")
    val closing = section(doc, 1, 2, "Closing sentence.\n")
    var failNext = true
    val requested = mutableListOf<Int>()
    val narrator = TtsNarrator(engine, this) { _, _, hint ->
      requested.add(hint)
      when {
        hint == 0 -> opening
        failNext -> null
        else -> closing
      }
    }
    var last: TtsPlaybackState? = null
    narrator.onState = { last = it }

    narrator.start(doc, SemanticCursor(doc, "b0", 0), 1f, "Title", null)
    advanceUntilIdle()
    engine.finish()
    advanceUntilIdle()

    assertNotNull(last)
    assertNotNull("a failed section load surfaces an error", last!!.error)
    assertTrue("the error is retryable", last!!.retryable)
    assertFalse(last!!.playing)

    failNext = false
    narrator.play()
    advanceUntilIdle()

    assertEquals("retry asks for the failed section, not the article start", listOf(0, 1, 1), requested)
    assertTrue(engine.spoken.any { it.contains("Closing sentence") })
    assertTrue("the retry clears the error", last!!.error == null)
  }
}
