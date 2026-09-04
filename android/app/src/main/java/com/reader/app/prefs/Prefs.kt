package com.reader.app.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("reader_settings")

enum class ArticleFont { NEWSREADER, CRIMSON_PRO, ASUL, ATKINSON, ABEEZEE }
enum class ArticleMargin { NARROW, DEFAULT, WIDE }
enum class ArticleBackground { PAPER, SOFT, INK, BLACK }

data class ReaderSettings(
  val font: ArticleFont = ArticleFont.NEWSREADER,
  val fontSizeSp: Float = 19f,
  val margin: ArticleMargin = ArticleMargin.DEFAULT,
  val background: ArticleBackground = ArticleBackground.PAPER,
  val ttsSpeed: Float = 1.0f,
  val rsvpWpm: Int = 300,
)

class Prefs(private val ctx: Context) {
  private object K {
    val FONT = stringPreferencesKey("font")
    val FONT_SIZE = floatPreferencesKey("fontSize")
    val MARGIN = stringPreferencesKey("margin")
    val BG = stringPreferencesKey("bg")
    val TTS = floatPreferencesKey("tts")
    val WPM = intPreferencesKey("wpm")
  }

  suspend fun load(): ReaderSettings {
    val d = ctx.store.data.map { it }.first()
    return ReaderSettings(
      font = runCatching { ArticleFont.valueOf(d[K.FONT] ?: "NEWSREADER") }.getOrDefault(ArticleFont.NEWSREADER),
      fontSizeSp = (d[K.FONT_SIZE] ?: 19f).coerceIn(14f, 32f),
      margin = runCatching { ArticleMargin.valueOf(d[K.MARGIN] ?: "DEFAULT") }.getOrDefault(ArticleMargin.DEFAULT),
      background = runCatching { ArticleBackground.valueOf(d[K.BG] ?: "PAPER") }.getOrDefault(ArticleBackground.PAPER),
      ttsSpeed = (d[K.TTS] ?: 1f).coerceIn(0.75f, 2.5f),
      rsvpWpm = (d[K.WPM] ?: 300).coerceIn(100, 1200),
    )
  }

  suspend fun save(s: ReaderSettings) {
    ctx.store.updateData {
      it.toMutablePreferences().apply {
        set(K.FONT, s.font.name)
        set(K.FONT_SIZE, s.fontSizeSp)
        set(K.MARGIN, s.margin.name)
        set(K.BG, s.background.name)
        set(K.TTS, s.ttsSpeed)
        set(K.WPM, s.rsvpWpm)
      }
    }
  }
}
