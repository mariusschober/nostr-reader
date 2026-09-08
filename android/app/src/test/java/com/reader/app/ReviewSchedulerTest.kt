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
}
