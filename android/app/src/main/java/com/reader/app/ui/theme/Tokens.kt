package com.reader.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
  val Base800 = Color(0xFF403E3C)
  val Base900 = Color(0xFF282726)
  val Base950 = Color(0xFF1C1B1A)
  val Black = Color(0xFF100F0F)
  val Red600 = Color(0xFFAF3029)
  val Orange600 = Color(0xFFBC5215)
  val Green600 = Color(0xFF66800B)
  val Blue600 = Color(0xFF205EA6)
  val Red400 = Color(0xFFD14D41)
  val Orange400 = Color(0xFFDA702C)
  val Green400 = Color(0xFF879A39)
  val Blue400 = Color(0xFF4385BE)
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
  ArticleBackground.PAPER -> ReaderColors(Flexoki.Paper, Flexoki.Paper, Flexoki.Black, Flexoki.Base600, Flexoki.Base200, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Red600)
  ArticleBackground.SOFT -> ReaderColors(Flexoki.Base50, Flexoki.Base50, Flexoki.Black, Flexoki.Base600, Flexoki.Base200, Flexoki.Blue600, Flexoki.Green600, Flexoki.Orange600, Flexoki.Red600, Flexoki.Red600)
  ArticleBackground.INK -> ReaderColors(Flexoki.Base950, Flexoki.Base950, Flexoki.Paper, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Red400)
  ArticleBackground.BLACK -> ReaderColors(Flexoki.Black, Flexoki.Black, Flexoki.Base100, Flexoki.Base400, Flexoki.Base800, Flexoki.Blue400, Flexoki.Green400, Flexoki.Orange400, Flexoki.Red400, Flexoki.Red400)
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
