package com.reader.app

import com.reader.app.ui.ArticleAction
import com.reader.app.ui.Triage
import org.junit.Assert.*
import org.junit.Test

class TriageMovesTest {
  @Test fun archivedArticleActionsAndThresholds() {
    assertEquals(ArticleAction.Unarchive, ArticleAction.forSwipe(Triage.ARCHIVED, true))
    assertEquals(ArticleAction.Delete, ArticleAction.forSwipe(Triage.ARCHIVED, false))
    assertFalse(ArticleAction.commits(ArticleAction.Delete, -599f, 1000f))
    assertTrue(ArticleAction.commits(ArticleAction.Delete, -600f, 1000f))
    assertFalse(ArticleAction.commits(ArticleAction.Delete, -299f, 1000f))
    assertTrue(ArticleAction.commits(ArticleAction.Unarchive, 300f, 1000f))
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
  fun openArticleSwipeMatrixMatchesListTriage() {
    for (list in listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER)) {
      for (swipe in Triage.Swipe.entries) {
        val expected = Triage.swipeTarget(list, swipe)
        val right = swipe == Triage.Swipe.RIGHT
        assertEquals("$list/$swipe", expected, ArticleAction.forSwipe(list, right)?.target)
      }
    }
    assertEquals(Triage.INBOX, ArticleAction.forSwipe(Triage.ARCHIVED, true)?.target)
    assertNull(ArticleAction.forSwipe(Triage.ARCHIVED, false)?.target)
    assertNull(ArticleAction.forSwipe(null, true))
    assertNull(ArticleAction.forSwipe("unknown", false))
  }

  @Test
  fun readerMenuOffersEveryListWithCurrentDisabled() {
    for (list in listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER)) {
      val menu = ArticleAction.forMenu(list)
      assertEquals(listOf(ArticleAction.Inbox, ArticleAction.Priority, ArticleAction.Later, ArticleAction.Archive), menu)
      assertEquals(list, menu.single { it.target == list }.target)
    }
    assertEquals(listOf(ArticleAction.Unarchive, ArticleAction.Delete), ArticleAction.forMenu(Triage.ARCHIVED))
    assertTrue(ArticleAction.forMenu(null).isEmpty())
  }

  @Test
  fun swipeHintIsDerivedFromTheActualMapping() {
    assertEquals("Swipe inside the page: ← Move to Later · Move to Priority →", ArticleAction.swipeHint(Triage.INBOX))
    assertEquals("Swipe inside the page: ← Move to Later · Move to Inbox →", ArticleAction.swipeHint(Triage.PRIORITY))
    assertEquals("Swipe inside the page: ← Archive · Move to Inbox →", ArticleAction.swipeHint(Triage.LATER))
    assertEquals("Swipe inside the page: ← Delete · Unarchive →", ArticleAction.swipeHint(Triage.ARCHIVED))
    assertNull(ArticleAction.swipeHint(null))
  }

  @Test
  fun tabsCoverAllLists() {
    assertEquals(setOf("inbox", "priority", "later", "archived"), Triage.TABS.toSet())
  }
}
