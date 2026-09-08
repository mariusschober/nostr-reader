package com.reader.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned ordered persistence; failed commands remain available for retry. */
class ReadingSession(
  private val scope: CoroutineScope,
  private val journal: java.io.File? = null,
  private val saveDraft: (suspend (HighlightEntity) -> HighlightMutation)? = null,
) {
  private val pending = ArrayDeque<suspend () -> Unit>()
  private val mutex = Mutex()
  private val failure = MutableStateFlow<String?>(null)
  val error = failure.asStateFlow()

  private val scanned = CompletableDeferred<Unit>()

  init {
    if (journal != null && saveDraft != null) submit {
      val files = withContext(Dispatchers.IO) {
        journal.mkdirs()
        journal.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
      }
      scanned.complete(Unit)
      for (file in files) {
        val draft = withContext(Dispatchers.IO) {
          check(file.length() <= 1024 * 1024) { "Pending selection exceeds its limit" }
          kotlinx.serialization.json.Json.decodeFromString(HighlightEntity.serializer(), file.readText())
        }
        saveDraft.invoke(draft)
        withContext(Dispatchers.IO) { check(file.delete()) { "Couldn’t settle saved selection" } }
      }
    }
  }

  /** Journal independently of the Room write queue, including while Room is busy. */
  fun submitSelection(draft: HighlightEntity, onSaved: (HighlightMutation) -> Unit) {
    val directory = checkNotNull(journal)
    val file = java.io.File(directory, "%019d-%s.json".format(System.nanoTime(), java.util.UUID.randomUUID()))
    suspend fun prepare() = withContext(Dispatchers.IO) {
      scanned.await()
      directory.mkdirs()
      val atomic = android.util.AtomicFile(file)
      val output = atomic.startWrite()
      try {
        output.write(kotlinx.serialization.json.Json.encodeToString(HighlightEntity.serializer(), draft).toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
      } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    val prepared = scope.async { prepare() }
    submit {
      try { prepared.await() } catch (_: Exception) { prepare() }
      val change = checkNotNull(saveDraft).invoke(draft)
      withContext(Dispatchers.IO) { check(file.delete()) { "Couldn’t settle saved selection" } }
      onSaved(change)
    }
  }

  // Called on the UI thread: register the frozen command before any transition.
  fun submit(command: suspend () -> Unit) {
    pending.addLast(command)
    scope.launch { runCatching { flush() } }
  }

  suspend fun flush() = mutex.withLock {
    try {
      while (pending.isNotEmpty()) {
        pending.first().invoke()
        pending.removeFirst()
      }
      failure.value = null
    } catch (error: Exception) {
      failure.value = "Couldn’t save reading changes. Retry before leaving."
      throw error
    }
  }
}
