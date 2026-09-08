package com.reader.app.sync

import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.Secp256k1
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Each authenticated channel snapshot owns cancellable network work. */
internal class ChannelLease(private val snapshot: ChannelEntity, private val job: Job) {
  companion object {
    // Recently-revoked IDs for instant-cancel of late acquires. Bounded FIFO:
    // channelIds are random, reuse is negligible, but an unbounded set would
    // leak and permanently ban a reused ID. Cap at 128 recent revocations.
    private const val MAX_REVOKED = 128
    private val revoked: LinkedHashMap<String, Unit> = object : LinkedHashMap<String, Unit>(128, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > MAX_REVOKED
    }
    private val owners = mutableMapOf<String, MutableSet<ChannelLease>>()
    @Synchronized fun revoke(id: String) {
      revoked[id] = Unit
      owners.remove(id)?.forEach { it.job.cancel(CancellationException("Channel revoked")) }
    }
    @Synchronized fun acquire(channel: ChannelEntity, job: Job): ChannelLease = ChannelLease(channel, job).also {
      if (channel.channelId in revoked) job.cancel(CancellationException("Channel revoked"))
      else owners.getOrPut(channel.channelId) { mutableSetOf() }.add(it)
    }
  }
  fun close() { synchronized(Companion) { owners[snapshot.channelId]?.remove(this) } }
  suspend fun check(db: ReaderDb) {
    if (!job.isActive) throw CancellationException("Channel lease ended")
    val current = db.channels().byId(snapshot.channelId)
    if (current == null || current.state != "active" || current.protocolVersion != 2 ||
      current.createdAt != snapshot.createdAt || current.sessionId != snapshot.sessionId ||
      current.receiverPubkey != snapshot.receiverPubkey || current.trustedSenderPubkey != snapshot.trustedSenderPubkey ||
      current.relaysJson != snapshot.relaysJson || current.relaySetDigest != snapshot.relaySetDigest) {
      throw CancellationException("Channel generation changed")
    }
  }
}

internal fun validatedChannelRelays(channel: ChannelEntity, key: ByteArray): List<String> {
  require(channel.revokedAt == null && channel.state == "active" && channel.protocolVersion == 2)
  require(Secp256k1.bytesToHex(Secp256k1.getPublicKey(key)) == channel.receiverPubkey) { "Channel key binding mismatch" }
  val relays = Json.parseToJsonElement(channel.relaysJson).jsonArray.map { it.jsonPrimitive.content }
  require(relays.size in 2..8 && relays.distinct().size == relays.size)
  relays.forEach { require(PairingProtocol.normalizeRelayUrl(it) == it) }
  require(PairingProtocol.relaySetDigest(relays) == channel.relaySetDigest) { "Channel relay digest mismatch" }
  return relays
}
