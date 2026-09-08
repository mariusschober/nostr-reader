package com.reader.app

import com.reader.app.ui.HighlightAction
import org.junit.Assert.*
import org.junit.Test

class HighlightActionTest {
  @Test fun rightSwipeMarksImportantAtThirtyPercent() {
    assertEquals(HighlightAction.Important, HighlightAction.forSwipe(true))
    assertFalse(HighlightAction.commits(HighlightAction.Important, 290f, 1000f))
    assertTrue(HighlightAction.commits(HighlightAction.Important, 300f, 1000f))
  }

  @Test fun leftSwipeRemovesAtSixtyPercentLikeArchiveDelete() {
    assertEquals(HighlightAction.Remove, HighlightAction.forSwipe(false))
    assertFalse(HighlightAction.commits(HighlightAction.Remove, -599f, 1000f))
    assertTrue(HighlightAction.commits(HighlightAction.Remove, -600f, 1000f))
  }

  @Test fun zeroWidthNeverCommits() {
    assertFalse(HighlightAction.commits(HighlightAction.Important, 500f, 0f))
    assertFalse(HighlightAction.commits(null, 500f, 1000f))
  }
}
