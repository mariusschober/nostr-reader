package com.reader.app

import com.reader.app.data.ReadingSession
import com.reader.app.data.HighlightEntity
import com.reader.app.data.HighlightMutation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun draft(id: String = "selection", end: Int = 5) = HighlightEntity(
    id, "document", "quote".take(end), "Source", null, 1, 1,
    "block", 0, "block", end, 1, 0, end, "", "",
  )

  private fun journal(directory: java.io.File, name: String, value: HighlightEntity) =
    java.io.File(directory, name).apply {
      writeText(Json.encodeToString(HighlightEntity.serializer(), value))
    }

  @Test fun startupSaveFailureRetainsRealJournalAndExposesRetry() = runTest {
    val directory = temporary.newFolder()
    val value = draft()
    val file = journal(directory, "001.json", value)
    var unavailable = true
    val saved = mutableListOf<HighlightEntity>()
    val owner = ReadingSession(backgroundScope, directory) {
      if (unavailable) throw java.io.IOException("temporary storage failure")
      saved += it
      HighlightMutation(null, it)
    }
    runCatching { owner.flush() }
    assertTrue("A valid draft must remain a replayable .json after a save failure", file.exists())
    assertFalse(java.io.File(directory, "001.json.bad").exists())
    assertNotNull(owner.error.value)
    assertTrue(owner.error.value!!.retryable)
    assertTrue(saved.isEmpty())
    var unrelatedCommand = false
    owner.submit { unrelatedCommand = true }
    runCurrent()
    assertTrue("An unresolved selection must not block unrelated commands", unrelatedCommand)
    assertNotNull("Unrelated success must not hide recovery failure", owner.error.value)
    assertTrue(owner.error.value!!.retryable)
    unavailable = false
    owner.flush()
    owner.flush()
    assertEquals(listOf(value), saved)
    assertFalse(file.exists())
    assertNull(owner.error.value)
  }

  @Test fun corruptAndOversizedJournalsAreQuarantinedWhileValidWorkProceeds() = runTest {
    val directory = temporary.newFolder()
    val oldQuarantine = java.io.File(directory, "000.json.bad").apply { writeText("preserved evidence") }
    val corrupt = java.io.File(directory, "001.json").apply { writeText("{broken") }
    val oversized = java.io.File(directory, "002.json").apply { writeBytes(ByteArray(1024 * 1024 + 1)) }
    val valid = journal(directory, "003.json", draft())
    val saved = mutableListOf<HighlightEntity>()
    val owner = ReadingSession(backgroundScope, directory) {
      saved += it
      HighlightMutation(null, it)
    }
    owner.flush()
    assertEquals(listOf(draft()), saved)
    assertFalse(valid.exists())
    assertFalse(corrupt.exists())
    assertFalse(oversized.exists())
    assertEquals("{broken", java.io.File(directory, "001.json.bad").readText())
    assertEquals(1024 * 1024 + 1L, java.io.File(directory, "002.json.bad").length())
    assertEquals("preserved evidence", oldQuarantine.readText())
    assertTrue(owner.error.value?.message.orEmpty().contains("saved copies have been kept"))
    assertFalse("Quarantined input cannot be settled by Retry", owner.error.value!!.retryable)
    owner.submit { /* later unrelated work */ }
    owner.flush()
    assertNotNull("An empty successful flush cannot hide quarantined evidence", owner.error.value)
    assertFalse(owner.error.value!!.retryable)
    assertEquals(1, saved.size)
  }

  @Test fun cleanupFailureRetainsIdentityOrderAndRestartIsIdempotent() = runTest {
    val directory = temporary.newFolder()
    val initial = draft(end = 3)
    val final = draft(end = 5)
    val firstFile = journal(directory, "001.json", initial)
    val finalFile = journal(directory, "002.json", final)
    val unrelated = draft(id = "unrelated")
    journal(directory, "003.json", unrelated)
    val rows = mutableMapOf<String, HighlightEntity>()
    val writes = mutableListOf<HighlightEntity>()
    val attempts = mutableListOf<HighlightEntity>()
    // Representative repository semantics: identical anchors/content are a no-op;
    // an actual changed range keeps identity and bumps the persisted revision once.
    val save: suspend (HighlightEntity) -> HighlightMutation = { value ->
      attempts += value
      val before = rows[value.id]
      if (before != null && before.quote == value.quote && before.endOffset == value.endOffset) {
        HighlightMutation(before, before)
      } else {
        val after = value.copy(revision = before?.revision?.plus(1) ?: value.revision)
        rows[value.id] = after
        writes += after
        HighlightMutation(before, after)
      }
    }
    val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    try {
      val owner = ReadingSession(firstScope, directory, save).apply {
        deleteJournal = { file -> if (file == firstFile) false else file.delete() }
      }
      assertTrue(runCatching { owner.flush() }.exceptionOrNull() is IllegalStateException)
      assertNotNull(owner.error.value)
      assertTrue(owner.error.value!!.retryable)
      assertTrue(firstFile.exists())
      assertTrue("A newer range cannot overtake unresolved cleanup", finalFile.exists())
      assertEquals(initial, rows[initial.id])
      assertEquals(unrelated, rows[unrelated.id])
      assertTrue(runCatching { owner.flush() }.exceptionOrNull() is IllegalStateException)
      assertEquals("Same-process cleanup retry must not repeat the completed save", 1,
        attempts.count { it.id == initial.id })
    } finally { firstScope.cancel() }
    val restarted = ReadingSession(backgroundScope, directory, save)
    restarted.flush()
    restarted.flush()
    assertFalse(firstFile.exists())
    assertFalse(finalFile.exists())
    assertEquals(2, rows.size)
    assertEquals(final.copy(revision = 2), rows[initial.id])
    assertEquals(listOf(initial, unrelated, final.copy(revision = 2)), writes)
    assertEquals(2, attempts.count { it.id == initial.id && it.endOffset == initial.endOffset })
    assertNull(restarted.error.value)
  }

  @Test fun startupCancellationRetainsJournalWithoutQuarantineOrStorageError() = runTest {
    val directory = temporary.newFolder()
    val value = draft()
    val file = journal(directory, "001.json", value)
    var cancelled = true
    val saved = mutableListOf<HighlightEntity>()
    val owner = ReadingSession(backgroundScope, directory) {
      if (cancelled) throw CancellationException("caller cancelled")
      saved += it
      HighlightMutation(null, it)
    }
    try {
      owner.flush()
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { /* expected */ }
    assertTrue(file.exists())
    assertFalse(java.io.File(directory, "001.json.bad").exists())
    assertNull(owner.error.value)
    assertTrue(saved.isEmpty())
    cancelled = false
    owner.flush()
    assertEquals(listOf(value), saved)
    assertFalse(file.exists())
    assertNull(owner.error.value)
  }

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
