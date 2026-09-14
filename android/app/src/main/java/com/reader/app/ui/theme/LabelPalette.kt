package com.reader.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.reader.app.prefs.LabelColorKey

/** Theme-appropriate Flexoki 600 (light) / 400 (dark) accent for a label colour. */
fun labelAccent(key: LabelColorKey, dark: Boolean): Color = when (key) {
  LabelColorKey.NEUTRAL -> if (dark) Flexoki.Base400 else Flexoki.Base600
  LabelColorKey.RED -> if (dark) Flexoki.Red400 else Flexoki.Red600
  LabelColorKey.ORANGE -> if (dark) Flexoki.Orange400 else Flexoki.Orange600
  LabelColorKey.GREEN -> if (dark) Flexoki.Green400 else Flexoki.Green600
  LabelColorKey.BLUE -> if (dark) Flexoki.Blue400 else Flexoki.Blue600
}

