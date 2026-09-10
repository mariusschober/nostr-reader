package com.reader.app.core

/**
 * Offline search query construction. Pure Kotlin, fully unit-tested.
 *
 * FTS5 has no backslash escaping, so safety comes from an allowlist
 * (letters/numbers/whitespace survive; everything else becomes a space) plus
 * per-token quoting. Never interpolate raw user text into MATCH.
 */
object SearchQuery {
  const val MAX_QUERY_CHARS = 100
  const val MAX_TOKENS = 12
  const val MAX_LIKE_CHARS = 60

  private val nonText = Regex("[^\\p{L}\\p{N}\\s]")
  private val whitespace = Regex("\\s+")
  private val hanRange1 = '\u3400'..'\u4DBF'
  private val hanRange2 = '\u4E00'..'\u9FFF'

  /**
   * Index/query normalization for the FTS copy only (originals untouched):
   * NFKC plus Arabic tatweel/harakat/alef folding. Diacritic folding itself
   * is the tokenizer's job (remove_diacritics=1).
   */
  fun ftsNormalize(s: String): String {
    var t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
    t = t.replace("\u0640", "")
      .replace(Regex("[\u064B-\u0652\u0670]"), "")
      .replace(Regex("[أإآٱ]"), "ا")
    return t
  }

  /**
   * Build a safe FTS5 MATCH string, or null when the input is degenerate
   * (blank, emoji/punctuation-only) and no query should run at all —
   * `MATCH ''` is a syntax error, so callers must branch on null.
   */
  fun buildFtsQuery(raw: String): String? {
    var s = ftsNormalize(raw.trim().take(MAX_QUERY_CHARS))
    if (s.isBlank()) return null
    s = nonText.replace(s, " ")
    s = whitespace.replace(s, " ").trim()
    if (s.isBlank()) return null
    val tokens = s.split(" ").filter { it.isNotEmpty() }.take(MAX_TOKENS)
    if (tokens.isEmpty()) return null
    val parts = mutableListOf<String>()
    for (tok in tokens) {
      val han = tok.filter { it in hanRange1 || it in hanRange2 }
      if (han.isNotEmpty()) {
        // Per-char tokens need a phrase: "北京" alone would match
        // non-adjacent characters anywhere in the corpus.
        parts.add("\"" + han.toCharArray().joinToString(" ") + "\"")
        val rest = tok.filterNot { it in hanRange1 || it in hanRange2 }
        if (rest.isNotEmpty()) parts.add(quote(rest))
      } else {
        parts.add(quote(tok))
      }
    }
    if (parts.isEmpty()) return null
    // Prefix only on the last term, only when long enough, never on a
    // single CJK character (explodes: every document matches one char).
    return parts.mapIndexed { i, p ->
      if (i == parts.lastIndex && !p.contains(" ") && p.length >= 4) "$p*" else p
    }.joinToString(" AND ")
  }

  private fun quote(token: String): String = "\"" + token.replace("\"", "\"\"") + "\""

  /** LIKE pattern with wildcard escaping (for the recall-net tier). */
  fun likePattern(raw: String): String {
    val t = raw.trim().take(MAX_LIKE_CHARS)
      .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$t%"
  }

  /**
   * Raw surface tokens for Kotlin-side matching (fallback path, recents
   * display). Same allowlist as the FTS builder, but unquoted and unfolded:
   * CJK runs stay whole for exact-substring search.
   */
  fun surfaceTokens(raw: String): List<String> {
    var s = ftsNormalize(raw.trim().take(MAX_QUERY_CHARS))
    if (s.isBlank()) return emptyList()
    s = nonText.replace(s, " ")
    s = whitespace.replace(s, " ").trim()
    if (s.isBlank()) return emptyList()
    return s.split(" ").filter { it.isNotEmpty() }.take(MAX_TOKENS)
  }

  /**
   * Match excerpt with <b> sentinels around the first hit (same markers the
   * FTS snippet() path emits, so one renderer serves both). Window of ~120
   * chars each side, cut at the string bounds, prefixed/suffixed with … when
   * trimmed. Null when the term is absent.
   */
  fun snippetFor(haystack: String, term: String, window: Int = 120): String? {
    if (term.isBlank()) return null
    val at = haystack.indexOf(term, ignoreCase = term.any { it in 'A'..'Z' || it in 'a'..'z' })
    if (at < 0) return null
    val start = (at - window).coerceAtLeast(0)
    val end = (at + term.length + window).coerceAtMost(haystack.length)
    return buildString {
      if (start > 0) append('…')
      append(haystack.substring(start, at))
      append("<b>")
      append(haystack.substring(at, at + term.length))
      append("</b>")
      append(haystack.substring(at + term.length, end))
      if (end < haystack.length) append('…')
    }
  }

  /** Fold for Kotlin-side comparisons (mirrors index-time folding). */
  fun foldForMatch(s: String): String = ftsNormalize(s).lowercase()

  /** True when the query wants CJK-phrase or compound recall help. */
  fun wantsSupplement(raw: String, tier1Hits: Int): Boolean {
    if (tier1Hits >= 10) return false
    if (raw.any { it in hanRange1 || it in hanRange2 }) return true
    return raw.trim().length >= 8
  }

  // German umlaut primaries for LIKE-variant recall (fallback path only).
  // French/Spanish diacritics stay exact-match on non-FTS devices — a
  // documented limitation; FTS devices fold them via the tokenizer.
  private val umlautPrimary = mapOf(
    'a' to 'ä', 'A' to 'Ä', 'o' to 'ö', 'O' to 'Ö', 'u' to 'ü', 'U' to 'Ü',
  )

  /**
   * Concrete spelling variants for LIKE recall where folding is impossible
   * (Muller→Müller, strasse→Straße). At most 4 short queries; exact original
   * always first so ranking can prefer it. Empty only for blank input.
   */
  fun umlautVariants(raw: String): List<String> {
    val base = raw.trim().take(MAX_LIKE_CHARS)
    if (base.isBlank()) return emptyList()
    val out = mutableListOf(base)
    val umlauted = base.map { umlautPrimary[it] ?: it }.joinToString("")
    if (umlauted != base) out += umlauted
    for (src in out.toList()) {
      val idx = src.lowercase().indexOf("ss")
      if (idx >= 0) {
        val withEszett = src.substring(0, idx) + "ß" + src.substring(idx + 2)
        if (withEszett !in out) out += withEszett
      }
    }
    return out.take(4)
  }
}
