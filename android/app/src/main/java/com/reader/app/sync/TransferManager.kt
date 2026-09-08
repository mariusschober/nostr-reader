package com.reader.app.sync

import androidx.room.withTransaction
import com.reader.app.data.TransferOutcomeEntity
import com.reader.app.core.ReaderCore
import com.reader.app.core.ReaderGzip
import com.reader.app.data.AckIntentEntity
import com.reader.app.data.ChunkEntity
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ManifestEntity
import com.reader.app.data.ProcessedEventEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.NostrEvent
import com.reader.app.nostr.Secp256k1
import com.reader.app.nostr.StrictJson
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class StagingLimits(
  val transfers: Int = 32,
  val totalBytes: Long = 64L * 1024 * 1024,
  val transferBytes: Long = ReaderCore.MAX_COMPRESSED_BYTES.toLong(),
  val processedEvents: Int = 100_000,
)

class StagingCapacityException : java.io.IOException("Incoming storage budget reached; pending transfers remain recoverable")

/** Incoming transfer assembly: authenticate, bind manifest/chunks, verify, commit, ACK. */
class TransferManager(
  private val db: ReaderDb,
  private val limits: StagingLimits = StagingLimits(),
) {
  data class Intake(
    val transferId: String,
    val documentId: String,
    val manifestId: String,
    val status: String,
    val expiresAt: Long,
    val compressedSha256: String = "",
    val chunkCount: Int = 0,
  )

  private val hex16 = Regex("^[0-9a-f]{32}$")
  private val hex32 = Regex("^[0-9a-f]{64}$")
  private val sourceTypes = setOf(
    "web", "chatgpt", "claude", "gemini", "perplexity", "notebook", "grok", "substack", "x", "selection",
    "android-share", "android-process-text", "file", "mac-share", "mac-quick-action",
  )

  suspend fun ingestWrap(
    wrap: NostrEvent,
    channelId: String,
    trustedSender: String,
    receiverSeckey: ByteArray,
  ): Intake? {
    return ingestForSync(wrap, channelId, trustedSender, receiverSeckey)
  }

  /**
   * Sync entrypoint: document mutation, processed-wrapper recording, and ACK
   * intent creation share one Room transaction. A crash can therefore leave
   * either all durable effects or none, never a committed document with no
   * recoverable ACK path.
   */
  suspend fun ingestForSync(
    wrap: NostrEvent,
    channelId: String,
    trustedSender: String,
    receiverSeckey: ByteArray,
    activeGeneration: suspend () -> Unit = {},
  ): Intake? {
    require(channelId.isNotBlank()) { "invalid channel" }
    // This is a no-op for already committed content, never authentication of a
    // new effect. Recompute the canonical hash instead of trusting a claimed ID.
    val processed = db.processedEvents().byId(wrap.id)
    if (processed?.channelId == channelId && NostrCodec.eventId(wrap.pubkey, wrap.createdAt,
        wrap.kind, wrap.tags, wrap.content) == wrap.id) return null
    val envelope = try {
      NostrCodec.unwrapAndVerifyEnvelope(wrap, receiverSeckey, trustedSender)
    } catch (_: Exception) {
      return null
    }
    val payload = StrictJson.parseObject(envelope.payloadJson)
    when (payload["type"]?.jsonPrimitive?.content) {
      "manifest" -> exactKeys(payload, setOf("protocol", "type", "transferId", "manifestId", "documentId", "title", "sourceType",
        "capturedAt", "mime", "compression", "wordCount", "uncompressedBytes", "compressedBytes", "compressedSha256", "documentSha256",
        "chunkCount", "senderDevicePubkey", "recipientChannelPubkey", "expiresAt"),
        setOf("sourceName", "sourceUrl", "author", "publishedAt", "language"))
      "chunk" -> exactKeys(payload, setOf("protocol", "type", "transferId", "manifestId", "documentId", "compressedSha256",
        "index", "count", "dataBase64", "expiresAt"))
      else -> return null
    }
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverSeckey))
    val wrapExpiresAt = wrap.tags.single { it.firstOrNull() == "expiration" }[1].toLong()
    val nowSecs = System.currentTimeMillis() / 1000
    val ledgerExpiresAt = minOf(wrapExpiresAt, nowSecs + ReaderCore.SYNC_WINDOW_DAYS * 86400L)
    return db.withTransaction {
      activeGeneration()
      if (db.processedEvents().byId(wrap.id) != null) return@withTransaction null
      if (db.processedEvents().count() >= limits.processedEvents) throw StagingCapacityException()
      val historical = payload["transferId"]?.jsonPrimitive?.content?.let { db.transferOutcomes().byTransfer(channelId, it) }
      val intake = if (historical != null) {
        require(payload["type"]?.jsonPrimitive?.content in setOf("manifest", "chunk"))
        require(payload["protocol"]?.jsonPrimitive?.content == ReaderCore.READER_PROTOCOL)
        if (payload["type"]?.jsonPrimitive?.content == "manifest") {
          require(payload["senderDevicePubkey"]?.jsonPrimitive?.content == trustedSender &&
            payload["recipientChannelPubkey"]?.jsonPrimitive?.content == receiverPubkey)
        }
        require(historical.recipientDevicePubkey == trustedSender &&
          payload["manifestId"]?.jsonPrimitive?.content == historical.manifestId &&
          payload["documentId"]?.jsonPrimitive?.content == historical.documentId &&
          payload["expiresAt"]?.jsonPrimitive?.long == historical.expiresAt) { "conflicting completed transfer" }
        // Bind the full byte identity when known (v10+ outcomes). Unknown
        // (empty/zero from pre-v10 backfill) skips that sub-check for compat.
        // A conflicting payload with the same IDs is rejected, never ACKed.
        if (historical.compressedSha256.isNotEmpty()) {
          require(payload["compressedSha256"]?.jsonPrimitive?.content == historical.compressedSha256) { "conflicting completed transfer" }
        }
        if (historical.chunkCount != 0) {
          val claimedCount = if (payload["type"]?.jsonPrimitive?.content == "manifest") {
            payload["chunkCount"]?.jsonPrimitive?.int
          } else {
            payload["count"]?.jsonPrimitive?.int
          }
          require(claimedCount == historical.chunkCount) { "conflicting completed transfer" }
        }
        // A fresh authenticated wrapper may refresh a historical receipt but
        // never stage payload or recreate a locally deleted document.
        Intake(historical.transferId, historical.documentId, historical.manifestId, "duplicate", historical.expiresAt,
          historical.compressedSha256, historical.chunkCount)
      } else when (payload["type"]?.jsonPrimitive?.content) {
        "manifest" -> handleManifest(payload, envelope.senderPubkey, receiverPubkey)
        "chunk" -> handleChunk(payload)
        else -> return@withTransaction null
      }
      // A null intake means the authenticated fragment was durably staged but
      // did not complete a transfer. Record it too: otherwise the same retained
      // wrapper would be parsed and staged on every rolling-window catch-up.
      val transferId = payload["transferId"]!!.jsonPrimitive.content
      val inserted = db.processedEvents().insert(
        ProcessedEventEntity(
          eventId = wrap.id,
          channelId = channelId,
          transferId = transferId,
          processedAt = System.currentTimeMillis(),
          expiresAt = ledgerExpiresAt,
        ),
      )
      if (inserted != -1L && intake != null) {
        if (historical == null) {
          // Concurrent live + coverage ingestion of the same transfer can both
          // observe no outcome and race to insert. ABORT would escape as a
          // SQLiteConstraintException and abort the window without checkpoint.
          // Re-read the winner and treat as duplicate instead.
          try {
            db.transferOutcomes().insert(TransferOutcomeEntity(
              channelId, intake.transferId, intake.manifestId, intake.documentId,
              trustedSender, intake.status, nowSecs, intake.expiresAt, null,
              intake.compressedSha256, intake.chunkCount))
          } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: android.database.SQLException) {
            db.transferOutcomes().byTransfer(channelId, intake.transferId)
              ?: throw IllegalStateException("completed transfer outcome missing")
          } catch (error: Exception) {
            // Non-constraint failures (e.g. storage full) must escape as
            // IOException so the window is retained, not checkpointed.
            if (error is kotlinx.coroutines.CancellationException) throw error
            val winner = runCatching { db.transferOutcomes().byTransfer(channelId, intake.transferId) }.getOrNull()
            if (winner == null) throw java.io.IOException("transfer outcome unavailable")
          }
        }
        queueAckIntent(channelId, trustedSender, intake, nowSecs, historical?.receivedAt ?: nowSecs)
      }
      activeGeneration()
      intake
    }
  }

  private suspend fun queueAckIntent(
    channelId: String,
    recipientDevicePubkey: String,
    intake: Intake,
    nowSecs: Long,
    receivedAt: Long = nowSecs,
  ) {
    require(intake.status == "stored" || intake.status == "duplicate") { "invalid ACK status" }
    require(intake.expiresAt > nowSecs) { "cannot queue expired ACK" }
    val dao = db.ackIntents()
    val existing = dao.byTransfer(channelId, intake.transferId)
    val preferredStatus = if (existing?.status == "stored" || intake.status == "stored") "stored" else "duplicate"
    if (existing == null) {
      dao.insert(
        AckIntentEntity(
          channelId = channelId,
          transferId = intake.transferId,
          manifestId = intake.manifestId,
          documentId = intake.documentId,
          recipientDevicePubkey = recipientDevicePubkey,
          status = preferredStatus,
          receivedAt = receivedAt,
          expiresAt = intake.expiresAt,
          acceptedRelaysJson = "[]",
          attemptCount = 0,
          nextAttemptAt = null,
          completedAt = null,
          failedAt = null,
          lastErrorCode = null,
        ),
      )
      return
    }
    require(
      existing.manifestId == intake.manifestId && existing.documentId == intake.documentId &&
        existing.recipientDevicePubkey == recipientDevicePubkey,
    ) { "conflicting ACK intent" }
    // Called only for a newly authenticated wrapper, after the durable replay
    // ledger check. Never extend immutable expiry or reset lifetime attempts.
    require(existing.expiresAt == intake.expiresAt) { "conflicting ACK expiry" }
    dao.update(refreshAckOnDemand(existing.copy(status = preferredStatus), nowSecs * 1000))
  }

  private fun exactKeys(payload: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
    require(payload.keys.containsAll(required)) { "missing message field" }
    require(payload.keys.all { it in required || it in optional }) { "unknown message field" }
    val numbers = setOf("capturedAt", "publishedAt", "expiresAt", "wordCount", "uncompressedBytes", "compressedBytes", "chunkCount", "index", "count")
    for ((key, value) in payload) {
      require(value is JsonPrimitive && if (key in numbers) !value.isString && value.longOrNull != null else value.isString) { "invalid field type" }
    }
  }

  private suspend fun requireStagingCapacity(transferId: String, additionalBytes: Int = 0) {
    val chunks = db.chunks()
    if ((!chunks.hasStagedTransfer(transferId) && chunks.stagedTransferCount() >= limits.transfers) ||
      chunks.stagedBytes() + additionalBytes > limits.totalBytes ||
      chunks.transferBytes(transferId) + additionalBytes > limits.transferBytes) throw StagingCapacityException()
  }

  private fun requiredString(payload: JsonObject, key: String, max: Int): String {
    val element = payload[key] ?: throw IllegalArgumentException("missing $key")
    require(element is JsonPrimitive && element.isString) { "invalid $key" }
    return element.content.also { require(it.length in 1..max) { "invalid $key" } }
  }

  private fun optionalString(payload: JsonObject, key: String, max: Int): String? {
    val element = payload[key] ?: return null
    require(element is JsonPrimitive && element.isString) { "invalid $key" }
    return element.content.also { require(it.length <= max) { "invalid $key" } }
  }

  private fun manifestIdentity(manifest: ManifestEntity): String {
    val canonical = buildJsonArray {
      add(ReaderCore.READER_PROTOCOL)
      add(manifest.transferId)
      add(manifest.documentId)
      add(manifest.compressedSha256)
      add(manifest.compressedBytes)
      add(manifest.chunkCount)
      add(manifest.senderDevicePubkey)
      add(manifest.recipientChannelPubkey)
      add(manifest.expiresAt)
    }.toString()
    return ReaderCore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
  }

  private suspend fun handleManifest(
    payload: JsonObject,
    verifiedSenderPubkey: String,
    receiverPubkey: String,
  ): Intake? {
    val required = setOf(
      "protocol", "type", "transferId", "manifestId", "documentId", "title", "sourceType",
      "capturedAt", "mime", "compression", "wordCount", "uncompressedBytes", "compressedBytes",
      "compressedSha256", "documentSha256", "chunkCount", "senderDevicePubkey",
      "recipientChannelPubkey", "expiresAt",
    )
    val optional = setOf("sourceName", "sourceUrl", "author", "publishedAt", "language")
    exactKeys(payload, required, optional)
    require(payload["protocol"]?.jsonPrimitive?.content == ReaderCore.READER_PROTOCOL) { "wrong manifest protocol" }
    require(payload["type"]?.jsonPrimitive?.content == "manifest") { "wrong manifest type" }
    val nowSecs = System.currentTimeMillis() / 1000
    val transferId = requiredString(payload, "transferId", 32).also { require(hex16.matches(it)) { "invalid transferId" } }
    val manifestId = requiredString(payload, "manifestId", 64).also { require(hex32.matches(it)) { "invalid manifestId" } }
    val documentId = requiredString(payload, "documentId", 64).also { require(hex32.matches(it)) { "invalid documentId" } }
    val compressedSha256 = requiredString(payload, "compressedSha256", 64).also { require(hex32.matches(it)) { "invalid compressedSha256" } }
    val documentSha256 = requiredString(payload, "documentSha256", 64).also { require(hex32.matches(it)) { "invalid documentSha256" } }
    require(documentSha256 == documentId) { "document hash binding mismatch" }
    val sender = requiredString(payload, "senderDevicePubkey", 64).also { require(hex32.matches(it)) { "invalid sender key" } }
    val recipient = requiredString(payload, "recipientChannelPubkey", 64).also { require(hex32.matches(it)) { "invalid recipient key" } }
    require(sender == verifiedSenderPubkey && recipient == receiverPubkey) { "manifest endpoint binding mismatch" }
    val title = requiredString(payload, "title", ReaderCore.MAX_TITLE_LEN)
    val sourceType = requiredString(payload, "sourceType", 32).also { require(it in sourceTypes) { "invalid source type" } }
    val capturedAt = payload["capturedAt"]!!.jsonPrimitive.long
    val expiresAt = payload["expiresAt"]!!.jsonPrimitive.long
    require(capturedAt <= nowSecs + 60 && capturedAt >= nowSecs - 8L * 86400) { "manifest timestamp out of range" }
    require(expiresAt > nowSecs && expiresAt <= capturedAt + 7L * 86400 + 60) { "manifest expired or overlong" }
    require(payload["mime"]!!.jsonPrimitive.content == "text/markdown") { "unsupported MIME type" }
    require(payload["compression"]!!.jsonPrimitive.content == "gzip") { "unsupported compression" }
    val wordCount = payload["wordCount"]!!.jsonPrimitive.int
    val uncompressedBytes = payload["uncompressedBytes"]!!.jsonPrimitive.int
    val compressedBytes = payload["compressedBytes"]!!.jsonPrimitive.int
    val chunkCount = payload["chunkCount"]!!.jsonPrimitive.int
    require(wordCount >= 0) { "invalid word count" }
    require(uncompressedBytes in 1..ReaderCore.MAX_EXPANDED_BYTES) { "invalid expanded size" }
    val sourceUrl = optionalString(payload, "sourceUrl", ReaderCore.MAX_URL_LEN)
    ReaderCore.checkLimits(compressedBytes, uncompressedBytes, title.length, sourceUrl?.length ?: 0, chunkCount)
    val manifest = ManifestEntity(
      transferId = transferId,
      manifestId = manifestId,
      documentId = documentId,
      title = title,
      sourceType = sourceType,
      sourceName = optionalString(payload, "sourceName", 200),
      sourceUrl = sourceUrl,
      author = optionalString(payload, "author", 300),
      publishedAt = payload["publishedAt"]?.jsonPrimitive?.long,
      capturedAt = capturedAt,
      language = optionalString(payload, "language", 16),
      mime = "text/markdown",
      compression = "gzip",
      wordCount = wordCount,
      uncompressedBytes = uncompressedBytes,
      compressedBytes = compressedBytes,
      compressedSha256 = compressedSha256,
      documentSha256 = documentSha256,
      chunkCount = chunkCount,
      senderDevicePubkey = sender,
      recipientChannelPubkey = recipient,
      expiresAt = expiresAt,
      receivedAt = System.currentTimeMillis(),
    )
    require(manifestIdentity(manifest) == manifestId) { "manifest identity mismatch" }
    requireStagingCapacity(transferId)
    val existing = db.manifests().byTransfer(transferId)
    if (existing != null) {
      require(existing.copy(receivedAt = manifest.receivedAt) == manifest) { "conflicting manifest" }
    } else {
      require(db.manifests().insert(manifest) != -1L) { "manifest insert conflict" }
    }
    if (db.documents().exists(documentId)) {
      db.withTransaction {
        db.chunks().clearTransfer(transferId)
        db.manifests().clearTransfer(transferId)
      }
      return Intake(transferId, documentId, manifestId, "duplicate", expiresAt, compressedSha256, chunkCount)
    }
    val chunks = db.chunks().forTransfer(transferId)
    return if (chunks.size == chunkCount) assemble(manifest, chunks.sortedBy { it.index }) else null
  }

  private suspend fun handleChunk(payload: JsonObject): Intake? {
    val required = setOf(
      "protocol", "type", "transferId", "manifestId", "documentId", "compressedSha256",
      "index", "count", "dataBase64", "expiresAt",
    )
    exactKeys(payload, required)
    require(payload["protocol"]?.jsonPrimitive?.content == ReaderCore.READER_PROTOCOL && payload["type"]?.jsonPrimitive?.content == "chunk") {
      "wrong chunk protocol or type"
    }
    val transferId = requiredString(payload, "transferId", 32).also { require(hex16.matches(it)) { "invalid transferId" } }
    val manifestId = requiredString(payload, "manifestId", 64).also { require(hex32.matches(it)) { "invalid manifestId" } }
    val documentId = requiredString(payload, "documentId", 64).also { require(hex32.matches(it)) { "invalid documentId" } }
    val compressedSha256 = requiredString(payload, "compressedSha256", 64).also { require(hex32.matches(it)) { "invalid compressedSha256" } }
    val index = payload["index"]!!.jsonPrimitive.int
    val count = payload["count"]!!.jsonPrimitive.int
    val expiresAtSecs = payload["expiresAt"]!!.jsonPrimitive.long
    val nowSecs = System.currentTimeMillis() / 1000
    require(count in 1..ReaderCore.MAX_CHUNKS && index in 0 until count) { "invalid chunk position" }
    require(expiresAtSecs > nowSecs && expiresAtSecs <= nowSecs + 7L * 86400 + 60) { "chunk expired or overlong" }
    val data = requiredString(payload, "dataBase64", 200000)
    val decoded = runCatching { Base64.getDecoder().decode(data) }
      .getOrElse { throw IllegalArgumentException("invalid chunk base64") }
    require(decoded.isNotEmpty() && decoded.size <= 64 * 1024) { "invalid chunk size" }
    val chunk = ChunkEntity(
      transferId = transferId,
      index = index,
      manifestId = manifestId,
      documentId = documentId,
      compressedSha256 = compressedSha256,
      count = count,
      bytesB64 = data,
      receivedAt = System.currentTimeMillis(),
      expiresAt = expiresAtSecs * 1000,
    )
    db.chunks().byIndex(transferId, index)?.let { existing ->
      require(existing == chunk.copy(receivedAt = existing.receivedAt)) { "conflicting duplicate chunk" }
      return null
    }
    db.chunks().first(transferId)?.let { first ->
      require(
        first.manifestId == manifestId && first.documentId == documentId &&
          first.compressedSha256 == compressedSha256 && first.count == count && first.expiresAt == chunk.expiresAt,
      ) { "conflicting chunk set" }
    }
    requireStagingCapacity(transferId, decoded.size)
    db.chunks().insert(chunk)
    val manifest = db.manifests().byTransfer(transferId) ?: return null
    return if (db.chunks().count(transferId) == manifest.chunkCount) assemble(manifest, db.chunks().forTransfer(transferId)) else null
  }

  private suspend fun assemble(manifest: ManifestEntity, chunks: List<ChunkEntity>): Intake {
    require(chunks.size == manifest.chunkCount) { "incomplete chunk set" }
    require(chunks.map { it.index } == (0 until manifest.chunkCount).toList()) { "chunk index gap" }
    require(chunks.all {
      it.transferId == manifest.transferId && it.manifestId == manifest.manifestId &&
        it.documentId == manifest.documentId && it.compressedSha256 == manifest.compressedSha256 &&
        it.count == manifest.chunkCount && it.expiresAt == manifest.expiresAt * 1000
    }) { "chunk manifest binding mismatch" }
    if (db.documents().exists(manifest.documentId)) {
      db.withTransaction {
        db.chunks().clearTransfer(manifest.transferId)
        db.manifests().clearTransfer(manifest.transferId)
      }
      return Intake(manifest.transferId, manifest.documentId, manifest.manifestId, "duplicate", manifest.expiresAt,
        manifest.compressedSha256, manifest.chunkCount)
    }
    val compressed = ByteArrayOutputStream()
    var compressedTotal = 0
    for (chunk in chunks) {
      val bytes = Base64.getDecoder().decode(chunk.bytesB64)
      compressedTotal += bytes.size
      require(compressedTotal <= ReaderCore.MAX_COMPRESSED_BYTES) { "compressed overflow" }
      compressed.write(bytes)
    }
    val gzipBytes = compressed.toByteArray()
    require(gzipBytes.size == manifest.compressedBytes) { "compressed size mismatch" }
    require(ReaderCore.sha256Hex(gzipBytes) == manifest.compressedSha256) { "compressed hash mismatch" }
    val canonicalBytes = ReaderGzip.decode(gzipBytes)
    require(canonicalBytes.size == manifest.uncompressedBytes) { "expanded size mismatch" }
    val canonical = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(canonicalBytes))
      .toString()
    require(ReaderCore.canonicalize(canonical) == canonical) { "content is not canonical" }
    require(ReaderCore.documentId(canonical) == manifest.documentId) { "document hash mismatch" }
    require(ReaderCore.wordCount(canonical) == manifest.wordCount) { "word count mismatch" }
    val nowMillis = System.currentTimeMillis()
    val document = DocumentEntity(
      documentId = manifest.documentId,
      title = manifest.title,
      sourceType = manifest.sourceType,
      sourceName = manifest.sourceName,
      sourceUrl = manifest.sourceUrl,
      author = manifest.author,
      publishedAt = manifest.publishedAt,
      capturedAt = manifest.capturedAt * 1000,
      language = manifest.language,
      canonicalMarkdown = canonical,
      wordCount = manifest.wordCount,
      parserVersion = 2,
      state = "unread",
      progressBlockId = null,
      progressCharOffset = 0,
      progressFraction = 0f,
      lastOpenedAt = 0L,
      createdAt = nowMillis,
      updatedAt = nowMillis,
    )
    db.withTransaction {
      val inserted = db.documents().insert(document)
      require(inserted != -1L || db.documents().exists(manifest.documentId)) { "document commit failed" }
      db.chunks().clearTransfer(manifest.transferId)
      db.manifests().clearTransfer(manifest.transferId)
    }
    return Intake(manifest.transferId, manifest.documentId, manifest.manifestId, "stored", manifest.expiresAt,
      manifest.compressedSha256, manifest.chunkCount)
  }

  /** Encrypted endpoint-bound ACK, emitted only after durable document presence. */
  fun buildAck(intake: Intake, senderDevicePubkey: String, channelSeckey: ByteArray): NostrEvent {
    val now = System.currentTimeMillis() / 1000
    return buildAck(
      AckIntentEntity(
        channelId = "direct",
        transferId = intake.transferId,
        manifestId = intake.manifestId,
        documentId = intake.documentId,
        recipientDevicePubkey = senderDevicePubkey,
        status = intake.status,
        receivedAt = now,
        expiresAt = minOf(intake.expiresAt, now + ReaderCore.TRANSPORT_TTL_DAYS * 86400L),
        acceptedRelaysJson = "[]",
        attemptCount = 0,
        nextAttemptAt = null,
        completedAt = null,
        failedAt = null,
        lastErrorCode = null,
      ),
      channelSeckey,
    )
  }

  /** Build a fresh NIP-59 wrapper around one stable durable ACK intent. */
  fun buildAck(intent: AckIntentEntity, channelSeckey: ByteArray): NostrEvent {
    val senderDevicePubkey = intent.recipientDevicePubkey
    require(hex32.matches(senderDevicePubkey)) { "invalid ACK recipient" }
    require(intent.status == "stored" || intent.status == "duplicate") { "invalid ACK status" }
    val channelPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(channelSeckey))
    val now = System.currentTimeMillis() / 1000
    require(intent.receivedAt <= now + 60 && intent.expiresAt > now) { "ACK intent expired or in future" }
    val payload = buildJsonObject {
      put("protocol", ReaderCore.READER_PROTOCOL)
      put("type", "ack")
      put("transferId", intent.transferId)
      put("documentId", intent.documentId)
      put("manifestId", intent.manifestId)
      put("contentHash", intent.documentId)
      put("senderChannelPubkey", channelPubkey)
      put("recipientDevicePubkey", senderDevicePubkey)
      put("status", intent.status)
      put("receivedAt", intent.receivedAt)
      put("expiresAt", intent.expiresAt)
    }.toString()
    return NostrCodec.sealAndWrap(channelSeckey, senderDevicePubkey, payload).second
  }
}
