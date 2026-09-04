package com.reader.app.sync

import android.content.Context
import androidx.work.*
import com.reader.app.core.ReaderCore
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.nostr.WRAP_KIND_EPHEMERAL
import com.reader.app.security.KeystoreWrap
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/** Background poll: rolling 10-day window, dedupe, assemble, ACK. No permanent socket. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    val db = ReaderDb.get(applicationContext)
    val keys = KeystoreWrap(applicationContext)
    val relays = RelayClient()
    return try {
      db.chunks().purgeExpired(System.currentTimeMillis())
      val channels = db.channels().active()
      val tm = TransferManager(db, keys, relays)
      for (ch in channels) {
        val seckey = keys.openChannelKey(ch.channelId) ?: continue
        val receiverPubkey = ch.receiverPubkey
        val relayList = runCatching {
          Json.parseToJsonElement(ch.relaysJson).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band"))
        val since = ReaderCore.syncSince(System.currentTimeMillis() / 1000)
        val seen = mutableSetOf<String>()
        for (url in relayList) {
          val events = try {
            relays.subscribe(url, receiverPubkey, since, listOf(WRAP_KIND, WRAP_KIND_EPHEMERAL), 20)
          } catch (e: Exception) {
            continue
          }
          for (ev in events) {
            if (!seen.add(ev.id)) continue // relays overlap: dedupe
            val intake = try {
              tm.ingestWrap(ev, ch.channelId, ch.trustedSenderPubkey, seckey)
            } catch (e: Exception) {
              continue
            } ?: continue
            // ACK only after durable commit (ingestWrap returns non-null iff committed).
            try {
              val ack = tm.buildAck(intake, ch.trustedSenderPubkey, seckey)
              var sent = 0
              for (r in relayList) {
                if (runCatching { relays.publish(r, ack, 10) }.getOrDefault(false)) sent++
              }
            } catch (e: Exception) {
            }
          }
        }
      }
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }

  companion object {
    fun schedule(ctx: Context) {
      val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
      WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reader-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
  }
}
