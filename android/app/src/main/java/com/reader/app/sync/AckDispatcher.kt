package com.reader.app.sync

import androidx.room.withTransaction
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.READER_RELAY_WRITE_QUORUM
import com.reader.app.nostr.RelayClient
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One session-owned dispatcher; reserve before IO, merge each result durably. */
internal class AckDispatcher(
  private val db: ReaderDb,
  private val manager: TransferManager,
  relayClient: RelayClient,
  private val publish: suspend (String, com.reader.app.nostr.NostrEvent, ByteArray) -> RelayClient.PublishResult = { url, event, key ->
    runInterruptible { relayClient.publishDetailed(url, event, 10, key) }
  },
) {
  suspend fun dispatch(channel: ChannelEntity, key: ByteArray, relayList: List<String>, lease: ChannelLease): Boolean {
    require(validatedChannelRelays(channel, key) == relayList)
    lease.check(db)
    val dao = db.ackIntents()
    val startedAt = System.currentTimeMillis()
    for (candidate in dao.due(channel.channelId, startedAt / 1000, startedAt, 8)) {
      if (System.currentTimeMillis() - startedAt > 12_000) break
      val reserved = db.withTransaction {
        lease.check(db)
        val current = dao.byTransfer(channel.channelId, candidate.transferId) ?: return@withTransaction null
        val now = System.currentTimeMillis()
        if (current.completedAt != null || current.failedAt != null || current.expiresAt <= now / 1000 ||
          (current.nextAttemptAt ?: 0) > now) return@withTransaction null
        if (current.attemptCount >= 168) {
          dao.update(current.copy(failedAt = now, nextAttemptAt = null, lastErrorCode = "retry_ceiling"))
          return@withTransaction null
        }
        current.copy(attemptCount = current.attemptCount + 1,
          nextAttemptAt = now + ackRetryDelayMillis(current.attemptCount + 1, current.transferId))
          .also { dao.update(it) }
      } ?: continue
      val acceptedBefore = acceptedAckRelays(reserved.acceptedRelaysJson, relayList)
      coroutineScope {
        relayList.filterNot { it in acceptedBefore }.map { url -> async(Dispatchers.IO) {
          lease.check(db)
          val result = try {
            publish(url, manager.buildAck(reserved, key), key)
          } catch (error: CancellationException) { throw error }
          catch (_: Exception) { null }
          db.withTransaction {
            lease.check(db)
            val latest = dao.byTransfer(channel.channelId, reserved.transferId) ?: return@withTransaction
            if (latest.attemptCount != reserved.attemptCount || latest.refreshCount != reserved.refreshCount) return@withTransaction
            val accepted = acceptedAckRelays(latest.acceptedRelaysJson, relayList)
            if (result?.accepted == true) accepted += url
            val complete = accepted.size >= READER_RELAY_WRITE_QUORUM
            dao.update(latest.copy(
              acceptedRelaysJson = Json.encodeToString(accepted.toList()),
              completedAt = if (complete) latest.completedAt ?: System.currentTimeMillis() else null,
              nextAttemptAt = if (complete || latest.attemptCount >= 168) null else latest.nextAttemptAt,
              failedAt = if (!complete && latest.attemptCount >= 168) System.currentTimeMillis() else null,
              lastErrorCode = if (complete) null else result?.terminalState?.name?.lowercase() ?: "socket_error",
            ))
            lease.check(db)
          }
        } }.awaitAll()
      }
    }
    return dao.pendingCount(channel.channelId, System.currentTimeMillis() / 1000) > 0
  }
}
