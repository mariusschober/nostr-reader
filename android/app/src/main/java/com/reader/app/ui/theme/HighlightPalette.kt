package com.reader.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Flexoki extended palette, kepano/flexoki (MIT). Opaque fills avoid compositing ambiguity. */
enum class HighlightColor(val label: String, val light: Long, val dark: Long) {
  YELLOW("Yellow", 0xFFF6E2A0, 0xFF664D01),
  GREEN("Green", 0xFFDDE2B2, 0xFF3D4C07),
  CYAN("Cyan", 0xFFBFE8D9, 0xFF164F4A),
  PURPLE("Purple", 0xFFE2D9E9, 0xFF3C2A62);
  fun background(isDark: Boolean) = Color(if (isDark) dark else light)
  companion object {
    fun parse(name: String) = entries.firstOrNull { it.name == name } ?: YELLOW
    fun text(isDark: Boolean) = if (isDark) Flexoki.Paper else Flexoki.Black
  }
}
