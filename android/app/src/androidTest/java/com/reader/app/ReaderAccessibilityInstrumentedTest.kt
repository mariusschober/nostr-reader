package com.reader.app

import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ArticleParser
import com.reader.app.core.RenderedText
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.screens.NativeArticleView
import com.reader.app.ui.screens.nativeArticleText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAccessibilityInstrumentedTest {
  @Test fun linkAndFootnoteExposeNamedActionsWithTheSameReaderDestinations() {
    val projection = RenderedText.project(ArticleParser.parseWithSources(
      "[Source](https://example.org/story) and [contents](#chapter) with a note[^one].\n\n[^one]: Offline note.",
    ))
    val invoked = mutableListOf<String>()
    val labels = mutableListOf<String>()
    val runner = InstrumentationRegistry.getInstrumentation()
    runner.runOnMainSync {
      val article = NativeArticleView(runner.targetContext)
      article.onLink = { invoked.add(it) }
      article.display("doc", projection, nativeArticleText(projection), ReaderSettings(), Color.BLACK, Color.WHITE,
        16, SemanticCursor.start("doc"), Color.BLUE)
      val body = (0 until article.childCount).map(article::getChildAt).filterIsInstance<TextView>().first { it.isTextSelectable }
      val node = body.createAccessibilityNodeInfo()
      val accessible = node.text as Spanned
      val accessibleLabels = accessible.getSpans(0, accessible.length, ClickableSpan::class.java)
        .sortedBy(accessible::getSpanStart)
        .map { accessible.subSequence(accessible.getSpanStart(it), accessible.getSpanEnd(it)).toString() }
      assertEquals(listOf("Source", "contents", "[one]"), accessibleLabels)
      val text = body.text as Spanned
      val spans = text.getSpans(0, text.length, ClickableSpan::class.java).sortedBy(text::getSpanStart)
      for (span in spans) {
        labels.add(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString())
        span.onClick(body)
      }
      article.setPenMode(true)
      spans.first().onClick(body)
    }
    assertEquals(listOf("Source", "contents", "[one]"), labels)
    assertEquals(listOf("https://example.org/story", "#chapter", "#^one"), invoked)
  }
}
