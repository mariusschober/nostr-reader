package com.reader.app

import com.reader.app.data.ReadingDayEntity
import com.reader.app.data.ReadingRun
import org.junit.Assert.*
import org.junit.Test

class ReadingRunTest {
  private fun row(day: String, minutes: Int = 0, finished: Int = 0) =
    ReadingDayEntity(day, minutes, finished)

  @Test fun consecutiveDaysCount() {
    val days = mapOf(
      "2026-09-10" to row("2026-09-10", 12, 1),
      "2026-09-09" to row("2026-09-09", 30, 2),
      "2026-09-08" to row("2026-09-08", 6, 0),
      "2026-09-06" to row("2026-09-06", 60, 3),
    )
    assertEquals(3, ReadingRun.computeRun(days, "2026-09-10"))
  }

  @Test fun quietTodayKeepsYesterdayAlive() {
    val days = mapOf("2026-09-09" to row("2026-09-09", 10, 1))
    assertEquals(1, ReadingRun.computeRun(days, "2026-09-10"))
  }

  @Test fun twoQuietDaysMeansZero() {
    val days = mapOf("2026-09-08" to row("2026-09-08", 99, 9))
    assertEquals(0, ReadingRun.computeRun(days, "2026-09-10"))
  }

  @Test fun minutesQualifyWithoutFinish() {
    val days = mapOf(
      "2026-09-10" to row("2026-09-10", 5, 0),
      "2026-09-09" to row("2026-09-09", 4, 0),
    )
    assertEquals(1, ReadingRun.computeRun(days, "2026-09-10"))
  }

  @Test fun malformedDaysNeverCrash() {
    assertEquals(0, ReadingRun.computeRun(mapOf("nope" to row("nope", 99, 9)), "also-nope"))
  }
}
