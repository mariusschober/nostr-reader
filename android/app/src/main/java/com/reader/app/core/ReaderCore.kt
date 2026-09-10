package com.reader.app.core

import java.security.MessageDigest
import java.text.Normalizer

/** Kotlin mirror of rust-core + PROTOCOL.md. Codec-v2 gates every release. */
object ReaderCore {
  const val READER_PROTOCOL = "reader/2"
  const val RUMOR_KIND = 30078
  const val SYNC_WINDOW_DAYS = 10L
  const val TRANSPORT_TTL_DAYS = 7L
  const val MAX_COMPRESSED_BYTES = 5 * 1024 * 1024
  const val MAX_EXPANDED_BYTES = 20 * 1024 * 1024
  const val MAX_CHUNKS = 512
  const val MAX_TITLE_LEN = 500
  const val MAX_URL_LEN = 2000

  fun canonicalize(input: String): String {
    val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
    val lf = nfc.replace("\r\n", "\n").replace('\r', '\n')
    return buildString {
      var pendingBlanks = 0
      for (line in lf.lineSequence()) {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) {
          if (isNotEmpty()) pendingBlanks = minOf(2, pendingBlanks + 1)
        } else {
          if (isNotEmpty()) {
            append('\n')
            repeat(pendingBlanks) { append('\n') }
          }
          append(trimmed)
          pendingBlanks = 0
        }
      }
      append('\n')
    }
  }

  fun escapePlainText(input: String): String {
    val specials = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '!', '|', '>')
    return buildString {
      for (ch in input) {
        if (ch in specials) append('\\')
        append(ch)
      }
    }
  }

  fun sha256Hex(bytes: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    return d.joinToString("") { "%02x".format(it) }
  }

  fun documentId(canonicalMarkdown: String): String =
    sha256Hex(canonicalMarkdown.toByteArray(Charsets.UTF_8))

  /** Rolling sync window. NEVER last-sync (NIP-59 randomized past timestamps). */
  fun syncSince(nowSecs: Long): Long = nowSecs - SYNC_WINDOW_DAYS * 86400L

  private val wordSeparator = Regex("\\s+")
  private val listMarker = Regex("^[-*+>]+$")
  private val numberedMarker = Regex("^\\d+[.)]$")

  fun wordCount(canonical: String): Int {
    var inFence = false
    var n = 0
    for (line in canonical.lineSequence()) {
      val t = line.trim()
      if (t.startsWith("```")) {
        inFence = !inFence
        continue
      }
      if (inFence) continue
      for (w in t.split(wordSeparator)) {
        if (w.isEmpty()) continue
        if (w.startsWith("http://") || w.startsWith("https://")) continue
        if (listMarker.matches(w) || numberedMarker.matches(w)) continue
        n++
      }
    }
    return n
  }

  /**
   * Spaceless-script characters (Han, Hiragana, Katakana, Hangul, Thai):
   * whitespace splitting counts a whole Chinese paragraph as one "word",
   * which false-tripped the article floor and broke reading time.
   */
  fun cjkCount(canonical: String): Int {
    var n = 0
    for (ch in canonical) {
      when (Character.UnicodeBlock.of(ch)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        Character.UnicodeBlock.THAI,
        -> n++
        else -> {}
      }
    }
    return n
  }

  /** Reading words: whitespace words plus spaceless-script characters. */
  fun effectiveWords(canonical: String): Int = wordCount(canonical) + cjkCount(canonical)

  /**
   * Article floor across scripts: 30 whitespace words, or 60 spaceless-script
   * characters (~2-3 CJK sentences; one CJK character carries roughly a word's
   * meaning, and CJK prose runs denser than spaced text).
   */
  fun hasEnoughText(canonical: String): Boolean =
    wordCount(canonical) >= 30 || cjkCount(canonical) >= 60

  fun readingMinutes(words: Int): Int = maxOf(1, (words + 224) / 225)

  /** Relative age for library rows. Clock-skew futures read as Just now. */
  fun formatAge(addedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val delta = nowMillis - addedAtMillis
    if (delta < 60_000L) return "Just now"
    val minutes = delta / 60_000L
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    val days = hours / 24
    if (days < 7) return "${days}d ago"
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(addedAtMillis).atZone(zone).toLocalDate()
    val locale = java.util.Locale.getDefault()
    return if (days < 365) {
      date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", locale))
    } else {
      date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
    }
  }

  /**
   * Short display host: the registrable domain, so rows read `wikipedia.org`
   * instead of `en.wikipedia.org`. Last-two-labels with a small exception set
   * for two-level public suffixes (no bundled PSL — wrong cuts would
   * misattribute sources, so unknown deep hosts keep one extra label rather
   * than guessing). IP literals pass through untouched.
   */
  fun registrableHost(host: String): String {
    var h = host.trim().trimEnd('.').lowercase(java.util.Locale.ROOT)
    if (h.isBlank() || '.' !in h) return h
    if (h.matches(Regex("^[0-9.]+$")) || ':' in h) return h
    val twoLevel = setOf(
      "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "net.uk",
      "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp",
      "com.br", "com.mx", "com.ar", "com.co", "com.pe",
      "co.nz", "co.in", "co.za", "com.cn", "com.tw", "com.hk", "co.kr", "com.sg",
    )
    val labels = h.split('.')
    val keep = if (twoLevel.any { h == it || h.endsWith(".$it") }) 3 else 2
    if (labels.size <= keep) return h
    return labels.takeLast(keep).joinToString(".")
  }

  /**
   * Human source label. Prefers the stored publisher name, then the short
   * link host, then a pretty intake type — never blank, never raw developer
   * vocabulary like "android-share".
   */
  fun shortDisplaySource(sourceType: String, sourceName: String?, sourceUrl: String?): String {
    val named = sourceName?.trim().orEmpty()
    if (named.isNotBlank()) {
      // Capture stores the bare host as the name. A hostname-shaped name
      // shortens to its registrable domain (wikipedia.org); real publisher
      // names ("The Verge") pass through untouched.
      val hostShaped = !named.contains(' ') && named.contains('.') &&
        named.matches(Regex("[A-Za-z0-9.-]+"))
      return (if (hostShaped) registrableHost(named) else named).take(30)
    }
    val host = runCatching {
      val h = java.net.URI(sourceUrl ?: "").host?.lowercase(java.util.Locale.ROOT).orEmpty()
      h.trimEnd('.')
    }.getOrDefault("")
    if (host.isNotBlank() && '.' in host) return registrableHost(host).take(30)
    return when (sourceType.lowercase(java.util.Locale.ROOT)) {
      "web", "url" -> "Web"
      "link" -> "Link"
      "file" -> "File"
      "paste" -> "Paste"
      "selection", "android-process-text" -> "Selection"
      "android-share" -> "Shared"
      "mac-share", "mac-quick-action" -> "Mac"
      "chatgpt" -> "ChatGPT"
      "claude" -> "Claude"
      "gemini" -> "Gemini"
      "perplexity" -> "Perplexity"
      "notebook" -> "Notebook"
      "grok" -> "Grok"
      "substack" -> "Substack"
      "x" -> "X"
      else -> sourceType.ifBlank { "Saved" }.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString()
      }.take(30)
    }
  }

  fun formatAttention(totalMinutes: Int): String = when {
    totalMinutes < 60 -> "${totalMinutes}m"
    else -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
  }

  /** RSVP policy object (mirrors TS + Rust). Single place to tune. */
  object Rsvp {
    fun focalIndex(tokenLen: Int): Int = when {
      tokenLen <= 1 -> 0
      tokenLen <= 5 -> 1
      tokenLen <= 9 -> 2
      tokenLen <= 13 -> 3
      else -> minOf(tokenLen - 1, Math.round(tokenLen * 0.35).toInt())
    }

    fun factor(token: String, paragraphBreak: Boolean, headingBreak: Boolean): Double {
      if (headingBreak || paragraphBreak) return 2.7
      val len = token.codePointCount(0, token.length)
      var f = 1.0
      if (len > 12) f = 1.2 else if (len > 8) f = 1.1
      when (token.lastOrNull()) {
        '.', '!', '?' -> f = maxOf(f, 2.2)
        ',', ';', ':' -> f = maxOf(f, 1.4)
      }
      return f
    }

    fun intervalMs(wpm: Int, token: String, paragraphBreak: Boolean, headingBreak: Boolean): Long {
      val base = 60_000.0 / wpm.coerceIn(100, 1200)
      return (base * factor(token, paragraphBreak, headingBreak)).toLong()
    }
  }

  fun checkLimits(compressedBytes: Int, expandedBytes: Int, titleLen: Int, urlLen: Int, chunkCount: Int) {
    require(expandedBytes in 1..MAX_EXPANDED_BYTES) { "expanded transfer must be 1 byte to 20 MiB" }
    require(compressedBytes >= 1) { "compressed transfer must not be empty" }
    require(compressedBytes <= MAX_COMPRESSED_BYTES) { "compressed transfer exceeds 5 MiB" }
    require(chunkCount <= MAX_CHUNKS) { "chunk count exceeds 512" }
    require(titleLen <= MAX_TITLE_LEN) { "title too long" }
    require(urlLen <= MAX_URL_LEN) { "url too long" }
  }
}
