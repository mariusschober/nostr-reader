package com.reader.app

import com.reader.app.ui.SourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SourceMetadataTest {
  private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }

  @Test
  fun onlyHttpUrlsAreOfferedToTheSystem() {
    assertEquals("https://example.com/a", SourceMetadata.safeHttpUrl(" https://example.com/a "))
    assertEquals("http://example.com/a", SourceMetadata.safeHttpUrl("http://example.com/a"))
    assertNull(SourceMetadata.safeHttpUrl("javascript:alert(1)"))
    assertNull(SourceMetadata.safeHttpUrl("data:text/html,hello"))
    assertNull(SourceMetadata.safeHttpUrl("file:///etc/passwd"))
    assertNull(SourceMetadata.safeHttpUrl("/relative"))
    assertNull(SourceMetadata.safeHttpUrl("https://user:pass@example.com/a"))
    assertNull(SourceMetadata.safeHttpUrl(""))
    assertNull(SourceMetadata.safeHttpUrl(null))
  }

  @Test
  fun metadataLineIsReadableAndNormalizesSecondTimestamps() {
    assertEquals("Ada Lovelace · Example Weekly", SourceMetadata.line("Ada Lovelace", "Example Weekly", null, date))
    assertEquals("Ada Lovelace · 2027-01-15", SourceMetadata.line("Ada Lovelace", null, 1_800_000_000L, date))
    assertEquals("Ada Lovelace · 2027-01-15", SourceMetadata.line("Ada Lovelace", null, 1_800_000_000_000L, date))
    assertNull(SourceMetadata.line(" ", null, 0, date))
  }
}
