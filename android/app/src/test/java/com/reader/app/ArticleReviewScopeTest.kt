package com.reader.app

import com.reader.app.core.ReviewState
import com.reader.app.data.ArticleRound
import com.reader.app.data.ReviewEnvelope
import com.reader.app.data.advance
import com.reader.app.data.articleOrder
import com.reader.app.data.decodeReviewEnvelope
import com.reader.app.data.previous
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused checks for the article-scoped review round: legacy envelope
 * compatibility, scope isolation and the credit/skip/complete transitions.
 * Real behavior is exercised, not a re-implementation of the repository.
 */
class ArticleReviewScopeTest {
  private val globalState = ReviewState(
    seed = 7L, randomState = 9L, members = listOf("a", "b"),
    baseRemaining = listOf("b"), currentId = "a",
  )

  @Test fun legacyBareGlobalStateDecodesLosslessly() {
    // A stored blob written before the envelope existed is a bare ReviewState.
    val legacy = Json.encodeToString(globalState)
    val envelope = decodeReviewEnvelope(legacy)
    assertEquals(globalState, envelope.global)
    assertNull(envelope.article)
  }

  @Test fun versionedEnvelopeRoundTripsBothScopes() {
    val round = ArticleRound(documentId = "doc", order = listOf("x", "y"), cursor = 1, consumed = setOf("x"))
    val json = Json.encodeToString(ReviewEnvelope(global = globalState, article = round))
    val envelope = decodeReviewEnvelope(json)
    assertEquals(globalState, envelope.global)
    assertEquals(round, envelope.article)
  }

  @Test fun writingOneScopePreservesTheOther() {
    // Article write must not drop the global round, and vice versa.
    val round = ArticleRound(documentId = "doc", order = listOf("x"))
    val withArticle = ReviewEnvelope().copy(article = round)
    assertNull("Article-only write leaves the global round absent", withArticle.global)
    val withGlobal = withArticle.copy(global = globalState)
    assertEquals("Global write keeps the existing article round", round, withGlobal.article)
    assertEquals(globalState, withGlobal.global)
  }

  @Test fun emptyOrUnparseableBlobDegradesSafely() {
    assertEquals(ReviewEnvelope(), decodeReviewEnvelope(""))
    assertEquals(ReviewEnvelope(), decodeReviewEnvelope("not json at all"))
  }

  @Test fun presentedPositionAndLastSemantics() {
    val round = ArticleRound("doc", listOf("a", "b", "c"))
    assertEquals("a", round.presentedId)
    assertEquals(1, round.position)
    assertEquals(3, round.total)
    assertFalse(round.atLast)
    assertTrue(round.copy(cursor = 2).atLast)
    assertNull("A completed round presents nothing", round.copy(cursor = 2, completed = true).presentedId)
  }

  @Test fun orderRotatesChosenMemberFirstWithoutDuplicates() {
    assertEquals(listOf("c", "a", "b"), articleOrder(listOf("a", "b", "c"), "c"))
    assertEquals(listOf("a", "b", "c"), articleOrder(listOf("a", "b", "c"), null))
    assertEquals("Duplicates are removed", listOf("a", "b"), articleOrder(listOf("a", "a", "b"), "a"))
    assertEquals("A chosen id outside the set is ignored", listOf("a", "b"), articleOrder(listOf("a", "b"), "zzz"))
  }

  @Test fun advanceCreditsOnceSkipsDeletedAndCompletesAtEnd() {
    val present = setOf("a", "c") // "b" was deleted
    val round = ArticleRound("doc", listOf("a", "b", "c"))
    val next = round.advance("a", present)
    assertEquals("Credited exactly once", setOf("a"), next.consumed)
    assertEquals("Deleted member skipped", "c", next.presentedId)
    assertEquals("A late repeat tap is a no-op", next, next.advance("a", present))
    val done = next.advance("c", present)
    assertTrue("Last present member finishes the round", done.completed)
    assertNull(done.presentedId)
    assertEquals(setOf("a", "c"), done.consumed)
  }

  @Test fun advanceIgnoresStaleExpectedId() {
    val round = ArticleRound("doc", listOf("a", "b"))
    val same = round.advance("z", setOf("a", "b"))
    assertEquals(round, same)
    assertTrue("Nothing is credited on an expected-id mismatch", same.consumed.isEmpty())
  }

  @Test fun previousStepsBackWithoutCrediting() {
    val present = setOf("a", "c") // "b" was deleted
    val round = ArticleRound("doc", listOf("a", "b", "c"), cursor = 2)
    val back = round.previous("c", present)
    assertEquals("Deleted member skipped", "a", back.presentedId)
    assertTrue("Previous credits nothing", back.consumed.isEmpty())
    assertFalse(back.completed)
    assertEquals("Repeated Previous stays on the first member", "a", back.previous("a", present).presentedId)
  }
}
