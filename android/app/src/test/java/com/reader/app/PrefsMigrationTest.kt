package com.reader.app

import androidx.datastore.preferences.core.preferencesOf
import com.reader.app.prefs.ArticleFont
import com.reader.app.prefs.Prefs
import com.reader.app.prefs.ThemeMode
import com.reader.app.prefs.decodeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preference migration: the one-time highlight explanation must show once for a
 * new install and never again for an existing one, and unknown stored values
 * must fall back to defaults rather than throwing.
 */
class PrefsMigrationTest {
  @Test fun freshInstallStartsWithTheCoachUnseen() {
    val s = decodeSettings(preferencesOf())
    assertFalse("A fresh install must show the highlight explanation once", s.highlightCoachSeen)
    assertEquals(ArticleFont.NEWSREADER, s.font)
    assertEquals(ThemeMode.SYSTEM, s.themeMode)
  }

  @Test fun existingInstallIsTreatedAsAlreadySeen() {
    // Any previously saved preference marks the install as pre-existing.
    assertTrue(decodeSettings(preferencesOf(Prefs.K.FONT to "ASUL")).highlightCoachSeen)
    assertTrue(decodeSettings(preferencesOf(Prefs.K.WELCOME to true)).highlightCoachSeen)
    assertTrue(decodeSettings(preferencesOf(Prefs.K.THEME to "DARK")).highlightCoachSeen)
  }

  @Test fun anExplicitFlagAlwaysWins() {
    assertTrue(decodeSettings(preferencesOf(Prefs.K.HIGHLIGHT_COACH to true)).highlightCoachSeen)
    assertFalse(
      "An explicit false must not be overridden by the legacy-install guess",
      decodeSettings(preferencesOf(Prefs.K.HIGHLIGHT_COACH to false, Prefs.K.FONT to "ASUL")).highlightCoachSeen,
    )
  }

  @Test fun unknownStoredValuesFallBackToDefaults() {
    val s = decodeSettings(preferencesOf(Prefs.K.FONT to "NOT_A_FONT", Prefs.K.THEME to "NOT_A_THEME"))
    assertEquals(ArticleFont.NEWSREADER, s.font)
    assertEquals(ThemeMode.SYSTEM, s.themeMode)
  }

  @Test fun einkThemeModeDecodesFromStorage() {
    assertEquals("The e-ink theme must persist without renaming existing values",
      ThemeMode.EINK, decodeSettings(preferencesOf(Prefs.K.THEME to "EINK")).themeMode)
  }
}
