package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.core.ArticleParser
import com.reader.app.core.Graphemes
import com.reader.app.core.RenderedProjection
import com.reader.app.data.FIND_PAGE_SIZE
import com.reader.app.data.MatchPosition
import com.reader.app.data.scanMatches
import com.reader.app.nostr.PublicNetworkRoute
import com.reader.app.ui.screens.NativeMark
import com.reader.app.ui.screens.forEachVisibleMark
import java.net.*
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderQualityTest {
  @Test fun boundedFindPreservesAllHitsAcrossPartsAndStopsOnFirstHit() = runBlocking {
    val counts = listOf(0, 70, 135, 11)
    val projected = counts.map { RenderedProjection("Wort ".repeat(it), emptyList(), emptyList(), emptyMap()) }
    val opened = mutableListOf<Int>()
    val first = scanMatches("doc", "wort", counts.size, MatchPosition(), firstOnly = true) { opened.add(it); projected[it] }
    assertEquals(listOf(0, 1), opened)
    assertEquals(1, first.matches.size)
    val found = mutableListOf<Pair<Int, Int>>()
    var cursor: MatchPosition? = MatchPosition()
    do {
      val page = scanMatches("doc", "wort", counts.size, requireNotNull(cursor)) { projected[it] }
      assertTrue(page.matches.size <= FIND_PAGE_SIZE)
      found.addAll(page.matches.map { match ->
        assertEquals("Wort", match.snippet.substring(match.matchStart, match.matchEnd))
        match.part to requireNotNull(match.textEnd)
      })
      cursor = page.next
    } while (cursor != null)
    assertEquals(counts.flatMapIndexed { part, count -> (0 until count).map { part to (it * 5 + 4) } }, found)
    val exact = RenderedProjection("X".repeat(FIND_PAGE_SIZE), emptyList(), emptyList(), emptyMap())
    assertNull(scanMatches("doc", "X", 1, MatchPosition()) { exact }.next)
  }

  @Test fun highlightSweepMatchesNewestCoveringMarkAtEveryOffset() {
    val length = 512
    val rng = Random(9142)
    val marks = (0 until 1200).map {
      val start = rng.nextInt(-10, length + 1)
      NativeMark(it.toString(), start, start + rng.nextInt(0, 90), rng.nextLong(6), 0, 0)
    }
    val painted = arrayOfNulls<String>(length)
    forEachVisibleMark(marks, length) { start, end, mark ->
      assertTrue(end > start)
      for (i in start until end) { assertNull(painted[i]); painted[i] = mark.id }
    }
    val valid = marks.filter { it.start >= 0 && it.end <= length }
    for (i in painted.indices) {
      val newest = valid.filter { i >= it.start && i < it.end }
        .maxWithOrNull(compareBy<NativeMark> { it.createdAt }.thenBy { it.id })
      assertEquals("offset $i", newest?.id, painted[i])
    }
  }

  @Test fun textBoundariesRetainCrlfEmojiAndCombiningCharactersInMixedProse() {
    val clusters = listOf("A", "\r\n", "e\u0301", "👩🏿‍🔬", "🇩🇪", ".", " ", "Z")
    val boundaries = Graphemes(clusters.joinToString(""))
    var start = 0
    for (cluster in clusters) {
      val end = start + cluster.length
      assertEquals(start, boundaries.floor(start))
      assertEquals(end, boundaries.ceil(end))
      for (offset in start + 1 until end) {
        assertEquals(start, boundaries.floor(offset))
        assertEquals(end, boundaries.ceil(offset))
      }
      start = end
    }
  }

  @Test fun preservesLocalAnchorsAndAcceptsMixedCaseWebSchemes() {
    assertEquals("#section+1", ArticleParser.sanitizeUrl("#section+1"))
    assertNull(ArticleParser.sanitizeUrl("#"))
    assertEquals("HTTPS://example.org/a", ArticleParser.sanitizeUrl("HTTPS://example.org/a"))
    assertNull(ArticleParser.sanitizeUrl("//example.org/a"))
  }

  @Test fun htmlUsesDeclaredCharsetAndFallsBackToDocumentMetadata() {
    val html = "<html><head><meta charset='windows-1252'></head><body><article><h1>Crème brûlée</h1><p>" +
      "L’été arrive. Un café coûte trois euros. ".repeat(20) + "</p></article></body></html>"
    val input = html.toByteArray(charset("windows-1252"))
    for (encoding in listOf("windows-1252", null)) {
      val article = ArticleExtractor.extract(input, "https://example.org/lecture", encoding)
      assertTrue(article.markdown.contains("L’été arrive"))
    }
  }

  @Test fun honorsDestinationSpecificProxyRouting() {
    val direct = URI("https://direct.example")
    val proxied = URI("https://proxy-required.example")
    val selector = object : ProxySelector() {
      override fun select(uri: URI) = listOf(if (uri == direct) Proxy.NO_PROXY else
        Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("gateway.example", 3128)))
      override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {}
    }
    PublicNetworkRoute.requireDirect(direct, selector)
    assertThrows(UnknownServiceException::class.java) { PublicNetworkRoute.requireDirect(proxied, selector) }
  }
}
