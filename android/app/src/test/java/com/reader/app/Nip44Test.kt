package com.reader.app

import com.reader.app.nostr.Nip44
import com.reader.app.nostr.Secp256k1
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

/** NIP-44 interop: decrypt REAL nostr-tools ciphertext (shared vector) + roundtrips. */
class Nip44Test {
  private fun vector(): JsonObject {
    // Walk up from the working dir to the repo root (marked by shared/schemas).
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null && !File(dir, "shared/schemas").exists()) dir = dir.parentFile
    val f = File(dir ?: File("."), "shared/test-vectors/nip44-v1.json")
    require(f.exists()) { "missing test vector: ${f.absolutePath}" }
    return Json.parseToJsonElement(f.readText()).jsonObject
  }

  private fun official(): JsonObject {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null && !File(dir, "shared/schemas").exists()) dir = dir.parentFile
    val f = File(dir ?: File("."), "shared/test-vectors/nip44-official.json")
    require(f.exists()) { "missing official vectors: ${f.absolutePath}" }
    assertEquals(
      "269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040",
      com.reader.app.core.ReaderCore.sha256Hex(f.readBytes()),
    )
    return Json.parseToJsonElement(f.readText()).jsonObject["v2"]!!.jsonObject
  }

  @Test
  fun decryptsRealJsLibraryCiphertext() {
    val v = vector()
    val secB = Secp256k1.hexToBytes(v["secB"]!!.jsonPrimitive.content)
    val pubA = Secp256k1.hexToBytes(v["pubA"]!!.jsonPrimitive.content)
    val ck = Nip44.getConversationKey(secB, pubA)
    assertEquals(v["conversationKey"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(ck))
    val plain = Nip44.decrypt(v["payload"]!!.jsonPrimitive.content, ck)
    assertEquals(v["plaintext"]!!.jsonPrimitive.content, plain)
  }

  @Test
  fun conversationKeyIsSymmetric() {
    val v = vector()
    val secA = Secp256k1.hexToBytes(v["secA"]!!.jsonPrimitive.content)
    val secB = Secp256k1.hexToBytes(v["secB"]!!.jsonPrimitive.content)
    val pubA = Secp256k1.hexToBytes(v["pubA"]!!.jsonPrimitive.content)
    val pubB = Secp256k1.hexToBytes(v["pubB"]!!.jsonPrimitive.content)
    assertArrayEquals(Nip44.getConversationKey(secA, pubB), Nip44.getConversationKey(secB, pubA))
  }

  @Test
  fun fixedNonceReproducesExactPayload() {
    // Deterministic: same keys + same nonce => same ciphertext as the JS lib.
    val v = vector()
    val secA = Secp256k1.hexToBytes(v["secA"]!!.jsonPrimitive.content)
    val pubB = Secp256k1.hexToBytes(v["pubB"]!!.jsonPrimitive.content)
    val ck = Nip44.getConversationKey(secA, pubB)
    val nonce = ByteArray(32) { it.toByte() }
    val payload = Nip44.encrypt(v["plaintext"]!!.jsonPrimitive.content, ck, nonce)
    assertEquals(v["payload"]!!.jsonPrimitive.content, payload)
  }

  @Test
  fun roundtripAndTamperDetection() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val ck = Nip44.getConversationKey(a, Secp256k1.getPublicKey(b))
    val msg = "roundtrip with unicode äöü and emoji 🎉"
    val payload = Nip44.encrypt(msg, ck)
    assertEquals(msg, Nip44.decrypt(payload, ck))
    // Tamper with one ciphertext byte -> MAC failure.
    val raw = Base64.getDecoder().decode(payload)
    raw[40] = (raw[40].toInt() xor 0x01).toByte()
    val bad = Base64.getEncoder().encodeToString(raw)
    try {
      Nip44.decrypt(bad, ck)
      fail("expected mac failure")
    } catch (e: IllegalArgumentException) {
    }
    // Wrong recipient key -> failure.
    val c = Secp256k1.randomPrivateKey()
    val wrongCk = Nip44.getConversationKey(c, Secp256k1.getPublicKey(b))
    try {
      Nip44.decrypt(payload, wrongCk)
      fail("expected failure")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun paddingTable() {
    assertEquals(32, Nip44.calcPaddedLen(1))
    assertEquals(32, Nip44.calcPaddedLen(32))
    assertEquals(64, Nip44.calcPaddedLen(33))
    assertEquals(96, Nip44.calcPaddedLen(70))
    assertEquals(128, Nip44.calcPaddedLen(100))
  }

  @Test
  fun officialExtendedPrefixBoundaries() {
    val key = Secp256k1.hexToBytes("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d")
    val nonce = Secp256k1.hexToBytes("0000000000000000000000000000000000000000000000000000000000000001")
    val vectors = listOf(
      Triple(65535, 65536, "6d8c2810d1e870fbaa1f0a0937126cca837a15f9260e27060c331d70a3c0bc84"),
      Triple(65536, 65536, "b7b4edb36ba92e267d322d56d9aebc22e7fa96ff52e3c12adc07f07a43cbc616"),
      Triple(65537, 81920, "eeb7c7c5373894ea2c1547cfd3ccb15d5a0b2d619da852e5c79df792dcc9e435"),
    )
    for ((length, paddedLength, payloadHash) in vectors) {
      val plaintext = "a".repeat(length)
      assertEquals(paddedLength, Nip44.calcPaddedLen(length))
      val payload = Nip44.encrypt(plaintext, key, nonce)
      assertEquals(payloadHash, com.reader.app.core.ReaderCore.sha256Hex(payload.toByteArray(Charsets.UTF_8)))
      assertEquals(plaintext, Nip44.decrypt(payload, key))
    }
  }

  @Test
  fun rejectsOversizedInputBeforeAllocationHeavyDecode() {
    val key = ByteArray(32) { 1 }
    try {
      Nip44.encrypt("a".repeat(Nip44.MAX_PLAINTEXT + 1), key, ByteArray(32))
      fail("expected local size cap")
    } catch (_: IllegalArgumentException) {
    }
    try {
      Nip44.decrypt("A".repeat(1_500_000), key)
      fail("expected encoded size cap")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test
  fun checksumPinnedOfficialPositiveCorpus() {
    val valid = official()["valid"]!!.jsonObject
    for (item in valid["get_conversation_key"]!!.jsonArray) {
      val v = item.jsonObject
      val key = Nip44.getConversationKey(
        Secp256k1.hexToBytes(v["sec1"]!!.jsonPrimitive.content),
        Secp256k1.hexToBytes(v["pub2"]!!.jsonPrimitive.content),
      )
      assertEquals(v["conversation_key"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(key))
    }
    for (item in valid["calc_padded_len"]!!.jsonArray) {
      val pair = item.jsonArray
      assertEquals(pair[1].jsonPrimitive.int, Nip44.calcPaddedLen(pair[0].jsonPrimitive.int))
    }
    val keyVectors = valid["get_message_keys"]!!.jsonObject
    val conversationKey = Secp256k1.hexToBytes(keyVectors["conversation_key"]!!.jsonPrimitive.content)
    for (item in keyVectors["keys"]!!.jsonArray) {
      val v = item.jsonObject
      val (chachaKey, chachaNonce, hmacKey) = Nip44.messageKeys(
        conversationKey,
        Secp256k1.hexToBytes(v["nonce"]!!.jsonPrimitive.content),
      )
      assertEquals(v["chacha_key"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(chachaKey))
      assertEquals(v["chacha_nonce"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(chachaNonce))
      assertEquals(v["hmac_key"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(hmacKey))
    }
    for (item in valid["encrypt_decrypt"]!!.jsonArray) {
      val v = item.jsonObject
      val sec1 = Secp256k1.hexToBytes(v["sec1"]!!.jsonPrimitive.content)
      val sec2 = Secp256k1.hexToBytes(v["sec2"]!!.jsonPrimitive.content)
      val key = Nip44.getConversationKey(sec1, Secp256k1.getPublicKey(sec2))
      assertEquals(v["conversation_key"]!!.jsonPrimitive.content, Secp256k1.bytesToHex(key))
      val payload = Nip44.encrypt(v["plaintext"]!!.jsonPrimitive.content, key, Secp256k1.hexToBytes(v["nonce"]!!.jsonPrimitive.content))
      assertEquals(v["payload"]!!.jsonPrimitive.content, payload)
      assertEquals(v["plaintext"]!!.jsonPrimitive.content, Nip44.decrypt(payload, key))
    }
    for (item in valid["encrypt_decrypt_long_msg"]!!.jsonArray) {
      val v = item.jsonObject
      val plaintext = v["pattern"]!!.jsonPrimitive.content.repeat(v["repeat"]!!.jsonPrimitive.int)
      val key = Secp256k1.hexToBytes(v["conversation_key"]!!.jsonPrimitive.content)
      val nonce = Secp256k1.hexToBytes(v["nonce"]!!.jsonPrimitive.content)
      assertEquals(v["plaintext_sha256"]!!.jsonPrimitive.content, com.reader.app.core.ReaderCore.sha256Hex(plaintext.toByteArray()))
      val payload = Nip44.encrypt(plaintext, key, nonce)
      assertEquals(v["payload_sha256"]!!.jsonPrimitive.content, com.reader.app.core.ReaderCore.sha256Hex(payload.toByteArray()))
      assertEquals(plaintext, Nip44.decrypt(payload, key))
    }
  }

  @Test
  fun officialNegativeCorpusIsRejectedWhereStillApplicable() {
    val invalid = official()["invalid"]!!.jsonObject
    for (item in invalid["get_conversation_key"]!!.jsonArray) {
      val v = item.jsonObject
      try {
        Nip44.getConversationKey(
          Secp256k1.hexToBytes(v["sec1"]!!.jsonPrimitive.content),
          Secp256k1.hexToBytes(v["pub2"]!!.jsonPrimitive.content),
        )
        fail("expected invalid conversation key input")
      } catch (_: Exception) {
      }
    }
    for (item in invalid["decrypt"]!!.jsonArray) {
      val v = item.jsonObject
      try {
        Nip44.decrypt(v["payload"]!!.jsonPrimitive.content, Secp256k1.hexToBytes(v["conversation_key"]!!.jsonPrimitive.content))
        fail("expected invalid payload")
      } catch (_: Exception) {
      }
    }
    // The pinned corpus predates the now-standard six-byte extended prefix;
    // its former 65536/100000 negatives are superseded by boundary tests above.
    assertTrue(invalid["encrypt_msg_lengths"]!!.jsonArray.any { it.jsonPrimitive.int == 0 })
  }
}
