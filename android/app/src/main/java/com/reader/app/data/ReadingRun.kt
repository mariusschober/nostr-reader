package com.reader.app.data

/**
 * Pure run/milestone math over local day rows. Device-zone days, consecutive
 * calendar days with finished>=1 OR minutes>=5. A quiet today keeps a live
 * run (the day isn't over); two quiet days hide the line entirely.
 */
object ReadingRun {
  fun computeRun(byDay: Map<String, ReadingDayEntity>, today: String): Int {
    fun active(day: String): Boolean {
      val row = byDay[day] ?: return false
      return row.finished > 0 || row.minutes >= 5
    }
    var day = if (active(today)) today else previousDay(today)
    if (!active(day)) return 0
    var run = 0
    while (active(day)) {
      run++
      if (run > 370) break
      day = previousDay(day)
    }
    return run
  }

  fun previousDay(day: String): String = try {
    java.time.LocalDate.parse(day).minusDays(1).toString()
  } catch (_: Exception) {
    ""
  }

  fun today(): String = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()

  fun daysAgo(n: Long): String =
    java.time.LocalDate.now(java.time.ZoneId.systemDefault()).minusDays(n).toString()
}
