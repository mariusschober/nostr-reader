package com.reader.app.tts

import com.reader.app.core.*

/** A separate narration projection retains a map back to every rendered UTF-16 offset. */
object Narration {
  fun sentences(blocks: List<ArticleBlock>): List<NarrationUnit> = sentences(RenderedText.project(blocks))

  fun sentences(projection: RenderedProjection): List<NarrationUnit> {
    val out = mutableListOf<NarrationUnit>()
    for (block in projection.blocks) {
      if (block.kind in setOf(TextKind.CODE, TextKind.FOOTNOTE, TextKind.DIVIDER)) continue
      val excluded = projection.styles.filter { it.style == TextStyle.CODE && it.start < block.bodyEnd && it.end > block.bodyStart }
      val spoken = StringBuilder()
      val offsets = mutableListOf<Int>()
      for (global in block.bodyStart until block.bodyEnd) {
        if (excluded.none { global in it.start until it.end }) {
          spoken.append(projection.text[global]); offsets += global - block.start
        }
      }
      for (match in Regex("[^.!?\\n]+(?:[.!?]+|(?=\\n)|$)").findAll(spoken)) {
        var start = match.range.first
        var end = match.range.last + 1
        while (start < end && spoken[start].isWhitespace()) start++
        while (end > start && spoken[end - 1].isWhitespace()) end--
        while (start < end) {
          var cut = (start + 1500).coerceAtMost(end)
          if (cut < end) {
            val word = spoken.lastIndexOf(" ", cut)
            if (word > start + 200) cut = word
          }
          if (cut < spoken.length && cut > start && Character.isHighSurrogate(spoken[cut - 1])) cut--
          if (cut > start) out += NarrationUnit(block.id, spoken.substring(start, cut), offsets.subList(start, cut).toIntArray(), block.kind == TextKind.HEADING)
          start = cut
          while (start < end && spoken[start].isWhitespace()) start++
        }
      }
    }
    return out
  }

  fun readableForCount(blocks: List<ArticleBlock>): String = sentences(blocks).joinToString("\n") { it.text }
}

data class NarrationUnit(
  val blockId: String, val text: String, val renderedOffsets: IntArray? = null, val heading: Boolean = false,
) {
  fun blockOffset(at: Int): Int = renderedOffsets?.getOrNull(at.coerceIn(0, text.lastIndex.coerceAtLeast(0))) ?: at.coerceAtLeast(0)
}
