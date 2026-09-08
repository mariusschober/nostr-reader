package com.reader.app.sync

import android.content.Context
import com.reader.app.readerRelayClient
import android.util.Log
import androidx.work.*
import androidx.room.withTransaction
import com.reader.app.core.ReaderCore
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.READER_RELAY_WRITE_QUORUM
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.security.KeystoreWrap
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/**
 * An active database row without its Android-Keystore-wrapped private key can
 * never authenticate or decrypt again. Revoke it immediately instead of
 * leaving the UI in a false "connected" state.
 */
internal suspend fun openActiveChannelKeyOrRevoke(
  db: ReaderDb,
  keys: KeystoreWrap,
  channel: ChannelEntity,
  nowMillis: Long = System.currentTimeMillis(),
): ByteArray? {
  val seckey = runCatching { keys.openChannelKey(channel.channelId) }.getOrNull()
  if (seckey != null) return seckey
  db.channels().revoke(channel.channelId, nowMillis)
  runCatching { keys.deleteChannelKey(channel.channelId) }
  return null
}

private const val ACK_MAX_BACKOFF_MILLIS = 6L * 60L * 60L * 1000L

internal fun acceptedAckRelays(rawJson: String, configuredRelays: List<String>): LinkedHashSet<String> {
  val stored = runCatching {
    Json.parseToJsonElement(rawJson).jsonArray.map { it.jsonPrimitive.content }.toSet()
  }.getOrDefault(emptySet())
  return configuredRelays.filterTo(linkedSetOf()) { it in stored }
}

internal fun ackRetryDelayMillis(attempt: Int, transferId: String): Long {
  val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(16)
  val base = (60_000L * (1L shl exponent)).coerceAtMost(ACK_MAX_BACKOFF_MILLIS)
  val jitterPercent = 80 + ((transferId.takeLast(2).toIntOrNull(16) ?: 20) % 41)
  return (base * jitterPercent / 100L).coerceAtMost(ACK_MAX_BACKOFF_MILLIS)
}

internal fun syncNeedsRetry(pairingPending: Boolean, ackPending: Boolean): Boolean =
  pairingPending || ackPending

