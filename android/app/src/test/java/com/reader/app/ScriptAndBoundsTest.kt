package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.capture.CaptureException
import com.reader.app.capture.CaptureFetcher
import com.reader.app.capture.CaptureHttp
import com.reader.app.capture.CaptureUrlPolicy
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import com.reader.app.rsvp.RsvpModel
import com.reader.app.tts.Narration
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ScriptAndBoundsTest {
  @Test fun cjkCountingAndFloor() {
    assertEquals(0, ReaderCore.cjkCount("Hello world"))
    assertTrue(ReaderCore.cjkCount("中文测试文章阅读") > 5)
    assertTrue(ReaderCore.cjkCount("ひらがなカタカナ한글ไทย") > 5)
    assertFalse(ReaderCore.hasEnoughText("Just a few words"))
    val probe = "阅读是获取知识的重要途径和方法之一我们每天都应该坚持阅读好书".repeat(3)
    assertTrue(ReaderCore.hasEnoughText(probe))
    assertTrue(ReaderCore.effectiveWords("abc 中文测试") >= 6)
  }

  @Test fun chineseArticleExtractsInsteadOfLinkOnly() {
    val para = "阅读是人类获取知识的重要途径。通过阅读我们可以了解世界开阔视野增长见识。"
    val html = ("<!doctype html><html><head><title>阅读的意义</title></head><body>" +
      "<main><article><h1>阅读的意义</h1><p>$para</p><p>$para</p><p>$para</p></article></main>" +
      "<nav>导航</nav></body></html>").toByteArray(Charsets.UTF_8)
    val out = ArticleExtractor.extract(html, "https://example.com/zh")
    assertTrue(out.wordCount >= 60)
    assertTrue(out.markdown.contains("阅读是人类"))
    assertFalse(out.markdown.contains("导航"))
  }

  @Test fun narrationSplitsCjkSentencesAndChunks() {
    val blocks = ArticleParser.parse("这是第一句。这是第二句！这是第三句？\n")
    val units = Narration.sentences(blocks)
    assertTrue("units=${units.size}", units.size >= 3)
    // Spaceless long run is chunked, never one giant unit.
    val long = ArticleParser.parse("阅读".repeat(600) + "\n")
    val units2 = Narration.sentences(long)
    assertTrue(units2.all { it.text.length <= 1500 })
  }

  @Test fun rsvpSlicesLongCjkTokensWithExactOffsets() {
    val text = "阅读".repeat(60) // 120 chars, no spaces
    val blocks = ArticleParser.parse("$text\n")
    val tokens = RsvpModel.tokens(blocks)
    assertTrue("tokens=${tokens.size}", tokens.size > 4)
    assertTrue(tokens.all { it.text.length <= 24 })
    assertTrue(tokens.all { it.end > it.start })
    // Coverage is contiguous over the match range.
    val starts = tokens.map { it.start }
    assertEquals(0, starts.first())
    for (i in 1 until tokens.size) assertTrue(tokens[i].start <= tokens[i - 1].end)
  }

  @Test fun footnoteMarkersUnspokenButVisible() {
    val blocks = ArticleParser.parse("Claim holds.[^1]\n\n[^1]: The source.\n")
    val spoken = Narration.sentences(blocks).joinToString(" ") { it.text }
    assertFalse("spoken=$spoken", spoken.contains("[1]"))
    assertTrue(spoken.contains("Claim holds"))
    val tokens = RsvpModel.tokens(blocks)
    assertFalse(tokens.any { it.text.contains("[1]") })
    // Renderer still shows the marker (smaller superscript span).
    val projection = com.reader.app.core.RenderedText.project(blocks)
    assertTrue(projection.text.contains("[1]"))
    assertTrue(projection.styles.any { it.style == com.reader.app.core.TextStyle.FOOTNOTE_REF })
  }

  @Test fun contentLengthLieFailsFastWithoutBody() = runBlocking {
    // Raw socket declares 100MB with a 4-byte body and then stalls: production
    // must reject on the header alone (fast), not buffer or time out.
    val server = java.net.ServerSocket(0)
    val port = server.localPort
    val served = java.util.concurrent.CountDownLatch(1)
    Thread {
      try {
        server.accept().use { socket ->
          socket.getInputStream().read(ByteArray(4096))
          val out = socket.getOutputStream()
          out.write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 100000000\r\nConnection: close\r\n\r\ntiny".toByteArray(),
          )
          out.flush()
          served.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
      } catch (_: Exception) { } finally { served.countDown() }
    }.also { it.isDaemon = true }.start()
    try {
      val permissive = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
      }
      val http = com.reader.app.capture.OkHttpCaptureHttp(permissive)
      val started = System.nanoTime()
      try {
        http.get("http://127.0.0.1:$port/lie", 30)
        fail("declared huge body must reject")
      } catch (e: CaptureException) {
        assertEquals("body_too_large", e.code)
      }
      assertTrue("fail-fast took ${(System.nanoTime() - started) / 1_000_000}ms", System.nanoTime() - started < 10_000_000_000L)
    } finally {
      served.countDown()
      server.close()
    }
  }

  @Test fun chunkedOverCapBodyStreamsBounded() = runBlocking {
    val server = MockWebServer()
    val big = Buffer().also { it.write(ByteArray(3 * 1024 * 1024) { 65 }) }
    server.enqueue(
      MockResponse().setResponseCode(200).addHeader("Content-Type", "text/html")
        .setChunkedBody(big, 8192),
    )
    server.start()
    try {
      val permissive = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
      }
      val http = com.reader.app.capture.OkHttpCaptureHttp(permissive)
      val url = server.url("/big").toString().replace("localhost", "127.0.0.1")
      try {
        http.get(url, 30)
        fail("over-cap chunked body must reject")
      } catch (e: CaptureException) {
        assertEquals("body_too_large", e.code)
      }
    } finally {
      server.shutdown()
    }
  }

  @Test fun fetchChainBudgetCountsEveryHop() = runBlocking {
    var calls = 0
    val hop = ByteArray(3 * 1024 * 1024) { 66 }
    val http = object : CaptureHttp {
      override suspend fun get(url: String, totalTimeoutSecs: Long): CaptureHttp.Response {
        calls++
        return if (calls <= 3) {
          CaptureHttp.Response(302, mapOf("Location" to "https://example.com/$calls", "Content-Type" to "text/html"), ByteArray(0))
        } else {
          CaptureHttp.Response(200, mapOf("Content-Type" to "text/html"), hop)
        }
      }
    }
    val allowPublic = CaptureUrlPolicy.AddressResolver { _ -> arrayOf(InetAddress.getByName("93.184.216.34")) }
    // 3 empty hops + one 3MB body = under the 8MB chain cap, over per-body cap.
    try {
      CaptureFetcher(http, allowPublic).fetch("https://example.com/a")
      fail("per-body cap must reject first")
    } catch (e: CaptureException) {
      assertEquals("body_too_large", e.code)
    }
  }
}
