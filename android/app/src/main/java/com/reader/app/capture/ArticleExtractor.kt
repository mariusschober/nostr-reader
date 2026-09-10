package com.reader.app.capture

import com.reader.app.core.HtmlMarkdown
import com.reader.app.core.ReaderCore
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Static article extraction on the existing Jsoup + HtmlMarkdown foundation.
 *
 * Deliberately NOT [com.reader.app.sync.Ingest.importHtml] (which converts the
 * whole page including nav/ads). This picks a main-content candidate with a
 * small Readability-inspired heuristic, strips noise conservatively (never
 * inside <main>/<article> unless clearly noise), converts with the safe
 * text-only [HtmlMarkdown] converter, and rejects empty / login / paywall /
 * interstitial / JS-only pages so they become honestly-labeled link fallbacks
 * instead of fake articles.
 *
 * No JavaScript is executed. No subresources are fetched.
 */
object ArticleExtractor {
  data class ExtractedArticle(
    val title: String,
    val markdown: String,
    val author: String?,
    val language: String?,
    val wordCount: Int,
  )

  /** Thrown when static extraction cannot produce a usable article. */
  class ExtractionFailed(code: String, message: String) : Exception("$code: $message") {
    val errorCode: String = code
  }

  // Parser-complexity guards.
  const val MAX_ELEMENTS = 60_000
  const val MIN_WORDS_FOR_ARTICLE = 30

  // NOTE: Jsoup 1.17.2 parses the CSS4 case-insensitive flag (`[attr*=v i]`)
  // without error but matches NOTHING (verified against the bundled jar).
  // All contains-selectors below are therefore plain lowercase: publisher
  // class/id attributes are lowercase in practice, and every selector here
  // is covered by a unit test that fails if it ever goes dead.
  private val noiseSelectors = listOf(
    "script", "style", "noscript", "template", "iframe", "object", "embed",
    "nav", "footer",
    "[hidden]", "[aria-hidden='true']",
    "[class*=cookie], [id*=cookie]",
    "[class*=consent], [id*=consent]",
    "[class*=gdpr]",
    ".ads", "[class*=advert], [id*=advert]",
    "[class*=newsletter], [id*=newsletter]",
    "[class*=signup], [class*=subscribe]",
    "[class*=comment], [id*=comment]",
    "[class*=related-article], [class*=recommend]",
    "[class*=sidebar], [class*=widget]",
    "[role='banner']", "[role='contentinfo']", "[role='complementary']",
  )

  private val positiveHint = Regex("""article|content|post|entry|main|story|blog|text|body""", RegexOption.IGNORE_CASE)
  private val negativeHint = Regex("""nav|sidebar|comment|footer|header|menu|advert|promo|related|recommend|newsletter|signup|subscribe|cookie|consent|widget|share|social|breadcrumb|pagination""", RegexOption.IGNORE_CASE)

