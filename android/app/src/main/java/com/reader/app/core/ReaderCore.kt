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

  fun readingMinutes(words: Int): Int = maxOf(1, (words + 224) / 225)

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
