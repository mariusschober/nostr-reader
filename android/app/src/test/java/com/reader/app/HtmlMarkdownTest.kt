package com.reader.app

import com.reader.app.core.*
import com.reader.app.sync.Ingest
import org.junit.Assert.*
import org.junit.Test

class HtmlMarkdownTest {
  @Test fun compactTablesAreMarkdownWithOrWithoutOuterPipes() {
    for (text in listOf("| A | B |\n|---|---|\n|one|two|", "A | B\n--- | ---\none | two")) {
      assertTrue(Ingest.looksLikeMarkdown(text))
      val table = ArticleParser.parse(text).filterIsInstance<ArticleBlock.Table>().single()
      assertEquals(listOf("A", "B"), table.header)
      assertEquals(listOf(listOf("one", "two")), table.rows)
    }
  }
  @Test fun nestedHtmlKeepsTableAndInlineMeaning() {
    val md = HtmlMarkdown.convert("""<article><h2>Title &amp; more</h2><p>A <strong>bold</strong> and <em>soft</em> <a href="https://example.com">link</a>.</p><table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>one | two</td><td><b>three</b></td></tr></tbody></table><ul><li>First<ul><li>Nested</li></ul></li><li>Second</li></ul></article>""")
    val blocks = ArticleParser.parse(md)
    assertEquals("Title & more", ArticleParser.readableText(listOf(blocks.first())).trim())
    val paragraph = blocks.filterIsInstance<ArticleBlock.Paragraph>().single()
    assertTrue(paragraph.inlines.any { it is Inline.Strong })
    assertTrue(paragraph.inlines.any { it is Inline.Emphasis })
    assertTrue(paragraph.inlines.any { it is Inline.Link })
    val table = blocks.filterIsInstance<ArticleBlock.Table>().single()
    assertEquals(listOf("one | two", "three"), table.rows.single())
    assertTrue(blocks.filterIsInstance<ArticleBlock.BulletList>().single().items.first().blocks.any { it is ArticleBlock.BulletList })
  }
  @Test fun htmlDoesNotExecuteOrAccidentallyCreateMarkdown() {
    val md = HtmlMarkdown.convert("<script>alert(1)</script><p>Literal *stars* and [brackets]. <a href='javascript:alert(2)'>safe label</a></p><pre>one\n  two\n```</pre>")
    val blocks = ArticleParser.parse(md)
    assertFalse(md.contains("alert"))
    val paragraph = blocks.filterIsInstance<ArticleBlock.Paragraph>().single()
    assertFalse(paragraph.inlines.any { it is Inline.Emphasis || it is Inline.Link })
    assertEquals("one\n  two\n```", blocks.filterIsInstance<ArticleBlock.CodeBlock>().single().code.trimEnd())
  }
}
