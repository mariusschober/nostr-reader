package com.reader.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Monochrome edge styles that survive gray compression on e-ink panels. */
enum class MonoEdge { SOLID, DOUBLE, DASHED, DOTTED }

/**
 * One shared presentation mapping for highlight color identity.
 * Storage keeps YELLOW/GREEN/CYAN/PURPLE; this resolves how each one looks
 * in the current theme without touching stored color IDs or anchors.
 */
data class HighlightPresentation(
  val label: String,
  val shortId: String,
  val fill: Color,
  val text: Color,
  val edge: MonoEdge,
)

fun highlightPresentation(colorName: String, monochrome: Boolean, dark: Boolean): HighlightPresentation {
  val color = HighlightColor.parse(colorName)
  if (!monochrome) {
    return HighlightPresentation(color.label, color.label.take(1), color.background(dark), HighlightColor.text(dark), MonoEdge.SOLID)
  }
  return when (color) {
    HighlightColor.YELLOW -> HighlightPresentation("Yellow", "Y", Color(0xFFE0E0E0), Color.Black, MonoEdge.SOLID)
    HighlightColor.GREEN -> HighlightPresentation("Green", "G", Color(0xFFC8C8C8), Color.Black, MonoEdge.DOUBLE)
    HighlightColor.CYAN -> HighlightPresentation("Cyan", "C", Color(0xFFB0B0B0), Color.Black, MonoEdge.DASHED)
    HighlightColor.PURPLE -> HighlightPresentation("Purple", "P", Color(0xFF989898), Color.Black, MonoEdge.DOTTED)
  }
}

/** Adaptive quote size: base+6sp to 160 graphemes, base+3sp to 450, base beyond. */
fun quoteSizeSp(baseSp: Float, length: Int): Float = when {
  length <= 160 -> baseSp + 6f
  length <= 450 -> baseSp + 3f
  else -> baseSp
}
