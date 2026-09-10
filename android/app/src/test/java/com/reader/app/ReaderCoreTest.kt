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
      ReaderCore.checkLimits(6 * 1024 * 1024, 1, 1, 1, 1)
      fail("expected")
    } catch (e: IllegalArgumentException) {
    }
    try {
      ReaderCore.checkLimits(10, 1, 1, 1, 513)
      fail("expected")
    } catch (e: IllegalArgumentException) {
    }
  }

  @Test
  fun escapePreventsMarkdownInjection() {
    val out = ReaderCore.escapePlainText("# not a heading *x*")
    assertTrue(out.startsWith("\\#"))
  }

  @Test
  fun formatAgeBands() {
    val now = 1_700_000_000_000L
    assertEquals("Just now", ReaderCore.formatAge(now, now))
    assertEquals("Just now", ReaderCore.formatAge(now + 60_000L, now))
    assertEquals("Just now", ReaderCore.formatAge(now - 30_000L, now))
    assertEquals("5m ago", ReaderCore.formatAge(now - 5 * 60_000L, now))
    assertEquals("1m ago", ReaderCore.formatAge(now - 60_000L, now))
    assertEquals("3h ago", ReaderCore.formatAge(now - 3 * 3_600_000L, now))
    assertEquals("3d ago", ReaderCore.formatAge(now - 3 * 86_400_000L, now))
    assertTrue(ReaderCore.formatAge(now - 40 * 86_400_000L, now).matches(Regex("[A-Z][a-z]{2} \\d{1,2}")))
    assertTrue(ReaderCore.formatAge(now - 400 * 86_400_000L, now).matches(Regex("[A-Z][a-z]{2} \\d{1,2}, \\d{4}")))
  }

  @Test
  fun shortDisplaySourcePrefersNameThenHost() {    assertEquals("Example", ReaderCore.shortDisplaySource("url", "Example", "https://example.com/x"))
    assertEquals("wikipedia.org", ReaderCore.shortDisplaySource("url", null, "https://en.wikipedia.org/wiki/X"))
    assertEquals("wikipedia.org", ReaderCore.shortDisplaySource("url", "  ", "https://www.wikipedia.org/"))
    assertEquals("ChatGPT", ReaderCore.shortDisplaySource("chatgpt", null, null))
    assertEquals("Shared", ReaderCore.shortDisplaySource("android-share", null, null))
    assertEquals("Paste", ReaderCore.shortDisplaySource("paste", null, null))
    assertEquals("Link", ReaderCore.shortDisplaySource("link", null, "not a url"))
    assertEquals("Saved", ReaderCore.shortDisplaySource("", null, null))
  }

  @Test
  fun registrableHostShortensKnownPrefixesOnly() {
    assertEquals("wikipedia.org", ReaderCore.registrableHost("en.wikipedia.org"))
    assertEquals("wikipedia.org", ReaderCore.registrableHost("m.wikipedia.org"))
    assertEquals("x.com", ReaderCore.registrableHost("www.x.com"))
    assertEquals("substack.com", ReaderCore.registrableHost("marius.substack.com"))
    assertEquals("bbc.co.uk", ReaderCore.registrableHost("bbc.co.uk"))
    assertEquals("example.co.uk", ReaderCore.registrableHost("example.co.uk"))
    assertEquals("example.com", ReaderCore.registrableHost("example.com"))
    assertEquals("1.2.3.4", ReaderCore.registrableHost("1.2.3.4"))
    assertEquals("localhost", ReaderCore.registrableHost("localhost"))
    assertEquals("", ReaderCore.registrableHost(""))
  }
}