  /**
   * Encyclopedia/CMS chrome stripped unconditionally — even inside
   * `<main>`/`<article>`, where the generic [stripNoise] guard would otherwise
   * keep everything. Every selector below was grounded against the live
   * `en.wikipedia.org/wiki/Reading` Parsoid HTML (2026-09-09): the toolbar
   * with Article/Talk tabs, revision dialog, tagline, hatnotes, TOC, edit
   * links, citations, reference lists, navboxes, category footer and print
   * footer all live inside `<main id="content">` and all survived v1.
   */
  private val chromeSelectors = listOf(
    // Page toolbar with namespace/view tabs (inside <main> on MediaWiki).
    ".vector-page-toolbar", "#p-associated-pages", "#p-views", "#p-cactions",
    ".vector-menu-tabs",
    // Revision/version dialogs and indicators.
    "#mw-fr-revision-details", "[class*=\"mw-fr-\"]",
    // Tagline, jump links, table of contents.
    "#siteSub", ".mw-jump-link", ".toc", "#toc", "[href*=\"#toc\"]",
    "nav.vector-toc-landmark", "#mw-panel-toc-list", ".vector-toc",
    // Notices, disambiguation previews, print-only content.
    ".hatnote", ".shortdescription", ".noprint", ".ambox", ".sistersitebox",
    ".printfooter",
    // Reference data boxes (not article prose; decided: drop, not condense).
    "table.infobox", ".portalbox", ".authority-control",
    // Navigation boxes and sidebars.
    "table.navbox", "div.navbox", ".navbox", "table.sidebar",
    // Category footer link dump.
    "#catlinks", "#mw-normal-catlinks", "#mw-hidden-catlinks", ".catlinks",
    // Per-section edit controls.
    "span.mw-editsection",
    // Inline citation markers and their backlinks (decided: drop from prose;
    // the reference list itself is dropped below, not kept as endnotes).
    "sup.mw-ref", "sup.reference", "a[href*=\"cite_note\"]", "a[href*=\"cite_ref\"]",
    "span.cite-bracket", ".mw-cite-backlink",
    // Reference lists (bibliography tails, not the argument).
    "div.mw-references-wrap", "ol.mw-references", "ol.references",
    // Post footers (tag/category lines) and post-navigation wrappers that
    // are not <nav> elements (those already die in conversion).
    ".entry-footer", "[class*=post-navigation]",
    // Gutenberg post-meta blocks: date, category/tag term lines (the title
    // block itself is never touched).
    ".wp-block-post-date", ".wp-block-post-terms",
    "div.taxonomy-category", "div.taxonomy-post_tag",
    // WordPress/blog chrome (grounded on a live WordPress post whose v1
    // payload ended in a tag cloud plus Like/related/comment widgets):
    // header meta (date/category/read-time live here, not in the prose),
    // tag clouds and tag footers, share/like widgets, related-posts
    // carousels, prev/next navigation, comment threads and reply forms,
    // author bio boxes.
    ".entry-meta", ".post-meta", ".entry-header-meta",
    ".tagcloud", ".wp-block-tag-cloud", ".tags-links",
    ".sharedaddy", ".sd-like", ".sd-sharing",
    // Jetpack/WordPress.com widget blocks (likes, subscriptions, sharing,
    // related): class names contain neither "share" helpers above nor the
    // generic newsletter/signup markers ("subscriptions" does not contain
    // "subscribe"), so match the stems explicitly.
    "[class*=sharing]", "[class*=subscri]", "[class*=jetpack]",
    ".jp-relatedposts", ".related-posts", "#related-posts",
    ".post-navigation", ".nav-links",
    "#respond", ".comment-respond", "#comments", ".comments-area",
    ".author-box", ".author-bio", ".author-info",
    // Observed on web.dev: publisher controls nested inside the article/H1.
    // Remove these identified widgets before extracting the title and prose.
    ".devsite-banner", ".devsite-article-meta", ".devsite-page-title-meta",
    "devsite-actions", "devsite-toc", "devsite-feedback",
  )

  /**
   * Appendix sections pruned from the chosen candidate: link dumps and
   * metadata that follow the article proper. Matched on heading id or text,
   * English and German.
   */
  private val appendixHeading = Regex(
    """^(see also|further reading|external links?|references?|notes?|footnotes?|bibliography|sources?|cited works|works cited|gallery|further literature)$""",
    RegexOption.IGNORE_CASE,
  )
  private val appendixHeadingDe = Regex(
    """^(siehe auch|weblinks?|einzelnachweise|literatur|quellen?|anmerkungen|fußnoten|fussnoten|bilder|galerie)$""",
    RegexOption.IGNORE_CASE,
  )

