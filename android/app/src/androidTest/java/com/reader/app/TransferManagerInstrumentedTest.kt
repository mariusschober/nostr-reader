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
import com.reader.app.sync.StagingLimits
import com.reader.app.sync.StagingCapacityException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
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

  private fun fixture(senderKey: ByteArray, receiverKey: ByteArray, salt: String = "01", textSalt: String = salt, text: String? = null): Fixture {
    val canonical = ReaderCore.canonicalize(text ?: "# Transfer $textSalt\n\nOut-of-order chunks remain authenticated.\n")
    val plain = canonical.toByteArray(Charsets.UTF_8)
    val compressed = gzip(plain)
    val documentId = ReaderCore.documentId(canonical)
    val compressedHash = ReaderCore.sha256Hex(compressed)
    val transferId = salt.padStart(32, '0').takeLast(32)
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(senderKey))
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiverKey))
    val now = System.currentTimeMillis() / 1000
    val expiresAt = now + 3600
    val sliceSize = minOf(24 * 1024, maxOf(1, compressed.size / 2))
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

  @Test fun measuresSmallMediumAndNearExpandedLimitIngestionSeparatelyFromPreparation() = runBlocking {
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val rows = buildJsonArray {
      for ((index, size) in listOf(4096, 256 * 1024, 19 * 1024 * 1024).withIndex()) {
        val paragraph = "Synthetic bounded measurement paragraph with repeated words.\n\n"
        val text = paragraph.repeat(size / paragraph.length + 1).take(size)
        val f = fixture(sender, receiver, (71 + index).toString(), text = text)
        val events = (f.chunks + f.manifest).map { wrap(it, sender, receiver) }
        val before = System.nanoTime()
        for (event in events) manager.ingestForSync(event, "measurement", pubkey, receiver)
        val stored = System.nanoTime()
        val ready = com.reader.app.data.ArticleRepository(db).section(f.documentId, 0)
        val prepared = System.nanoTime()
        assertTrue(db.documents().exists(f.documentId))
        assertTrue(ready.projection.text.isNotEmpty())
        add(buildJsonObject {
          put("expandedBytes", f.manifest["uncompressedBytes"]!!)
          put("compressedBytes", f.manifest["compressedBytes"]!!)
          put("ingestionMillis", (stored - before) / 1_000_000.0)
          put("firstSectionPreparationMillis", (prepared - stored) / 1_000_000.0)
          put("renderingMeasured", false)
        })
      }
    }
    val context = ApplicationProvider.getApplicationContext<Context>()
    java.io.File(context.getExternalFilesDir("qa"), "ingestion-measurements.json").writeText(rows.toString())
  }

  @Test fun revokingPausedIntakeRollsBackAllNewOldLeaseEffects() = runBlocking {
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val receiverPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiver))
    val relays = listOf("wss://nos.lol", "wss://relay.primal.net")
    val channel = com.reader.app.data.ChannelEntity("revocable", receiverPubkey, pubkey, 1, null,
      Json.encodeToString(relays), state = "active", relaySetDigest = com.reader.app.nostr.PairingProtocol.relaySetDigest(relays))
    db.channels().upsert(channel)
    val f = fixture(sender, receiver, "61")
    for (payload in f.chunks) manager.ingestForSync(wrap(payload, sender, receiver), channel.channelId, pubkey, receiver)
    val manifest = wrap(f.manifest, sender, receiver)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val intake = launch(Dispatchers.IO) {
      val lease = com.reader.app.sync.ChannelLease.acquire(channel, coroutineContext[Job]!!)
      try {
        manager.ingestForSync(manifest, channel.channelId, pubkey, receiver) {
          lease.check(db)
          entered.complete(Unit)
          release.await()
          lease.check(db)
        }
      } finally { lease.close() }
    }
    withTimeout(5000) { entered.await() }
    db.channels().revoke(channel.channelId, System.currentTimeMillis())
    release.complete(Unit)
    intake.join()
    assertTrue(intake.isCancelled)
    assertFalse(db.documents().exists(f.documentId))
    assertNull(db.processedEvents().byId(manifest.id))
    assertNull(db.ackIntents().byTransfer(channel.channelId, f.manifest["transferId"]!!.jsonPrimitive.content))
  }

  @Test fun revokedAckCannotMergeALateSuccessfulPublish() = runBlocking {
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val relays = listOf("wss://nos.lol", "wss://relay.primal.net")
    val channel = com.reader.app.data.ChannelEntity("revocable-ack", Secp256k1.bytesToHex(Secp256k1.getPublicKey(receiver)), pubkey, 1, null,
      Json.encodeToString(relays), state = "active", relaySetDigest = com.reader.app.nostr.PairingProtocol.relaySetDigest(relays))
    db.channels().upsert(channel)
    val f = fixture(sender, receiver, "71")
    for (payload in f.chunks + f.manifest) manager.ingestForSync(wrap(payload, sender, receiver), channel.channelId, pubkey, receiver)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val dispatcher = com.reader.app.sync.AckDispatcher(db, manager, com.reader.app.nostr.RelayClient()) { url, event, _ ->
      withContext(NonCancellable) {
        entered.complete(Unit); release.await()
        com.reader.app.nostr.RelayClient.PublishResult(url, event.id, true, com.reader.app.nostr.RelayClient.PublishState.OK_TRUE, null, emptyList())
      }
    }
    val job = launch(Dispatchers.IO) {
      val lease = com.reader.app.sync.ChannelLease.acquire(channel, coroutineContext[Job]!!)
      try { dispatcher.dispatch(channel, receiver, relays, lease) } finally { lease.close() }
    }
    withTimeout(5000) { entered.await() }
    val transferId = f.manifest["transferId"]!!.jsonPrimitive.content
    val reserved = db.ackIntents().byTransfer(channel.channelId, transferId)
    db.channels().revoke(channel.channelId, System.currentTimeMillis())
    release.complete(Unit); job.join()
    assertTrue(job.isCancelled)
    assertEquals(reserved, db.ackIntents().byTransfer(channel.channelId, transferId))
    assertTrue(db.documents().exists(f.documentId))
  }

  @Test fun deletedTransferCannotResurrectButExplicitNewCaptureCan() = runBlocking {
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val f = fixture(sender, receiver, "51")
    val wrappers = (f.chunks + f.manifest).map { wrap(it, sender, receiver) }
    for (event in wrappers) manager.ingestForSync(event, "channel", pubkey, receiver)
    val article = db.documents().byId(f.documentId)!!
    db.documents().update(article.copy(list = "archived"))
    com.reader.app.data.ArticleRepository(db).delete(f.documentId)
    assertNotNull(db.transferOutcomes().byTransfer("channel", f.manifest["transferId"]!!.jsonPrimitive.content)!!.deletedAt)
    for (event in wrappers) manager.ingestForSync(event, "channel", pubkey, receiver)
    for (payload in f.chunks + f.manifest) manager.ingestForSync(wrap(payload, sender, receiver), "channel", pubkey, receiver)
    assertNull(db.documents().byId(f.documentId))
    assertEquals(0L, db.chunks().stagedBytes())
    val next = fixture(sender, receiver, "52", "51")
    assertEquals(f.documentId, next.documentId)
    for (payload in next.chunks + next.manifest) manager.ingestForSync(wrap(payload, sender, receiver), "channel", pubkey, receiver)
    assertNotNull(db.documents().byId(f.documentId))
  }

  @Test fun conflictingCompletedTransferPayloadIsRejectedNeverAcked() = runBlocking {
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val f = fixture(sender, receiver, "61")
    for (payload in f.chunks + f.manifest) manager.ingestForSync(wrap(payload, sender, receiver), "channel", pubkey, receiver)
    val transferId = f.manifest["transferId"]!!.jsonPrimitive.content
    val outcome = db.transferOutcomes().byTransfer("channel", transferId)!!
    assertTrue(outcome.compressedSha256.isNotEmpty())
    assertTrue(outcome.chunkCount > 0)
    // Same IDs but different byte identity must be rejected, not ACKed.
    val badManifest = JsonObject(f.manifest.toMutableMap().also {
      it["compressedSha256"] = JsonPrimitive("00".repeat(32))
    })
    try {
      manager.ingestForSync(wrap(badManifest, sender, receiver), "channel", pubkey, receiver)
      fail("conflicting manifest must be rejected")
    } catch (_: IllegalArgumentException) { }
    val badChunk = JsonObject(f.chunks.first().toMutableMap().also {
      it["compressedSha256"] = JsonPrimitive("ff".repeat(32))
    })
    try {
      manager.ingestForSync(wrap(badChunk, sender, receiver), "channel", pubkey, receiver)
      fail("conflicting chunk must be rejected")
    } catch (_: IllegalArgumentException) { }
    // No new ACK intent, no staging, no document mutation from conflicts.
    assertEquals(1, db.ackIntents().let { dao ->
      var count = 0
      runBlocking { count = if (dao.byTransfer("channel", transferId) != null) 1 else 0 }
      count
    })
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

  @Test fun incompleteTransferBudgetRejectsAtomicallyAndExistingTransferCanStillFinish() = runBlocking {
    manager = TransferManager(db, StagingLimits(transfers = 1))
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val first = fixture(sender, receiver, "31")
    val second = fixture(sender, receiver, "32")
    manager.ingestForSync(wrap(first.chunks.first(), sender, receiver), "channel", pubkey, receiver)
    val rejected = wrap(second.chunks.first(), sender, receiver)
    try { manager.ingestForSync(rejected, "channel", pubkey, receiver); fail("capacity must reject") }
    catch (_: StagingCapacityException) {}
    assertNull(db.processedEvents().byId(rejected.id))
    assertEquals(1, db.chunks().stagedTransferCount())
    for (chunk in first.chunks.drop(1)) manager.ingestForSync(wrap(chunk, sender, receiver), "channel", pubkey, receiver)
    val stored = manager.ingestForSync(wrap(first.manifest, sender, receiver), "channel", pubkey, receiver)!!
    assertEquals("stored", stored.status)
    assertEquals(0L, db.chunks().stagedBytes())
    // Capacity released by completion permits the originally rejected wrapper.
    manager.ingestForSync(rejected, "channel", pubkey, receiver)
    assertNotNull(db.processedEvents().byId(rejected.id))
  }

  @Test fun byteBudgetAndStrictNumberTypesCannotLeavePartialWrites() = runBlocking {
    manager = TransferManager(db, StagingLimits(totalBytes = 1))
    val sender = Secp256k1.randomPrivateKey()
    val receiver = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val f = fixture(sender, receiver, "41")
    val oversized = wrap(f.chunks.first(), sender, receiver)
    try { manager.ingestForSync(oversized, "channel", pubkey, receiver); fail("byte capacity must reject") }
    catch (_: StagingCapacityException) {}
    assertEquals(0L, db.chunks().stagedBytes())
    assertNull(db.processedEvents().byId(oversized.id))
    val malformed = JsonObject(f.manifest.toMutableMap().apply { this["chunkCount"] = JsonPrimitive(f.chunks.size.toString()) })
    try { manager.ingestForSync(wrap(malformed, sender, receiver), "channel", pubkey, receiver); fail("string number must reject") }
    catch (_: IllegalArgumentException) {}
    assertEquals(0, db.chunks().stagedTransferCount())
  }
}
