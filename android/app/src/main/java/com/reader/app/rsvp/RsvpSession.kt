package com.reader.app.rsvp

import com.reader.app.core.ArticleBlock
import com.reader.app.core.Inline
import com.reader.app.core.ReaderCore

data class RsvpToken(val text: String, val blockId: String, val start: Int, val end: Int, val heading: Boolean)

object RsvpModel {
  /** Build narration/RSVP tokens from semantic blocks; skips code/URLs. */
  fun tokens(blocks: List<ArticleBlock>): List<RsvpToken> {
    return tokens(com.reader.app.core.RenderedText.project(blocks))
  }

  fun tokens(projection: com.reader.app.core.RenderedProjection): List<RsvpToken> = buildList {
    for (unit in com.reader.app.tts.Narration.sentences(projection)) {
      for (match in Regex("\\S+").findAll(unit.text)) {
        if (match.value.startsWith("http://") || match.value.startsWith("https://")) continue
        // Spaceless scripts yield paragraph-long "words": slice into short
        // tokens at CJK punctuation, else hard slices (offsets stay exact).
        for (slice in cjkSlices(match.value)) {
          val start = unit.blockOffset(match.range.first + slice.first)
          val end = unit.blockOffset(match.range.first + slice.last) + 1
          add(RsvpToken(match.value.substring(slice.first, slice.last + 1), unit.blockId, start, end, unit.heading))
        }
      }
    }
  }

  private val cjkTokenEnd = setOf('。', '！', '？', '…', '、', '，', '；', '：', '」', '』', '）', '、')

  private fun cjkSlices(token: String): List<IntRange> {
    if (token.length <= 24) return listOf(token.indices)
    val out = mutableListOf<IntRange>()
    var start = 0
    while (start < token.length) {
      var end = (start + 12).coerceAtMost(token.length)
      if (end < token.length) {
        var boundary = -1
        var i = start + 2
        while (i < end) {
          if (token[i] in cjkTokenEnd) boundary = i + 1
          i++
        }
        if (boundary > start) end = boundary
        // Never split a surrogate pair across tokens.
        if (end < token.length && end > start + 1 &&
          Character.isHighSurrogate(token[end - 1]) && Character.isLowSurrogate(token[end])
        ) end--
      }
      out += start until end
      start = end
    }
    return out
  }

  /** Monotonic-deadline scheduler: no drift accumulation. */
  class Scheduler(var wpm: Int) {
    private var nextDeadline: Long = 0L
    fun reset(nowElapsedRealtime: Long) {
      nextDeadline = nowElapsedRealtime
    }
    fun delayFor(token: RsvpToken, paragraphBreak: Boolean): Long {
      val d = ReaderCore.Rsvp.intervalMs(wpm, token.text, paragraphBreak, token.heading)
      nextDeadline += d
      return d
    }

    fun deadline(): Long = nextDeadline
  }
}
