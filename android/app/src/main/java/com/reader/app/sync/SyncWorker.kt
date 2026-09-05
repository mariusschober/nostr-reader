package com.reader.app.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.reader.app.core.ReaderCore
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.security.KeystoreWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/** Different relays carry fresh NIP-59 wrappers for the same transfer. */
internal fun markAckForThisRun(ackedTransfers: MutableSet<String>, transferId: String): Boolean =
  ackedTransfers.add(transferId)

/** Background poll: rolling 10-day window, dedupe, assemble, ACK. No permanent socket. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  private fun safeRelay(url: String): String = runCatching {
    java.net.URI(url).let { uri -> "${uri.scheme}://${uri.host}${uri.rawPath.orEmpty()}" }
  }.getOrDefault("invalid-relay")

  private fun safeFailure(error: Throwable): String =
    (error.message ?: error.javaClass.simpleName)
      .replace(Regex("(?i)[0-9a-f]{48,}"), "[redacted-hex]")
      .replace(Regex("[A-Za-z0-9_+/=-]{48,}"), "[redacted-token]")
      .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
      .take(160)

  override suspend fun doWork(): Result {
    val db = ReaderDb.get(applicationContext)
    val keys = KeystoreWrap(applicationContext)
    val relays = RelayClient()
    return try {
      db.chunks().purgeExpired(System.currentTimeMillis())
      db.manifests().purgeExpired(System.currentTimeMillis() / 1000)
      PairingCoordinator(applicationContext, db, keys, relays).processDue()
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
        val ackedTransfers = mutableSetOf<String>()
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
              tm.ingestWrap(ev, ch.channelId, ch.trustedSenderPubkey, seckey)
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
            if (!markAckForThisRun(ackedTransfers, intake.transferId)) {
              Log.i(
                "NostrReaderSync",
                "transfer=${intake.transferId.take(8)} ack=already_published_this_run",
              )
              continue
            }
            // ACK only after durable commit (ingestWrap returns non-null iff committed).
            var sent = 0
            for (r in relayList) {
              // Fresh NIP-59 wrapper key and signature per relay.
              val result = runCatching {
                relays.publishDetailed(r, tm.buildAck(intake, ch.trustedSenderPubkey, seckey), 10, seckey)
              }
              result.onSuccess { outcome ->
                if (outcome.accepted) sent++
                Log.i(
                  "NostrReaderSync",
                  "relay=${safeRelay(r)} ack=${outcome.terminalState.name}" +
                    (outcome.reasonPrefix?.let { " reason=${safeFailure(IllegalStateException(it))}" } ?: ""),
                )
              }.onFailure { error ->
                Log.w("NostrReaderSync", "relay=${safeRelay(r)} ack=${safeFailure(error)}")
              }
            }
            if (sent == 0) {
              ackRetryNeeded = true
              Log.w(
                "NostrReaderSync",
                "transfer=${intake.transferId.take(8)} ack=not_confirmed retry=scheduled",
              )
            }
          }
        }
      }
      if (ackRetryNeeded) Result.retry() else Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }

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
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork("reader-sync-now", ExistingWorkPolicy.KEEP, req)
    }
  }
}
