package com.reader.app

import com.reader.app.core.SearchTerms
import org.junit.Assert.*
import org.junit.Test

class SearchTermsTest {
  @Test fun quotedPhrasesStayContiguousAndOrdinaryTermsCombine() {
    assertEquals(listOf("quiet reading", "habits"), SearchTerms.parse("\"quiet reading\" habits"))
    assertEquals(listOf("red", "green", "blue"), SearchTerms.parse(" red   green blue "))
    assertEquals(listOf("two words", "另一个词"), SearchTerms.parse("\"two words\" 另一个词"))
  }
  @Test fun operatorsAreOrdinaryTerms() {
    assertEquals(listOf("word", "OR", "another"), SearchTerms.parse("word OR another"))
    assertEquals(listOf("50%", "under_score"), SearchTerms.parse("50% under_score"))
  }
  @Test fun invalidInputIsDistinctFromNoMatches() {
    for (query in listOf("\"unfinished", "...", "x".repeat(101))) {
      assertThrows(IllegalArgumentException::class.java) { SearchTerms.parse(query) }
    }
    assertTrue(SearchTerms.parse("").isEmpty())
  }
}
