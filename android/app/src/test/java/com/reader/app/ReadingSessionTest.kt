package com.reader.app

import com.reader.app.data.ReadingSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionTest {
  @Test fun frozenCommandsSurviveCallerCancellationAndBlockTransitionUntilCommit() = runTest {
    val owner = ReadingSession(backgroundScope)
    val release = CompletableDeferred<Unit>()
    val saved = mutableListOf<String>()
    var color = "YELLOW"
    val frozen = color
    owner.submit { release.await(); saved += frozen }
    color = "PURPLE"
    val caller = launch { owner.flush(); saved += "transition" }
    runCurrent()
    assertTrue(saved.isEmpty())
    caller.cancelAndJoin() // activity recreation does not cancel application persistence
    release.complete(Unit)
    runCurrent()
    assertEquals(listOf("YELLOW"), saved)
    owner.flush()
    assertNull(owner.error.value)
  }

  @Test fun storageFailureRetainsOrderedCommandsForExplicitRetry() = runTest {
    val owner = ReadingSession(backgroundScope)
    var full = true
    val saved = mutableListOf<Int>()
    owner.submit { if (full) throw java.io.IOException("quota"); saved += 1 }
    owner.submit { saved += 2 }
    runCurrent()
    assertNotNull(owner.error.value)
    assertTrue(saved.isEmpty())
    full = false
    owner.flush()
    assertEquals(listOf(1, 2), saved)
    assertNull(owner.error.value)
  }

  @Test fun cancellationIsNeverRecordedAsStorageFailure() = runTest {
    val owner = ReadingSession(backgroundScope)
    owner.submit { throw CancellationException("revoked") }
    val job = launch { runCatching { owner.flush() } }
    runCurrent()
    job.join()
    // Cancellation propagates without setting the user-visible Retry error.
    assertNull(owner.error.value)
  }

  @Test fun submitFromAnyThreadDoesNotCorruptTheQueue() = runTest {
    val owner = ReadingSession(backgroundScope)
    val saved = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val threads = (0 until 20).map { i ->
      Thread { owner.submit { saved += i } }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join(2000) }
    // Drain on the test dispatcher; all 20 commands must run exactly once.
    backgroundScope.launch { owner.flush() }.join()
    runCurrent()
    assertEquals(20, saved.size)
    assertEquals((0 until 20).toSet(), saved.toSet())
  }
}
