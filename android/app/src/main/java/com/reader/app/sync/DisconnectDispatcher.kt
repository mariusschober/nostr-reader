package com.reader.app.sync

import com.reader.app.data.ChannelEntity
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.WRAP_KIND
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Best-effort signed teardown to the paired Chrome device; local revoke never waits on it. */
class DisconnectDispatcher(private val relayClient: RelayClient) {
  suspend fun publish(
    channel: ChannelEntity,
    channelSeckey: ByteArray,
    nowSecs: Long = System.currentTimeMillis() / 1000,
    timeoutSecs: Long = 4,
  ): Int {
    val relays = runCatching {
      Json.parseToJsonElement(channel.relaysJson).jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())
    if (relays.size !in 2..8 || relays.distinct().size != relays.size) return 0
    val payload = DisconnectProtocol.payload(nowSecs)
    return coroutineScope {
      relays.map { relay ->
        async(Dispatchers.IO) {
          runCatching {
            val wrap = NostrCodec.sealAndWrap(
              senderSeckey = channelSeckey,
              recipientPubkeyHex = channel.trustedSenderPubkey,
              payloadJson = payload,
              wrapKind = WRAP_KIND,
              expireSecs = DisconnectProtocol.TTL_SECS,
            ).second
            runInterruptible { relayClient.publishDetailed(relay, wrap, timeoutSecs, channelSeckey) }.accepted
          }.getOrDefault(false)
        }
      }.awaitAll().count { it }
    }
  }
}
