package com.reader.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Strict decoder for the deterministic, one-member Reader v2 gzip profile. */
object ReaderGzip {
  private val header = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 3)

  private fun littleEndianU32(input: ByteArray, offset: Int): Long =
    (input[offset].toLong() and 0xffL) or
      ((input[offset + 1].toLong() and 0xffL) shl 8) or
      ((input[offset + 2].toLong() and 0xffL) shl 16) or
      ((input[offset + 3].toLong() and 0xffL) shl 24)

  fun decode(input: ByteArray): ByteArray {
    require(input.size in 18..ReaderCore.MAX_COMPRESSED_BYTES) { "invalid Reader gzip size" }
    require(input.copyOfRange(0, header.size).contentEquals(header)) { "invalid Reader gzip framing" }
    val output = ByteArrayOutputStream()
    val inflater = Inflater(true)
    try {
      inflater.setInput(input, header.size, input.size - header.size)
      val buffer = ByteArray(32768)
      var total = 0
      while (!inflater.finished()) {
        val read = inflater.inflate(buffer)
        if (read == 0) {
          require(!inflater.needsDictionary() && !inflater.needsInput()) { "invalid or truncated Reader gzip stream" }
          throw IllegalArgumentException("invalid or truncated Reader gzip stream")
        }
        total += read
        require(total <= ReaderCore.MAX_EXPANDED_BYTES) { "expansion bomb" }
        output.write(buffer, 0, read)
      }
      val trailerOffset = header.size + inflater.bytesRead.toInt()
      require(trailerOffset + 8 == input.size) { "invalid Reader gzip: expected exactly one member and no trailing bytes" }
      val decoded = output.toByteArray()
      val crc = CRC32().apply { update(decoded) }.value
      require(littleEndianU32(input, trailerOffset) == crc) { "invalid or truncated Reader gzip stream" }
      require(littleEndianU32(input, trailerOffset + 4) == decoded.size.toLong()) {
        "invalid or truncated Reader gzip stream"
      }
    } catch (error: IllegalArgumentException) {
      throw error
    } catch (_: Exception) {
      throw IllegalArgumentException("invalid or truncated Reader gzip stream")
    } finally {
      inflater.end()
    }
    return output.toByteArray().also {
      require(it.isNotEmpty()) { "expanded transfer must not be empty" }
    }
  }
}
