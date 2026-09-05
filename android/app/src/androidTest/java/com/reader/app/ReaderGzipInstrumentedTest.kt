package com.reader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.runner.RunWith
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderGzipInstrumentedTest {
  private fun gzip(input: ByteArray): ByteArray {
    val encoded = ByteArrayOutputStream().also { output ->
      GZIPOutputStream(output).use { it.write(input) }
    }.toByteArray()
    encoded[9] = 3
    return encoded
  }

  private fun assertRejected(input: ByteArray) {
    try {
      ReaderGzip.decode(input)
      fail("expected Reader gzip rejection")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test
  fun physicalRuntimeDecodesChromeAndNormativeGzipVectors() {
    // The shared conformance vectors are packaged in the instrumentation APK,
    // not the production APK, so read them from the test context explicitly.
    val context = InstrumentationRegistry.getInstrumentation().context
    val fixture = Json.parseToJsonElement(
      context.assets.open("codec-v2.json").bufferedReader().use { it.readText() },
    ).jsonObject
    assertEquals("reader/2", fixture["protocol"]!!.jsonPrimitive.content)
    for (element in fixture["vectors"]!!.jsonArray) {
      val vector = element.jsonObject
      val canonical = vector["canonicalMarkdown"]!!.jsonPrimitive.content
      for (producer in listOf("chrome", "normative")) {
        val gzip = Base64.getDecoder().decode(vector["${producer}GzipBase64"]!!.jsonPrimitive.content)
        assertEquals(vector["${producer}GzipSha256"]!!.jsonPrimitive.content, ReaderCore.sha256Hex(gzip))
        assertArrayEquals(canonical.toByteArray(Charsets.UTF_8), ReaderGzip.decode(gzip))
      }
    }
  }

  @Test
  fun physicalRuntimeRejectsConcatenatedMembersAndTrailingBytes() {
    val first = gzip("first\n".toByteArray())
    val second = gzip("second\n".toByteArray())
    assertRejected(first + second)
    assertRejected(first + byteArrayOf(0))
  }
}
