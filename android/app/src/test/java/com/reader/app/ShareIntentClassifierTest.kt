package com.reader.app

import com.reader.app.capture.CaptureUrlPolicy
import com.reader.app.capture.ShareClassification
import com.reader.app.capture.ShareIntentClassifier
import com.reader.app.sync.Ingest
import org.junit.Assert.*
import org.junit.Test

class ShareIntentClassifierTest {
  private fun probe(text: String): Boolean = Ingest.looksLikeMarkdown(text)
  private fun classify(text: String?, subject: String? = null) =
    ShareIntentClassifier.classify(text, subject, ::probe)

  @Test fun standaloneUrlIsRecognized() {
    val result = classify("https://example.com/article-1")
    assertTrue(result is ShareClassification.SingleUrl)
    assertEquals("https://example.com/article-1", (result as ShareClassification.SingleUrl).url)
  }

  @Test fun standaloneUrlWithWrappingIsRecognized() {
    val result = classify("<https://example.com/wrapped>")
    assertTrue(result is ShareClassification.SingleUrl)
  }

  @Test fun subjectPlusUrlIsRecognized() {
    val result = classify("https://example.com/nice-piece", "A Nice Piece")
    assertTrue(result is ShareClassification.SingleUrl || result is ShareClassification.SubjectPlusUrl)
  }

  @Test fun titleLinePlusUrlIsSubjectPlus() {
    val result = classify("A Nice Piece\nhttps://example.com/nice-piece")
    assertTrue(result is ShareClassification.SubjectPlusUrl)
    assertEquals("https://example.com/nice-piece", (result as ShareClassification.SubjectPlusUrl).url)
    assertTrue(result.titleHint!!.contains("Nice"))
  }

  @Test fun markdownIsPreservedNeverFetched() {
    assertTrue(classify("# Title\n\nSee [docs](https://example.com) for more.") is ShareClassification.MarkdownImport)
    assertTrue(classify("Intro\n\n- one\n- two\n\nhttps://example.com") is ShareClassification.MarkdownImport)
  }

  @Test fun multiUrlProseIsNeverFetched() {
    val prose = "I read https://example.com/a and https://example.com/b today, both were good."
    val result = classify(prose)
    // Either MarkdownImport (link-preserving) or SelectedText is fine — never a fetch.
    assertTrue(result is ShareClassification.SelectedText || result is ShareClassification.MarkdownImport)
  }

  @Test fun longProseWithSingleLinkIsNotFetched() {
    val prose = buildString {
      append("This is a long personal note about what I read today. ")
      repeat(20) { append("It was interesting and I kept thinking about attention and memory. ") }
      append("See https://example.com/article for reference.")
    }
    val result = classify(prose)
    assertTrue(result is ShareClassification.SelectedText || result is ShareClassification.MarkdownImport)
  }

  @Test fun plainSelectionStaysText() {
    assertTrue(classify("Just a few words here") is ShareClassification.SelectedText)
    assertTrue(classify("Meet at 5. Bring version 1.2") is ShareClassification.SelectedText)
  }

  @Test fun unsupportedSingleUrlIsNotFetched() {
    // Intentional: no silent fetch of non-http schemes.
    assertTrue(classify("ftp://example.com/file") is ShareClassification.SelectedText)
  }

  @Test fun blankIsNull() {
    assertNull(classify(null))
    assertNull(classify("   "))
  }

  @Test fun originalUrlProvenanceKept() {
    val raw = "https://example.com/Article?utm_source=share#frag"
    val result = classify(raw)
    assertTrue(result is ShareClassification.SingleUrl)
    assertEquals(raw, (result as ShareClassification.SingleUrl).url)
  }
}
