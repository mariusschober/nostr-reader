package com.reader.app.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.store by preferencesDataStore("reader_settings")

enum class ArticleFont { NEWSREADER, CRIMSON_PRO, ASUL, ATKINSON, ABEEZEE, INTER }
enum class ArticleMargin { NARROW, DEFAULT, WIDE }
enum class ArticleBackground { FOLLOW_APP, PAPER, SOFT, SEPIA, INK, BLACK }
enum class ThemeMode { SYSTEM, LIGHT, DARK, EINK }
enum class LineSpacing { COMPACT, COMFORT, AIRY }
enum class LibrarySort { NEWEST, OLDEST, QUICKEST, LONGEST, TITLE }
enum class AgeFilter { ANY, TODAY, WEEK, MONTH, OLDER }

data class ReaderSettings(
  val font: ArticleFont = ArticleFont.NEWSREADER,
  val fontSizeSp: Float = 19f,
  val margin: ArticleMargin = ArticleMargin.DEFAULT,
  val background: ArticleBackground = ArticleBackground.FOLLOW_APP,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val ttsSpeed: Float = 1.0f,
  val rsvpWpm: Int = 300,
  val lineSpacing: LineSpacing = LineSpacing.COMFORT,
  val sort: LibrarySort = LibrarySort.NEWEST,
  val age: AgeFilter = AgeFilter.ANY,
  /** Bold body text — a low-vision/comfort pairing, real weight not fake. */
  val bold: Boolean = false,
  val labelIds: Set<String> = emptySet(),
  val unlabeled: Boolean = false,
  val searchCurrentShelf: Boolean = false,
  val searchTitlesOnly: Boolean = false,
  /** One-time gestures coach shown on the first Archive visit. */
  val archiveCoachShown: Boolean = false,
  /**
   * One-time continuous-highlighting explanation. New installs show it on the
   * first Highlight tap; installs that already have preferences start seen so
   * the user who has already highlighted is not re-taught.
   */
  val highlightCoachSeen: Boolean = false,
) {
  fun lineHeightMultiplier(): Float = when (lineSpacing) {
    LineSpacing.COMPACT -> 1.35f
    LineSpacing.COMFORT -> 1.50f
    LineSpacing.AIRY -> 1.65f
  }
}

class Prefs(private val ctx: Context) {
  internal object K {
    val FONT = stringPreferencesKey("font")
    val FONT_SIZE = floatPreferencesKey("fontSize")
    val MARGIN = stringPreferencesKey("margin")
    val BG = stringPreferencesKey("bg")
    val TTS = floatPreferencesKey("tts")
    val WPM = intPreferencesKey("wpm")
    val THEME = stringPreferencesKey("themeMode")
    val SPACING = stringPreferencesKey("lineSpacing")
    val SORT = stringPreferencesKey("sort")
    val AGE = stringPreferencesKey("age")
    val LABELS = stringSetPreferencesKey("selectedLabelIds")
    val UNLABELED = booleanPreferencesKey("unlabeledOnly")
    val SEARCH_CURRENT = booleanPreferencesKey("searchCurrentShelf")
    val SEARCH_TITLES = booleanPreferencesKey("searchTitlesOnly")
    val BOLD = booleanPreferencesKey("boldText")
    val ARCHIVE_COACH = booleanPreferencesKey("archiveCoachShown")
    val HIGHLIGHT_COACH = booleanPreferencesKey("highlightCoachSeen")
    val WELCOME = booleanPreferencesKey("welcomeShown")
    val MILESTONES = stringSetPreferencesKey("milestonesDone")
  }

  val flow = ctx.store.data.map(::decode).distinctUntilChanged()
  suspend fun load(): ReaderSettings = flow.first()

  private fun decode(d: Preferences): ReaderSettings = decodeSettings(d)

