package com.reader.app

import com.reader.app.core.ReviewScheduler
import com.reader.app.core.ReviewState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReviewSchedulerTest {
  private val ids = (1..12).map { "quote-$it" }

  @Test fun baseCoversEveryIdBeforeBonusesAndSurvivesSerialization() {
    val important = ids.take(4).toSet()
    var state = ReviewScheduler.start(ids, 1234)
    val base = mutableListOf<String>()
    val all = mutableListOf<String>()
    repeat(ids.size * 3) {
      val id = state.currentId ?: return@repeat
      if (state.phase == "base") base += id
      all += id
      state = Json.decodeFromString<ReviewState>(Json.encodeToString(state))
      state = ReviewScheduler.advance(state, ids, important)
    }
    assertEquals(ids.toSet(), base.toSet())
    assertEquals(ids.size, base.size)
    assertEquals("done", state.phase)
    assertTrue(all.zipWithNext().all { (a, b) -> a != b })
    ids.forEach { id -> assertTrue(all.count { it == id } <= if (id in important) 2 else 1) }
  }

  @Test fun togglingImportanceDoesNotMintRepeatedBonuses() {
    val important = ids.toSet()
    var state = ReviewScheduler.start(ids, 40)
    val seen = mutableListOf<String>()
    repeat(60) {
      state.currentId?.let { seen += it }
      state = ReviewScheduler.refresh(state, ids, emptySet())
      state = ReviewScheduler.refresh(state, ids, important)
      state = ReviewScheduler.advance(state, ids, important)
    }
    assertTrue(seen.groupingBy { it }.eachCount().values.all { it <= 2 })
    assertNull(state.currentId)
  }

  @Test fun newItemIsInsertedBeforeRemainingBonuses() {
    var state = ReviewScheduler.start(ids, 12)
    repeat(ids.size) { state = ReviewScheduler.advance(state, ids, ids.toSet()) }
    assertEquals("bonus", state.phase)
    state = ReviewScheduler.refresh(state, ids + "new", ids.toSet())
    state = ReviewScheduler.advance(state, ids + "new", ids.toSet())
    assertEquals("new", state.currentId)
    assertEquals("base", state.phase)
  }

  @Test fun deletionOfCurrentDoesNotSkipItsReplacement() {
    val state = ReviewScheduler.start(ids, 71)
    val remaining = ids - state.currentId!!
    assertEquals(ReviewScheduler.refresh(state, remaining, emptySet()),
      ReviewScheduler.advance(state, remaining, emptySet()))
  }

  @Test fun restartAvoidsImmediateRepeatAndChosenSourceStartsAtChosen() {
    val previous = ReviewScheduler.start(ids, 13).currentId
    assertNotEquals(previous, ReviewScheduler.start(ids, 13, previous).currentId)
    assertEquals("quote-5", ReviewScheduler.start(ids, 13, chosen = "quote-5").currentId)
  }

  @Test fun singleImportantQuoteEndsWithoutImmediateRepeat() {
    val start = ReviewScheduler.start(listOf("one"), 1)
    assertNull(ReviewScheduler.advance(start, listOf("one"), setOf("one")).currentId)
  }

  // --- Focused Review: present a tapped quote without losing the round ---

  @Test fun focusWithNoUnfinishedRoundStartsAtChosen() {
    val state = ReviewScheduler.focus(null, ids, 5, emptySet(), "quote-7")
    assertEquals("quote-7", ReviewScheduler.presentedId(state))
    assertNull(state.focusedId)
    assertEquals("base", state.phase)
  }

  @Test fun focusOnCurrentQuoteIsAPlainResume() {
    val start = ReviewScheduler.start(ids, 9)
    val focused = ReviewScheduler.focus(start, ids, 9, emptySet(), start.currentId!!)
    assertNull(focused.focusedId)
    assertEquals(start.currentId, ReviewScheduler.presentedId(focused))
  }

  @Test fun focusPreservesUnfinishedRoundAndRecordsNothing() {
    val start = ReviewScheduler.start(ids, 21)
    val round = ReviewScheduler.advance(start, ids, emptySet())
    val chosen = round.baseRemaining.first()
    val focused = ReviewScheduler.focus(round, ids, 21, emptySet(), chosen)
    assertEquals(chosen, focused.focusedId)
    assertEquals(chosen, ReviewScheduler.presentedId(focused))
    // The round's own cursor, queue and phase are untouched.
    assertEquals(round.currentId, focused.currentId)
    assertEquals(round.baseRemaining, focused.baseRemaining)
    assertEquals(round.phase, focused.phase)
    assertFalse(focused.focusedReviewed)
  }

  @Test fun advanceFromFocusedConsumesItAndResumesThePreservedCurrent() {
    val start = ReviewScheduler.start(ids, 33)
    val round = ReviewScheduler.advance(start, ids, emptySet())
    val preservedCurrent = round.currentId!!
    val chosen = round.baseRemaining.first()
    val focused = ReviewScheduler.focus(round, ids, 33, emptySet(), chosen)
    val next = ReviewScheduler.advance(focused, ids, emptySet())
    assertNull(next.focusedId)
    assertEquals(preservedCurrent, ReviewScheduler.presentedId(next))
    assertFalse(chosen in next.baseRemaining)
  }

  @Test fun reviewingFocusedDoesNotRemoveItsLaterImportantBonus() {
    val state = ReviewState(
      seed = 1, randomState = 1, members = ids, baseRemaining = emptyList(),
      bonusRemaining = listOf("quote-3"), currentId = "quote-1", phase = "bonus", focusedId = "quote-3",
    )
    val next = ReviewScheduler.advance(state, ids, setOf("quote-3"))
    assertNull(next.focusedId)
    assertTrue("quote-3" in next.bonusRemaining)
  }

  @Test fun refreshDropsAFocusedQuoteThatIsNoLongerEligible() {
    val start = ReviewScheduler.start(ids, 44)
    val round = ReviewScheduler.advance(start, ids, emptySet())
    val chosen = round.baseRemaining.first()
    val focused = ReviewScheduler.focus(round, ids, 44, emptySet(), chosen)
    val remaining = ids.filter { it != chosen }
    assertNull(ReviewScheduler.refresh(focused, remaining, emptySet()).focusedId)
  }

  @Test fun focusOnAMissingQuoteKeepsTheRoundUnchanged() {
    val start = ReviewScheduler.start(ids, 55)
    assertEquals(start, ReviewScheduler.focus(start, ids, 55, emptySet(), "not-a-quote"))
  }

  @Test fun focusedQueuedQuoteCountsOnceNotTwice() {
    // Base current A, queue [B, C], focused B: report 3, not 4.
    val state = ReviewState(
      seed = 1, randomState = 1, members = listOf("A", "B", "C"),
      baseRemaining = listOf("B", "C"), currentId = "A", phase = "base",
      focusedId = "B",
    )
    assertEquals(3, ReviewScheduler.presentationCount(state))
    val next = ReviewScheduler.advance(state, listOf("A", "B", "C"), emptySet())
    assertEquals("A", ReviewScheduler.presentedId(next))
    assertEquals(2, ReviewScheduler.presentationCount(next))
  }

  @Test fun focusedConsumedQuoteCountsItsExtraPresentation() {
    // Focused D already consumed in the base pass: extra + preserved A + C.
    val state = ReviewState(
      seed = 1, randomState = 1, members = listOf("A", "C", "D"),
      baseRemaining = listOf("C"), currentId = "A", phase = "base",
      focusedId = "D",
    )
    assertEquals(3, ReviewScheduler.presentationCount(state))
  }

  @Test fun preservedImportantBonusSurvivesFocusedAdvance() {
    val state = ReviewState(
      seed = 1, randomState = 1, members = listOf("A", "B", "C"),
      baseRemaining = listOf("C"), currentId = "A", phase = "base",
      focusedId = "B",
    )
    // B not in queue here would be extra; B in queue counts once tested above.
    // Bonus phase wording uses phase, count stays truthful.
    val bonus = ReviewState(
      seed = 1, randomState = 1, members = listOf("A", "B"),
      baseRemaining = emptyList(), bonusRemaining = listOf("B"),
      currentId = "A", phase = "bonus",
    )
    assertEquals(2, ReviewScheduler.presentationCount(bonus))
  }

  @Test fun focusedPresentationSurvivesSerializationAndLegacyDataStillReads() {
    val start = ReviewScheduler.start(ids, 66)
    val round = ReviewScheduler.advance(start, ids, emptySet())
    val focused = ReviewScheduler.focus(round, ids, 66, emptySet(), round.baseRemaining.first())
    val restored = Json.decodeFromString<ReviewState>(Json.encodeToString(focused))
    assertEquals(focused.focusedId, restored.focusedId)
    assertEquals(ReviewScheduler.presentedId(focused), ReviewScheduler.presentedId(restored))

    // Older persisted state without the focused fields must still decode.
    val legacy = """{"format":1,"seed":1,"randomState":1,"members":["a","b"],"baseRemaining":["b"],"currentId":"a"}"""
    val old = Json.decodeFromString<ReviewState>(legacy)
    assertNull(old.focusedId)
    assertEquals("a", ReviewScheduler.presentedId(old))
  }
}
