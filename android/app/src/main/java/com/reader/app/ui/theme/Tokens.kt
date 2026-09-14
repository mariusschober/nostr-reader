package com.reader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.reader.app.prefs.ThemeMode
import com.reader.app.prefs.DisplayMode
import com.reader.app.R
import com.reader.app.prefs.ArticleBackground
import com.reader.app.prefs.ArticleFont

/** Flexoki tokens (kepano/flexoki). Single source per platform. */
object Flexoki {
  val Paper = Color(0xFFFFFCF0)
  /**
   * Reader Paper surface — the warmer Financial Times paper tone (#FFF1E5).
   * Kept separate from [Paper], which is also used as light text on the dark
   * Ink/Black themes; only the Paper reading/app surface changes.
   */
  val ReaderPaper = Color(0xFFFFF1E5)
  val ReaderPaperSurface = Color(0xFFFBE7D7)
  val ReaderPaperDivider = Color(0xFFE9D6C4)
  val Base50 = Color(0xFFF2F0E5)
  val Base100 = Color(0xFFE6E4D9)
  val Base200 = Color(0xFFCECDC3)
  val Base300 = Color(0xFFB7B5AC)
  val Base400 = Color(0xFF9F9D96)
  val Base600 = Color(0xFF6F6E69)
  val Base700 = Color(0xFF575653)
  val Base800 = Color(0xFF403E3C)
  val Base900 = Color(0xFF282726)
  val Base950 = Color(0xFF1C1B1A)
  val Black = Color(0xFF100F0F)
  val Red600 = Color(0xFFAF3029)
  val Orange600 = Color(0xFFBC5215)
  val Green600 = Color(0xFF66800B)
  val Blue600 = Color(0xFF015A97)
  val Red400 = Color(0xFFD14D41)
  val Orange400 = Color(0xFFDA702C)
  val Green400 = Color(0xFF879A39)
  val Blue400 = Color(0xFF70B5E8)
  /** Important-star tint (requested highlight yellow). Single value for both
   * themes; verified against Paper and Ink on-device. */
  val StarYellow = Color(0xFFECCB60)
}

data class ReaderColors(
  val background: Color,
  val surface: Color,
  val text: Color,
  val secondary: Color,
  val divider: Color,
  val link: Color,
  val success: Color,
  val warning: Color,
  val error: Color,
  val focal: Color,
)

fun colorsFor(bg: ArticleBackground): ReaderColors = when (bg) {
  ArticleBackground.FOLLOW_APP, ArticleBackground.PAPER -> ReaderColors(Flexoki.ReaderPaper, Flexoki.ReaderPaperSurface, Flexoki.Black, Flexoki.Base700, Flexoki.ReaderPaperDivider, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Blue600)
  ArticleBackground.SOFT -> ReaderColors(Flexoki.Base50, Flexoki.Base100, Flexoki.Black, Flexoki.Base700, Flexoki.Base200, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Blue600)
  // Sepia: warm mid-tone for evening reading; contrast pairs still AAA.
  ArticleBackground.SEPIA -> ReaderColors(
    Color(0xFFF4ECD8), Color(0xFFEAE0C8), Color(0xFF292524), Color(0xFF6E6A5E),
    Color(0xFFD8CCB2), Color(0xFF2A5C8F), Color(0xFF566D0E), Color(0xFF96591B), Color(0xFF9A3324), Color(0xFF2A5C8F),
  )
  ArticleBackground.INK -> ReaderColors(Flexoki.Base950, Flexoki.Base950, Flexoki.Paper, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Blue400)
  ArticleBackground.BLACK -> ReaderColors(Flexoki.Black, Flexoki.Black, Flexoki.Base100, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Blue400)
}

/**
 * E-ink / NXTPAPER monochrome palette: a pure white ground, black primary
 * text and a #333 secondary, white surfaces with a visible border rather than
 * tinted fills, and black links that also carry an underline. Hue never
 * carries meaning in this theme.
 */
/** Monochrome light: white ground, black text/edges, #333 secondary. */
val EinkColors = ReaderColors(
  background = Color(0xFFFFFFFF),
  surface = Color(0xFFFFFFFF),
  text = Color(0xFF000000),
  secondary = Color(0xFF333333),
  divider = Color(0xFF000000),
  link = Color(0xFF000000),
  success = Color(0xFF000000),
  warning = Color(0xFF000000),
  error = Color(0xFF000000),
  focal = Color(0xFF000000),
)

