package com.reader.app.sync

import android.util.Base64
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import com.reader.app.data.ChunkEntity
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.NostrEvent
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.SEAL_KIND
import com.reader.app.nostr.WRAP_KIND
import com.reader.app.nostr.WRAP_KIND_EPHEMERAL
import com.reader.app.security.KeystoreWrap
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Incoming transfer assembly: auth first, bounded inflate, verify, commit, ACK. */
class TransferManager(
  private val db: ReaderDb,
  private val keys: KeystoreWrap,
  private val relays: RelayClient = RelayClient(),
) {
  data class Intake(val transferId: String, val documentId: String, val status: String)

  suspend fun ingestWrap(wrap: NostrEvent, channelId: String, trustedSender: String, receiverSeckey: ByteArray): Intake? {
    val payloadJson = try {
      NostrCodec.unwrapAndVerify(wrap, receiverSeckey, trustedSender)
    } catch (e: Exception) {
      return null // hostile or unrelated: bounded ignore
    }
    val p = Json.parseToJsonElement(payloadJson).jsonObject
    return when (p["type"]?.jsonPrimitive?.content) {
      "manifest" -> {
        handleManifest(p)
        null
      }
      "chunk" -> handleChunk(p)
      else -> null
    }
  }

  private suspend fun handleManifest(p: JsonObject) {
    // Manifest is informational in v1; chunk assembly is authoritative.
    // Hard validation still applies so oversized transfers fail fast.
    val chunks = p["chunkCount"]?.jsonPrimitive?.int ?: return
    val comp = p["compressedBytes"]?.jsonPrimitive?.long ?: return
    ReaderCore.checkLimits(comp.toInt(), p["title"]?.jsonPrimitive?.content?.length ?: 0, 0, chunks)
  }

  private suspend fun handleChunk(p: JsonObject): Intake? {
    val transferId = p["transferId"]!!.jsonPrimitive.content
    val documentId = p["documentId"]!!.jsonPrimitive.content
    val index = p["index"]!!.jsonPrimitive.int
    val count = p["count"]!!.jsonPrimitive.int
    val data = p["dataBase64"]!!.jsonPrimitive.content
    require(count in 1..ReaderCore.MAX_CHUNKS) { "bad count" }
    require(index in 0 until count) { "bad index" }
    require(data.length <= 200000) { "chunk too large" }
    val existing = db.chunks().forTransfer(transferId)
    if (existing.isNotEmpty()) {
      val e0 = existing.first()
      require(e0.documentId.equals(documentId, ignoreCase = true)) { "conflicting documentId" }
      require(e0.count == count) { "conflicting count" }
      if (existing.any { it.index == index }) return null // duplicate: idempotent
    }
    db.chunks().insert(
      ChunkEntity(transferId, index, documentId, count, data, System.currentTimeMillis(), System.currentTimeMillis() + 7 * 86400_000L),
    )
    val all = db.chunks().forTransfer(transferId)
    if (all.size < count) return null // partial: wait, no ACK yet
    return assemble(transferId, documentId, all.sortedBy { it.index }, count)
  }

  private suspend fun assemble(transferId: String, documentId: String, chunks: List<ChunkEntity>, count: Int): Intake {
    if (db.documents().byId(documentId) != null) {
      db.chunks().clearTransfer(transferId)
      return Intake(transferId, documentId, "duplicate")
    }
    val out = ByteArrayOutputStream()
    var total = 0
    for (c in chunks) {
      val b = Base64.decode(c.bytesB64, Base64.DEFAULT)
      total += b.size
      require(total <= ReaderCore.MAX_COMPRESSED_BYTES) { "compressed overflow" }
      out.write(b)
    }
    val gz = out.toByteArray()
    // Bounded streaming inflate: abort past 20 MiB.
    val inflated = ByteArrayOutputStream()
    GZIPInputStream(gz.inputStream()).use { gin ->
      val buf = ByteArray(32768)
      var n = 0
      while (true) {
        val r = gin.read(buf)
        if (r < 0) break
        n += r
        require(n <= ReaderCore.MAX_EXPANDED_BYTES) { "expansion bomb" }
        inflated.write(buf, 0, r)
      }
    }
    val canonical = inflated.toByteArray().toString(Charsets.UTF_8)
    require(ReaderCore.documentId(canonical).equals(documentId, ignoreCase = true)) { "hash mismatch" }
    val blocks = ArticleParser.parse(canonical)
    val now = System.currentTimeMillis()
    val title = canonical.lines().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: "Untitled"
    db.documents().insert(
      DocumentEntity(
        documentId = documentId, title = title.take(500), sourceType = "nostr",
        sourceName = null, sourceUrl = null, author = null, publishedAt = null,
        capturedAt = now, language = null, canonicalMarkdown = canonical,
        wordCount = ReaderCore.wordCount(canonical), parserVersion = 1,
        state = "unread", progressBlockId = blocks.firstOrNull()?.id,
        progressCharOffset = 0, progressFraction = 0f,
        lastOpenedAt = 0L, createdAt = now, updatedAt = now,
      ),
    )
    db.chunks().clearTransfer(transferId)
    return Intake(transferId, documentId, "stored")
  }

  /** Encrypted ACK back to the sender device pubkey. Sent only after commit. */
  fun buildAck(intake: Intake, senderDevicePubkey: String, channelSeckey: ByteArray): NostrEvent {
    val now = System.currentTimeMillis() / 1000
    val payload = buildJsonObject {
      put("protocol", ReaderCore.READER_PROTOCOL); put("type", "ack")
      put("transferId", intake.transferId); put("documentId", intake.documentId)
      put("status", intake.status); put("receivedAt", now)
    }.toString()
    return NostrCodec.sealAndWrap(channelSeckey, senderDevicePubkey, payload).second
  }
}
