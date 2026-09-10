package com.reader.app

import com.reader.app.ui.Triage
import org.junit.Assert.*
import org.junit.Test

class TriageMovesTest {
  @Test fun archivedArticleActionsAndThresholds() {
    val action = com.reader.app.ui.ArticleAction.Companion
    assertEquals(com.reader.app.ui.ArticleAction.Unarchive, action.forArticle(Triage.ARCHIVED, true))
    assertEquals(com.reader.app.ui.ArticleAction.Delete, action.forArticle(Triage.ARCHIVED, false))
    assertFalse(action.commits(com.reader.app.ui.ArticleAction.Delete, -599f, 1000f))
    assertTrue(action.commits(com.reader.app.ui.ArticleAction.Delete, -600f, 1000f))
    assertFalse(action.commits(com.reader.app.ui.ArticleAction.Delete, -299f, 1000f))
    assertTrue(action.commits(com.reader.app.ui.ArticleAction.Unarchive, 300f, 1000f))
  }

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

  @Test
  fun ageMatchesBands() {
    val now = 1_700_000_000_000L
    val day = 86_400_000L
    assertTrue(Triage.ageMatches(now, com.reader.app.prefs.AgeFilter.ANY, now))
    val todayStart = Triage.startOfTodayMillis(now)
    assertTrue(Triage.ageMatches(todayStart, com.reader.app.prefs.AgeFilter.TODAY, now))
    assertFalse(Triage.ageMatches(todayStart - 1, com.reader.app.prefs.AgeFilter.TODAY, now))
    assertTrue(Triage.ageMatches(now - 6 * day, com.reader.app.prefs.AgeFilter.WEEK, now))
    assertFalse(Triage.ageMatches(now - 8 * day, com.reader.app.prefs.AgeFilter.WEEK, now))
    assertTrue(Triage.ageMatches(now - 20 * day, com.reader.app.prefs.AgeFilter.MONTH, now))
    assertTrue(Triage.ageMatches(now - 40 * day, com.reader.app.prefs.AgeFilter.OLDER, now))
    assertFalse(Triage.ageMatches(now - 10 * day, com.reader.app.prefs.AgeFilter.OLDER, now))
  }
}
