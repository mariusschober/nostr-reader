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
}
