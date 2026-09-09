package com.reader.app.tts

import com.reader.app.core.*

/** A separate narration projection retains a map back to every rendered UTF-16 offset. */
object Narration {
  fun sentences(blocks: List<ArticleBlock>): List<NarrationUnit> = sentences(RenderedText.project(blocks))

  fun sentences(projection: RenderedProjection): List<NarrationUnit> {
    val out = mutableListOf<NarrationUnit>()
    for (block in projection.blocks) {
      if (block.kind in setOf(TextKind.CODE, TextKind.FOOTNOTE, TextKind.DIVIDER)) continue
      // Spoken text skips code spans and footnote markers ("[n]"): listeners
      // hear prose, matching what reading-time counts (see readableText).
      val excluded = projection.styles.filter {
        it.style in setOf(TextStyle.CODE, TextStyle.FOOTNOTE_REF) &&
          it.start < block.bodyEnd && it.end > block.bodyStart
      }
      val spoken = StringBuilder()
      val offsets = mutableListOf<Int>()
      for (global in block.bodyStart until block.bodyEnd) {
        if (excluded.none { global in it.start until it.end }) {
          spoken.append(projection.text[global]); offsets += global - block.start
        }
      }
      for (match in Regex("[^.!?。！？…\n]+(?:[.!?。！？…]+|(?=\\n)|$)").findAll(spoken)) {
        var start = match.range.first
        var end = match.range.last + 1
        while (start < end && spoken[start].isWhitespace()) start++
        while (end > start && spoken[end - 1].isWhitespace()) end--
        while (start < end) {
          var cut = (start + 1500).coerceAtMost(end)
          if (cut < end) {
            // Prefer a space break; for spaceless scripts fall back to a
            // CJK punctuation boundary, else a hard cut (surrogate-safe).
            val word = spoken.lastIndexOf(" ", cut)
            cut = when {
              word > start + 200 -> word
              else -> cjkBreak(spoken, start, cut) ?: cut
            }
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

  private val cjkSentenceEnd = setOf('。', '！', '？', '…', '、', '，', '；', '：', '」', '』', '）')

  /** Last CJK-friendly cut position in [start, cut): after sentence-ending punctuation. */
  private fun cjkBreak(spoken: CharSequence, start: Int, cut: Int): Int? {
    var at: Int? = null
    var i = start
    while (i < cut) {
      if (spoken[i] in cjkSentenceEnd) at = i + 1
      i++
    }
    return at?.takeIf { it > start + 8 }
  }
}

data class NarrationUnit(
  val blockId: String, val text: String, val renderedOffsets: IntArray? = null, val heading: Boolean = false,
) {
  fun blockOffset(at: Int): Int = renderedOffsets?.getOrNull(at.coerceIn(0, text.lastIndex.coerceAtLeast(0))) ?: at.coerceAtLeast(0)
}
