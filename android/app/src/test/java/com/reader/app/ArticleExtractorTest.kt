package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArticleExtractorTest {
  private fun html(body: String, head: String = "<title>Test</title>"): ByteArray =
    "<!doctype html><html><head>$head</head><body>$body</body></html>".toByteArray(Charsets.UTF_8)

  @Test fun publisherControlsInsideArticleDoNotBecomeReadingContent() {
    val out = ArticleExtractor.extract(html("""<main><article class="devsite-article">
      <div class="devsite-banner"><p>Join our research community and sign up now.</p></div>
      <div class="devsite-article-meta">Home CSS Learn</div>
      <h1>Box Model<devsite-actions hidden><span>Stay organized with collections</span></devsite-actions></h1>
      <div class="devsite-article-body"><p>The box model describes how content, padding, borders and margins combine to determine an element's size.</p>
      <h2>Content and sizing</h2><p>A fixed width creates a constraint on the content box, and the other areas add space around it.</p></div>
      </article></main>""", "<meta property='og:title' content='Box Model &nbsp;|&nbsp; web.dev'>"), "https://web.dev/learn/css/box-model")
    assertEquals("Box Model", out.title)
    assertFalse(out.markdown.contains("research community"))
    assertFalse(out.markdown.contains("collections"))
    assertFalse(out.markdown.contains("Home CSS"))
    assertTrue(out.markdown.contains("Content and sizing"))
    assertTrue(out.markdown.contains("padding, borders"))
  }

  @Test fun keepsParagraphsHeadingsListsLinksCodeAndTables() {
    val page = html(
      """<main><article>
        <h1>Quiet Cost</h1>
        <p>First real paragraph with meaningful content about attention and reading habits today.</p>
        <p>Second paragraph continues the argument with a concrete example and a <a href="https://example.com/src">source link</a>.</p>
        <h2>What changes</h2>
        <ul><li>One thing</li><li>Another thing</li></ul>
        <pre><code>let x = 1;</code></pre>
        <table><tr><th>A</th><th>B</th></tr><tr><td>one</td><td>two</td></tr></table>
      </article></main>
      <nav>Home About</nav><div class="ads">Buy now</div>""",
      "<title>Quiet Cost</title><meta property='og:title' content='Quiet Cost'>",
    )
    val out = ArticleExtractor.extract(page, "https://example.com/quiet")
    assertTrue(out.title.contains("Quiet"))
    assertTrue(out.markdown.contains("First real paragraph"))
    assertTrue(out.markdown.contains("What changes"))
    assertTrue(out.markdown.contains("One thing"))
    assertTrue(out.markdown.contains("source link"))
    assertTrue(out.markdown.contains("let x = 1"))
    assertTrue(out.markdown.contains("one") && out.markdown.contains("two"))
    assertFalse(out.markdown.contains("Buy now"))
    assertFalse(out.markdown.contains("Home About"))
    // Output remains parseable text-only markdown.
    val blocks = ArticleParser.parse(out.markdown)
    assertTrue(blocks.any { it is ArticleBlock.Heading })
    assertTrue(blocks.any { it is ArticleBlock.BulletList })
    assertTrue(blocks.any { it is ArticleBlock.CodeBlock })
    assertTrue(blocks.any { it is ArticleBlock.Table })
  }

  @Test fun germanArticleExtracts() {
    val page = html(
      """<article><h1>Die stille Kunst des Lesens</h1>
        <p>Erster Absatz über Aufmerksamkeit und Gedächtnis, mit einem konkreten Beispiel aus dem Alltag und einer Quelle.</p>
        <p>Zweiter Absatz vertieft das Argument mit Zahlen, Zitaten und einem <a href="https://example.com/q">weiterführenden Link</a>.</p>
        <p>Dritter Absatz schließt mit einer Einladung zum langsamen Lesen und wiederholten Besuchen der Bibliothek.</p></article>""",
    )
    val out = ArticleExtractor.extract(page, "https://example.com/de-lesen")
    assertTrue(out.wordCount >= 30)
    assertTrue(out.markdown.contains("stille Kunst") || out.markdown.contains("Aufmerksamkeit"))
  }

  @Test fun navigationHeavyPageKeepsArticleDropsChrome() {
    val page = html(
      """<header><nav>Home News Sport Wetter Kontakt Impressum Datenschutz Abo Anmelden</nav></header>
      <div class="sidebar">Meistgelesen: eins zwei drei Newsletter anmelden</div>
      <main><article><h1>Echte Geschichte</h1>
      <p>Erster Absatz der echten Geschichte mit mehr als dreißig Wörtern insgesamt über mehrere Sätze hinweg erzählt und mit Details angereichert.</p>
      <p>Zweiter Absatz der echten Geschichte mit weiteren Details, Namen, Zahlen wie 42 und einem Zitat: „Langsam lesen hilft.“</p>
      <p>Dritter Absatz rundet die Geschichte ab und verweist auf weitere Entwicklungen in der kommenden Woche.</p>
      </article></main>
      <footer>Copyright 2026. Alle Rechte vorbehalten.</footer>""",
    )
    val out = ArticleExtractor.extract(page, "https://example.com/story")
    assertTrue(out.markdown.contains("Echte Geschichte") || out.markdown.contains("echten Geschichte"))
    assertFalse(out.markdown.contains("Impressum"))
  }

  @Test fun loginPageIsRejectedNeverPassedAsArticle() {
    val page = html(
      """<main><h1>Sign in</h1><form><input type="password" name="pw">
        <p>Please sign in to continue reading this subscriber story.</p></form></main>""",
    )
    try {
      ArticleExtractor.extract(page, "https://example.com/login")
      fail("login must be rejected")
    } catch (e: ArticleExtractor.ExtractionFailed) {
      assertTrue(e.errorCode == "login_wall" || e.errorCode == "too_short")
    }
  }

  @Test fun chromeHeavyLoginPageReportsLoginWallPrecisely() {
    // GitHub-style: password form + login-titled page buried in hundreds of
    // footer/nav words. Precision matters — the label, not the fallback.
    val footer = "<p>" + "Footer link text privacy terms status docs contact. ".repeat(30) + "</p>"
    val page = html(
      """<main><h1>Sign in to ExampleHub</h1>
        <form action="/session"><input type="text" name="login"><input type="password" name="pw">
        <input type="submit" value="Sign in"></form>$footer</main>""",
      "<title>Sign in to ExampleHub · Build software better, together</title>",
    )
    try {
      ArticleExtractor.extract(page, "https://example.com/session")
      fail("login must be rejected")
    } catch (e: ArticleExtractor.ExtractionFailed) {
      assertEquals("login_wall", e.errorCode)
    }
  }

  @Test fun articleMentioningLoginWithoutPasswordFieldStillExtracts() {
    val page = html(
      """<article><h1>Login Security Basics</h1>
        <p>Logging in safely matters: use a password manager and turn on two-factor authentication everywhere you can.</p>
        <p>Security keys beat codes by text message, and recovery codes belong on paper in a drawer.</p>
        <p>Adopt these habits once and every future sign in gets calmer and safer for good.</p></article>""",
    )
    val out = ArticleExtractor.extract(page, "https://example.com/security")
    assertTrue(out.markdown.contains("password manager"))
  }
  @Test fun paywallPageIsRejected() {
    val page = html(
      """<div class="paywall"><p>Subscribe to continue reading.</p></div>
        <p>Short teaser with few words.</p>""",
    )
    try {
      ArticleExtractor.extract(page, "https://example.com/paywall")
      fail("paywall must be rejected")
    } catch (_: ArticleExtractor.ExtractionFailed) { }
  }

  @Test fun jsOnlyShellIsRejectedWithGuidance() {
    val page = html(
      """<div id="root"></div><div id="app"></div>""",
      "<title>App</title><script src='/a.js'></script><script src='/b.js'></script>",
    )
    try {
      ArticleExtractor.extract(page, "https://example.com/app")
      fail("js-only must be rejected")
    } catch (e: ArticleExtractor.ExtractionFailed) {
      assertEquals("js_only", e.errorCode)
    }
  }

  @Test fun emptyPageIsRejected() {
    try {
      ArticleExtractor.extract(html("<p></p>"), "https://example.com/empty")
      fail("empty must be rejected")
    } catch (_: ArticleExtractor.ExtractionFailed) { }
  }

  @Test fun malformedHtmlStillExtracts() {
    val raw = ("<html><body><article><h1>Broken" +
      "<p>First paragraph without closing tags and with enough words to pass the article threshold for testing purposes here.</p>" +
      "<p>Second paragraph bold without close and more filler words to reach thirty words total across sentences and additional context.</p>" +
      "<p>Third paragraph adds even more content about reading, attention, memory, and slow thoughtful consumption of long texts.</p>").toByteArray()
    val out = ArticleExtractor.extract(raw, "https://example.com/broken")
    assertTrue(out.wordCount >= 30)
  }

  @Test fun sharedNoiseFixtureMatchesExpectations() {
    val candidates = listOf(
      File("../../shared/fixtures/generic/article-noise.html"),
      File("../shared/fixtures/generic/article-noise.html"),
      File("shared/fixtures/generic/article-noise.html"),
      File("/Users/schober/Projects/Nostr Reader/shared/fixtures/generic/article-noise.html"),
    )
    val fixture = candidates.firstOrNull { it.exists() } ?: return // CI without checkout: embedded cases above cover.
    val expectedCandidates = listOf(
      File("../../shared/fixtures/generic/expected.md"),
      File("/Users/schober/Projects/Nostr Reader/shared/fixtures/generic/expected.md"),
    )
    val out = ArticleExtractor.extract(fixture.readBytes(), "https://example.com/noise")
    assertTrue(out.markdown.contains("First real paragraph"))
    assertTrue(out.markdown.contains("Second paragraph"))
    assertTrue(out.markdown.contains("What changes"))
    assertFalse(out.markdown.contains("Buy now"))
    val expected = expectedCandidates.firstOrNull { it.exists() }?.readText()
    if (expected != null) {
      for (line in expected.lines().filter { it.isNotBlank() }.take(6)) {
        val snippet = line.replace(Regex("^#+\\s+"), "").replace(Regex("[\\[\\]()|]"), "").trim().split(Regex("\\s+")).take(4).joinToString(" ")
        if (snippet.length > 12) assertTrue("missing: $snippet", out.markdown.contains(snippet.split(" ").first()))
      }
    }
  }
}
