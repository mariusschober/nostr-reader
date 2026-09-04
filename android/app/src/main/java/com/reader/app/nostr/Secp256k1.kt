package com.reader.app.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** secp256k1 via BouncyCastle EC math. No NDK. BIP-340 Schnorr + ECDH. */
object Secp256k1 {
  private val params = SECNamedCurves.getByName("secp256k1")
  private val curve = params.curve
  private val G: ECPoint = params.g
  private val N: BigInteger = params.n
  private val P: BigInteger = curve.field.characteristic
  private val random = SecureRandom()

  fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
    val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
    val md = MessageDigest.getInstance("SHA-256")
    md.update(tagHash)
    md.update(tagHash)
    for (p in parts) md.update(p)
    return md.digest()
  }

  fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
  }

  fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

  fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex" }
    return ByteArray(hex.length / 2) { i ->
      hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
  }

  fun randomPrivateKey(): ByteArray {
    while (true) {
      val b = ByteArray(32)
      random.nextBytes(b)
      val d = BigInteger(1, b)
      if (d > BigInteger.ZERO && d < N) return to32(b, d)
    }
  }

  private fun to32(raw: ByteArray, d: BigInteger): ByteArray {
    val b = d.toByteArray()
    return when {
      b.size == 32 -> b
      b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
      b.size < 32 -> ByteArray(32 - b.size) + b
      else -> throw IllegalArgumentException("bad scalar")
    }
  }

  private fun hasEvenY(p: ECPoint): Boolean {
    val n = p.normalize()
    return !n.yCoord.toBigInteger().testBit(0)
  }

  private fun xBytes(p: ECPoint): ByteArray {
    val x = p.normalize().xCoord.toBigInteger()
    return to32(ByteArray(0), x)
  }

  /** BIP-340 x-only pubkey for a private key. */
  fun getPublicKey(seckey: ByteArray): ByteArray {
    val d = BigInteger(1, seckey)
    require(d > BigInteger.ZERO && d < N) { "bad seckey" }
    var p = G.multiply(d).normalize()
    if (!hasEvenY(p)) p = G.multiply(N.subtract(d)).normalize()
    return xBytes(p)
  }

  /** ECDH shared x-coordinate (NIP-44 input). */
  fun ecdh(seckey: ByteArray, pubkeyX: ByteArray): ByteArray {
    // Lift x-only pubkey to the even-Y point.
    val x = BigInteger(1, pubkeyX)
    require(x < P) { "pubkey out of range" }
    val ySq = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
    var y = ySq.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    val candidate = curve.createPoint(x, y).normalize()
    val point = if (hasEvenY(candidate)) candidate else curve.createPoint(x, P.subtract(y)).normalize()
    val shared = point.multiply(BigInteger(1, seckey)).normalize()
    require(!shared.isInfinity) { "ecdh infinity" }
    return xBytes(shared)
  }

  /** BIP-340 sign. Returns 64-byte sig. */
  fun schnorrSign(msg32: ByteArray, seckey: ByteArray, auxRand: ByteArray = ByteArray(32).also { random.nextBytes(it) }): ByteArray {
    require(msg32.size == 32)
    val d0 = BigInteger(1, seckey)
    require(d0 > BigInteger.ZERO && d0 < N)
    val p = G.multiply(d0).normalize()
    val d = if (hasEvenY(p)) d0 else N.subtract(d0)
    val dBytes = to32(ByteArray(0), d)
    val aux = taggedHash("BIP0340/aux", auxRand)
    val tBytes = ByteArray(32) { i -> (dBytes[i].toInt() xor aux[i].toInt()).toByte() }
    val t = BigInteger(1, tBytes).mod(N)
    val rPoint = G.multiply(t).normalize()
    val k = if (hasEvenY(rPoint)) t else N.subtract(t)
    val r = xBytes(G.multiply(k).normalize())
    val pub = xBytes(G.multiply(d).normalize())
    val e = BigInteger(1, taggedHash("BIP0340/challenge", r, pub, msg32)).mod(N)
    val s = k.add(e.multiply(d)).mod(N)
    return r + to32(ByteArray(0), s)
  }

  /** BIP-340 verify. */
  fun schnorrVerify(msg32: ByteArray, pubkeyX: ByteArray, sig: ByteArray): Boolean {
    try {
      require(msg32.size == 32 && pubkeyX.size == 32 && sig.size == 64)
      val r = sig.copyOfRange(0, 32)
      val s = BigInteger(1, sig.copyOfRange(32, 64))
      if (s >= N) return false
      val x = BigInteger(1, pubkeyX)
      if (x >= P) return false
      val ySq = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
      val y = ySq.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
      var point = curve.createPoint(x, y).normalize()
      if (!hasEvenY(point)) point = curve.createPoint(x, P.subtract(y)).normalize()
      val e = BigInteger(1, taggedHash("BIP0340/challenge", r, pubkeyX, msg32)).mod(N)
      val rPoint = G.multiply(s).add(point.multiply(N.subtract(e))).normalize()
      if (rPoint.isInfinity || !hasEvenY(rPoint)) return false
      return xBytes(rPoint).contentEquals(r)
    } catch (e: Exception) {
      return false
    }
  }
}
