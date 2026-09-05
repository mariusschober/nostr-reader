package com.reader.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Strict decoder for the deterministic, one-member Reader v2 gzip profile. */
object ReaderGzip {
  private val header = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 3)

  fun decode(input: ByteArray): ByteArray {
    require(input.size in 18..ReaderCore.MAX_COMPRESSED_BYTES) { "invalid Reader gzip size" }
    require(input.copyOfRange(0, header.size).contentEquals(header)) { "invalid Reader gzip framing" }
    val output = ByteArrayOutputStream()
    try {
      GZIPInputStream(input.inputStream()).use { stream ->
        val buffer = ByteArray(32768)
        var total = 0
        while (true) {
          val read = stream.read(buffer)
          if (read < 0) break
          total += read
          require(total <= ReaderCore.MAX_EXPANDED_BYTES) { "expansion bomb" }
          output.write(buffer, 0, read)
        }
      }
    } catch (error: IllegalArgumentException) {
      throw error
    } catch (_: Exception) {
      throw IllegalArgumentException("invalid or truncated Reader gzip stream")
    }
    return output.toByteArray().also {
      require(it.isNotEmpty()) { "expanded transfer must not be empty" }
    }
  }
}
