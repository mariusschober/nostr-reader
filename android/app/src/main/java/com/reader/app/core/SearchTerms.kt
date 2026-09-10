package com.reader.app.core

/** Ordinary terms are ANDed, quoted phrases stay contiguous. No query operators. */
object SearchTerms {
  fun parse(raw: String): List<String> {
    require(raw.length <= 100) { "Use up to 100 characters." }
    require(raw.count { it == '\"' } % 2 == 0) { "Close the quotation mark to search a phrase." }
    val terms = Regex("\"([^\"]+)\"|([^\\s\"]+)").findAll(raw).map {
      (it.groups[1]?.value ?: it.groups[2]!!.value).trim()
    }.filter { it.any(Char::isLetterOrDigit) }.toList()
    require(raw.isBlank() || terms.isNotEmpty()) { "Search needs letters or numbers." }
    require(terms.size <= 12) { "Use up to 12 words or phrases." }
    return terms
  }
}
