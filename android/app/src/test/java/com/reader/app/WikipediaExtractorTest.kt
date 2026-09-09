package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden corpus for encyclopedia-style pages, grounded against the live
 * `en.wikipedia.org/wiki/Reading` Parsoid HTML (verified 2026-09-09):
 * toolbar tabs inside `<main>`, revision dialog, tagline, hatnotes, TOC,
 * edit links, 100+ inline citations, reference list, navbox, category footer,
 * infobox and See-also/References/External-links appendices.
 */
class WikipediaExtractorTest {
  private fun wikiPage(
    h1: String = "Reading",
    ogTitle: String = "Reading - Wikipedia",
    appendixHeadings: List<Pair<String, String>> = listOf(
      "See_also" to "See also",
      "References" to "References",
      "External_links" to "External links",
    ),
  ): ByteArray {
    val prose = "<p>" + "Core sentence about reading with meaning and substance. ".repeat(14) + "</p>"
    val appendix = appendixHeadings.joinToString("") { (id, label) ->
      """<section><div class="mw-heading mw-heading2"><h2 id="$id">$label</h2><span class="mw-editsection"><span class="mw-editsection-bracket">[</span>edit<span class="mw-editsection-bracket">]</span></span></div><ul><li><a href="https://example.com/x">Link farm entry one</a></li><li><a href="https://example.com/y">Link farm entry two</a></li></ul></section>"""
    }
    return (
      "<!doctype html><html><head><title>$ogTitle</title>" +
        "<meta property='og:title' content='$ogTitle'></head><body>" +
        "<main id=\"content\" class=\"mw-body\">" +
        "<header class=\"mw-body-header vector-page-titlebar\">" +
        "<nav class=\"vector-page-toolbar\"><div id=\"p-associated-pages\" class=\"vector-menu vector-menu-tabs\">" +
        "<ul><li><span>Article</span></li><li><span>Talk</span></li></ul></div></nav>" +
        "<h1 class=\"firstHeading mw-first-heading\"><span class=\"mw-page-title-main\">$h1</span></h1></header>" +
        "<div id=\"siteSub\">From Wikipedia, the free encyclopedia</div>" +
        "<div class=\"cdx-dialog\" id=\"mw-fr-revision-details\">This is the <a href=\"https://example.com/log\">latest accepted revision</a>, reviewed on 1 September 2026.</div>" +
        "<a class=\"mw-jump-link\" href=\"#bodyContent\">Jump to content</a>" +
        "<div role=\"note\" class=\"hatnote navigation-not-searchable\">For the town, see <a href=\"https://example.com/town\">Town</a>.</div>" +
        "<div class=\"mw-parser-output\">" +
        "<section><p><b>$h1</b> is the process of taking in meaning. " + "It rewards slow attention over skimming. ".repeat(10) + "<sup class=\"mw-ref reference\"><a href=\"#cite_note-1\">[1]</a></sup></p>" +
        "<h2>History<span class=\"mw-editsection\"><span class=\"mw-editsection-bracket\">[</span>edit<span class=\"mw-editsection-bracket\">]</span></span></h2>" +
        prose + prose +
        "<table class=\"infobox\"><tr><th>Field</th><td>Value</td></tr></table>" +
        "<h2>Methods</h2>" + prose +
        "<ul><li>Skimming</li><li>Close reading</li></ul>" +
        "<table class=\"wikitable\"><tr><th>Method</th><th>Speed</th></tr><tr><td>Skim</td><td>Fast</td></tr><tr><td>Study</td><td>Slow</td></tr></table>" +
        "</section>" +
        "<div class=\"mw-references-wrap mw-references-columns\"><ol class=\"mw-references references\">" +
        "<li><span class=\"mw-reference-text reference-text\">Citation body one with <a class=\"mw-cite-backlink\" href=\"#cite_ref-1\">^</a> backlink.</span></li>" +
        "<li><span class=\"mw-reference-text reference-text\">Citation body two.</span></li></ol></div>" +
        "<table class=\"navbox nowraplinks\"><tr><th class=\"navbox-title\">Navigation header</th></tr><tr><td class=\"navbox-list\"><a href=\"https://example.com/a\">Alpha</a> <a href=\"https://example.com/b\">Beta</a></td></tr></table>" +
        appendix +
        "</div>" +
        "<div id=\"catlinks\" class=\"catlinks\"><a href=\"https://example.com/cat\">Category dump entry</a></div>" +
        "</main></body></html>"
      ).toByteArray(Charsets.UTF_8)
  }

