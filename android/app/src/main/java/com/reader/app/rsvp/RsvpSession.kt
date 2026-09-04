package com.reader.app.rsvp

import com.reader.app.core.ArticleBlock
import com.reader.app.core.Inline
import com.reader.app.core.ReaderCore

data class RsvpToken(val text: String, val blockId: String, val start: Int, val end: Int, val heading: Boolean)

object RsvpModel {
  /** Build narration/RSVP tokens from semantic blocks; skips code/URLs. */
  fun tokens(blocks: List<ArticleBlock>): List<RsvpToken> {
    val out = mutableListOf<RsvpToken>()
    fun inlineText(inlines: List<Inline>): String = buildString {
      for (i in inlines) when (i) {
        is Inline.Text -> append(i.text)
        is Inline.Strong -> append(inlineText(i.inlines))
        is Inline.Emphasis -> append(inlineText(i.inlines))
        is Inline.Strike -> append(inlineText(i.inlines))
        is Inline.InlineCode -> {}
        is Inline.Link -> {
          if (!(i.url.startsWith("http://") || i.url.startsWith("https://"))) append(inlineText(i.inlines))
          else append(inlineText(i.inlines))
        }
        is Inline.FootnoteRef -> {}
      }
    }
    for (b in blocks) {
      when (b) {
        is ArticleBlock.Paragraph -> emitText(out, b.id, inlineText(b.inlines), false)
        is ArticleBlock.Heading -> emitText(out, b.id, inlineText(b.inlines), true)
        is ArticleBlock.BulletList -> for (item in b.items) for (sb in item.blocks) {
          if (sb is ArticleBlock.Paragraph) emitText(out, sb.id, inlineText(sb.inlines), false)
        }
        is ArticleBlock.OrderedList -> for (item in b.items) for (sb in item.blocks) {
          if (sb is ArticleBlock.Paragraph) emitText(out, sb.id, inlineText(sb.inlines), false)
        }
        is ArticleBlock.Quote -> for (sb in b.blocks) {
          if (sb is ArticleBlock.Paragraph) emitText(out, sb.id, inlineText(sb.inlines), false)
        }
        else -> {}
      }
    }
    return out
  }

  private fun emitText(out: MutableList<RsvpToken>, blockId: String, text: String, heading: Boolean) {
    var idx = 0
    for (raw in text.split(Regex("\\s+"))) {
      if (raw.isEmpty()) continue
      if (raw.startsWith("http://") || raw.startsWith("https://")) continue
      val start = text.indexOf(raw, idx).let { if (it < 0) idx else it }
      out.add(RsvpToken(raw, blockId, start, start + raw.length, heading))
      idx = start + raw.length
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
