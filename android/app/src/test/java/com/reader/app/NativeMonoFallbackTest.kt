package com.reader.app

import com.reader.app.ui.screens.nativeMonoFallbackDescent
import org.junit.Assert.assertEquals
import org.junit.Test

/** SDK-independent contract for the pre-34 metric descent calculation. */
class NativeMonoFallbackTest {
  @Test fun fallbackUsesActualSpanDescentAndExcludesLayoutSpacing() {
    assertEquals(11f, nativeMonoFallbackDescent(7f, listOf(11f, 9f)), 0f)
    assertEquals(7f, nativeMonoFallbackDescent(7f, emptyList()), 0f)
  }
}
