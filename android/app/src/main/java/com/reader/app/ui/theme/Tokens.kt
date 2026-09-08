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
import com.reader.app.R
import com.reader.app.prefs.ArticleBackground
import com.reader.app.prefs.ArticleFont

/** Flexoki tokens (kepano/flexoki). Single source per platform. */
object Flexoki {
  val Paper = Color(0xFFFFFCF0)
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
  ArticleBackground.FOLLOW_APP, ArticleBackground.PAPER -> ReaderColors(Flexoki.Paper, Flexoki.Base50, Flexoki.Black, Flexoki.Base700, Flexoki.Base200, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Blue600)
  ArticleBackground.SOFT -> ReaderColors(Flexoki.Base50, Flexoki.Base100, Flexoki.Black, Flexoki.Base700, Flexoki.Base200, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Blue600)
  ArticleBackground.INK -> ReaderColors(Flexoki.Base950, Flexoki.Base950, Flexoki.Paper, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Blue400)
  ArticleBackground.BLACK -> ReaderColors(Flexoki.Black, Flexoki.Black, Flexoki.Base100, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Blue400)
}

val LocalReaderDark = staticCompositionLocalOf { false }

fun resolvedBackground(background: ArticleBackground, dark: Boolean): ArticleBackground =
  if (background == ArticleBackground.FOLLOW_APP) {
    if (dark) ArticleBackground.INK else ArticleBackground.PAPER
  } else background

@Composable
fun readerColors(background: ArticleBackground): ReaderColors = colorsFor(resolvedBackground(background, LocalReaderDark.current))

@Composable
fun appColors(): ReaderColors = colorsFor(if (LocalReaderDark.current) ArticleBackground.INK else ArticleBackground.PAPER)

@Composable
fun ReaderTheme(mode: ThemeMode, content: @Composable () -> Unit) {
  val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
  val c = colorsFor(if (dark) ArticleBackground.INK else ArticleBackground.PAPER)
  val accentSurface = if (dark) Color(0xFF153E5A) else Color(0xFFD8E8F2)
  val scheme = if (dark) darkColorScheme(
    primary = c.link, onPrimary = c.background, primaryContainer = accentSurface, onPrimaryContainer = c.text,
    secondaryContainer = accentSurface, onSecondaryContainer = c.text,
    inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = if (dark) Flexoki.Blue600 else Flexoki.Blue400,
    secondary = c.link, onSecondary = c.background, background = c.background, onBackground = c.text,
    surface = c.surface, onSurface = c.text, surfaceVariant = c.divider, onSurfaceVariant = c.secondary,
    outline = c.secondary, error = c.error,
  ) else lightColorScheme(
    primary = c.link, onPrimary = c.background, primaryContainer = accentSurface, onPrimaryContainer = c.text,
    secondaryContainer = accentSurface, onSecondaryContainer = c.text,
    inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = if (dark) Flexoki.Blue600 else Flexoki.Blue400,
    secondary = c.link, onSecondary = c.background, background = c.background, onBackground = c.text,
    surface = c.surface, onSurface = c.text, surfaceVariant = c.divider, onSurfaceVariant = c.secondary,
    outline = c.secondary, error = c.error,
  )
  CompositionLocalProvider(LocalReaderDark provides dark) { MaterialTheme(colorScheme = scheme, content = content) }
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
}

fun marginDp(m: com.reader.app.prefs.ArticleMargin, isWide: Boolean): Int {
  val base = when (m) {
    com.reader.app.prefs.ArticleMargin.NARROW -> 12
    com.reader.app.prefs.ArticleMargin.DEFAULT -> 24
    com.reader.app.prefs.ArticleMargin.WIDE -> 48
  }
  return if (isWide) base + 24 else base
}
