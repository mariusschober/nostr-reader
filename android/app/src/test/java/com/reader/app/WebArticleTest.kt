package com.reader.app

import com.reader.app.sync.UrlCapture
import com.reader.app.sync.WebArticleExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebArticleTest {
  private val html = """
    <html><head>
      <title>Fallback title</title>
      <meta property="og:title" content="The Real Article">
      <meta name="author" content="Ada Lovelace">
    </head><body>
      <nav>Navigation noise</nav>
      <article>
        <h1>The Real Article</h1>
        <p>First paragraph with enough text to be a readable article body.</p>
        <ul><li>first point</li><li>second point</li></ul>
        <script>stealEverything()</script>
        <p>Second paragraph continues the story with useful detail.</p>
      </article>
    </body></html>
  """.trimIndent()

  @Test
  fun detectsASingleHttpUrlEvenWithSurroundingText() {
    assertEquals("https://example.com/a", UrlCapture.singleHttpUrl("Read this https://example.com/a."))
    assertEquals("http://example.com/a?q=1", UrlCapture.singleHttpUrl("http://example.com/a?q=1"))
    assertNull(UrlCapture.singleHttpUrl("https://one.example and https://two.example"))
    assertNull(UrlCapture.singleHttpUrl("javascript:alert(1)"))
    assertNull(UrlCapture.singleHttpUrl("https://user:pass@example.com/a"))
    assertNull(UrlCapture.singleHttpUrl("not a link"))
  }

  @Test
  fun rejectsRawIpAndLegacyNumericHosts() {
    val prohibited = listOf(
      "https://127.0.0.1/",
      "http://10.0.0.1/path",
      "https://192.168.1.1/",
      "https://169.254.169.254/latest/meta-data",
      "https://100.64.0.1/",
      "https://0.0.0.0/",
      "https://224.0.0.1/",
      "https://255.255.255.255/",
      "https://2130706433/",
      "https://0177.0.0.1/",
      "https://0x7f.0.0.1/",
      "https://0x7f000001/",
      "https://127.1/",
      "https://127.0.0.1./",
      "https://127.0.0.1../",
      "https://0127.0.0.1/",
    )
    prohibited.forEach { assertNull(it, UrlCapture.normalize(it)) }
  }

  @Test
  fun rejectsIpv6LoopbackMappedAndLocalHosts() {
    val prohibited = listOf(
      "https://[::1]/",
      "https://[::ffff:127.0.0.1]/",
      "https://[::ffff:10.0.0.1]/",
      "https://[fc00::1]/",
      "https://[fe80::1]/",
      "https://[::]/",
      "https://[ff02::1]/",
    )
    prohibited.forEach { assertNull(it, UrlCapture.normalize(it)) }
  }

  @Test
  fun acceptsPublicHostnamesAndAddresses() {
    assertEquals("https://example.com/article", UrlCapture.normalize("https://example.com/article"))
    assertEquals("http://example.com/a?q=1", UrlCapture.normalize("http://example.com/a?q=1"))
    assertEquals("https://8.8.8.8/", UrlCapture.normalize("https://8.8.8.8/"))
    assertEquals("https://[2606:4700:4700::1111]/", UrlCapture.normalize("https://[2606:4700:4700::1111]/"))
  }

  @Test
  fun extractsReadableMarkdownAndDropsPageNoise() {
    val article = WebArticleExtractor.extract(html, "https://example.com/article")!!
    assertEquals("The Real Article", article.title)
    assertEquals("Ada Lovelace", article.author)
    assertEquals("example.com", article.sourceName)
    assertTrue(article.markdown.contains("# The Real Article"))
    assertTrue(article.markdown.contains("First paragraph"))
    assertTrue(article.markdown.contains("- first point"))
    assertTrue(article.markdown.contains("Second paragraph"))
    assertFalse(article.markdown.contains("Navigation noise"))
    assertFalse(article.markdown.contains("stealEverything"))
  }

  @Test
  fun refusesPagesWithNoReadableBody() {
    assertNull(WebArticleExtractor.extract("<html><body><nav>only nav</nav></body></html>", "https://example.com"))
  }
}
