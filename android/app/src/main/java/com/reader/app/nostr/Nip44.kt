package com.reader.app.nostr

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NIP-44 v2, byte-exact with nostr-tools (the Chrome side). */
object Nip44 {
  const val VERSION: Byte = 0x02
  const val MIN_PLAINTEXT = 1
  const val MAX_PLAINTEXT = 65535
  private val random = SecureRandom()

  /** Byte-exact reference padding (NIP-44): chunked, not power-of-two. */
  fun calcPaddedLen(len: Int): Int {
    require(len >= MIN_PLAINTEXT) { "plaintext out of range" }
    if (len <= 32) return 32
    var nextPower = 1
    while (nextPower < len) nextPower = nextPower shl 1
    val chunk = if (nextPower <= 256) 32 else nextPower / 8
    return chunk * ((len - 1) / chunk + 1)
  }

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  /** HKDF-extract(salt, ikm). */
  fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

  /** HKDF-expand(prk, info, L). */
  fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val out = mutableListOf<Byte>()
    var t = ByteArray(0)
    var i = 1
    while (out.size < length) {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(prk, "HmacSHA256"))
      mac.update(t)
      mac.update(info)
      mac.update(i.toByte())
      t = mac.doFinal()
      out.addAll(t.toList())
      i++
    }
    return out.take(length).toByteArray()
  }

  fun getConversationKey(privA: ByteArray, pubB: ByteArray): ByteArray {
    val shared = Secp256k1.ecdh(privA, pubB)
    return extract("nip44-v2".toByteArray(Charsets.UTF_8), shared)
  }

  fun messageKeys(conversationKey: ByteArray, nonce32: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
    require(nonce32.size == 32)
    val keys = expand(conversationKey, nonce32, 76)
    return Triple(
      keys.copyOfRange(0, 32),
      keys.copyOfRange(32, 44),
      keys.copyOfRange(44, 76),
    )
  }

  private fun chacha(key: ByteArray, nonce12: ByteArray, data: ByteArray): ByteArray {
    val engine = ChaCha7539Engine()
    engine.init(true, ParametersWithIV(KeyParameter(key), nonce12))
    val out = ByteArray(data.size)
    engine.processBytes(data, 0, data.size, out, 0)
    engine.reset()
    return out
  }

  fun encrypt(plaintext: String, conversationKey: ByteArray, nonce32: ByteArray? = null): String {
    val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
    require(plainBytes.size in MIN_PLAINTEXT..MAX_PLAINTEXT) { "plaintext out of range" }
    val nonce = nonce32 ?: ByteArray(32).also { random.nextBytes(it) }
    require(nonce.size == 32)
    val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
    val paddedLen = calcPaddedLen(plainBytes.size)
    val padded = ByteArray(2 + paddedLen)
    padded[0] = ((plainBytes.size shr 8) and 0xff).toByte()
    padded[1] = (plainBytes.size and 0xff).toByte()
    plainBytes.copyInto(padded, 2)
    val ciphertext = chacha(chachaKey, chachaNonce, padded)
    val mac = hmac(hmacKey, nonce + ciphertext)
    val payload = byteArrayOf(VERSION) + nonce + ciphertext + mac
    return Base64.getEncoder().encodeToString(payload)
  }

  fun decrypt(payloadB64: String, conversationKey: ByteArray): String {
    val payload = try {
      Base64.getDecoder().decode(payloadB64)
    } catch (e: Exception) {
      throw IllegalArgumentException("bad base64")
    }
    require(payload.size >= 99 && payload.size <= 65603 + 32) { "bad payload length" }
    require(payload[0] == VERSION) { "unknown version" }
    val nonce = payload.copyOfRange(1, 33)
    val ciphertext = payload.copyOfRange(33, payload.size - 32)
    val mac = payload.copyOfRange(payload.size - 32, payload.size)
    val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
    val expected = hmac(hmacKey, nonce + ciphertext)
    if (!MessageDigestIsEqual(expected, mac)) throw IllegalArgumentException("bad mac")
    val padded = chacha(chachaKey, chachaNonce, ciphertext)
    require(padded.size >= 2) { "bad padded" }
    val unpaddedLen = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
    require(unpaddedLen in MIN_PLAINTEXT..MAX_PLAINTEXT) { "bad unpadded len" }
    require(padded.size == 2 + calcPaddedLen(unpaddedLen)) { "bad padding length" }
    for (i in 2 + unpaddedLen until padded.size) require(padded[i] == 0.toByte()) { "nonzero padding" }
    return padded.copyOfRange(2, 2 + unpaddedLen).toString(Charsets.UTF_8)
  }

  private fun MessageDigestIsEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
  }
}