  @Test fun chromeStrippedCoreKept() {
    val out = ArticleExtractor.extract(wikiPage(), "https://en.wikipedia.org/wiki/Reading")
    val md = out.markdown
    // Chrome gone.
    for (gone in listOf("Article\n\n- Talk", "latest accepted revision", "From Wikipedia", "Jump to content", "For the town", "edit", "[1]", "Citation body", "^", "Navigation header", "Category dump", "Field", "Value", "Link farm")) {
      assertFalse("must not contain: $gone", md.contains(gone))
    }
    // Core kept: prose, real headings, real list, real data table.
    assertTrue(md.contains("taking in meaning"))
    assertTrue(md.contains("## History"))
    assertTrue(md.contains("## Methods"))
    assertTrue(md.contains("Skimming") && md.contains("Close reading"))
    assertTrue(md.contains("Method") && md.contains("Skim"))
    val blocks = ArticleParser.parse(md)
    assertTrue(blocks.any { it is ArticleBlock.Heading })
    assertTrue(blocks.any { it is ArticleBlock.Table })
  }

  @Test fun titleSuffixDeduped() {
    val out = ArticleExtractor.extract(wikiPage(), "https://en.wikipedia.org/wiki/Reading")
    assertEquals("Reading", out.title)
    assertFalse(out.markdown.contains("# Reading - Wikipedia"))
  }

  @Test fun wordCountInCoreBand() {
    val out = ArticleExtractor.extract(wikiPage(), "https://en.wikipedia.org/wiki/Reading")
    // Core prose is ~450 words; chrome/appendix previously pushed Wikipedia
    // payloads into the thousands. Band guards both directions.
    assertTrue("words=${out.wordCount}", out.wordCount in 200..900)
    assertEquals(out.wordCount, ReaderCore.wordCount(out.markdown))
  }

  @Test fun germanAppendixPruned() {
    val page = wikiPage(
      h1 = "Lesen",
      ogTitle = "Lesen – Wikipedia",
      appendixHeadings = listOf("Siehe_auch" to "Siehe auch", "Weblinks" to "Weblinks", "Einzelnachweise" to "Einzelnachweise"),
    )
    val out = ArticleExtractor.extract(page, "https://de.wikipedia.org/wiki/Lesen")
    // En-dash suffix is not an exact H1 prefix match, so the og title stands —
    // but appendix link farms must still be gone.
    assertFalse(out.markdown.contains("Link farm"))
    assertFalse(out.markdown.contains("Siehe auch"))
    assertFalse(out.markdown.contains("Einzelnachweise"))
    assertTrue(out.markdown.contains("taking in meaning"))
  }

  @Test fun scorerPrefersProseOverNavbox() {
    val html = (
      "<html><body><div id=\"prose\">" + "<p>Real argument sentence with commas, clauses, and conclusions. ".repeat(12) + "</p></div>" +
        "<div id=\"nav\"><table class=\"navbox\"><tr><td><a href=\"https://example.com/a\">Alpha</a> <a href=\"https://example.com/b\">Beta</a> <a href=\"https://example.com/c\">Gamma</a></td></tr></table></div>" +
        "</body></html>"
      ).toByteArray()
    val doc = org.jsoup.Jsoup.parse(String(html, Charsets.UTF_8))
    val prose = doc.selectFirst("#prose")!!
    val nav = doc.selectFirst("#nav")!!
    assertTrue(ArticleExtractor.scoreCandidate(prose) > ArticleExtractor.scoreCandidate(nav))
  }

    @Test fun appendixFallbackWithoutSectionWrapper() {    val html = (
      "<html><body><article><h1>T</h1><p>" + "Core words here with substance. ".repeat(16) + "</p>" +
        "<h2>See also</h2><ul><li><a href=\"https://example.com/a\">Elsewhere</a></li></ul>" +
        "<p>Trailing tail.</p></article></body></html>"
      ).toByteArray()
    val out = ArticleExtractor.extract(html, "https://example.com/t")
    assertFalse(out.markdown.contains("Elsewhere"))
    assertFalse(out.markdown.contains("See also"))
    assertFalse(out.markdown.contains("Trailing tail"))
    assertTrue(out.markdown.contains("Core words"))
  }

