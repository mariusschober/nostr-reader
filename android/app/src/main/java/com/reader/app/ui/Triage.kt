package com.reader.app.ui

/** Four-list triage model. Pure swipe-direction mapping, unit-tested. */
object Triage {
  const val INBOX = "inbox"
  const val PRIORITY = "priority"
  const val LATER = "later"
  const val ARCHIVED = "archived"

  val TABS = listOf(INBOX, PRIORITY, LATER, ARCHIVED)

  fun tabLabel(list: String): String = when (list) {
    INBOX -> "Inbox"
    PRIORITY -> "Priority"
    LATER -> "Later"
    ARCHIVED -> "Archive"
    "highlights" -> "Highlights"
    else -> list
  }

  enum class Swipe { RIGHT, LEFT } // RIGHT = drag left-to-right, LEFT = drag right-to-left

  /** Target list for a swipe, or null when the list has no swipe in that direction (Archive). */
  fun swipeTarget(current: String, swipe: Swipe): String? = when (current) {
    INBOX -> if (swipe == Swipe.RIGHT) PRIORITY else LATER
    PRIORITY -> if (swipe == Swipe.RIGHT) INBOX else LATER
    LATER -> if (swipe == Swipe.RIGHT) INBOX else ARCHIVED
    else -> null
  }

  fun movedLabel(target: String): String = when (target) {
    PRIORITY -> "Moved to Priority"
    LATER -> "Saved for later"
    INBOX -> "Moved to Inbox"
    ARCHIVED -> "Archived"
    else -> "Moved"
  }

  /** Start-of-today millis in device zone, shared by every age computation. */
  fun startOfTodayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
      .atStartOfDay(zone).toInstant().toEpochMilli()
  }

  /** Single source of truth for the age predicate (lists, search, badges). */
  fun ageMatches(createdAt: Long, age: com.reader.app.prefs.AgeFilter, nowMillis: Long = System.currentTimeMillis()): Boolean =
    when (age) {
      com.reader.app.prefs.AgeFilter.ANY -> true
      com.reader.app.prefs.AgeFilter.TODAY -> createdAt >= startOfTodayMillis(nowMillis)
      com.reader.app.prefs.AgeFilter.WEEK -> createdAt >= nowMillis - 7L * 86_400_000L
      com.reader.app.prefs.AgeFilter.MONTH -> createdAt >= nowMillis - 30L * 86_400_000L
      com.reader.app.prefs.AgeFilter.OLDER -> createdAt < nowMillis - 30L * 86_400_000L
    }
}
