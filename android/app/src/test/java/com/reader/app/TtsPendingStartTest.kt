package com.reader.app

import com.reader.app.cursor.SemanticCursor
import com.reader.app.tts.PendingTtsStart
import com.reader.app.tts.TtsSourceMetadata
import com.reader.app.tts.TtsStartRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class TtsPendingStartTest {
  private fun request(id: String) = TtsStartRequest(id, SemanticCursor.start(id), 1f)

  @Test fun pendingMetadataHonorsPauseAndPlayBeforeStartingNarrator() = runTest {
    val gate = CompletableDeferred<Unit>()
    val ready = mutableListOf<Pair<String, Boolean>>()
    val intents = mutableListOf<Boolean>()
    var loads = 0
    val starts = PendingTtsStart(this,
      loadMetadata = { loads++; gate.await(); TtsSourceMetadata("Title", null) },
      onPending = { _, intent -> intents.add(intent) },
      onReady = { request, _, intent -> ready.add(request.documentId to intent) },
      onMissing = { fail("Metadata exists") },
    )
    starts.start(request("doc"))
    advanceUntilIdle()
    assertTrue(starts.pause()) // Same service path as focus loss and Media3 Pause.
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf("doc" to false), ready)
    assertEquals(listOf(true, false), intents)
    assertEquals(1, loads)
    assertFalse("After metadata, transport returns to the narrator", starts.play())
  }

  @Test fun playDuringPendingMetadataRestoresIntentWithoutAnotherLoad() = runTest {
    val gate = CompletableDeferred<Unit>()
    var loads = 0
    var intent: Boolean? = null
    var speed: Float? = null
    val starts = PendingTtsStart(this,
      loadMetadata = { loads++; gate.await(); TtsSourceMetadata("Title", null) },
      onPending = { _, _ -> },
      onReady = { request, _, value -> intent = value; speed = request.speed },
      onMissing = { fail("Metadata exists") },
    )
    starts.start(request("doc"))
    advanceUntilIdle()
    starts.pause(); starts.play(); starts.play(); starts.setSpeed(1.5f)
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(true, intent)
    assertEquals(1.5f, speed)
    assertEquals(1, loads)
  }

  @Test fun stopInvalidatesEvenANonCancellableMetadataResult() = runTest {
    val gate = CompletableDeferred<Unit>()
    var completions = 0
    val starts = PendingTtsStart(this,
      loadMetadata = { withContext(NonCancellable) { gate.await(); TtsSourceMetadata("Title", null) } },
      onPending = { _, _ -> },
      onReady = { _, _, _ -> completions++ },
      onMissing = { completions++ },
    )
    starts.start(request("doc"))
    advanceUntilIdle()
    starts.cancel()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(0, completions)
    assertFalse(starts.isPending)
  }

  @Test fun staleMissingArticleCannotStopANewerStart() = runTest {
    val gate = CompletableDeferred<Unit>()
    val completed = mutableListOf<String>()
    var missing = 0
    val starts = PendingTtsStart(this,
      loadMetadata = { id ->
        if (id == "old") withContext(NonCancellable) { gate.await(); null }
        else TtsSourceMetadata("New", null)
      },
      onPending = { _, _ -> },
      onReady = { request, _, _ -> completed.add(request.documentId) },
      onMissing = { missing++ },
    )
    starts.start(request("old"))
    advanceUntilIdle()
    starts.start(request("new"))
    advanceUntilIdle()
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf("new"), completed)
    assertEquals(0, missing)
  }
}
