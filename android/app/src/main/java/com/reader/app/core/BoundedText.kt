package com.reader.app.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Local files obey the same expanded byte budget as relay transfers. */
object BoundedText {
  fun requireSize(text: String, limit: Int = ReaderCore.MAX_EXPANDED_BYTES) {
    require(text.length <= limit && text.toByteArray(Charsets.UTF_8).size <= limit) {
      "Article exceeds the 20 MiB limit"
    }
  }

  fun readUtf8(input: InputStream, limit: Int = ReaderCore.MAX_EXPANDED_BYTES): String {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
      if (count < 0) break
      if (count == 0) {
        val byte = input.read()
        if (byte < 0) break
        require(total < limit) { "Article exceeds the 20 MiB limit" }
        output.write(byte)
        total++
        continue
      }
      require(count <= limit - total) { "Article exceeds the 20 MiB limit" }
      output.write(buffer, 0, count)
      total += count
    }
    return Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF")
  }
}
