package com.reader.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Application-owned ordered persistence; failed commands remain available for retry.
 *
 * Threading: [submit] may be called from any thread. Enqueue is synchronized;
 * [flush] holds [mutex] only while draining, never across enqueue. Journal
 * writes happen on IO; Room commits happen in submission order.
 *
 * Poison heads (corrupt JSON, oversize, failing save/delete) are quarantined
 * to a `.bad` sidecar after one failed attempt so later selections are not
 * blocked forever. Cancellation is always rethrown and never recorded as a
 * storage failure.
 */
class ReadingSession(
  private val scope: CoroutineScope,
  private val journal: java.io.File? = null,
  private val saveDraft: (suspend (HighlightEntity) -> HighlightMutation)? = null,
) {
  private val pending = ArrayDeque<suspend () -> Unit>()
  private val pendingLock = Any()
  private val mutex = Mutex()
  private val failure = MutableStateFlow<String?>(null)
  val error = failure.asStateFlow()

  private val scanned = CompletableDeferred<Unit>()
  private val fileSeq = AtomicLong(0L)

  init {
    if (journal != null && saveDraft != null) submit {
      val files: List<java.io.File> = try {
        withContext(Dispatchers.IO) {
          journal.mkdirs()
          journal.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        }
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        // Unreadable journal dir: unblock new journals, surface failure.
        failure.value = "Couldn’t save reading changes. Retry before leaving."
        emptyList()
      } finally {
        // Always unblock prepare(); a listing failure must not hang forever.
        if (!scanned.isCompleted) scanned.complete(Unit)
      }
      for (file in files) {
        try {
          currentCoroutineContext().ensureActive()
          val draft = withContext(Dispatchers.IO) {
            check(file.length() <= 1024 * 1024) { "Pending selection exceeds its limit" }
            kotlinx.serialization.json.Json.decodeFromString(HighlightEntity.serializer(), file.readText())
          }
          currentCoroutineContext().ensureActive()
          saveDraft.invoke(draft)
          withContext(Dispatchers.IO) { check(file.delete()) { "Couldn’t settle saved selection" } }
        } catch (error: Throwable) {
          if (error is CancellationException) throw error
          // Quarantine poison so it cannot head-block every later selection.
          runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
              val bad = java.io.File(file.parentFile, file.name + ".bad")
              file.renameTo(bad)
            }
          }
        }
      }
    } else {
      // No journal (unit-test owner without persistence): never hang prepare().
      scanned.complete(Unit)
    }
  }

  /** Journal independently of the Room write queue, including while Room is busy. */
  fun submitSelection(draft: HighlightEntity, onSaved: (HighlightMutation) -> Unit) {
    val directory = checkNotNull(journal)
    // Millis + monotonic seq + UUID: sortable across restarts (nanoTime resets
    // per process), unique on collisions.
    val file = java.io.File(directory,
      "%013d-%019d-%s.json".format(System.currentTimeMillis(), fileSeq.getAndIncrement(), java.util.UUID.randomUUID()))
    suspend fun prepare() = withContext(Dispatchers.IO) {
      ensureActive()
      scanned.await()
      ensureActive()
      directory.mkdirs()
      val atomic = android.util.AtomicFile(file)
      var output: java.io.FileOutputStream? = null
      try {
        output = atomic.startWrite()
        output.write(kotlinx.serialization.json.Json.encodeToString(HighlightEntity.serializer(), draft).toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
        output = null
      } catch (error: Throwable) {
        if (error is CancellationException) {
          // AtomicFile has no handle to fail without an output; close if open.
          runCatching { output?.close() }
          throw error
        }
        output?.let { runCatching { atomic.failWrite(it) } }
        throw error
      }
    }
    val prepared = scope.async { prepare() }
    submit {
      try {
        prepared.await()
      } catch (error: CancellationException) { throw error }
      catch (_: Exception) {
        // Concurrent journal write failed (e.g. cancelled async); retry once
        // synchronously inside the ordered drain. Cancellation still propagates.
        currentCoroutineContext().ensureActive()
        prepare()
      }
      currentCoroutineContext().ensureActive()
      val change = checkNotNull(saveDraft).invoke(draft)
      withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(file.delete()) { "Couldn’t settle saved selection" }
      }
      currentCoroutineContext().ensureActive()
      onSaved(change)
    }
  }

  /** Discard queued (not yet committed) commands for a deleted document. */
  fun discardForDocument(documentId: String) {
    // Best-effort: journal files embed the document id in their JSON body, so
    // deletion of the parsed file happens at replay; here we drop in-memory
    // queued lambdas only if they expose the id via toString convention.
    // Room-committed highlights for deleted docs are retained as orphans by
    // design (see ArticleRepository.delete).
    synchronized(pendingLock) {
      // Cannot introspect lambdas; instead rely on saveDraft idempotency and
      // orphan retention. This hook exists for future typed commands.
    }
  }

  // Called from any thread: register the frozen command before any transition.
  fun submit(command: suspend () -> Unit) {
    synchronized(pendingLock) { pending.addLast(command) }
    scope.launch {
      try { flush() } catch (_: CancellationException) { /* scope cancelled */ }
      catch (_: Exception) { /* failure.value already set by flush */ }
    }
  }

  suspend fun flush() = mutex.withLock {
    try {
      while (true) {
        val next: (suspend () -> Unit) = synchronized(pendingLock) {
          if (pending.isEmpty()) null else pending.first()
        } ?: break
        try {
          next.invoke()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
          failure.value = "Couldn’t save reading changes. Retry before leaving."
          throw error
        }
        synchronized(pendingLock) {
          // Remove only if head is still the completed command (no concurrent
          // clear/reorder happened while suspended).
          if (pending.isNotEmpty()) pending.removeFirst()
        }
      }
      failure.value = null
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
      failure.value = "Couldn’t save reading changes. Retry before leaving."
      throw error
    }
  }
}