  fun extract(htmlBytes: ByteArray, pageUrl: String): ExtractedArticle {
    val html = try {
      String(htmlBytes, Charsets.UTF_8)
    } catch (_: Exception) {
      throw ExtractionFailed("bad_encoding", "This page could not be decoded")
    }
    if (html.isBlank()) throw ExtractionFailed("empty_page", "This page has no readable content")
    val doc = Jsoup.parse(html, pageUrl)
    val elements = doc.allElements.size
    if (elements > MAX_ELEMENTS) throw ExtractionFailed("page_too_complex", "This page is too complex to capture; open the original instead")
    // JS-shell signals must be captured before noise stripping removes scripts.
    val preStripScripts = doc.select("script").size
    val preStripMounts = doc.select("#root, #app, [id*=__next], [ng-app], [data-reactroot]").size
    stripNoise(doc)
    stripChrome(doc)
    detectBlockedPage(doc)

    val title = extractTitle(doc, pageUrl)
    val author = extractAuthor(doc)
    val language = doc.selectFirst("html")?.attr("lang")?.trim()?.take(16)?.ifBlank { null }
      ?: doc.selectFirst("meta[property='og:locale']")?.attr("content")?.trim()?.take(16)?.ifBlank { null }

    val candidate = pickCandidate(doc)
    pruneAppendix(candidate)
    pruneNewsletterBlocks(candidate)
    val markdown = HtmlMarkdown.convert(candidate.html())
    val canonical = ReaderCore.canonicalize(markdown)
    // Script-aware floor: spaceless CJK/Thai prose counts characters, not
    // whitespace words (a whole Chinese article otherwise reads as ~1 word).
    if (!ReaderCore.hasEnoughText(canonical)) {
      // Distinguish JS-only shells from thin-but-real pages for guidance.
      val bodyText = doc.body()?.text().orEmpty()
      if (isLikelyJsOnly(preStripScripts, preStripMounts, bodyText)) {
        throw ExtractionFailed("js_only", "This page needs JavaScript to show its article; Reader kept the link — open the original or share selected text")
      }
      throw ExtractionFailed("too_short", "No confident article text was found; Reader kept the link instead")
    }
    // Interstitial/login text that survived stripping but dominates the top
    // must not pass as an article.
    if (looksLikeLoginWall(canonical) && ReaderCore.effectiveWords(canonical) < 120) {
      throw ExtractionFailed("login_wall", "This page looks like a login or paywall; Reader kept the link instead")
    }
    val withTitle = if (canonical.contains(title) || title == pageUrl) canonical else "# $title\n\n$canonical"
    val finalCanonical = ReaderCore.canonicalize(withTitle)
    return ExtractedArticle(
      title = title,
      markdown = finalCanonical,
      author = author,
      language = language,
      wordCount = ReaderCore.effectiveWords(finalCanonical),
    )
  }

  private fun stripNoise(doc: Document) {
    for (sel in noiseSelectors) {
      try {
        for (el in doc.select(sel).toList()) {
          // Never strip inside the main article element itself unless the
          // element itself is clearly noise (cookie/ad/newsletter/comment).
          val insideMain = el.parents().any { it.tagName() == "article" || it.tagName() == "main" }
          if (insideMain) {
            val cls = (el.className() + " " + el.id()).lowercase()
            if (!Regex("""cookie|consent|advert|newsletter|signup|subscri|comment|share""").containsMatchIn(cls) &&
              el.tagName() !in setOf("script", "style", "noscript", "template", "iframe")
            ) continue
          }
          el.remove()
        }
      } catch (_: Exception) { /* next selector */ }
    }
  }

  /** Unconditional chrome removal: no inside-main exemption (see [chromeSelectors]). */
  private fun stripChrome(doc: Document) {
    for (sel in chromeSelectors) {
      try {
        for (el in doc.select(sel).toList()) el.remove()
      } catch (_: Exception) { /* next selector */ }
    }
  }

