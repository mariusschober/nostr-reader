package com.reader.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class ReadingRecoveryIssue(val message: String, val retryable: Boolean)

/** Application-owned persistence; frozen selections are journaled independently of Room.
 * Malformed journals are quarantined. Persistence/cleanup failures retain their .json
 * and block only later selections with the same identity until explicit retry/restart.
 * Cancellation propagates without turning pending work into a storage error.
 */
class ReadingSession(
  private val scope: CoroutineScope,
  private val journal: File? = null,
  private val saveDraft: (suspend (HighlightEntity) -> HighlightMutation)? = null,
) {
  private abstract class Command {
    var failed = false
    open val identity: String? get() = null
    open val selection = false
    open val independentRetry = false
    abstract suspend fun execute()
  }
  private val pending = ArrayDeque<Command>()
  private val pendingLock = Any()
  private val mutex = Mutex()
  private val failure = MutableStateFlow<ReadingRecoveryIssue?>(null)
  val error = failure.asStateFlow()
  private var quarantined = false
  private val scanned = CompletableDeferred<Unit>()
  private val fileSeq = AtomicLong(0L)

  // Fault injection for post-commit cleanup; production keeps File.delete semantics.
  internal var deleteJournal: (File) -> Boolean = { it.delete() }

  init {
    if (journal != null && saveDraft != null) enqueue(object : Command() {
      override val independentRetry = true
      // Until the scan succeeds, earlier identities are unknown: preserve their order.
      override val selection = true
      override suspend fun execute() {
        val files = try {
          withContext(Dispatchers.IO) {
            journal.mkdirs()
            checkNotNull(journal.listFiles()) { "Couldn’t read pending selections" }.toList()
          }
        } finally {
          // A listing failure/cancellation must not hang independent journal writes.
          if (!scanned.isCompleted) scanned.complete(Unit)
        }
        quarantined = quarantined || files.any { it.name.endsWith(".json.bad") }
        synchronized(pendingLock) {
          // Startup work precedes selections submitted while the scan was suspended.
          // A retrying scan can see files already prepared by live commands; each
          // file must have one owner so an older live range cannot replay twice.
          val owned = pending.filterIsInstance<SelectionCommand>().mapTo(mutableSetOf()) { it.file }
          val replay = files.filter { it.name.endsWith(".json") && it !in owned }.sortedBy { it.name }
            .map { SelectionCommand(it) }
          replay.asReversed().forEach { pending.addFirst(it) }
        }
      }
    }) else scanned.complete(Unit)
  }

  private class InvalidJournal(message: String) : Exception(message)

  private suspend fun readDraft(file: File): HighlightEntity = withContext(Dispatchers.IO) {
    // Bound the actual read as well as file length; never allocate from an unchecked size.
    val bytes = file.inputStream().use { input ->
      val buffer = ByteArray(1024 * 1024 + 1)
      var size = 0
      while (size < buffer.size) {
        val count = input.read(buffer, size, buffer.size - size)
        if (count < 0) break
        size += count
      }
      if (size == buffer.size) throw InvalidJournal("Pending selection exceeds its limit")
      buffer.copyOf(size)
    }
    val text = try {
      Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
      throw InvalidJournal("Pending selection is not UTF-8")
    }
    val value = Json.decodeFromString(HighlightEntity.serializer(), text)
    if (value.id.isEmpty() || value.documentId.isEmpty() || value.quote.isEmpty() ||
      value.quote.toByteArray(Charsets.UTF_8).size > MAX_QUOTE_BYTES ||
      value.startOffset < 0 || value.endOffset < 0 ||
      (value.startBlockId == value.endBlockId && value.endOffset <= value.startOffset)) {
      throw InvalidJournal("Pending selection has invalid anchors")
    }
    value
  }

  private inner class SelectionCommand(
    val file: File,
    private var draft: HighlightEntity? = null,
    private val prepare: (suspend () -> Unit)? = null,
    private val prepared: Deferred<Unit>? = null,
    private val onSaved: ((HighlightMutation) -> Unit)? = null,
  ) : Command() {
    override val identity: String? get() = draft?.id
    override val selection = true
    override val independentRetry = true
    private var committed: HighlightMutation? = null

    suspend fun identify(): Boolean {
      currentCoroutineContext().ensureActive()
      if (draft == null) {
        try {
          draft = readDraft(file)
        } catch (error: Exception) {
          if (error is CancellationException) throw error
          if (error !is SerializationException && error !is InvalidJournal) throw error
          // Only invalid input is quarantined. Rename failure itself remains retryable.
          quarantined = true
          withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val bad = File(file.parentFile, file.name + ".bad")
            check(!bad.exists() && file.renameTo(bad)) { "Couldn’t quarantine pending selection" }
          }
          return false
        }
      }
      return true
    }

    override suspend fun execute() {
      if (committed == null) {
        if (prepared != null) {
          try { prepared.await() }
          catch (error: CancellationException) { throw error }
          catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            checkNotNull(prepare).invoke()
          }
        }
        currentCoroutineContext().ensureActive()
        committed = checkNotNull(saveDraft).invoke(checkNotNull(draft))
      }
      withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(!file.exists() || deleteJournal(file)) { "Couldn’t settle saved selection" }
      }
      currentCoroutineContext().ensureActive()
      onSaved?.invoke(checkNotNull(committed))
    }
  }

  /** Journal independently of the Room write queue, including while Room is busy. */
  fun submitSelection(draft: HighlightEntity, onSaved: (HighlightMutation) -> Unit) {
    val directory = checkNotNull(journal)
    synchronized(pendingLock) {
      // Assign filenames under the enqueue lock so restart order matches submission order.
      val file = File(directory,
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
          output.write(Json.encodeToString(HighlightEntity.serializer(), draft).toByteArray(Charsets.UTF_8))
          atomic.finishWrite(output)
          output = null
        } catch (error: Throwable) {
          output?.let { runCatching { atomic.failWrite(it) } }
          throw error
        }
      }
      val prepared = scope.async { prepare() }
      enqueue(SelectionCommand(file, draft, ::prepare, prepared, onSaved))
    }
  }

  /** Committed highlights for deleted documents remain as orphans (ArticleRepository). */
  fun discardForDocument(documentId: String) {
    // Frozen commands deliberately retain their idempotent/orphan-preserving semantics.
  }

  // Called from any thread: register the frozen command before any transition.
  fun submit(command: suspend () -> Unit) = enqueue(object : Command() {
    override suspend fun execute() = command()
  })

  private fun enqueue(command: Command) {
    synchronized(pendingLock) { pending.addLast(command) }
    scope.launch {
      try { drain(retryFailures = false) }
      catch (_: CancellationException) { /* scope cancelled */ }
      catch (_: Exception) { /* error already exposed by drain */ }
    }
  }

  suspend fun flush() = drain(retryFailures = true)

  private fun refreshFailure() {
    val retry = synchronized(pendingLock) { pending.any { it.failed } }
    val message = when {
      retry && quarantined -> "Some highlights could not be restored. The saved copies have been kept. Other reading changes still need saving."
      retry -> "Couldn’t save reading changes. Retry before leaving."
      quarantined -> "Some highlights could not be restored. The saved copies have been kept."
      else -> null
    }
    failure.value = message?.let { ReadingRecoveryIssue(it, retryable = retry) }
  }

  private suspend fun drain(retryFailures: Boolean) = mutex.withLock {
    val attempted = mutableSetOf<Command>()
    val blocked = mutableSetOf<String>()
    var unknownIdentity = false
    var firstError: Exception? = null
    fun block(command: Command) {
      if (command.selection) {
        val identity = command.identity
        if (identity == null) unknownIdentity = true else blocked += identity
      }
    }
    while (true) {
      val next = synchronized(pendingLock) {
        pending.firstOrNull { it !in attempted &&
          (!it.selection || (!unknownIdentity && it.identity !in blocked)) }
      } ?: break
      attempted += next
      if (next.failed && !retryFailures) {
        if (!next.independentRetry) break
        block(next)
        continue
      }
      try {
        // Startup identities come from their bytes, not filenames. Decode before
        // deciding whether this range must wait behind an earlier failed range.
        if (next is SelectionCommand) {
          if (!next.identify()) {
            synchronized(pendingLock) { pending.remove(next) }
            continue
          }
          if (next.identity in blocked) continue
        }
        next.execute()
      } catch (error: CancellationException) { throw error }
      catch (error: Exception) {
        next.failed = true
        refreshFailure()
        if (!next.independentRetry) throw error
        firstError = firstError ?: error
        block(next)
        continue
      }
      synchronized(pendingLock) { pending.remove(next) }
    }
    // Unrelated success must never hide retained or quarantined work.
    refreshFailure()
    firstError?.let { throw it }
  }
}
