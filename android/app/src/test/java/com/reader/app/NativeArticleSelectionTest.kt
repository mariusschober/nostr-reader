package com.reader.app

import com.reader.app.ui.screens.selectionRangesOverlap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small boundary contract for the native selection-session ownership gate. */
class NativeArticleSelectionTest {
  @Test fun halfOpenOverlapKeepsSharedCharacterButNotAdjacentRanges() {
    // IntRange stores the inclusive last offset; the candidate end is the
    // Android/TextView exclusive end.
    assertTrue(selectionRangesOverlap(10..19, 19, 24))
    assertTrue(selectionRangesOverlap(10..19, 10, 11))
    assertFalse(selectionRangesOverlap(10..19, 20, 24))
    assertFalse(selectionRangesOverlap(10..19, 0, 10))
  }
}