  suspend fun save(s: ReaderSettings) {
    ctx.store.updateData {
      it.toMutablePreferences().apply {
        set(K.FONT, s.font.name)
        set(K.FONT_SIZE, s.fontSizeSp)
        set(K.MARGIN, s.margin.name)
        set(K.BG, s.background.name)
        set(K.TTS, s.ttsSpeed)
        set(K.WPM, s.rsvpWpm)
        set(K.THEME, s.themeMode.name)
        set(K.SPACING, s.lineSpacing.name)
        set(K.SORT, s.sort.name)
        set(K.AGE, s.age.name)
        set(K.BOLD, s.bold)
        set(K.LABELS, s.labelIds)
        set(K.UNLABELED, s.unlabeled)
        set(K.SEARCH_CURRENT, s.searchCurrentShelf)
        set(K.SEARCH_TITLES, s.searchTitlesOnly)
        if (s.archiveCoachShown) set(K.ARCHIVE_COACH, true)
        if (s.highlightCoachSeen) set(K.HIGHLIGHT_COACH, true)
      }
    }
  }

  suspend fun isWelcomeShown(): Boolean = ctx.store.data.map { it[K.WELCOME] == true }.first()
  suspend fun setWelcomeShown() {
    ctx.store.updateData { it.toMutablePreferences().apply { set(K.WELCOME, true) } }
  }

  /** Milestone keys already celebrated (each fires once per install). */
  suspend fun takeMilestone(key: String): Boolean {
    var took = false
    ctx.store.updateData {
      val done = it[K.MILESTONES].orEmpty()
      if (key !in done) {
        took = true
        it.toMutablePreferences().apply { set(K.MILESTONES, done + key) }
      } else it.toMutablePreferences()
    }
    return took
  }
}

/**
 * Pure decode so the migration rules are testable without a Context.
 *
 * Any previously saved preference implies an existing install, which marks the
 * one-time highlight explanation as already seen so an established reader is
 * not re-taught. A fresh install has no keys and starts unseen. An explicitly
 * stored flag always wins.
 */
internal fun decodeSettings(d: Preferences): ReaderSettings {
  fun legacyInstall(): Boolean =
    listOf(
      Prefs.K.FONT, Prefs.K.FONT_SIZE, Prefs.K.MARGIN, Prefs.K.BG, Prefs.K.THEME,
      Prefs.K.SPACING, Prefs.K.SORT, Prefs.K.AGE, Prefs.K.BOLD, Prefs.K.WELCOME,
    ).any { d.contains(it) }
  return ReaderSettings(
    font = runCatching { ArticleFont.valueOf(d[Prefs.K.FONT] ?: "NEWSREADER") }.getOrDefault(ArticleFont.NEWSREADER),
    fontSizeSp = (d[Prefs.K.FONT_SIZE] ?: 19f).coerceIn(14f, 32f),
    margin = runCatching { ArticleMargin.valueOf(d[Prefs.K.MARGIN] ?: "DEFAULT") }.getOrDefault(ArticleMargin.DEFAULT),
    // Existing explicit backgrounds survive; only new/unset installs follow the app.
    background = runCatching { ArticleBackground.valueOf(d[Prefs.K.BG] ?: "FOLLOW_APP") }.getOrDefault(ArticleBackground.FOLLOW_APP),
    themeMode = runCatching { ThemeMode.valueOf(d[Prefs.K.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
    ttsSpeed = (d[Prefs.K.TTS] ?: 1f).coerceIn(0.75f, 2.5f),
    rsvpWpm = (d[Prefs.K.WPM] ?: 300).coerceIn(100, 1200),
    lineSpacing = runCatching { LineSpacing.valueOf(d[Prefs.K.SPACING] ?: "COMFORT") }.getOrDefault(LineSpacing.COMFORT),
    sort = runCatching { LibrarySort.valueOf(d[Prefs.K.SORT] ?: "NEWEST") }.getOrDefault(LibrarySort.NEWEST),
    age = runCatching { AgeFilter.valueOf(d[Prefs.K.AGE] ?: "ANY") }.getOrDefault(AgeFilter.ANY),
    bold = d[Prefs.K.BOLD] == true,
    labelIds = d[Prefs.K.LABELS].orEmpty(),
    unlabeled = d[Prefs.K.UNLABELED] == true,
    searchCurrentShelf = d[Prefs.K.SEARCH_CURRENT] == true,
    searchTitlesOnly = d[Prefs.K.SEARCH_TITLES] == true,
    archiveCoachShown = d[Prefs.K.ARCHIVE_COACH] == true,
    highlightCoachSeen = d[Prefs.K.HIGHLIGHT_COACH] ?: legacyInstall(),
  )
}
