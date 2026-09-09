package com.reader.app.capture

/**
 * Stage A input classification.
 *
 * Goals:
 * - Preserve selected-text and intentional Markdown import behavior exactly.
 * - Recognize a standalone supported article URL from share/paste.
 * - Handle common subject-plus-URL share forms without silently fetching
 *   every URL inside arbitrary selected prose.
 * - Keep original URL/provenance and an operation identity (handled by
 *   [CaptureRepository]; this file only classifies).
 *
 * Rules (pure, unit-tested):
 * - HTML extra is handled by the caller (existing importHtml path); this
 *   classifier only sees plain text + optional subject.
 * - If the text looks like intentional Markdown (via the existing
 *   Markdown detector contract), it is [ShareClassification.MarkdownImport]
 *   even when it contains URLs. Callers must not fetch.
 * - A "standalone URL" is the entire trimmed text (after stripping one pair
 *   of surrounding <>, quotes, or parentheses) being a single supported
 *   http/https URL with nothing else.
 * - A "subject-plus-URL" form is: optional subject (Intent EXTRA_SUBJECT or
 *   a first title line) plus exactly one supported URL in the body, where the
 *   non-URL remainder is title-like (short, at most 2 lines, <= 200 chars)
 *   and the body does not look like multi-paragraph prose. Multi-URL bodies
 *   and long prose are always [ShareClassification.SelectedText].
 * - Everything else is [ShareClassification.SelectedText] (existing
 *   plain-text import path).
 */
sealed interface ShareClassification {
  data class SingleUrl(val url: String, val titleHint: String?) : ShareClassification
  data class SubjectPlusUrl(val url: String, val titleHint: String?) : ShareClassification
  data object MarkdownImport : ShareClassification
  data object SelectedText : ShareClassification
}

object ShareIntentClassifier {
  private val urlToken = Regex("""https?://[^\s<>"'()\[\]{}]+""")
  private const val MAX_TITLE_HINT = 200

  fun isMarkdownLike(text: String, markdownProbe: (String) -> Boolean): Boolean =
    markdownProbe(text)

  fun classify(
    text: String?,
    subject: String?,
    markdownProbe: (String) -> Boolean,
  ): ShareClassification? {
    if (text.isNullOrBlank()) return null
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    // Standalone URL first: a bare link is technically an autolink (and the
    // existing Markdown probe flags it), but it is still a capture intent.
    // Anything with real Markdown structure beyond a bare URL falls through.
    unwrapSingle(trimmed)?.let { candidate ->
      if (CaptureUrlPolicy.isSupportedHttpUrl(candidate)) {
        return ShareClassification.SingleUrl(candidate, subject?.trim()?.take(MAX_TITLE_HINT)?.ifBlank { null })
      }
      // A single but unsupported URL is still text (caller shows import path),
      // not a fetch. Return SelectedText rather than null.
      if (looksLikeSingleUrlShape(candidate)) return ShareClassification.SelectedText
    }
    // Subject-plus-URL before the generic Markdown probe: a title line plus a
    // bare URL is technically an autolink (the probe flags it), but it is
    // still a capture intent. Intentional Markdown with real link syntax
    // ([label](url), images, etc.) must never become a fetch.
    val urls = urlToken.findAll(trimmed).map { cleanTrailingPunctuation(it.value) }.toList()
    if (urls.size == 1 && !containsMarkdownLinkSyntax(trimmed)) {
      val url = urls.single()
      if (!CaptureUrlPolicy.isSupportedHttpUrl(url)) return ShareClassification.SelectedText
      val remainder = trimmed.replace(url, "").trim().trim { it in " \t\n\r\"'<>():-–—|•\n" }.trim()
      val subjectClean = subject?.trim().orEmpty()
      val titleHint = when {
        subjectClean.isNotBlank() -> subjectClean.take(MAX_TITLE_HINT)
        remainder.isNotBlank() -> remainder.take(MAX_TITLE_HINT)
        else -> null
      }
      // Remainder must be title-like: short, few lines, no markdown structure.
      // An empty remainder with a subject is the classic browser share
      // ("Title" subject + URL body). An empty remainder without subject is
      // handled above as SingleUrl; here it would mean the URL had trailing
      // punctuation only — still accept as subject-plus with no title.
      val remainderLines = if (remainder.isBlank()) 0 else remainder.lines().size
      val remainderOk = remainder.isBlank() ||
        (remainder.length <= MAX_TITLE_HINT && remainderLines <= 2 && !markdownProbe(remainder))
      // Guard: long prose with a single link must not auto-fetch.
      // Heuristic: > 500 chars of non-URL text or >= 3 sentences is prose.
      val isProse = remainder.length > 500 || sentenceCount(remainder) >= 3
      if (remainderOk && !isProse) {
        // If there was no subject and no remainder, this was effectively a
        // standalone URL with trailing punctuation — report as SingleUrl.
        if (subjectClean.isBlank() && remainder.isBlank()) {
          return ShareClassification.SingleUrl(url, null)
        }
        // A bare title+URL without subject, or subject+URL, is a capture.
        // Pure prose falls through to Markdown/SelectedText below.
        if (subjectClean.isNotBlank() || remainder.isNotBlank()) {
          return ShareClassification.SubjectPlusUrl(url, titleHint?.ifBlank { null })
        }
      }
      // Single bare URL with non-title remainder: not a fetch; fall through
      // to Markdown/SelectedText preservation below.
      if (remainderOk && !isProse && subjectClean.isBlank() && remainder.isBlank()) {
        return ShareClassification.SingleUrl(url, null)
      }
    }
    // Preserve intentional Markdown imports verbatim. Never fetch from them.
    if (markdownProbe(trimmed)) return ShareClassification.MarkdownImport
    if (urls.size == 1 && !containsMarkdownLinkSyntax(trimmed)) {
      // Re-evaluate single-URL prose that is not Markdown: it was already
      // judged non-title-like above, so preserve as text without fetching.
      return ShareClassification.SelectedText
    }
    // Zero or multiple URLs: never auto-fetch.
    return ShareClassification.SelectedText
  }

  private fun unwrapSingle(trimmed: String): String? {
    var s = trimmed.trim()
    // Strip one wrapping pair: <url>, "url", 'url', (url).
    if (s.length >= 2) {
      val pairs = listOf('<' to '>', '"' to '"', '\'' to '\'', '(' to ')', '[' to ']', '{' to '}')
      for ((open, close) in pairs) {
        if (s.first() == open && s.last() == close) {
          s = s.substring(1, s.length - 1).trim()
          break
        }
      }
    }
    // Must be a single token with no whitespace.
    if (s.any { it.isWhitespace() }) return null
    return s
  }

  private fun looksLikeSingleUrlShape(candidate: String): Boolean =
    candidate.startsWith("http://", ignoreCase = true) ||
      candidate.startsWith("https://", ignoreCase = true)

  internal fun cleanTrailingPunctuation(url: String): String {
    var s = url
    while (s.isNotEmpty() && s.last() in ".,;:!?)]}'\"") s = s.dropLast(1)
    // Balance: a URL legitimately ending in ) is rare; Chrome shares encode it.
    return s
  }

  private fun containsMarkdownLinkSyntax(text: String): Boolean =
    Regex("""\[[^\]]+\]\([^)]+\)""").containsMatchIn(text) || Regex("""!\[[^\]]*\]\([^)]+\)""").containsMatchIn(text)

  private fun sentenceCount(text: String): Int {
    if (text.isBlank()) return 0
    return Regex("""[.!?]+(\s|$)""").findAll(text).count() + 1
  }
}