  @Test fun wordpressChromeStrippedCoreKept() {
    val prose = "<p>" + "Argument sentence about connecting ideas across fields. ".repeat(16) + "</p>"
    val html = (
      "<!doctype html><html><head><title>Post - Blog</title></head><body>" +
        "<article><header class=\"entry-header\"><h1>Post</h1>" +
        "<div class=\"entry-meta\"><time>Aug 26, 2026</time> • <a href=\"https://example.com/cat\">Posts</a> • 4 min read</div>" +
        "<div class=\"wp-block-group\"><div class=\"wp-block-post-date\"><time>Aug 26, 2026</time></div>" +
        "<div class=\"taxonomy-category wp-block-post-terms\"><a href=\"https://example.com/cat\">Posts</a></div>" +
        "<p>•</p><p>4 min read</p></div></header>" +
        prose + prose +
        "<p><strong>Discover More Subjects</strong></p>" +
        "<p class=\"wp-block-tag-cloud\"><a href=\"https://example.com/t/z\">Zeta</a></p>" +
        "<div class=\"tags-links\"><a href=\"https://example.com/t/a\">Alpha</a> <a href=\"https://example.com/t/b\">Beta</a></div>" +
        "<div class=\"sharedaddy\"><h3>Like this:</h3></div>" +
        "<div class=\"wp-block-jetpack-subscriptions__container\"><h3>Discover more</h3>" +
        "<p>Type your email…</p><a href=\"https://example.com/s\">Subscribe</a></div>" +
        "<div class=\"wp-block-jetpack-sharing-buttons\"><a href=\"https://example.com/?share=x\">Share on X</a></div>" +
        "<div class=\"wp-block-group\"><h3>Discover more from Blog</h3>" +
        "<p>Subscribe to get the latest posts to your email.</p>" +
        "<form><input type=\"email\" name=\"email\"><button>Subscribe</button></form>" +
        "<p>No spam ever. Unsubscribe anytime.</p></div>" +
        "<footer class=\"entry-footer\">Tagged with <a href=\"https://example.com/t/c\">Gamma</a></footer>" +
        // CTA card nested in a shared content wrapper: the wrapper (and the
        // prose around it) must survive, only the card goes.
        "<div class=\"content-wrap\"><p>Wrapper prose before. " + "Filler words keep this block substantial. ".repeat(10) + "</p>" +
        "<div class=\"wp-block-group\"><h3>Discover more from Blog</h3>" +
        "<p>Subscribe to get the latest posts.</p></div>" +
        "<p>Wrapper prose after. " + "More filler words round out the section. ".repeat(10) + "</p></div>" +
        "<div class=\"jp-relatedposts\"><h3>Discover more</h3><a href=\"https://example.com/r\">Related</a></div>" +
        "<nav class=\"post-navigation\"><a href=\"https://example.com/prev\">Previous post</a></nav>" +
        "<div class=\"author-box\">About the author: writes things.</div>" +
        "<div id=\"comments\"><ol class=\"comment-list\"><li>Great post!</li></ol>" +
        "<div id=\"respond\"><h3>Leave a Reply</h3></div></div>" +
        "</article></body></html>"
      ).toByteArray()
    val out = ArticleExtractor.extract(html, "https://example.com/post")
    val md = out.markdown
    for (gone in listOf("4 min read", "Like this", "Discover more", "Discover More Subjects", "Previous post", "About the author", "Great post", "Leave a Reply", "Alpha", "Beta", "Zeta", "Type your email", "Share on X", "No spam ever", "Gamma", "Tagged with")) {
      assertFalse("must not contain: $gone", md.contains(gone))
    }
    assertFalse(Regex("""(?m)^[•·|]$""").containsMatchIn(md))
    assertTrue(md.contains("connecting ideas"))
    assertTrue(md.contains("Wrapper prose before"))
    assertTrue(md.contains("Wrapper prose after"))
    assertTrue(out.wordCount in 50..600)
  }

  /**
   * Selector vitality: every stripping-selector family must actually remove
   * its marker. (Jsoup 1.17.2 silently matches nothing for the CSS4
   * `[attr*=v i]` flag, which once left a whole selector generation dead.)
   */
  @Test fun everyStripSelectorFamilyRemovesItsMarker() {
    val markers = mapOf(
      "cookie" to "M-CookieBanner",
      "consent" to "M-ConsentWall",
      "gdpr" to "M-GdprNotice",
      "advert" to "M-AdvertBox",
      "newsletter" to "M-NewsletterBox",
      "signup" to "M-SignupBox",
      "comment" to "M-CommentThread",
      "related-article" to "M-RelatedArticle",
      "recommend" to "M-RecommendBox",
      "sidebar" to "M-SideBar",
      "widget" to "M-WidgetBox",
      "sharing" to "M-SharingRow",
      "subscri" to "M-SubscriptionsForm",
      "jetpack" to "M-JetpackBlock",
    )
    val chrome = markers.entries.joinToString("") { (family, marker) ->
      "<div class=\"x-$family-y\">$marker</div>"
    }
    val prose = "<p>" + "Core sentence that must survive every filter. ".repeat(16) + "</p>"
    val html = ("<!doctype html><html><head><title>Vitality</title></head><body>" +
      "<main><article><h1>Vitality</h1>$prose$prose$chrome</article></main></body></html>").toByteArray()
    val md = ArticleExtractor.extract(html, "https://example.com/vitality").markdown
    for ((_, marker) in markers) {
      assertFalse("selector family dead, marker survived: $marker", md.contains(marker))
    }
    assertTrue(md.contains("Core sentence"))
  }
}
