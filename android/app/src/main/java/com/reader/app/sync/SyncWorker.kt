package com.reader.app.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.reader.app.core.ReaderCore
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.READER_RELAY_WRITE_QUORUM
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.security.KeystoreWrap
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

private const val ACK_MAX_ATTEMPTS = 168
private const val ACK_BATCH_LIMIT = 32
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
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  private data class AckRelayAttempt(
    val relayUrl: String,
    val outcome: RelayClient.PublishResult? = null,
    val failure: Exception? = null,
  )

  private fun safeRelay(url: String): String = runCatching {
    java.net.URI(url).let { uri -> "${uri.scheme}://${uri.host}${uri.rawPath.orEmpty()}" }
  }.getOrDefault("invalid-relay")

  private fun safeFailure(error: Throwable): String =
    (error.message ?: error.javaClass.simpleName)
      .replace(Regex("(?i)[0-9a-f]{48,}"), "[redacted-hex]")
      .replace(Regex("[A-Za-z0-9_+/=-]{48,}"), "[redacted-token]")
      .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
      .take(160)

  private suspend fun dispatchPendingAcks(
    db: ReaderDb,
    tm: TransferManager,
    relays: RelayClient,
    channel: ChannelEntity,
    channelSeckey: ByteArray,
    relayList: List<String>,
  ): Boolean {
    val dao = db.ackIntents()
    val nowMillis = System.currentTimeMillis()
    val nowSecs = nowMillis / 1000
    for (intent in dao.due(channel.channelId, nowSecs, nowMillis, ACK_BATCH_LIMIT)) {
      val accepted = acceptedAckRelays(intent.acceptedRelaysJson, relayList)
      var lastError: String? = null
      val attempts = coroutineScope {
        relayList.filterNot { it in accepted }.map { relayUrl ->
          async(Dispatchers.IO) {
            try {
              val event = tm.buildAck(intent, channelSeckey)
              AckRelayAttempt(relayUrl, outcome = relays.publishDetailed(relayUrl, event, 10, channelSeckey))
            } catch (error: CancellationException) {
              throw error
            } catch (error: Exception) {
              AckRelayAttempt(relayUrl, failure = error)
            }
          }
        }.awaitAll()
      }
      for (relayAttempt in attempts) {
        val relayUrl = relayAttempt.relayUrl
        relayAttempt.outcome?.let { outcome ->
          if (outcome.accepted) {
            accepted += relayUrl
            dao.update(intent.copy(acceptedRelaysJson = Json.encodeToString(accepted.toList())))
          } else {
            lastError = outcome.terminalState.name.lowercase()
          }
          Log.i(
            "NostrReaderSync",
            "relay=${safeRelay(relayUrl)} ack=${outcome.terminalState.name}" +
              (outcome.reasonPrefix?.let { " reason=${safeFailure(IllegalStateException(it))}" } ?: ""),
          )
        }
        relayAttempt.failure?.let { error ->
          lastError = error.javaClass.simpleName.lowercase()
          Log.w("NostrReaderSync", "relay=${safeRelay(relayUrl)} ack=${safeFailure(error)}")
        }
      }
      val attempt = intent.attemptCount + 1
      val acceptedJson = Json.encodeToString(accepted.toList())
      when {
        accepted.size >= READER_RELAY_WRITE_QUORUM -> {
          dao.update(
            intent.copy(
              acceptedRelaysJson = acceptedJson,
              attemptCount = attempt,
              nextAttemptAt = null,
              completedAt = System.currentTimeMillis(),
              failedAt = null,
              lastErrorCode = null,
            ),
          )
          Log.i(
            "NostrReaderSync",
            "transfer=${intent.transferId.take(8)} ack=quorum_confirmed relays=${accepted.size}",
          )
        }
        attempt >= ACK_MAX_ATTEMPTS -> {
          dao.update(
            intent.copy(
              acceptedRelaysJson = acceptedJson,
              attemptCount = attempt,
              nextAttemptAt = null,
              completedAt = null,
              failedAt = System.currentTimeMillis(),
              lastErrorCode = lastError ?: "quorum_not_reached",
            ),
          )
          Log.w("NostrReaderSync", "transfer=${intent.transferId.take(8)} ack=retry_ceiling")
        }
        else -> {
          dao.update(
            intent.copy(
              acceptedRelaysJson = acceptedJson,
              attemptCount = attempt,
              nextAttemptAt = System.currentTimeMillis() + ackRetryDelayMillis(attempt, intent.transferId),
              completedAt = null,
              failedAt = null,
              lastErrorCode = lastError ?: "quorum_not_reached",
            ),
          )
          Log.w(
            "NostrReaderSync",
            "transfer=${intent.transferId.take(8)} ack=quorum_pending accepted=${accepted.size}",
          )
        }
      }
    }
    return dao.pendingCount(channel.channelId, System.currentTimeMillis() / 1000) > 0
  }

  override suspend fun doWork(): Result = syncMutex.withLock {
    val db = ReaderDb.get(applicationContext)
    val keys = KeystoreWrap(applicationContext)
    val relays = RelayClient()
    return@withLock try {
      db.chunks().purgeExpired(System.currentTimeMillis())
      db.manifests().purgeExpired(System.currentTimeMillis() / 1000)
      db.ackIntents().purgeExpired(System.currentTimeMillis() / 1000)
      db.processedEvents().purgeExpired(System.currentTimeMillis() / 1000)
      val pairingRetryNeeded = PairingCoordinator(applicationContext, db, keys, relays).processDue()
      val channels = db.channels().active()
      Log.i("NostrReaderSync", "activeChannels=${channels.size}")
      val tm = TransferManager(db)
      var ackRetryNeeded = false
      for (ch in channels) {
        val seckey = openActiveChannelKeyOrRevoke(db, keys, ch)
        if (seckey == null) {
          Log.w("NostrReaderSync", "channel=${ch.channelId.take(8)} key=unavailable_repair_required")
          continue
        }
        val receiverPubkey = ch.receiverPubkey
        val relayList = runCatching {
          Json.parseToJsonElement(ch.relaysJson).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        if (relayList.isEmpty()) continue
        if (runCatching { PairingProtocol.validateResolvedRelayAddresses(relayList) }.isFailure) {
          Log.w("NostrReaderSync", "channel=${ch.channelId.take(8)} relayValidation=failed")
          continue
        }
        Log.i("NostrReaderSync", "channel=${ch.channelId.take(8)} relays=${relayList.size}")
        val since = ReaderCore.syncSince(System.currentTimeMillis() / 1000)
        val seen = mutableSetOf<String>()
        val batches = coroutineScope {
          relayList.map { url ->
            async(Dispatchers.IO) {
              val result = runCatching {
                relays.subscribe(url, receiverPubkey, since, listOf(WRAP_KIND), 20, seckey)
              }
              result.onSuccess {
                Log.i("NostrReaderSync", "relay=${safeRelay(url)} received=${it.size}")
              }.onFailure {
                Log.w("NostrReaderSync", "relay=${safeRelay(url)} subscribe=${safeFailure(it)}")
              }
              result.getOrDefault(emptyList())
            }
          }.awaitAll()
        }
        for (events in batches) {
          for (ev in events) {
            if (!seen.add(ev.id)) continue // relays overlap: dedupe
            val intake = try {
              tm.ingestForSync(ev, ch.channelId, ch.trustedSenderPubkey, seckey)
            } catch (e: Exception) {
              Log.w("NostrReaderSync", "event=${ev.id.take(12)} ingest=${safeFailure(e)}")
              continue
            }
            if (intake == null) {
              Log.d("NostrReaderSync", "event=${ev.id.take(12)} ingest=no-ack-partial-or-unrelated")
              continue
            }
            Log.i(
              "NostrReaderSync",
              "event=${ev.id.take(12)} transfer=${intake.transferId.take(8)} status=${intake.status}",
            )
          }
        }
        if (dispatchPendingAcks(db, tm, relays, ch, seckey, relayList)) ackRetryNeeded = true
      }
      if (syncNeedsRetry(pairingRetryNeeded, ackRetryNeeded)) Result.retry() else Result.success()
    } catch (error: CancellationException) {
      throw error
    } catch (e: Exception) {
      Result.retry()
    }
  }

  companion object {
    private val syncMutex = Mutex()

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