/** Monochrome dark: black ground, white text/edges, #CCCCCC secondary. */
val EinkColorsDark = ReaderColors(
  background = Color(0xFF000000),
  surface = Color(0xFF000000),
  text = Color(0xFFFFFFFF),
  secondary = Color(0xFFCCCCCC),
  divider = Color(0xFFFFFFFF),
  link = Color(0xFFFFFFFF),
  success = Color(0xFFFFFFFF),
  warning = Color(0xFFFFFFFF),
  error = Color(0xFFFFFFFF),
  focal = Color(0xFFFFFFFF),
)

/** Resolved monochrome palette for the current darkness. */
fun einkColors(dark: Boolean): ReaderColors = if (dark) EinkColorsDark else EinkColors

/**
 * Effective presentation policy for the current theme. [monochrome] drives
 * the e-ink palette and non-colour state indicators; [reducedMotion] swaps
 * animated transitions for instant changes while leaving native drag physics
 * and direct touch scrolling intact; [dark] is the resolved darkness so a
 * nested reader theme can preserve monochrome and its Light/Dark/System
 * choice instead of inferring it from the stored article background.
 */
data class DisplayPolicy(
  val monochrome: Boolean = false,
  val reducedMotion: Boolean = false,
  val dark: Boolean = false,
)

val LocalReaderDark = staticCompositionLocalOf { false }
val LocalDisplayPolicy = staticCompositionLocalOf { DisplayPolicy() }

fun resolvedBackground(background: ArticleBackground, dark: Boolean): ArticleBackground =
  if (background == ArticleBackground.FOLLOW_APP) {
    if (dark) ArticleBackground.INK else ArticleBackground.PAPER
  } else background

@Composable
fun readerColors(background: ArticleBackground): ReaderColors =
  if (LocalDisplayPolicy.current.monochrome) einkColors(LocalDisplayPolicy.current.dark)
  else colorsFor(resolvedBackground(background, LocalReaderDark.current))

@Composable
fun appColors(): ReaderColors =
  if (LocalDisplayPolicy.current.monochrome) einkColors(LocalDisplayPolicy.current.dark)
  else colorsFor(if (LocalReaderDark.current) ArticleBackground.INK else ArticleBackground.PAPER)

@Composable
fun ReaderTheme(mode: ThemeMode, display: DisplayMode = DisplayMode.STANDARD, content: @Composable () -> Unit) {
  val outer = LocalDisplayPolicy.current
  val systemReduceMotion = rememberReduceMotion()
  // A nested ReaderTheme (the reading surface re-themes itself LIGHT/DARK)
  // must not silently drop an active monochrome override, so inherit it.
  val monochrome = display == DisplayMode.MONOCHROME || mode == ThemeMode.EINK || outer.monochrome
  // In monochrome the app's Light/Dark/System choice owns both article and
  // chrome; a nested reader theme keeps the resolved darkness rather than
  // re-deriving it from the stored article background.
  val dark = if (outer.monochrome) outer.dark
    else when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
  val reducedMotion = monochrome || systemReduceMotion || outer.reducedMotion
  val c = if (monochrome) einkColors(dark) else colorsFor(if (dark) ArticleBackground.INK else ArticleBackground.PAPER)
  val accentSurface = if (monochrome) c.surface else if (dark) Color(0xFF153E5A) else Color(0xFFD8E8F2)
  val scheme = when {
    monochrome -> lightColorScheme(
      // Selected containers invert to a black fill with white content so a
      // chosen chip or indicator is unambiguous without relying on hue.
      primary = c.text, onPrimary = c.background, primaryContainer = c.text, onPrimaryContainer = c.background,
      secondaryContainer = c.text, onSecondaryContainer = c.background,
      inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = c.background,
      secondary = c.text, onSecondary = c.background, background = c.background, onBackground = c.text,
      surface = c.surface, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.secondary,
      outline = c.secondary, error = c.error,
    )
    dark -> darkColorScheme(
      primary = c.link, onPrimary = c.background, primaryContainer = accentSurface, onPrimaryContainer = c.text,
      secondaryContainer = accentSurface, onSecondaryContainer = c.text,
      inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = if (dark) Flexoki.Blue600 else Flexoki.Blue400,
      secondary = c.link, onSecondary = c.background, background = c.background, onBackground = c.text,
      surface = c.surface, onSurface = c.text, surfaceVariant = c.divider, onSurfaceVariant = c.secondary,
      outline = c.secondary, error = c.error,
    )
    else -> lightColorScheme(
      primary = c.link, onPrimary = c.background, primaryContainer = accentSurface, onPrimaryContainer = c.text,
      secondaryContainer = accentSurface, onSecondaryContainer = c.text,
      inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = if (dark) Flexoki.Blue600 else Flexoki.Blue400,
      secondary = c.link, onSecondary = c.background, background = c.background, onBackground = c.text,
      surface = c.surface, onSurface = c.text, surfaceVariant = c.divider, onSurfaceVariant = c.secondary,
      outline = c.secondary, error = c.error,
    )
  }
  CompositionLocalProvider(
    LocalReaderDark provides dark,
    LocalDisplayPolicy provides DisplayPolicy(monochrome = monochrome, reducedMotion = reducedMotion, dark = dark),
  ) { MaterialTheme(colorScheme = scheme, typography = readerTypography(), content = content) }
}


