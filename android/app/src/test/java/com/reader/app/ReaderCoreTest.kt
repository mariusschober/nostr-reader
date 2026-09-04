package com.reader.app

import com.reader.app.core.ReaderCore
import org.junit.Assert.*
import org.junit.Test

class ReaderCoreTest {
  private val golden = "Hello Reader\n\nThis is a golden-vector article. It has two paragraphs.\n\n- one\n- two\n"

  @Test
  fun goldenDocumentId() {
    assertEquals(golden, ReaderCore.canonicalize(golden))
    assertEquals("78dad25a190e768000a895240766cbbcaab76c3d49db0129b5ba703ce5bff2d8", ReaderCore.documentId(golden))
  }

  @Test
  fun canonicalizationNormalizes() {
    assertEquals("a\n\n\nb\n", ReaderCore.canonicalize("a\r\n\r\n\r\n\r\nb   \n"))
  }

  @Test
  fun wordCountSkipsCodeAndUrls() {
    assertEquals(13, ReaderCore.wordCount(golden))
    assertEquals(1, ReaderCore.readingMinutes(13))
    assertEquals(2, ReaderCore.readingMinutes(226))
    assertEquals("1h 47m", ReaderCore.formatAttention(107))
    assertEquals("42m", ReaderCore.formatAttention(42))
  }

  @Test
  fun syncWindowIsRollingTenDays() {
    assertEquals(1725400000L - 864000L, ReaderCore.syncSince(1725400000L))
  }

  @Test
  fun rsvpPolicyMirrors() {
    assertEquals(0, ReaderCore.Rsvp.focalIndex(1))
    assertEquals(1, ReaderCore.Rsvp.focalIndex(4))
    assertEquals(2, ReaderCore.Rsvp.focalIndex(8))
    assertEquals(3, ReaderCore.Rsvp.focalIndex(12))
    assertEquals(1.0, ReaderCore.Rsvp.factor("word", false, false), 1e-9)
    assertEquals(2.2, ReaderCore.Rsvp.factor("end.", false, false), 1e-9)
    assertEquals(2.7, ReaderCore.Rsvp.factor("x", true, false), 1e-9)
    assertEquals(200L, ReaderCore.Rsvp.intervalMs(300, "word", false, false))
  }

  @Test
  fun limitsRejectAbuse() {
    try {
      ReaderCore.checkLimits(6 * 1024 * 1024, 1, 1, 1)
      fail("expected")
    } catch (e: IllegalArgumentException) {
    }
    try {
      ReaderCore.checkLimits(10, 1, 1, 513)
      fail("expected")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun escapePreventsMarkdownInjection() {
    val out = ReaderCore.escapePlainText("# not a heading *x*")
    assertTrue(out.startsWith("\\#"))
  }
}
