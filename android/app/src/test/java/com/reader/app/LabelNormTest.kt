package com.reader.app

import com.reader.app.data.LabelNorm
import org.junit.Assert.*
import org.junit.Test

class LabelNormTest {
  @Test fun normalizationRules() {
    assertEquals("climate", LabelNorm.normalize("  Climate  "))
    assertEquals("sci-fi", LabelNorm.normalize("#Sci-Fi"))
    assertEquals("a b", LabelNorm.normalize("a   b"))
    assertNull(LabelNorm.normalize(""))
    assertNull(LabelNorm.normalize("   "))
    assertNull(LabelNorm.normalize("#"))
    assertNull(LabelNorm.normalize("a".repeat(51)))
    assertNull(LabelNorm.normalize("a,b"))
    assertNull(LabelNorm.normalize("a\"b"))
    assertEquals("北京", LabelNorm.normalize("北京"))
  }

  @Test fun displayPreservesCasing() {
    assertEquals("Sci-Fi", LabelNorm.display("#Sci-Fi"))
    assertEquals("Climate", LabelNorm.display("  Climate  "))
    assertNull(LabelNorm.display("  "))
  }
}
