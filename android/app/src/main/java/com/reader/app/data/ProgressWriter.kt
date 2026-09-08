package com.reader.app.data

import com.reader.app.cursor.SemanticCursor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

  suspend fun flush() = writing.withLock {
    val batch = synchronized(lock) { pending.toMap() }
    for ((id, value) in batch) {
      db.documents().setProgress(id, value.cursor.blockId, value.cursor.charOffset, value.fraction, System.currentTimeMillis())
      synchronized(lock) { if (pending[id]?.sequence == value.sequence) pending.remove(id) }
    }
  }
}
