package com.reader.app

import com.reader.app.data.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
  @Test
  fun blankQueriesDoNotSearch() {
    assertNull(SearchQuery.pattern(""))
    assertNull(SearchQuery.pattern("   "))
  }

  @Test
  fun ordinaryQueriesBecomeContainingPatterns() {
    assertEquals("%harbor light%", SearchQuery.pattern("  harbor light  "))
    assertEquals("%🌱%", SearchQuery.pattern("🌱"))
  }

  @Test
  fun userWildcardsAndBackslashesAreEscaped() {
    assertEquals("%100\\% done%", SearchQuery.pattern("100% done"))
    assertEquals("%a\\_b%", SearchQuery.pattern("a_b"))
    assertEquals("%c\\\\d%", SearchQuery.pattern("c\\d"))
  }
}