/** Background poll: rolling 10-day window, dedupe, assemble, ACK. No permanent socket. */
class ReaderSyncSession(private val applicationContext: Context) {
  companion object { private val syncMutex = Mutex() }
  suspend fun runOnce(): Boolean = syncMutex.withLock {
    val db = ReaderDb.get(applicationContext)
    val keys = KeystoreWrap(applicationContext)
    val relays = applicationContext.readerRelayClient()
    var healthyConnections = 0
    var failedConnections = 0
    suspend fun recordHealth(error: String?) {
      val previous = db.syncHealth().get()
      val now = System.currentTimeMillis()
      db.syncHealth().put(com.reader.app.data.SyncHealthEntity(
        checkedAt = now, successfulAt = if (healthyConnections > 0 && error == null) now else previous?.successfulAt,
        healthyRelays = healthyConnections, failedRelays = failedConnections,
        pendingTransfers = db.syncHealth().pendingTransfers(), pendingReceipts = db.syncHealth().pendingReceipts(), error = error,
      ))
    }
    return@withLock try {
      db.chunks().purgeExpired(System.currentTimeMillis())
      db.manifests().purgeExpired(System.currentTimeMillis() / 1000)
      db.ackIntents().purgeExpired(System.currentTimeMillis() / 1000)
      db.processedEvents().purgeExpired(System.currentTimeMillis() / 1000)
      val pairingRetryNeeded = PairingCoordinator(applicationContext, db, keys, relays).processDue()
      val channels = db.channels().active()
      Log.i("NostrReaderSync", "activeChannels=${channels.size}")
      val tm = TransferManager(db)
      val ackDispatcher = AckDispatcher(db, tm, relays)
      var ackRetryNeeded = false
      var receiveFailed = false
      for (ch in channels) {
        val seckey = openActiveChannelKeyOrRevoke(db, keys, ch)
        if (seckey == null) {
          Log.w("NostrReaderSync", "channel=${ch.channelId.take(8)} key=unavailable_repair_required")
          continue
        }
        val receiverPubkey = ch.receiverPubkey
        val relayList = try { validatedChannelRelays(ch, seckey) } catch (_: Exception) {
          receiveFailed = true
          continue
        }
        Log.i("NostrReaderSync", "channel=${ch.channelId.take(8)} relays=${relayList.size}")
        val since = ReaderCore.syncSince(System.currentTimeMillis() / 1000)
        // Small bounded queue: process while sockets receive, not after the
        // slowest relay's collection window. Producers backpressure individually.
        coroutineScope {
          val lease = ChannelLease.acquire(ch, kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!)
          try {
          lease.check(db)
          val intakeQueue = Channel<com.reader.app.nostr.NostrEvent>(16)
          val ackSignals = Channel<Unit>(Channel.CONFLATED)
          val acknowledger = launch(Dispatchers.IO) {
            for (signal in ackSignals) {
              if (ackDispatcher.dispatch(ch, seckey, relayList, lease)) ackRetryNeeded = true
            }
          }
          ackSignals.trySend(Unit) // resume intents from a previous process

          val consumer = launch(Dispatchers.IO) {
            for (ev in intakeQueue) {
              try {
                val intake = tm.ingestForSync(ev, ch.channelId, ch.trustedSenderPubkey, seckey) { lease.check(db) }
                if (intake != null) ackSignals.trySend(Unit)
              } catch (error: CancellationException) { throw error
              } catch (_: java.io.IOException) {
                receiveFailed = true
                Log.w("NostrReaderSync", "local intake capacity or storage unavailable")
              } catch (_: Exception) {
                Log.w("NostrReaderSync", "authenticated intake rejected")
              }
            }
          }
          consumer.invokeOnCompletion { intakeQueue.cancel() }
          val coverage = launch(Dispatchers.IO) {
            relayList.map { url -> async {
              try {
                lease.check(db)
                val old = db.historyCoverage().get(ch.channelId, url)?.let { Json.decodeFromString<HistoryCoverage>(it.stateJson) }
                val state = old?.takeIf { it.pending.isNotEmpty() } ?: HistoryCoverage.start(since, System.currentTimeMillis() / 1000 + 60).copy(
                  incomplete = old?.incomplete.orEmpty().filter { it.until >= since })
                val result = scanHistory(state, query = { window ->
                  lease.check(db)
                  runInterruptible { relays.subscribe(url, receiverPubkey, window.since, listOf(WRAP_KIND), 10, seckey, window.until, window.limit) }
                }, consume = { event ->
                  // Authentication rejection is a consumed invalid event; storage
                  // failure/cancellation leaves the entire window uncheckpointed.
                  try {
                    if (tm.ingestForSync(event, ch.channelId, ch.trustedSenderPubkey, seckey) { lease.check(db) } != null) ackSignals.trySend(Unit)
                  } catch (error: CancellationException) { throw error }
                  catch (error: java.io.IOException) { throw error }
                  catch (_: IllegalArgumentException) { }
                }, checkpoint = { next ->
                  db.withTransaction {
                    lease.check(db)
                    db.historyCoverage().put(com.reader.app.data.HistoryCoverageEntity(ch.channelId, url, Json.encodeToString(next)))
                  }
                })
                if (result.pending.isNotEmpty() || result.incomplete.isNotEmpty()) receiveFailed = true
              } catch (error: CancellationException) { throw error }
              catch (_: Exception) { receiveFailed = true }
            } }.awaitAll()
          }
          try {
            relayList.map { url ->
              async(Dispatchers.IO) {
                try {
                  lease.check(db)
                  runInterruptible {
                    relays.subscribe(url, receiverPubkey, since, listOf(WRAP_KIND), 20, seckey) { event ->
                      // Bounded send; cancellation of this scope cancels the
                      // queue and runInterruptible closes each socket in finally.
                      runBlocking { intakeQueue.send(event) }
                    }
                  }
                  true
                } catch (error: CancellationException) { throw error
                } catch (_: Exception) { false }
              }
            }.awaitAll().let { results ->
              healthyConnections += results.count { it }
              failedConnections += results.count { !it }
              if (results.any { !it }) receiveFailed = true
            }
          } finally {
            intakeQueue.close()
          }
          consumer.join()
          coverage.join()
          ackSignals.close()
          acknowledger.join()
          } finally { lease.close() }
        }
      }
      recordHealth(if (receiveFailed) "Some relay connections or incoming transfers could not be completed. Reader will retry." else null)
      syncNeedsRetry(pairingRetryNeeded, ackRetryNeeded) || receiveFailed
    } catch (error: CancellationException) {
      throw error
    } catch (e: Exception) {
      runCatching { recordHealth("Sync could not finish. Reader will retry.") }
      true
    }
  }

}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result =
    if (ReaderSyncSession(applicationContext).runOnce()) Result.retry() else Result.success()

  companion object {
    private fun connectedConstraint(): Constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
      val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
        .setConstraints(connectedConstraint())
        .build()
      WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reader-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Opening Reader is an explicit opportunity to catch up immediately. */
    fun runNow(ctx: Context) {
      val req = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(connectedConstraint())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork("reader-sync-now", ExistingWorkPolicy.KEEP, req)
    }
  }
}