  /**
   * Remove appendix sections (references, see-also, external links, …) from
   * the chosen candidate. A matching `h2` removes its enclosing `section`
   * when that section lies inside the candidate; otherwise the heading and
   * everything up to the next `h1`/`h2` is removed.
   */
  internal fun pruneAppendix(candidate: Element) {
    for (heading in candidate.select("h1, h2").toList()) {
      val label = heading.id().replace('_', ' ').trim().ifBlank { heading.text().trim() }
      if (!appendixHeading.matches(label) && !appendixHeadingDe.matches(label)) continue
      var section: Element? = heading
      while (section != null && section != candidate && section.tagName() != "section") {
        section = section.parent()
      }
      if (section != null && section != candidate && section != heading) {
        section.remove()
      } else {
        var next = heading.nextElementSibling()
        heading.remove()
        while (next != null && next.tagName() != "h1" && next.tagName() != "h2") {
          val following = next.nextElementSibling()
          next.remove()
          next = following
        }
      }
    }
    // Paragraph-style section labels ("See also" / "Discover More Subjects"
    // as bold lines, not headings) plus immediately following tag lists.
    for (label in candidate.select("p").toList()) {
      val text = label.text().trim()
      val strong = label.selectFirst("strong")?.text()?.trim().orEmpty()
      val key = if (strong.isNotEmpty() && strong.length >= text.length - 2) strong else text
      if (!appendixHeading.matches(key) && !appendixHeadingDe.matches(key) &&
        !key.equals("discover more subjects", ignoreCase = true)
      ) continue
      var next = label.nextElementSibling()
      label.remove()
      while (next != null && next.tagName() in setOf("ul", "ol")) {
        val following = next.nextElementSibling()
        next.remove()
        next = following
      }
    }
    // Micro-chrome paragraphs: read-time lines and lone separator bullets.
    // Never prose: a paragraph whose entire text is "4 min read" or "•".
    val readTime = Regex("""^\d+\s*(min(ute)?s?\s*read|minuten\s*lesezeit)$""", RegexOption.IGNORE_CASE)
    for (p in candidate.select("p").toList()) {
      val text = p.text().trim()
      if (readTime.matches(text) || text in setOf("•", "·", "|")) p.remove()
    }
  }

  /**
   * Remove newsletter/subscribe CTA cards whose containers carry only generic
   * block classes (e.g. WordPress.com `wp-block-group` cards with
   * "Discover more from X / Subscribe to get the latest posts / Type your
   * email"). Two conjunction-guarded rules, both requiring an email-capture
   * form or explicit subscribe copy inside the same card, so a genuine
   * article section about newsletters is never cut:
   * 1. any card containing an email input is removed entirely;
   * 2. a "discover more from …" heading removes its enclosing card when that
   *    card carries subscribe/email/unsubscribe/no-spam copy.
   */
  private val newsletterHeading = Regex("""^discover more from\b.*""", RegexOption.IGNORE_CASE)
  private val newsletterCopy = Regex("""unsubscribe|no spam|type your email|get the latest posts""", RegexOption.IGNORE_CASE)

  internal fun pruneNewsletterBlocks(candidate: Element) {
    val candidateLen = candidate.text().length.coerceAtLeast(1)
    // Innermost small card around [el]: at most two div/section levels, and
    // never a container holding most of the article. (Climbing to the
    // outermost match gutted a live post whose CTA card shared nested
    // content wrappers with the prose.)
    fun smallBox(el: Element): Element? {
      var box: Element = el
      repeat(2) {
        val parent = box.parent() ?: return@repeat
        if (parent == candidate) return@repeat
        if (parent.tagName() != "div" && parent.tagName() != "section") return@repeat
        if (parent.text().length > candidateLen * 0.5) return@repeat
        box = parent
      }
      return box.takeIf { it != candidate }
    }
    // A following sibling is CTA ballast (not article prose) while it is a
    // list, a form container, a short line, or link-dense. Long prose
    // stops the sweep so a mid-article card can never eat the article.
    fun isBallast(el: Element): Boolean {
      if (el.tagName() in setOf("ul", "ol", "form")) return true
      if (el.select("form").isNotEmpty()) return true
      val text = el.text()
      if (text.split(Regex("\\s+")).count { it.isNotBlank() } < 40) return true
      return linkDensity(el) >= 0.3
    }
    fun removeSectionFrom(heading: Element) {
      var next = heading.nextElementSibling()
      heading.remove()
      while (next != null && next.tagName() !in setOf("h1", "h2", "h3", "h4") && isBallast(next)) {
        val following = next.nextElementSibling()
        next.remove()
        next = following
      }
    }
    // Rule 1: email-capture forms take their innermost small card when that
    // card carries a heading or subscribe copy; otherwise just the form.
    for (form in candidate.select("form").toList()) {
      if (form.select("input[type=email], input[name=email]").isEmpty()) continue
      val box = smallBox(form)
      if (box != null && box != form &&
        (box.select("h1, h2, h3, h4").isNotEmpty() || newsletterCopy.containsMatchIn(box.text()))
      ) {
        box.remove()
      } else {
        form.remove()
      }
    }
    // Rule 2: "Discover more from X" headings drop their following ballast.
    // Never climbs into shared wrappers: only following siblings go.
    for (heading in candidate.select("h1, h2, h3, h4").toList()) {
      if (!newsletterHeading.matches(heading.text().trim())) continue
      removeSectionFrom(heading)
    }
  }

