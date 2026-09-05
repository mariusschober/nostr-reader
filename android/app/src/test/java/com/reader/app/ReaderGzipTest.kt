package com.reader.app

import com.reader.app.core.ReaderCore
import com.reader.app.core.ReaderGzip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ReaderGzipTest {
  private fun sharedFixture() = Json.parseToJsonElement(
    checkNotNull(javaClass.classLoader?.getResourceAsStream("codec-v2.json")) {
      "shared codec-v2.json test resource missing"
    }.bufferedReader().use { it.readText() },
  ).jsonObject

  private fun deterministicTestGzip(input: ByteArray): ByteArray {
    val bytes = ByteArrayOutputStream().also { output ->
      GZIPOutputStream(output).use { it.write(input) }
    }.toByteArray()
    bytes[9] = 3
    return bytes
  }

  @Test
  fun decodesEveryChromeAndNormativeCodecVector() {
    val fixture = sharedFixture()
    assertEquals("reader/2", fixture["protocol"]!!.jsonPrimitive.content)
    for (element in fixture["vectors"]!!.jsonArray) {
      val vector = element.jsonObject
      val source = vector["source"]!!.jsonPrimitive.content
      val canonical = vector["canonicalMarkdown"]!!.jsonPrimitive.content
      assertEquals(canonical, ReaderCore.canonicalize(source))
      for (producer in listOf("chrome", "normative")) {
        val gzip = Base64.getDecoder().decode(vector["${producer}GzipBase64"]!!.jsonPrimitive.content)
        assertEquals(vector["${producer}GzipSha256"]!!.jsonPrimitive.content, ReaderCore.sha256Hex(gzip))
        assertArrayEquals(canonical.toByteArray(Charsets.UTF_8), ReaderGzip.decode(gzip))
      }
    }
  }

  @Test
  fun rejectsWrongFramingTruncationAndCorruption() {
    val good = deterministicTestGzip("safe\n".toByteArray())
    val zlib = byteArrayOf(0x78, 0x9c.toByte()) + good.copyOfRange(10, good.size - 8)
    expectRejected(zlib)
    expectRejected(good.copyOf(good.size - 1))
    expectRejected(good.clone().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() })
    expectRejected(good + deterministicTestGzip("second\n".toByteArray()))
    expectRejected(good + byteArrayOf(0))
    expectRejected(ByteArray(ReaderCore.MAX_COMPRESSED_BYTES + 1))
  }

  @Test
  fun enforcesExpandedBoundary() {
    val boundary = ByteArray(ReaderCore.MAX_EXPANDED_BYTES)
    assertEquals(boundary.size, ReaderGzip.decode(deterministicTestGzip(boundary)).size)
    expectRejected(deterministicTestGzip(ByteArray(ReaderCore.MAX_EXPANDED_BYTES + 1)))
  }

  private fun expectRejected(bytes: ByteArray) {
    try {
      ReaderGzip.decode(bytes)
      fail("expected Reader gzip rejection")
    } catch (_: IllegalArgumentException) {
    }
  }
}
