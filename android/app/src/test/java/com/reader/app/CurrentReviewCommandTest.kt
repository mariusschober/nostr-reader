package com.reader.app

import com.reader.app.ui.completeReviewCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentReviewCommandTest {
  @Test fun delayedImportantCannotReplaceTheNextQuote() = runTest {
    var quote = "A"
    val release = CompletableDeferred<Unit>()
    val command = launch {
      completeReviewCommand("A", { release.await(); "A" }, { quote }) { quote = it }
    }
    runCurrent()
    quote = "B" // Next completes while Important's DB response is delayed.
    release.complete(Unit)
    command.join()
    assertEquals("B", quote)
  }
}
