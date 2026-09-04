package com.reader.app

import com.reader.app.ui.Triage
import org.junit.Assert.*
import org.junit.Test

class TriageMovesTest {
  @Test
  fun inboxSwipes() {
    assertEquals(Triage.PRIORITY, Triage.swipeTarget(Triage.INBOX, Triage.Swipe.RIGHT))
    assertEquals(Triage.LATER, Triage.swipeTarget(Triage.INBOX, Triage.Swipe.LEFT))
  }

  @Test
  fun prioritySwipes() {
    assertEquals(Triage.INBOX, Triage.swipeTarget(Triage.PRIORITY, Triage.Swipe.RIGHT))
    assertEquals(Triage.LATER, Triage.swipeTarget(Triage.PRIORITY, Triage.Swipe.LEFT))
  }

  @Test
  fun laterSwipes() {
    assertEquals(Triage.INBOX, Triage.swipeTarget(Triage.LATER, Triage.Swipe.RIGHT))
    assertEquals(Triage.ARCHIVED, Triage.swipeTarget(Triage.LATER, Triage.Swipe.LEFT))
  }

  @Test
  fun archiveHasNoSwipe() {
    assertNull(Triage.swipeTarget(Triage.ARCHIVED, Triage.Swipe.RIGHT))
    assertNull(Triage.swipeTarget(Triage.ARCHIVED, Triage.Swipe.LEFT))
  }

  @Test
  fun tabsCoverAllLists() {
    assertEquals(setOf("inbox", "priority", "later", "archived"), Triage.TABS.toSet())
  }
}
