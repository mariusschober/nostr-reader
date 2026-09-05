package com.reader.app.sync

import androidx.room.withTransaction
import com.reader.app.core.ArticleParser
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

/** Incoming transfer assembly: authenticate, bind manifest/chunks, verify, commit, ACK. */
class TransferManager(
  private val db: ReaderDb,
) {
  data class Intake(
    val transferId: String,
    val documentId: String,
    val manifestId: String,
    val status: String,
    val expiresAt: Long,
  )

  private val hex16 = Regex("^[0-9a-f]{32}$")
  private val hex32 = Regex("^[0-9a-f]{64}$")
  private val sourceTypes = setOf(
    "web", "chatgpt", "claude", "gemini", "perplexity", "selection",
    "android-share", "android-process-text", "file", "mac-share", "mac-quick-action",
  )

  suspend fun ingestWrap(
    wrap: NostrEvent,
    channelId: String,
    trustedSender: String,
    receiverSeckey: ByteArray,
  ): Intake? {
    require(channelId.isNotBlank()) { "invalid channel" }
    val envelope = try {
      NostrCodec.unwrapAndVerifyEnvelope(wrap, receiverSeckey, trustedSender)
    } catch (_: Exception) {
      return null
    }
    val payload = StrictJson.parseObject(envelope.payloadJson)
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverSeckey))
    return when (payload["type"]?.jsonPrimitive?.content) {
      "manifest" -> handleManifest(payload, envelope.senderPubkey, receiverPubkey)
      "chunk" -> handleChunk(payload)
      else -> null
    }
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
  ): Intake? {
    require(channelId.isNotBlank()) { "invalid channel" }
    val envelope = try {
      NostrCodec.unwrapAndVerifyEnvelope(wrap, receiverSeckey, trustedSender)
    } catch (_: Exception) {
      return null
    }
    val payload = StrictJson.parseObject(envelope.payloadJson)
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverSeckey))
    val wrapExpiresAt = wrap.tags.single { it.firstOrNull() == "expiration" }[1].toLong()
    val nowSecs = System.currentTimeMillis() / 1000
    val ledgerExpiresAt = minOf(wrapExpiresAt, nowSecs + ReaderCore.SYNC_WINDOW_DAYS * 86400L)
    return db.withTransaction {
      if (db.processedEvents().byId(wrap.id) != null) return@withTransaction null
      val intake = when (payload["type"]?.jsonPrimitive?.content) {
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
      if (inserted != -1L && intake != null) queueAckIntent(channelId, trustedSender, intake, nowSecs)
      intake
    }
  }

  private suspend fun queueAckIntent(
    channelId: String,
    recipientDevicePubkey: String,
    intake: Intake,
    nowSecs: Long,
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
          receivedAt = nowSecs,
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
    // One immutable transfer owns one bounded ACK lifecycle. A Chrome retry or
    // a delayed wrapper from another relay must not reset a completed quorum or
    // an exhausted retry ceiling; the accepted ACK remains relay-retained until
    // the transfer itself expires.
    dao.update(
      existing.copy(
        status = preferredStatus,
        expiresAt = maxOf(existing.expiresAt, intake.expiresAt),
      ),
    )
  }

  private fun exactKeys(payload: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
    require(payload.keys.containsAll(required)) { "missing message field" }
    require(payload.keys.all { it in required || it in optional }) { "unknown message field" }
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
    val existing = db.manifests().byTransfer(transferId)
    if (existing != null) {
      require(existing.copy(receivedAt = manifest.receivedAt) == manifest) { "conflicting manifest" }
    } else {
      require(db.manifests().insert(manifest) != -1L) { "manifest insert conflict" }
    }
    if (db.documents().byId(documentId) != null) {
      db.withTransaction {
        db.chunks().clearTransfer(transferId)
        db.manifests().clearTransfer(transferId)
      }
      return Intake(transferId, documentId, manifestId, "duplicate", expiresAt)
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
    val existingChunks = db.chunks().forTransfer(transferId)
    existingChunks.firstOrNull { it.index == index }?.let { existing ->
      require(existing == chunk.copy(receivedAt = existing.receivedAt)) { "conflicting duplicate chunk" }
      return null
    }
    existingChunks.firstOrNull()?.let { first ->
      require(
        first.manifestId == manifestId && first.documentId == documentId &&
          first.compressedSha256 == compressedSha256 && first.count == count,
      ) { "conflicting chunk set" }
    }
    db.chunks().insert(chunk)
    val all = db.chunks().forTransfer(transferId)
    val manifest = db.manifests().byTransfer(transferId) ?: return null
    return if (all.size == manifest.chunkCount) assemble(manifest, all.sortedBy { it.index }) else null
  }

  private suspend fun assemble(manifest: ManifestEntity, chunks: List<ChunkEntity>): Intake {
    require(chunks.size == manifest.chunkCount) { "incomplete chunk set" }
    require(chunks.map { it.index } == (0 until manifest.chunkCount).toList()) { "chunk index gap" }
    require(chunks.all {
      it.transferId == manifest.transferId && it.manifestId == manifest.manifestId &&
        it.documentId == manifest.documentId && it.compressedSha256 == manifest.compressedSha256 &&
        it.count == manifest.chunkCount
    }) { "chunk manifest binding mismatch" }
    if (db.documents().byId(manifest.documentId) != null) {
      db.withTransaction {
        db.chunks().clearTransfer(manifest.transferId)
        db.manifests().clearTransfer(manifest.transferId)
      }
      return Intake(manifest.transferId, manifest.documentId, manifest.manifestId, "duplicate", manifest.expiresAt)
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
    val blocks = ArticleParser.parse(canonical)
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
      progressBlockId = blocks.firstOrNull()?.id,
      progressCharOffset = 0,
      progressFraction = 0f,
      lastOpenedAt = 0L,
      createdAt = nowMillis,
      updatedAt = nowMillis,
    )
    db.withTransaction {
      val inserted = db.documents().insert(document)
      require(inserted != -1L || db.documents().byId(manifest.documentId) != null) { "document commit failed" }
      db.chunks().clearTransfer(manifest.transferId)
      db.manifests().clearTransfer(manifest.transferId)
    }
    return Intake(manifest.transferId, manifest.documentId, manifest.manifestId, "stored", manifest.expiresAt)
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
