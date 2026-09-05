package com.reader.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.reader.app.data.ChannelEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.PAIRING_PROTOCOL
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.Secp256k1
import com.reader.app.nostr.StrictJson
import com.reader.app.nostr.ValidatedPairingRequest
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.security.KeystoreWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.min

/** Durable Android half of the reader-pair/2 authenticated handshake. */
class PairingCoordinator(
  context: Context,
  private val db: ReaderDb = ReaderDb.get(context),
  private val keys: KeystoreWrap = KeystoreWrap(context),
  private val relayClient: RelayClient = RelayClient(),
) {
  data class BeginResult(val channelId: String, val connected: Boolean, val acceptedRelays: Int)

  private val appContext = context.applicationContext
  private val random = SecureRandom()

  private fun appVersion(): String = runCatching {
    appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
  }.getOrNull()?.takeIf { it.matches(Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$")) } ?: "unknown"

  private fun restoredRequest(channel: ChannelEntity): ValidatedPairingRequest {
    val stored = channel.pairingRequestJson ?: throw IllegalStateException("PAIRING_REQUEST_MISSING")
    val createdAt = StrictJson.parseObject(stored)["createdAt"]!!.jsonPrimitive.long
    return PairingProtocol.validateRequest(stored, createdAt)
  }

  private fun relayList(channel: ChannelEntity): List<String> =
    Json.parseToJsonElement(channel.relaysJson).jsonArray.map { it.jsonPrimitive.content }

  private suspend fun publishFresh(
    relays: List<String>,
    senderSeckey: ByteArray,
    recipientPubkey: String,
    payload: String,
    timeoutSecs: Long,
  ): List<RelayClient.PublishResult> = coroutineScope {
    relays.map { relay ->
      async(Dispatchers.IO) {
        val wrap = NostrCodec.sealAndWrap(
          senderSeckey = senderSeckey,
          recipientPubkeyHex = recipientPubkey,
          payloadJson = payload,
          wrapKind = WRAP_KIND,
          expireSecs = 600,
        ).second
        relayClient.publishDetailed(relay, wrap, timeoutSecs, senderSeckey)
      }
    }.awaitAll()
  }

  private fun failureCode(results: List<RelayClient.PublishResult>): String = when {
    results.any { it.terminalState == RelayClient.PublishState.PROTOCOL_ERROR } -> "PROTOCOL_ERROR"
    results.any { it.terminalState == RelayClient.PublishState.AUTH_REJECTED } -> "AUTH_REJECTED"
    results.any { it.terminalState == RelayClient.PublishState.OK_FALSE } -> "RELAY_REJECTED"
    results.any { it.terminalState == RelayClient.PublishState.TLS_ERROR } -> "TLS_ERROR"
    results.any { it.terminalState == RelayClient.PublishState.SOCKET_ERROR } -> "SOCKET_ERROR"
    else -> "RELAY_CONFIRMATION_TIMEOUT"
  }

  private fun failureMessage(results: List<RelayClient.PublishResult>): String {
    if (results.any { it.terminalState == RelayClient.PublishState.PROTOCOL_ERROR }) {
      return "A pairing relay sent an invalid authentication message."
    }
    if (results.any { it.terminalState == RelayClient.PublishState.AUTH_REJECTED }) {
      return "A pairing relay refused the app's anonymous authentication key."
    }
    val rejected = results.firstOrNull { it.terminalState == RelayClient.PublishState.OK_FALSE }
    if (rejected != null) {
      val reason = rejected.reasonPrefix?.take(120)?.takeIf { it.isNotBlank() }
      return if (reason == null) "A pairing relay rejected the reply." else "A pairing relay rejected the reply: $reason"
    }
    if (results.any { it.terminalState == RelayClient.PublishState.TLS_ERROR }) {
      return "A secure connection to the pairing relays could not be established."
    }
    if (results.any { it.terminalState == RelayClient.PublishState.SOCKET_ERROR }) {
      return "The app could not connect to any pairing relay."
    }
    return "The pairing relays did not confirm the reply in time."
  }

  private fun nextAttemptSecs(attempt: Int, nowSecs: Long): Long {
    val exponent = min(6, maxOf(0, attempt - 1))
    val base = min(300L, 5L * (1L shl exponent))
    return nowSecs + base + random.nextInt(maxOf(1, (base / 4).toInt() + 1))
  }

  suspend fun begin(qrText: String, ackCollectSecs: Long = 8): BeginResult =
    pairingMutex.withLock { beginLocked(qrText, ackCollectSecs) }

  private suspend fun beginLocked(qrText: String, ackCollectSecs: Long): BeginResult {
    val nowSecs = System.currentTimeMillis() / 1000
    val request = PairingProtocol.validateRequest(qrText, nowSecs)
    withContext(Dispatchers.IO) { PairingProtocol.validateResolvedRelayAddresses(request.relays) }

    val existing = withContext(Dispatchers.IO) { db.channels().bySession(request.sessionId) }
    if (existing?.state == "provisioning") {
      // A prior process died between durable intent and wrapped-key commit.
      // That half-created channel is not resumable; remove it before retrying.
      cancelLocked(existing.channelId, "PAIRING_PROVISIONING_INTERRUPTED")
    } else if (existing != null) {
      require(existing.pairingRequestJson == request.json.toString()) { "PAIRING_SESSION_CONFLICT" }
      val connected = processLocked(existing.channelId, ackCollectSecs)
      return BeginResult(existing.channelId, connected, existing.acceptedRelaysJson?.let {
        runCatching { Json.parseToJsonElement(it).jsonArray.size }.getOrDefault(0)
      } ?: 0)
    }

    val channelSeckey = Secp256k1.randomPrivateKey()
    val channelPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(channelSeckey))
    val channelId = UUID.randomUUID().toString()
    val relaysJson = JsonArray(request.relays.map(::JsonPrimitive)).toString()
    try {
      withContext(Dispatchers.IO) {
        db.channels().upsert(
          ChannelEntity(
            channelId = channelId,
            receiverPubkey = channelPubkey,
            trustedSenderPubkey = request.chromeDevicePubkey,
            createdAt = System.currentTimeMillis(),
            revokedAt = null,
            relaysJson = relaysJson,
            protocolVersion = 2,
            // Durable intent comes first. Workers deliberately ignore this
            // state until the wrapped key has been committed below.
            state = "provisioning",
            sessionId = request.sessionId,
            pairingPubkey = request.pairingPubkey,
            pairingNonce = request.nonce,
            pairingRequestJson = request.json.toString(),
            relaySetDigest = request.relaySetDigest,
            pendingExpiresAt = request.expiresAt + 600,
            updatedAt = System.currentTimeMillis(),
          ),
        )
      }
      keys.sealChannelKey(channelId, channelSeckey)
      withContext(Dispatchers.IO) {
        db.channels().updatePairingState(
          id = channelId,
          state = "pending_response",
          acceptedRelaysJson = null,
          attemptCount = 0,
          nextAttemptAt = nowSecs,
          lastErrorCode = null,
          updatedAt = System.currentTimeMillis(),
        )
      }
    } catch (error: Exception) {
      runCatching { withContext(Dispatchers.IO) { db.channels().revoke(channelId, System.currentTimeMillis()) } }
      keys.deleteChannelKey(channelId)
      throw error
    }

    val payload = PairingProtocol.buildPairResponse(request, channelPubkey, appVersion(), nowSecs).toString()
    val results = publishFresh(request.relays, channelSeckey, request.pairingPubkey, payload, 12)
    val accepted = results.filter { it.accepted }.map { it.relayUrl }
    if (accepted.isEmpty()) {
      withContext(Dispatchers.IO) { db.channels().revoke(channelId, System.currentTimeMillis()) }
      keys.deleteChannelKey(channelId)
      throw IllegalStateException(failureMessage(results))
    }
    withContext(Dispatchers.IO) {
      db.channels().updatePairingState(
        id = channelId,
        state = "awaiting_ack",
        acceptedRelaysJson = JsonArray(accepted.map(::JsonPrimitive)).toString(),
        attemptCount = 1,
        nextAttemptAt = nowSecs + 3,
        lastErrorCode = null,
        updatedAt = System.currentTimeMillis(),
      )
    }
    val connected = processLocked(channelId, ackCollectSecs)
    if (!connected) scheduleNow(appContext)
    return BeginResult(channelId, connected, accepted.size)
  }

  suspend fun process(channelId: String, collectSecs: Long = 5): Boolean =
    pairingMutex.withLock { processLocked(channelId, collectSecs) }

  private suspend fun processLocked(channelId: String, collectSecs: Long): Boolean {
    var channel = withContext(Dispatchers.IO) { db.channels().byId(channelId) } ?: return false
    if (channel.state == "active") return true
    val nowSecs = System.currentTimeMillis() / 1000
    if ((channel.pendingExpiresAt ?: 0) <= nowSecs) {
      cancelLocked(channelId, "PAIRING_EXPIRED")
      return false
    }
    val request = restoredRequest(channel)
    val relays = relayList(channel)
    withContext(Dispatchers.IO) { PairingProtocol.validateResolvedRelayAddresses(relays) }
    val channelSeckey = keys.openChannelKey(channelId) ?: run {
      cancelLocked(channelId, "CHANNEL_KEY_UNAVAILABLE")
      return false
    }

    if (channel.state == "pending_response") {
      val response = PairingProtocol.buildPairResponse(request, channel.receiverPubkey, appVersion(), nowSecs).toString()
      val results = publishFresh(relays, channelSeckey, request.pairingPubkey, response, 10)
      val accepted = results.filter { it.accepted }.map { it.relayUrl }
      withContext(Dispatchers.IO) {
        db.channels().updatePairingState(
          channelId,
          if (accepted.isEmpty()) "pending_response" else "awaiting_ack",
          if (accepted.isEmpty()) null else JsonArray(accepted.map(::JsonPrimitive)).toString(),
          channel.attemptCount + 1,
          nextAttemptSecs(channel.attemptCount + 1, nowSecs),
          if (accepted.isEmpty()) failureCode(results) else null,
          System.currentTimeMillis(),
        )
      }
      if (accepted.isEmpty()) return false
      channel = withContext(Dispatchers.IO) { db.channels().byId(channelId) } ?: return false
    }

    if (channel.state == "awaiting_ack") {
      val batches = coroutineScope {
        relays.map { relay ->
          async(Dispatchers.IO) {
            runCatching {
              relayClient.subscribe(
                relay,
                channel.receiverPubkey,
                request.createdAt - 172800,
                listOf(WRAP_KIND),
                collectSecs,
                channelSeckey,
              )
            }.getOrDefault(emptyList())
          }
        }.awaitAll()
      }
      val seen = mutableSetOf<String>()
      var acceptedRelaysJson: String? = null
      for (event in batches.flatten().sortedBy { it.createdAt }) {
        if (!seen.add(event.id)) continue
        val valid = runCatching {
          val envelope = NostrCodec.unwrapAndVerifyEnvelope(
            wrap = event,
            recipientSeckey = channelSeckey,
            expectedSenderPubkey = request.chromeDevicePubkey,
            expectedProtocols = setOf(PAIRING_PROTOCOL),
            nowSecs = nowSecs,
          )
          val payload = StrictJson.parseObject(envelope.payloadJson)
          PairingProtocol.validatePairAck(payload, request, channel.receiverPubkey, envelope.senderPubkey, nowSecs)
        }.getOrNull() ?: continue
        acceptedRelaysJson = valid["acceptedRelays"]!!.jsonArray.toString()
        break
      }
      if (acceptedRelaysJson == null) {
        withContext(Dispatchers.IO) {
          db.channels().updatePairingState(
            channelId,
            "awaiting_ack",
            channel.acceptedRelaysJson,
            channel.attemptCount + 1,
            nextAttemptSecs(channel.attemptCount + 1, nowSecs),
            "ACK_NOT_FOUND",
            System.currentTimeMillis(),
          )
        }
        return false
      }
      withContext(Dispatchers.IO) {
        db.channels().updatePairingState(
          channelId,
          "ack_validated",
          acceptedRelaysJson,
          channel.attemptCount + 1,
          nowSecs,
          null,
          System.currentTimeMillis(),
        )
      }
      channel = withContext(Dispatchers.IO) { db.channels().byId(channelId) } ?: return false
    }

    if (channel.state == "ack_validated" || channel.state == "completion_pending") {
      val complete = PairingProtocol.buildPairComplete(request, channel.receiverPubkey, nowSecs).toString()
      val results = publishFresh(relays, channelSeckey, request.chromeDevicePubkey, complete, 10)
      if (results.none { it.accepted }) {
        withContext(Dispatchers.IO) {
          db.channels().updatePairingState(
            channelId,
            "completion_pending",
            channel.acceptedRelaysJson,
            channel.attemptCount + 1,
            nextAttemptSecs(channel.attemptCount + 1, nowSecs),
            failureCode(results),
            System.currentTimeMillis(),
          )
        }
        return false
      }
      val staleChannelIds = withContext(Dispatchers.IO) {
        db.channels().active().filter { it.channelId != channelId }.map { it.channelId }
      }
      val promoted = withContext(Dispatchers.IO) {
        val updatedAt = System.currentTimeMillis()
        db.withTransaction {
          val result = db.channels().promoteAfterValidatedAck(channelId, updatedAt)
          if (result == 1) db.channels().revokeOtherActive(channelId, updatedAt)
          result
        }
      }
      if (promoted == 1) staleChannelIds.forEach { keys.deleteChannelKey(it) }
      return promoted == 1
    }
    return false
  }

  suspend fun processDue(collectSecs: Long = 4) = pairingMutex.withLock {
    val nowSecs = System.currentTimeMillis() / 1000
    val expired = withContext(Dispatchers.IO) { db.channels().expiredPending(nowSecs) }
    for (channel in expired) cancelLocked(channel.channelId, "PAIRING_EXPIRED")
    val pending = withContext(Dispatchers.IO) { db.channels().pendingPairings() }
    for (channel in pending) {
      if ((channel.nextAttemptAt ?: 0) <= nowSecs) runCatching { processLocked(channel.channelId, collectSecs) }
    }
  }

  suspend fun cancel(channelId: String, reason: String = "PAIRING_CANCELLED") =
    pairingMutex.withLock { cancelLocked(channelId, reason) }

  private suspend fun cancelLocked(channelId: String, reason: String) {
    withContext(Dispatchers.IO) { db.channels().revoke(channelId, System.currentTimeMillis()) }
    keys.deleteChannelKey(channelId)
    // The reason is intentionally not persisted beside a deleted bootstrap key.
    require(reason.isNotBlank())
  }

  companion object {
    private val pairingMutex = Mutex()

    fun scheduleNow(context: Context) {
      val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(
          androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build(),
        )
        .build()
      androidx.work.WorkManager.getInstance(context)
        .enqueueUniqueWork("reader-sync-now", androidx.work.ExistingWorkPolicy.REPLACE, request)
    }
  }
}
