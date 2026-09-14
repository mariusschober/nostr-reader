package com.reader.app

import androidx.datastore.preferences.core.preferencesOf
import com.reader.app.prefs.ArticleFont
import com.reader.app.prefs.DisplayMode
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

  @Test fun materializedFreshDecisionSurvivesALaterWelcomeWrite() {
    // Regression for F11: the first read on an empty store decides `false`, and
    // that decision must be persisted *before* the optional sample writes its
    // welcome flag. Without materialization the later welcome made the decoder
    // treat the fresh install as legacy and silently skip the explanation.
    val decision = decodeSettings(preferencesOf()).highlightCoachSeen
    assertFalse("A fresh store decides unseen", decision)
    val afterWelcome = decodeSettings(preferencesOf(Prefs.K.HIGHLIGHT_COACH to decision, Prefs.K.WELCOME to true))
    assertFalse("The materialized decision must survive the welcome write", afterWelcome.highlightCoachSeen)
  }

  @Test fun unknownStoredValuesFallBackToDefaults() {
    val s = decodeSettings(preferencesOf(Prefs.K.FONT to "NOT_A_FONT", Prefs.K.THEME to "NOT_A_THEME"))
    assertEquals(ArticleFont.NEWSREADER, s.font)
    assertEquals(ThemeMode.SYSTEM, s.themeMode)
  }

  @Test fun einkThemeModeDecodesFromStorage() {
    // A legacy `EINK` theme value migrates to monochrome + System so the
    // existing install keeps its look and starts following the phone setting.
    val s = decodeSettings(preferencesOf(Prefs.K.THEME to "EINK"))
    assertEquals(ThemeMode.SYSTEM, s.themeMode)
    assertEquals(DisplayMode.MONOCHROME, s.display)
  }

  @Test fun displayDefaultsToStandardForEveryNonEinkInstall() {
    assertEquals(DisplayMode.STANDARD, decodeSettings(preferencesOf()).display)
    assertEquals(DisplayMode.STANDARD, decodeSettings(preferencesOf(Prefs.K.FONT to "ASUL")).display)
    assertEquals(DisplayMode.STANDARD, decodeSettings(preferencesOf(Prefs.K.THEME to "DARK")).display)
  }

  @Test fun explicitDisplayValueWinsOverLegacyTheme() {
    // Once the migration has materialized, an explicit STANDARD display must
    // not be re-derived to MONOCHROME from a stale theme value.
    val s = decodeSettings(preferencesOf(Prefs.K.DISPLAY to "STANDARD", Prefs.K.THEME to "EINK"))
    assertEquals(DisplayMode.STANDARD, s.display)
    assertEquals(ThemeMode.SYSTEM, s.themeMode)
    assertEquals(DisplayMode.MONOCHROME, decodeSettings(preferencesOf(Prefs.K.DISPLAY to "MONOCHROME")).display)
    assertEquals("Unknown display values resolve safely to Standard",
      DisplayMode.STANDARD, decodeSettings(preferencesOf(Prefs.K.DISPLAY to "NOT_A_MODE")).display)
  }
}
