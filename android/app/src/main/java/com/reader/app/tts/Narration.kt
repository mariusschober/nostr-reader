package com.reader.app.tts

import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser

/** Narration model: sentences from semantic blocks, code/URLs skipped. */
object Narration {
  fun sentences(blocks: List<ArticleBlock>): List<NarrationUnit> {
    val out = mutableListOf<NarrationUnit>()
    fun push(blockId: String, text: String) {
      val parts = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.length > 1 }
      for (p in parts) {
        // Keep utterances below TTS input limits by splitting long sentences.
        var rest = p
        while (rest.length > 1500) {
          val cut = rest.lastIndexOf(' ', 1500).let { if (it < 200) 1500 else it }
          out.add(NarrationUnit(blockId, rest.substring(0, cut)))
          rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(NarrationUnit(blockId, rest))
      }
    }
    for (b in blocks) when (b) {
      is ArticleBlock.Paragraph -> push(b.id, inlineText(b.inlines))
      is ArticleBlock.Heading -> push(b.id, inlineText(b.inlines))
      is ArticleBlock.BulletList -> for (i in b.items) for (sb in i.blocks) {
        if (sb is ArticleBlock.Paragraph) push(sb.id, inlineText(sb.inlines))
      }
      is ArticleBlock.OrderedList -> for (i in b.items) for (sb in i.blocks) {
        if (sb is ArticleBlock.Paragraph) push(sb.id, inlineText(sb.inlines))
      }
      is ArticleBlock.Quote -> for (sb in b.blocks) {
        if (sb is ArticleBlock.Paragraph) push(sb.id, inlineText(sb.inlines))
      }
      else -> {}
    }
    return out
  }

  private fun inlineText(inlines: List<com.reader.app.core.Inline>): String = buildString {
    for (i in inlines) when (i) {
      is com.reader.app.core.Inline.Text -> append(i.text)
      is com.reader.app.core.Inline.Strong -> append(inlineText(i.inlines))
      is com.reader.app.core.Inline.Emphasis -> append(inlineText(i.inlines))
      is com.reader.app.core.Inline.Strike -> append(inlineText(i.inlines))
      is com.reader.app.core.Inline.InlineCode -> {}
      is com.reader.app.core.Inline.Link -> append(inlineText(i.inlines))
      is com.reader.app.core.Inline.FootnoteRef -> {}
    }
  }

  fun readableForCount(blocks: List<ArticleBlock>): String = ArticleParser.readableText(blocks)
}

data class NarrationUnit(val blockId: String, val text: String)