@Composable
private fun readerTypography(): androidx.compose.material3.Typography {
  val base = androidx.compose.material3.Typography()
  return androidx.compose.material3.Typography(
    displayLarge = base.displayLarge.copy(fontFamily = ReaderFonts.Ui),
    displayMedium = base.displayMedium.copy(fontFamily = ReaderFonts.Ui),
    displaySmall = base.displaySmall.copy(fontFamily = ReaderFonts.Ui),
    headlineLarge = base.headlineLarge.copy(fontFamily = ReaderFonts.Ui),
    headlineMedium = base.headlineMedium.copy(fontFamily = ReaderFonts.Ui),
    headlineSmall = base.headlineSmall.copy(fontFamily = ReaderFonts.Ui),
    titleLarge = base.titleLarge.copy(fontFamily = ReaderFonts.Ui),
    titleMedium = base.titleMedium.copy(fontFamily = ReaderFonts.Ui),
    titleSmall = base.titleSmall.copy(fontFamily = ReaderFonts.Ui),
    bodyLarge = base.bodyLarge.copy(fontFamily = ReaderFonts.Ui),
    bodyMedium = base.bodyMedium.copy(fontFamily = ReaderFonts.Ui),
    bodySmall = base.bodySmall.copy(fontFamily = ReaderFonts.Ui),
    labelLarge = base.labelLarge.copy(fontFamily = ReaderFonts.Ui),
    labelMedium = base.labelMedium.copy(fontFamily = ReaderFonts.Ui),
    labelSmall = base.labelSmall.copy(fontFamily = ReaderFonts.Ui),
  )
}

/** Bundled article fonts (assets/fonts). No network loading. */
@OptIn(ExperimentalTextApi::class)
object ReaderFonts {
  val Newsreader = FontFamily(
    Font(R.font.newsreader_var, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.newsreader_var, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.newsreader_var, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
  )
  val CrimsonPro = FontFamily(
    Font(R.font.crimsonpro_var, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.crimsonpro_var, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
  )
  val Asul = FontFamily(
    Font(R.font.asul_regular, FontWeight.Normal),
    Font(R.font.asul_bold, FontWeight.Bold),
  )
  val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
  )
  val Atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
  )
  val ABeeZee = FontFamily(
    Font(R.font.abeezee_regular, FontWeight.Normal),
  )
  /** App chrome itself is always Atkinson. */
  val Ui = Atkinson
}

fun fontFor(f: ArticleFont): FontFamily = when (f) {
  ArticleFont.NEWSREADER -> ReaderFonts.Newsreader
  ArticleFont.CRIMSON_PRO -> ReaderFonts.CrimsonPro
  ArticleFont.ASUL -> ReaderFonts.Asul
  ArticleFont.ATKINSON -> ReaderFonts.Atkinson
  ArticleFont.ABEEZEE -> ReaderFonts.ABeeZee
  ArticleFont.INTER -> ReaderFonts.Inter
}

fun marginDp(m: com.reader.app.prefs.ArticleMargin, isWide: Boolean): Int {
  val base = when (m) {
    com.reader.app.prefs.ArticleMargin.NARROW -> 12
    com.reader.app.prefs.ArticleMargin.DEFAULT -> 24
    com.reader.app.prefs.ArticleMargin.WIDE -> 48
  }
  return if (isWide) base + 24 else base
}
