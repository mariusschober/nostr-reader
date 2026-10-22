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
