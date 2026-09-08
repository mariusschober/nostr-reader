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
        val start = unit.blockOffset(match.range.first)
        val end = unit.blockOffset(match.range.last) + 1
        add(RsvpToken(match.value, unit.blockId, start, end, unit.heading))
      }
    }
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
