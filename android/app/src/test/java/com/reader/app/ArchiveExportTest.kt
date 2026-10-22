package com.reader.app

import com.reader.app.data.ArchiveExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExportTest {
  @Test
  fun filenameIsTimestampedUtcAndZipTyped() {
    // 2026-10-22T15:06:07Z
    val name = ArchiveExport.filename(1_792_681_567_000L)
    assertEquals("reader-archive-20261022-150607.zip", name)
  }

  @Test
  fun filenameAlwaysHasSafeAsciiShape() {
    val name = ArchiveExport.filename(0L)
    assertTrue(name.matches(Regex("reader-archive-\\d{8}-\\d{6}\\.zip")))
  }
}
