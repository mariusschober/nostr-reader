package com.reader.app

import com.reader.app.core.ArticleParser
import com.reader.app.core.RenderedText
import com.reader.app.ui.screens.isDuplicateTitle
import org.junit.Assert.*
import org.junit.Test

/** Display-only duplicate-title suppression: genuine matches hide the header line only. */
class DuplicateTitleTest {
  @Test fun exactDuplicateHidesHeader() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("# Hello Reader\n\nBody text.\n"))
    assertTrue(isDuplicateTitle("Hello Reader", projection))
  }

  @Test fun caseAndWhitespaceVariantsStillMatch() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("#   Hello   Reader  \n\nBody.\n"))
    assertTrue(isDuplicateTitle("  hello reader ", projection))
  }

  @Test fun differentTitleStaysVisible() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("# Hello Reader\n\nBody.\n"))
    assertFalse(isDuplicateTitle("Another title", projection))
  }

  @Test fun nonHeadingFirstBlockStaysVisible() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("Hello Reader\n\nBody.\n"))
    assertFalse(isDuplicateTitle("Hello Reader", projection))
  }

  @Test fun secondLevelHeadingStaysVisible() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("## Hello Reader\n\nBody.\n"))
    assertFalse(isDuplicateTitle("Hello Reader", projection))
  }

  @Test fun blankTitleNeverCountsAsDuplicate() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("# Hello\n\nBody.\n"))
    assertFalse(isDuplicateTitle("   ", projection))
    assertFalse(isDuplicateTitle("", projection))
  }

  @Test fun checkLeavesProjectionUntouched() {
    val parsed = ArticleParser.parseWithSources("# Hello Reader\n\nBody.\n")
    val projection = RenderedText.project(parsed)
    val before = projection.text
    isDuplicateTitle("Hello Reader", projection)
    assertEquals(before, projection.text)
  }
}