  private fun detectBlockedPage(doc: Document) {
    val bodyText = doc.body()?.text().orEmpty()
    val lower = bodyText.lowercase()
    val hasPassword = doc.select("input[type='password']").isNotEmpty()
    val hasPaywallMarker = doc.select(
      "[class*=paywall], [id*=paywall], [class*=subscription-required], [class*=metered]",
    ).isNotEmpty()
    val loginPhrases = listOf(
      "sign in to continue reading", "log in to continue reading",
      "sign in to read", "subscribe to continue reading",
      "subscribe to read", "to continue reading, please sign in",
      "this content is for subscribers", "please sign in",
      "bitte melden sie sich an", "bitte loggen sie sich ein",
      "weiterlesen nur mit abo", "nur für abonnenten",
    )
    val loginHits = loginPhrases.count { it in lower }
    // A password field is the strong signal: with a login phrase it was
    // already decisive, and with a login-titled page (h1/title like
    // "Sign in to GitHub") it is decisive regardless of how much footer
    // chrome surrounds the form. This labels precisely; it never bypasses —
    // the outcome is always the honest link fallback.
    val titleText = ((doc.title() ?: "") + " " + (doc.selectFirst("h1")?.text() ?: "")).lowercase()
    val loginTitle = listOf(
      "sign in", "log in", "login", "sign-in",
      "anmelden", "einloggen", "anmeldung",
      "se connecter", "connexion", "iniciar sesión",
    ).any { it in titleText }
    if (hasPassword && (loginHits >= 1 || loginTitle)) {
      throw ExtractionFailed("login_wall", "This page needs a login; Reader does not bypass access controls")
    }
    if (hasPaywallMarker && bodyText.split(Regex("\\s+")).size < 120) {
      throw ExtractionFailed("paywall", "This page is behind a paywall; Reader kept the link instead")
    }
    if (loginHits >= 2 && bodyText.split(Regex("\\s+")).size < 150) {
      throw ExtractionFailed("login_wall", "This page needs a login; Reader does not bypass access controls")
    }
  }

  private fun isLikelyJsOnly(preStripScripts: Int, preStripMounts: Int, bodyText: String): Boolean {
    if (ReaderCore.hasEnoughText(bodyText)) return false
    return preStripScripts >= 2 && preStripMounts >= 1
  }

  private fun looksLikeLoginWall(canonical: String): Boolean {
    val head = canonical.take(800).lowercase()
    return listOf("sign in", "log in", "subscribe", "paywall", "abo", "anmelden").any { it in head } &&
      Regex("""captcha|verif""").containsMatchIn(head)
  }

  internal fun extractTitle(doc: Document, pageUrl: String): String {
    val h1 = doc.selectFirst("main h1, article h1")?.text()?.trim().orEmpty()
      .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
    val og = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
    // Publisher titles routinely append the site name ("Reading - Wikipedia").
    // When the page's own H1 is a prefix of that (modulo the suffix), the H1
    // is the article title: preferring it avoids a duplicated
    // "# Reading - Wikipedia" heading above the body's own "# Reading".
    if (og.isNotBlank() && h1.isNotBlank()) {
      val suffix = Regex("""[\s\p{Z}]+[-—–|:][\s\p{Z}]+[^-—–|:]+$""").find(og)?.value.orEmpty()
      if (suffix.isNotEmpty() && og.removeSuffix(suffix).trim().equals(h1, ignoreCase = true)) {
        return h1.take(500)
      }
      return og.take(500)
    }
    if (og.isNotBlank()) return og.take(500)
    if (h1.isNotBlank()) return h1.take(500)
    val docTitle = doc.title()?.trim().orEmpty()
    if (docTitle.isNotBlank()) return docTitle.take(500)
    return pageUrl.take(500)
  }

