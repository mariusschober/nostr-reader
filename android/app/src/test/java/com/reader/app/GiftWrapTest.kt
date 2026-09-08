package com.reader.app

import com.reader.app.core.ReaderCore
import com.reader.app.nostr.Nip44
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.Secp256k1
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Full NIP-59 checklist: roundtrip + every rejection path. */
class GiftWrapTest {
  @Test fun rejectsWireTypeCoercionBeforeSignatureVerification() {
    val key = Secp256k1.randomPrivateKey()
    val event = NostrCodec.signEvent(Secp256k1.bytesToHex(Secp256k1.getPublicKey(key)), 100, 1059, emptyList(), "synthetic", key)
    for (field in listOf("kind", "created_at")) {
      val malformed = JsonObject(Json.parseToJsonElement(event.toJson()).jsonObject.toMutableMap().apply {
        this[field] = JsonPrimitive(this[field]!!.jsonPrimitive.content)
      })
      try { NostrCodec.parseEvent(malformed.toString()); fail("must reject stringified $field") }
      catch (_: IllegalArgumentException) {}
    }
  }
  @Test
  fun sealWrapRoundtrip() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val pubA = Secp256k1.bytesToHex(Secp256k1.getPublicKey(a))
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val payload = """{"protocol":"reader/2","type":"chunk","transferId":"t1"}"""
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, payload)
    val out = NostrCodec.unwrapAndVerify(wrap, b, pubA)
    assertTrue(out.contains("\"transferId\":\"t1\""))
  }

  @Test
  fun rejectsUntrustedSender() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val evil = Secp256k1.randomPrivateKey()
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/2","type":"x"}""")
    try {
      NostrCodec.unwrapAndVerify(wrap, b, Secp256k1.bytesToHex(Secp256k1.getPublicKey(evil)))
      fail("expected untrusted sender")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("untrusted"))
    }
  }

  @Test
  fun rejectsTamperedOuterSig() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val pubA = Secp256k1.bytesToHex(Secp256k1.getPublicKey(a))
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/2","type":"x"}""")
    val bad = wrap.copy(sig = "0".repeat(128))
    try {
      NostrCodec.unwrapAndVerify(bad, b, pubA)
      fail("expected sig failure")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun rejectsWrongRecipient() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val c = Secp256k1.randomPrivateKey()
    val pubA = Secp256k1.bytesToHex(Secp256k1.getPublicKey(a))
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/2","type":"x"}""")
    try {
      NostrCodec.unwrapAndVerify(wrap, c, pubA)
      fail("expected decrypt failure")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun rejectsBadKindAndVersion() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val pubA = Secp256k1.bytesToHex(Secp256k1.getPublicKey(a))
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/2","type":"x"}""")
    try {
      NostrCodec.unwrapAndVerify(wrap.copy(kind = 9999), b, pubA)
      fail("expected kind failure")
    } catch (e: IllegalArgumentException) {
    }
    val (_, wrap2) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/9","type":"x"}""")
    try {
      NostrCodec.unwrapAndVerify(wrap2, b, pubA)
      fail("expected version failure")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun schnorrSelfCheck() {
    val k = Secp256k1.randomPrivateKey()
    val msg = ByteArray(32) { it.toByte() }
    val sig = Secp256k1.schnorrSign(msg, k)
    assertTrue(Secp256k1.schnorrVerify(msg, Secp256k1.getPublicKey(k), sig))
    val badSig = sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertFalse(Secp256k1.schnorrVerify(msg, Secp256k1.getPublicKey(k), badSig))
  }

  @Test
  fun outerRoutesToRecipientAndRumorCarriesCanonicalId() {
    val sender = Secp256k1.randomPrivateKey()
    val recipient = Secp256k1.randomPrivateKey()
    val recipientPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(recipient))
    val (_, wrap) = NostrCodec.sealAndWrap(sender, recipientPubkey, """{"protocol":"reader/2","type":"ping"}""")
    assertTrue(wrap.tags.contains(listOf("p", recipientPubkey)))

    val wrapCk = Nip44.getConversationKey(recipient, Secp256k1.hexToBytes(wrap.pubkey))
    val seal = NostrCodec.parseEvent(Nip44.decrypt(wrap.content, wrapCk))
    val sealCk = Nip44.getConversationKey(recipient, Secp256k1.hexToBytes(seal.pubkey))
    val rumor = Json.parseToJsonElement(Nip44.decrypt(seal.content, sealCk)).jsonObject
    val rumorTags = rumor["tags"]!!.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
    val expectedId = NostrCodec.eventId(
      rumor["pubkey"]!!.jsonPrimitive.content,
      rumor["created_at"]!!.jsonPrimitive.long,
      rumor["kind"]!!.jsonPrimitive.int,
      rumorTags,
      rumor["content"]!!.jsonPrimitive.content,
    )
    assertEquals(expectedId, rumor["id"]?.jsonPrimitive?.content)
    assertNull(rumor["sig"])
  }

  @Test
  fun rejectsExpiredOuterEvent() {
    val sender = Secp256k1.randomPrivateKey()
    val recipient = Secp256k1.randomPrivateKey()
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val recipientPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(recipient))
    val (_, wrap) = NostrCodec.sealAndWrap(
      sender,
      recipientPubkey,
      """{"protocol":"reader/2","type":"ping"}""",
      expireSecs = -1,
    )
    try {
      NostrCodec.unwrapAndVerify(wrap, recipient, senderPubkey)
      fail("expected expired event rejection")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message.orEmpty().contains("expired", ignoreCase = true))
    }
  }

  @Test
  fun rejectsValidEphemeralWrapperInDurableReaderV2() {
    val sender = Secp256k1.randomPrivateKey()
    val recipient = Secp256k1.randomPrivateKey()
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(sender))
    val recipientPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(recipient))
    val (_, wrap) = NostrCodec.sealAndWrap(
      sender,
      recipientPubkey,
      """{"protocol":"reader/2","type":"ping"}""",
      wrapKind = 21059,
    )
    try {
      NostrCodec.unwrapAndVerify(wrap, recipient, senderPubkey)
      fail("expected ephemeral kind rejection")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message.orEmpty().contains("bad wrap kind"))
    }
  }

  @Test
  fun nip01CanonicalEscapingMatchesReferenceVector() {
    val pubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    val tag = "x\b\t\n\u000c\r\"\\"
    val content = "c\b\t\n\u000c\r\"\\"
    assertEquals(
      "6ed1e6b6d3ae6f98b45f42f048731bf06472cc940d05e7c76cab5ff2772be565",
      NostrCodec.eventId(pubkey, 1_700_000_000, 1, listOf(listOf("p", tag)), content),
    )
  }

  @Test
  fun nip01RejectsUppercaseHexOnWire() {
    val key = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(key))
    val event = NostrCodec.signEvent(pubkey, 1_700_000_000, ReaderCore.RUMOR_KIND, emptyList(), "x", key)
    assertFalse(NostrCodec.verifyEvent(event.copy(id = event.id.uppercase())))
    assertFalse(NostrCodec.verifyEvent(event.copy(pubkey = event.pubkey.uppercase())))
    assertFalse(NostrCodec.verifyEvent(event.copy(sig = event.sig.uppercase())))
  }

  @Test
  fun bip340OfficialVectorsZeroThroughFourteen() {
    var root: File? = File(requireNotNull(System.getProperty("user.dir")))
    while (root != null && !File(root, "shared/test-vectors").exists()) root = root.parentFile
    val vectors = File(root ?: error("repository root not found"), "shared/test-vectors/bip340-official.csv")
      .readLines()
      .drop(1)
      .take(15)
    assertEquals(15, vectors.size)
    for (line in vectors) {
      val fields = line.split(",", limit = 8)
      val index = fields[0]
      val secretHex = fields[1]
      val publicKey = Secp256k1.hexToBytes(fields[2])
      val auxHex = fields[3]
      val message = Secp256k1.hexToBytes(fields[4])
      val signature = Secp256k1.hexToBytes(fields[5])
      val expectedValid = fields[6] == "TRUE"
      assertEquals("verification vector $index", expectedValid, Secp256k1.schnorrVerify(message, publicKey, signature))
      if (secretHex.isNotEmpty()) {
        val secret = Secp256k1.hexToBytes(secretHex)
        assertArrayEquals("public-key vector $index", publicKey, Secp256k1.getPublicKey(secret))
        assertArrayEquals(
          "signing vector $index",
          signature,
          Secp256k1.schnorrSign(message, secret, Secp256k1.hexToBytes(auxHex)),
        )
      }
    }
  }
}
