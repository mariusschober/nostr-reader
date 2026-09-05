package com.reader.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.ReaderCore
import com.reader.app.data.ReaderDb
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.Secp256k1
import com.reader.app.nostr.StrictJson
import com.reader.app.sync.TransferManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class TransferManagerInstrumentedTest {
  private lateinit var db: ReaderDb
  private lateinit var manager: TransferManager

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, ReaderDb::class.java).build()
    manager = TransferManager(db)
  }

  @After
  fun tearDown() {
    db.close()
  }

  private data class Fixture(
    val manifest: JsonObject,
    val chunks: List<JsonObject>,
    val documentId: String,
    val manifestId: String,
  )

  private fun gzip(input: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    GZIPOutputStream(output).use { it.write(input) }
  }.toByteArray().also { it[9] = 3 }

  private fun fixture(senderKey: ByteArray, receiverKey: ByteArray, salt: String = "01"): Fixture {
    val canonical = ReaderCore.canonicalize("# Transfer $salt\n\nOut-of-order chunks remain authenticated.\n")
    val plain = canonical.toByteArray(Charsets.UTF_8)
    val compressed = gzip(plain)
    val documentId = ReaderCore.documentId(canonical)
    val compressedHash = ReaderCore.sha256Hex(compressed)
    val transferId = salt.padStart(32, '0').takeLast(32)
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(senderKey))
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverKey))
    val now = System.currentTimeMillis() / 1000
    val expiresAt = now + 3600
    val sliceSize = maxOf(1, compressed.size / 2)
    val slices = compressed.toList().chunked(sliceSize).map { it.toByteArray() }
    val manifestId = ReaderCore.sha256Hex(buildJsonArray {
      add(ReaderCore.READER_PROTOCOL)
      add(transferId)
      add(documentId)
      add(compressedHash)
      add(compressed.size)
      add(slices.size)
      add(senderPubkey)
      add(receiverPubkey)
      add(expiresAt)
    }.toString().toByteArray(Charsets.UTF_8))
    val manifest = buildJsonObject {
      put("protocol", ReaderCore.READER_PROTOCOL)
      put("type", "manifest")
      put("transferId", transferId)
      put("manifestId", manifestId)
      put("documentId", documentId)
      put("title", "Transfer $salt")
      put("sourceType", "web")
      put("sourceUrl", "https://example.com/$salt")
      put("capturedAt", now)
      put("mime", "text/markdown")
      put("compression", "gzip")
      put("wordCount", ReaderCore.wordCount(canonical))
      put("uncompressedBytes", plain.size)
      put("compressedBytes", compressed.size)
      put("compressedSha256", compressedHash)
      put("documentSha256", documentId)
      put("chunkCount", slices.size)
      put("senderDevicePubkey", senderPubkey)
      put("recipientChannelPubkey", receiverPubkey)
      put("expiresAt", expiresAt)
    }
    val chunks = slices.mapIndexed { index, bytes ->
      buildJsonObject {
        put("protocol", ReaderCore.READER_PROTOCOL)
        put("type", "chunk")
        put("transferId", transferId)
        put("manifestId", manifestId)
        put("documentId", documentId)
        put("compressedSha256", compressedHash)
        put("index", index)
        put("count", slices.size)
        put("dataBase64", Base64.getEncoder().encodeToString(bytes))
        put("expiresAt", expiresAt)
      }
    }
    return Fixture(manifest, chunks, documentId, manifestId)
  }

  private fun wrap(payload: JsonObject, senderKey: ByteArray, receiverKey: ByteArray) =
    NostrCodec.sealAndWrap(
      senderKey,
      Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverKey)),
      payload.toString(),
    ).second

  @Test
  fun reorderedAndDuplicateChunksCommitAtomicallyThenProduceBoundAck() = runBlocking {
    val senderKey = Secp256k1.randomPrivateKey()
    val receiverKey = Secp256k1.randomPrivateKey()
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(senderKey))
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverKey))
    val f = fixture(senderKey, receiverKey)

    val chunkWraps = f.chunks.reversed().map { wrap(it, senderKey, receiverKey) }
    for (chunkWrap in chunkWraps) {
      assertNull(manager.ingestForSync(chunkWrap, "channel", senderPubkey, receiverKey))
      assertNotNull(db.processedEvents().byId(chunkWrap.id))
    }
    // An exact retained-wrapper replay is skipped and does not create another chunk.
    assertNull(manager.ingestForSync(chunkWraps.first(), "channel", senderPubkey, receiverKey))
    val manifestWrap = wrap(f.manifest, senderKey, receiverKey)
    val intake = manager.ingestForSync(manifestWrap, "channel", senderPubkey, receiverKey)!!
    assertEquals("stored", intake.status)
    assertEquals(f.documentId, db.documents().byId(f.documentId)!!.documentId)
    assertTrue(db.chunks().forTransfer(intake.transferId).isEmpty())
    assertNull(db.manifests().byTransfer(intake.transferId))

    val queuedAck = db.ackIntents().byTransfer("channel", intake.transferId)!!
    val ackWrap = manager.buildAck(queuedAck, receiverKey)
    val ackEnvelope = NostrCodec.unwrapAndVerifyEnvelope(ackWrap, senderKey, receiverPubkey)
    val ack = StrictJson.parseObject(ackEnvelope.payloadJson)
    assertEquals("ack", ack["type"]!!.jsonPrimitive.content)
    assertEquals(f.manifestId, ack["manifestId"]!!.jsonPrimitive.content)
    assertEquals(f.documentId, ack["contentHash"]!!.jsonPrimitive.content)
    assertEquals(receiverPubkey, ack["senderChannelPubkey"]!!.jsonPrimitive.content)
    assertEquals(senderPubkey, ack["recipientDevicePubkey"]!!.jsonPrimitive.content)

    db.ackIntents().update(
      queuedAck.copy(
        acceptedRelaysJson = "[\"wss://one.example\",\"wss://two.example\"]",
        completedAt = System.currentTimeMillis(),
      ),
    )
    assertNull(manager.ingestForSync(manifestWrap, "channel", senderPubkey, receiverKey))
    assertNotNull(db.ackIntents().byTransfer("channel", intake.transferId)!!.completedAt)

    // A delayed cross-relay copy or fresh Chrome retry has a new outer wrapper
    // ID but belongs to the same immutable transfer. It must not reset a
    // completed ACK quorum and cause another batch.
    val duplicate = manager.ingestForSync(
      wrap(f.manifest, senderKey, receiverKey),
      "channel",
      senderPubkey,
      receiverKey,
    )!!
    assertEquals("duplicate", duplicate.status)
    val stillCompleted = db.ackIntents().byTransfer("channel", intake.transferId)!!
    assertNotNull(stillCompleted.completedAt)
    assertEquals("[\"wss://one.example\",\"wss://two.example\"]", stillCompleted.acceptedRelaysJson)
    assertEquals("stored", stillCompleted.status)

    // Once accepted receipts have been lost, a new demand after cooldown
    // reopens exactly one batch. Retained authenticated wrapper IDs do not.
    db.ackIntents().update(stillCompleted.copy(completedAt = System.currentTimeMillis() - 301_000))
    assertNull(manager.ingestForSync(manifestWrap, "channel", senderPubkey, receiverKey))
    assertNotNull(db.ackIntents().byTransfer("channel", intake.transferId)!!.completedAt)
    val demand = wrap(f.manifest, senderKey, receiverKey)
    manager.ingestForSync(demand, "channel", senderPubkey, receiverKey)
    val reopened = db.ackIntents().byTransfer("channel", intake.transferId)!!
    assertNull(reopened.completedAt)
    assertEquals(1, reopened.refreshCount)
    assertEquals("[]", reopened.acceptedRelaysJson)
    assertNotNull(db.documents().byId(f.documentId))
    assertNull(manager.ingestForSync(demand, "channel", senderPubkey, receiverKey))
    assertEquals(reopened, db.ackIntents().byTransfer("channel", intake.transferId))

    // An exhausted ACK lifecycle is terminal too; a retry cannot turn an
    // intentionally bounded sender into unbounded relay traffic.
    db.ackIntents().update(
      stillCompleted.copy(completedAt = null, failedAt = System.currentTimeMillis(), attemptCount = 168),
    )
    assertEquals(
      "duplicate",
      manager.ingestForSync(
        wrap(f.manifest, senderKey, receiverKey),
        "channel",
        senderPubkey,
        receiverKey,
      )!!.status,
    )
    val stillFailed = db.ackIntents().byTransfer("channel", intake.transferId)!!
    assertNotNull(stillFailed.failedAt)
    assertEquals(168, stillFailed.attemptCount)
  }

  @Test
  fun wrongSenderAndConflictingDuplicateCannotMutateTransfer() = runBlocking {
    val senderKey = Secp256k1.randomPrivateKey()
    val attackerKey = Secp256k1.randomPrivateKey()
    val receiverKey = Secp256k1.randomPrivateKey()
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(senderKey))
    val f = fixture(senderKey, receiverKey, "02")

    assertNull(manager.ingestWrap(wrap(f.manifest, attackerKey, receiverKey), "channel", senderPubkey, receiverKey))
    assertNull(db.manifests().byTransfer(f.manifest["transferId"]!!.jsonPrimitive.content))

    assertNull(manager.ingestWrap(wrap(f.chunks.first(), senderKey, receiverKey), "channel", senderPubkey, receiverKey))
    val conflict = JsonObject(f.chunks.first().toMutableMap().also {
      it["dataBase64"] = JsonPrimitive(Base64.getEncoder().encodeToString("conflict".toByteArray()))
    })
    try {
      manager.ingestWrap(wrap(conflict, senderKey, receiverKey), "channel", senderPubkey, receiverKey)
      fail("expected conflicting duplicate rejection")
    } catch (_: IllegalArgumentException) {
    }
    assertEquals(1, db.chunks().forTransfer(f.manifest["transferId"]!!.jsonPrimitive.content).size)
    assertNull(db.documents().byId(f.documentId))
  }
}
