package com.reader.app

import com.reader.app.core.SearchQuery
import org.junit.Assert.*
import org.junit.Test

class SearchQueryTest {
  @Test fun degenerateInputsReturnNull() {
    assertNull(SearchQuery.buildFtsQuery(""))
    assertNull(SearchQuery.buildFtsQuery("   "))
    assertNull(SearchQuery.buildFtsQuery("🎉👍"))
    assertNull(SearchQuery.buildFtsQuery("***...***"))
  }

  @Test fun injectionSyntaxIsNeutralized() {
    val q = SearchQuery.buildFtsQuery("foo\" OR 1=1 --")!!
    assertFalse(q.contains(" OR "))
    assertFalse(q.contains("*"))
    assertFalse(q.contains(":"))
    assertTrue(q.contains("\"foo\""))
    val q2 = SearchQuery.buildFtsQuery("{title} : bar")!!
    assertFalse(q2.contains("{"))
    assertFalse(q2.contains(":"))
  }

  @Test fun singleAndMultiTermShapes() {
    assertEquals("\"ab\"*", SearchQuery.buildFtsQuery("ab"))
    assertEquals("\"a\"", SearchQuery.buildFtsQuery("a"))
    assertEquals("\"foo\" AND \"bar\"*", SearchQuery.buildFtsQuery("foo bar"))
    assertEquals("\"Lesegeschwindigkeit\"*", SearchQuery.buildFtsQuery("Lesegeschwindigkeit"))
  }

  @Test fun cjkBecomesPhraseNeverBarePrefix() {
    assertEquals("\"北 京\"", SearchQuery.buildFtsQuery("北京"))
    assertEquals("\"北\"", SearchQuery.buildFtsQuery("北"))
    val mixed = SearchQuery.buildFtsQuery("北京大学x")!!
    assertTrue(mixed.startsWith("\"北 京 大 学\""))
    assertTrue(mixed.contains("AND"))
  }

  @Test fun capsAndTruncation() {
    val long = SearchQuery.buildFtsQuery(("word ".repeat(30)).trim())!!
    assertTrue(long.split(" AND ").size <= 12)
    assertTrue(SearchQuery.likePattern("100%_x\\y").startsWith("%"))
    assertTrue(SearchQuery.likePattern("100%_x\\y").contains("\\%"))
    assertTrue(SearchQuery.likePattern("100%_x\\y").contains("\\_"))
  }

  @Test fun ftsNormalizeFoldsArabic() {
    val folded = SearchQuery.ftsNormalize("كِتَاب ـ test")
    assertFalse(folded.contains("ـ"))
    assertFalse(folded.contains("ِ"))
    assertTrue(SearchQuery.ftsNormalize("أحمد").startsWith("ا"))
  }

  @Test fun supplementHeuristics() {    assertFalse(SearchQuery.wantsSupplement("foo", 10))
    assertFalse(SearchQuery.wantsSupplement("foo", 25))
    assertTrue(SearchQuery.wantsSupplement("北京大学", 2))
    assertTrue(SearchQuery.wantsSupplement("a fairly long query", 0))
    assertFalse(SearchQuery.wantsSupplement("short", 3))
  }

  @Test fun surfaceTokensStayWhole() {
    assertEquals(listOf("hello", "world"), SearchQuery.surfaceTokens("hello world!"))
    assertEquals(listOf("北京大学"), SearchQuery.surfaceTokens("北京大学"))
    assertTrue(SearchQuery.surfaceTokens("🎉").isEmpty())
  }

  @Test fun snippetMarksFirstHit() {
    val snippet = SearchQuery.snippetFor("alpha beta gamma delta", "beta")!!
    assertTrue(snippet.contains("<b>beta</b>"))
    assertFalse(snippet.contains("…"))
    val long = "word ".repeat(100) + "needle " + "word ".repeat(100)
    val snip2 = SearchQuery.snippetFor(long, "needle")!!
    assertTrue(snip2.startsWith("…") && snip2.endsWith("…"))
    assertTrue(snip2.contains("<b>needle</b>"))
    assertNull(SearchQuery.snippetFor("nothing here", "absent"))
    assertNull(SearchQuery.snippetFor("anything", ""))
  }

  @Test fun umlautVariantsStayBounded() {
    // Exact spelling always first; umlaut twins follow for recall.
    assertEquals(listOf("hello", "hellö"), SearchQuery.umlautVariants("hello"))
    assertTrue(SearchQuery.umlautVariants("   ").isEmpty())
    val muller = SearchQuery.umlautVariants("muller")
    assertEquals(listOf("muller", "müller"), muller)
    // ß/ss both directions, exact spellings first.
    val strasse = SearchQuery.umlautVariants("strasse")
    assertTrue(strasse.first() == "strasse")
    assertTrue("straße" in strasse)
    assertTrue(strasse.size <= 4)
    assertTrue("Geschwindigkeit" in SearchQuery.umlautVariants("Geschwindigkeit"))
  }
}
