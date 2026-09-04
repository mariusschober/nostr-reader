package com.reader.app

import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import com.reader.app.core.Inline
import org.junit.Assert.*
import org.junit.Test

class ArticleModelTest {
  @Test
  fun parsesRichDocument() {
    val md = "# Title\n\nPara with **bold**, *em*, `code`, [link](https://example.com).\n\n- a\n- b\n\n> quote\n\n```kotlin\nval x = 1\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"
    val blocks = ArticleParser.parse(md)
    assertTrue(blocks.any { it is ArticleBlock.Heading })
    assertTrue(blocks.any { it is ArticleBlock.BulletList })
    assertTrue(blocks.any { it is ArticleBlock.Quote })
    assertTrue(blocks.any { it is ArticleBlock.CodeBlock })
    val text = ArticleParser.readableText(blocks)
    assertTrue(text.contains("bold"))
    assertFalse(text.contains("**")) // no raw markdown leaks
  }

  @Test
  fun stripsMaliciousContent() {
    val md = "Hi\n\n<script>alert(1)</script>\n\n[evil](javascript:alert(1)) and ![x](data:text/html,hi)\n"
    val blocks = ArticleParser.parse(md)
    val text = ArticleParser.readableText(blocks)
    assertFalse(text.contains("<script>"))
    assertFalse(text.contains("javascript:"))
    // Links to non-http schemes degrade to text.
    assertTrue(ArticleParser.sanitizeUrl("javascript:alert(1)") == null)
    assertTrue(ArticleParser.sanitizeUrl("https://example.com") == "https://example.com")
  }

  @Test
  fun malformedMarkdownDegradesToText() {
    val md = "# Unclosed **bold\n\n- item\n   - nested *em\n\n[broken](not a url\n"
    val blocks = ArticleParser.parse(md)
    assertTrue(blocks.isNotEmpty())
    val text = ArticleParser.readableText(blocks)
    assertTrue(text.isNotBlank())
  }

  @Test
  fun hardBreaksBecomeNewlines() {
    val blocks = ArticleParser.parse("Alpha line.\\\nBeta line.\n")
    val text = ArticleParser.readableText(blocks)
    assertTrue(text.contains("Alpha line.\nBeta line."))
  }

  @Test
  fun codeExcludedFromReadingText() {
    val md = "Intro words here.\n\n```\nsome code tokens xyz\n```\n\nMore words.\n"
    val text = ArticleParser.readableText(ArticleParser.parse(md))
    assertTrue(text.contains("Intro"))
    assertFalse(text.contains("xyz"))
  }
}
