package com.reader.app

import com.reader.app.core.BoundedText
import com.reader.app.core.ReaderCore
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.CharacterCodingException

class BoundedTextTest {
  @Test fun acceptsExactByteLimitAndUnicode() {
    val bytes = "é🙂".toByteArray()
    assertEquals("é🙂", BoundedText.readUtf8(ByteArrayInputStream(bytes), bytes.size))
  }
  @Test fun rejectsOneByteOverWithoutReadingTheRest() {
    val input = ByteArrayInputStream(ByteArray(100))
    assertThrows(IllegalArgumentException::class.java) { BoundedText.readUtf8(input, 8) }
    assertEquals(91, input.available())
  }
  @Test fun malformedUtf8IsNotSilentlyReplaced() {
    assertThrows(CharacterCodingException::class.java) {
      BoundedText.readUtf8(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), 8)
    }
  }
  @Test fun sizeLimitCountsBytesNotUtf16Units() {
    assertThrows(IllegalArgumentException::class.java) { BoundedText.requireSize("é🙂", 5) }
  }
  @Test fun canonicalWhitespaceRemainsStable() {
    assertEquals("a\n\n\nb\n", ReaderCore.canonicalize("\r\n a  \r\n\r\n\r\n\r\nb\n").trimStart())
    assertEquals("\n", ReaderCore.canonicalize(" \n\n"))
  }
}
