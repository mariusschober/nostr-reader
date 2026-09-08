package com.reader.app.core

/** Very large articles use bounded native reading parts. Canonical text is never changed. */
const val MAX_SECTION_CHARS = 196608
data class ArticleSection(val startUtf16: Int, val endUtf16: Int, val prefix: String = "", val suffix: String = "")

class ArticleSectionPlanner {
  private val buffer = StringBuilder()
  private val result = mutableListOf<ArticleSection>()
  private var start = 0
  private var fence: String? = null
  private var tableHeader: String? = null
  private var previousLine = ""

  fun append(text: String) {
    buffer.append(text)
    while (buffer.length >= MAX_SECTION_CHARS) emit(cut())
  }

  fun finish(): List<ArticleSection> {
    if (buffer.isNotEmpty()) emit(buffer.length)
    return result.toList()
  }

  private fun cut(): Int {
    val end = MAX_SECTION_CHARS.coerceAtMost(buffer.length)
    val preferred = buffer.lastIndexOf("\n\n", end - 2)
    var cut = if (preferred >= end / 3) preferred + 2 else {
      val line = buffer.lastIndexOf("\n", end - 1)
      if (line >= end / 3) line + 1 else end
    }
    if (cut < buffer.length && Character.isHighSurrogate(buffer[cut - 1])) cut--
    // A boundary inside ordinary combining/emoji text belongs to the whole grapheme.
    val probeStart = (cut - 256).coerceAtLeast(0)
    val probeEnd = (cut + 256).coerceAtMost(buffer.length)
    val safe = probeStart + Graphemes(buffer.substring(probeStart, probeEnd)).floor(cut - probeStart)
    if (safe > 0) cut = safe
    return cut
  }

  private fun emit(length: Int) {
    val prefix = fence?.let { "$it\n" } ?: tableHeader.orEmpty()
    val part = buffer.substring(0, length)
    for (line in part.removeSuffix("\n").lineSequence()) {
      val trimmed = line.trimStart()
      val marker = trimmed.takeWhile { it == 96.toChar() || it == '~' }
      if (marker.length >= 3 && marker.all { it == marker[0] }) {
        if (fence == null) fence = trimmed.take(256)
        else if (marker[0] == fence!![0] && marker.length >= fence!!.takeWhile { it == marker[0] }.length) fence = null
      }
      if (fence == null) {
        if (previousLine.contains('|') && trimmed.contains('-') && trimmed.all { it in "|-: \t" }) {
          tableHeader = previousLine.take(4096) + "\n" + line.take(4096) + "\n"
        } else if (line.isBlank() || !line.contains('|')) tableHeader = null
      }
      previousLine = line
    }
    val suffix = fence?.let { "\n" + it.takeWhile { ch -> ch == it[0] } + "\n" }.orEmpty()
    result += ArticleSection(start, start + length, prefix, suffix)
    start += length
    buffer.delete(0, length)
  }
}