  internal fun extractAuthor(doc: Document): String? {
    val meta = doc.selectFirst("meta[name='author']")?.attr("content")?.trim()
      ?: doc.selectFirst("meta[property='article:author']")?.attr("content")?.trim()
      ?: doc.selectFirst("[rel='author']")?.text()?.trim()
    return meta?.take(300)?.ifBlank { null }
  }

  internal fun pickCandidate(doc: Document): Element {
    // Prefer explicit semantic containers with real paragraph text.
    val article = doc.selectFirst("article")
    if (article != null && paragraphWords(article) >= MIN_WORDS_FOR_ARTICLE) return article
    val main = doc.selectFirst("main")
    if (main != null && paragraphWords(main) >= MIN_WORDS_FOR_ARTICLE) return main
    // Score block containers by paragraph text minus link-heavy penalty.
    var best: Element? = null
    var bestScore = Double.MIN_VALUE
    val candidates = doc.select("article, main, div, section").toList().take(500)
    for (el in candidates) {
      val score = scoreCandidate(el)
      if (score > bestScore) {
        bestScore = score
        best = el
      }
    }
    if (best != null && bestScore > 0) return best
    return doc.body() ?: doc
  }

  private fun paragraphWords(el: Element): Int {
    val text = el.select("p").joinToString(" ") { it.text() }
    return ReaderCore.effectiveWords(text)
  }

  internal fun scoreCandidate(el: Element): Double {
    var score = 0.0
    val cls = (el.className() + " " + el.id()).lowercase()
    if (positiveHint.containsMatchIn(cls)) score += 25
    if (negativeHint.containsMatchIn(cls)) score -= 50
    // Link density is computed for every block type, not just paragraphs:
    // appendix link-farms (See also / External links lists), navboxes and
    // reference lists are link-dense <ul>/<ol>/<table> element and previously
    // earned a flat bonus instead of a penalty.
    score -= linkDensity(el) * 18.0
    val paragraphs = el.select("> p, p")
    for (p in paragraphs.take(50)) {
      val text = p.text()
      // Script-aware: spaceless CJK/Thai paragraphs carry meaning per
      // character, not per whitespace token.
      val effective = ReaderCore.effectiveWords(text)
      if (effective < 8) continue
      score += 1.0 + effective / 40.0
      score -= linkDensity(p) * 12.0
      // Comma/period bonus: real sentences (Latin and CJK punctuation).
      if (',' in text || '.' in text || '，' in text || '。' in text) score += 1.0
    }
    // Structure signals: prose containers earn credit; tables and lists only
    // when they look like data (several rows/items, low link density).
    // Previously every table/list earned a flat bonus, which actively
    // promoted navboxes and See-also link dumps into the article.
    score += el.select("h1, h2, h3").size * 2.0
    score += el.select("pre, blockquote").size * 2.0
    for (table in el.select("table").take(20)) {
      if (isDataTable(table)) score += 2.0 else score -= 4.0
    }
    for (list in el.select("ul, ol").take(20)) {
      val items = list.select("> li").size
      val density = linkDensity(list)
      if (items >= 2 && density < 0.5) score += 1.5 else score -= 3.0
    }
    return score
  }

  /** Share of visible characters that live inside links (0..1). */
  private fun linkDensity(el: Element): Double {
    val text = el.text()
    if (text.isEmpty()) return 0.0
    val linkChars = el.select("a").sumOf { it.text().length }
    return (linkChars.toDouble() / text.length).coerceIn(0.0, 1.0)
  }

  /** A real data table: multiple rows/columns with mostly non-link cells. */
  private fun isDataTable(table: Element): Boolean {
    val rows = table.select("tr")
    if (rows.size < 2) return false
    val columns = rows.maxOfOrNull { it.select("th, td").size } ?: 0
    if (columns < 2) return false
    return linkDensity(table) < 0.4
  }
}
