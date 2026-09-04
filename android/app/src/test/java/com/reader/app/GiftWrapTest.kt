package com.reader.app

import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.Secp256k1
import org.junit.Assert.*
import org.junit.Test

/** Full NIP-59 checklist: roundtrip + every rejection path. */
class GiftWrapTest {
  @Test
  fun sealWrapRoundtrip() {
    val a = Secp256k1.randomPrivateKey()
    val b = Secp256k1.randomPrivateKey()
    val pubA = Secp256k1.bytesToHex(Secp256k1.getPublicKey(a))
    val pubB = Secp256k1.bytesToHex(Secp256k1.getPublicKey(b))
    val payload = """{"protocol":"reader/1","type":"chunk","transferId":"t1"}"""
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
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/1","type":"x"}""")
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
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/1","type":"x"}""")
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
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/1","type":"x"}""")
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
    val (_, wrap) = NostrCodec.sealAndWrap(a, pubB, """{"protocol":"reader/1","type":"x"}""")
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
}
