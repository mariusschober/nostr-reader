package com.reader.app.data

import com.reader.app.core.ReaderCore
import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coalesced targeted updates. A failed/cancelled write remains pending until committed. */
class ProgressWriter(private val db: ReaderDb) {
  private data class Pending(val cursor: SemanticCursor, val fraction: Float, val sequence: Long)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val signals = Channel<Unit>(Channel.CONFLATED)
  private val lock = Any()
  private val writing = Mutex()
  private val pending = mutableMapOf<String, Pending>()
  private var sequence = 0L

  /** Document IDs that crossed the finish line (sticky, once each). */
  private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 16)
  val finished: SharedFlow<String> = _finished

  init {
    scope.launch {
      for (signal in signals) {
        delay(450)
        try { flush() } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Kept pending; the next gesture/lifecycle checkpoint retries. */ }
      }
    }
  }

  fun offer(cursor: SemanticCursor, fraction: Float) {
    if (cursor.documentId.isBlank() || !fraction.isFinite()) return
    synchronized(lock) {
      pending[cursor.documentId] = Pending(cursor.copy(charOffset = cursor.charOffset.coerceAtLeast(0)), fraction.coerceIn(0f, 1f), ++sequence)
    }
    signals.trySend(Unit)
  }

  suspend fun discard(id: String): Unit = writing.withLock {
    synchronized(lock) { pending.remove(id) }; Unit
  }

  suspend fun flush() = writing.withLock {
    val batch = synchronized(lock) { pending.toMap() }
    for ((id, value) in batch) {
      val now = System.currentTimeMillis()
      db.documents().setProgress(id, value.cursor.blockId, value.cursor.charOffset, value.fraction, now)
      // Finish credit: full-article minutes, counted once, on device zone days.
      // Reading time is derived from length, never wall-clock — no timer runs.
      if (value.fraction >= 0.999f) {
        val words = db.readingStats().wordCountOf(id) ?: 0
        val day = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        if (db.readingStats().recordFinish(id, ReaderCore.readingMinutes(words), day, now)) {
          _finished.tryEmit(id)
        }
      }
      synchronized(lock) { if (pending[id]?.sequence == value.sequence) pending.remove(id) }
    }
  }
}
