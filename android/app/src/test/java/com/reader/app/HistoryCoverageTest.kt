package com.reader.app

import com.reader.app.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HistoryCoverageTest {
  @Test fun cappedHistoriesResumeUntilAllTimestampBucketsAreCovered() = runTest {
    for (cap in listOf(32, 64, 256)) {
      val events = (1L..1200L).toList()
      val stored = mutableSetOf<Long>()
      var checkpoint = HistoryCoverage.start(0, 1300)
      repeat(150) {
        if (checkpoint.pending.isNotEmpty()) scanHistory(checkpoint,
          query = { w -> events.filter { it in w.since..w.until }.take(cap) },
          consume = { stored += it }, checkpoint = { checkpoint = it })
      }
      assertTrue(checkpoint.pending.isEmpty())
      assertTrue(checkpoint.incomplete.isEmpty())
      assertEquals(events.toSet(), stored)
    }
  }
  @Test fun saturatedTiesStayExplicitAndStorageFailureCannotAdvanceCoverage() = runTest {
    val start = HistoryCoverage.start(1, 1)
    var saved = start
    val result = scanHistory(start, query = { List(32) { it } }, consume = {}, checkpoint = { saved = it })
    assertEquals(listOf(HistoryWindow(1, 1)), result.incomplete)
    saved = start
    try {
      scanHistory(start, query = { listOf(1) }, consume = { throw java.io.IOException("full") }, checkpoint = { saved = it })
      fail("storage failure must escape")
    } catch (_: java.io.IOException) { }
    assertEquals(start, saved)
  }
}
