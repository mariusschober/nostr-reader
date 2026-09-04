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
}
