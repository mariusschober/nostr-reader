package com.reader.app.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

private val Context.store by preferencesDataStore("reader_settings")

enum class ArticleFont { NEWSREADER, CRIMSON_PRO, ASUL, ATKINSON, ABEEZEE, INTER }
enum class ArticleMargin { NARROW, DEFAULT, WIDE }
enum class ArticleBackground { FOLLOW_APP, PAPER, SOFT, SEPIA, INK, BLACK }
enum class ThemeMode { SYSTEM, LIGHT, DARK, EINK }
/**
 * Display treatment, independent of Light/Dark/System. STANDARD keeps hue;
 * MONOCHROME is the app-wide E-ink / NXTPAPER presentation and follows the
 * theme's resolved darkness. The stored `EINK` theme value decodes to
 * MONOCHROME + SYSTEM so existing installs keep their appearance.
 */
enum class DisplayMode { STANDARD, MONOCHROME }
enum class LineSpacing { COMPACT, COMFORT, AIRY }
enum class LibrarySort { NEWEST, OLDEST, QUICKEST, LONGEST, TITLE }
enum class AgeFilter { ANY, TODAY, WEEK, MONTH, OLDER }

data class ReaderSettings(
  val font: ArticleFont = ArticleFont.NEWSREADER,
  val fontSizeSp: Float = 19f,
  val margin: ArticleMargin = ArticleMargin.DEFAULT,
  val background: ArticleBackground = ArticleBackground.FOLLOW_APP,
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val display: DisplayMode = DisplayMode.STANDARD,
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
    val DISPLAY = stringPreferencesKey("displayMode")
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

  val flow = ctx.store.data.onStart { materializeMigrations() }.map(::decode).distinctUntilChanged()
  suspend fun load(): ReaderSettings = flow.first()

  private fun decode(d: Preferences): ReaderSettings = decodeSettings(d)

  /**
   * Decide the one-time highlight-coach flag once, the first time settings are
   * read, and persist it before any new-install write (welcome, appearance) can
   * make the legacy-install heuristic true and wrongly skip the explanation.
   * Fresh installs persist `false`; established installs persist `true`.
   */
  private suspend fun materializeMigrations() {
    val first = ctx.store.data.first()
    val needCoach = first[K.HIGHLIGHT_COACH] == null
    val needDisplay = first[K.DISPLAY] == null
    if (!needCoach && !needDisplay) return
    val decoded = decodeSettings(first)
    ctx.store.updateData {
      it.toMutablePreferences().apply {
        if (needCoach) set(K.HIGHLIGHT_COACH, decoded.highlightCoachSeen)
        // Materialize the display migration once: a legacy `EINK` theme value
        // becomes MONOCHROME + SYSTEM, and a Standard install records STANDARD.
        if (needDisplay) {
          set(K.DISPLAY, decoded.display.name)
          set(K.THEME, decoded.themeMode.name)
        }
      }
    }
  }

  suspend fun save(s: ReaderSettings) {
    ctx.store.updateData { stored ->
      stored.toMutablePreferences().apply {
        set(K.FONT, s.font.name)
        set(K.FONT_SIZE, s.fontSizeSp)
        set(K.MARGIN, s.margin.name)
        set(K.BG, s.background.name)
        set(K.TTS, s.ttsSpeed)
        set(K.WPM, s.rsvpWpm)
        set(K.THEME, s.themeMode.name)
        set(K.DISPLAY, s.display.name)
        set(K.SPACING, s.lineSpacing.name)
        set(K.SORT, s.sort.name)
        set(K.AGE, s.age.name)
        set(K.BOLD, s.bold)
        set(K.LABELS, s.labelIds)
        set(K.UNLABELED, s.unlabeled)
        set(K.SEARCH_CURRENT, s.searchCurrentShelf)
        set(K.SEARCH_TITLES, s.searchTitlesOnly)
        if (s.archiveCoachShown) set(K.ARCHIVE_COACH, true)
        // Materialize the decision on the very first write if no read has yet;
        // thereafter the stored value wins unless the explanation was shown.
        if (stored[K.HIGHLIGHT_COACH] == null) set(K.HIGHLIGHT_COACH, decodeSettings(stored).highlightCoachSeen)
        else if (s.highlightCoachSeen) set(K.HIGHLIGHT_COACH, true)
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
  val rawTheme = d[Prefs.K.THEME]
  // The legacy `EINK` theme value predates the separate display setting. It
  // becomes MONOCHROME + SYSTEM so existing E-ink installs keep their look and
  // begin following the phone's dark setting.
  val legacyEink = rawTheme == "EINK"
  val themeMode = when {
    legacyEink -> ThemeMode.SYSTEM
    else -> runCatching { ThemeMode.valueOf(rawTheme ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
  }
  val display = when {
    d.contains(Prefs.K.DISPLAY) ->
      runCatching { DisplayMode.valueOf(d[Prefs.K.DISPLAY] ?: "STANDARD") }.getOrDefault(DisplayMode.STANDARD)
    legacyEink -> DisplayMode.MONOCHROME
    else -> DisplayMode.STANDARD
  }
  return ReaderSettings(
    font = runCatching { ArticleFont.valueOf(d[Prefs.K.FONT] ?: "NEWSREADER") }.getOrDefault(ArticleFont.NEWSREADER),
    fontSizeSp = (d[Prefs.K.FONT_SIZE] ?: 19f).coerceIn(14f, 32f),
    margin = runCatching { ArticleMargin.valueOf(d[Prefs.K.MARGIN] ?: "DEFAULT") }.getOrDefault(ArticleMargin.DEFAULT),
    // Existing explicit backgrounds survive; only new/unset installs follow the app.
    background = runCatching { ArticleBackground.valueOf(d[Prefs.K.BG] ?: "FOLLOW_APP") }.getOrDefault(ArticleBackground.FOLLOW_APP),
    themeMode = themeMode,
    display = display,
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
