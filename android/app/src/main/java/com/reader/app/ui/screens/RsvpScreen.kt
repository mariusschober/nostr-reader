package com.reader.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ReaderCore
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ReaderSettings
import com.reader.app.rsvp.RsvpModel
import com.reader.app.rsvp.RsvpToken
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.colorsFor
import com.reader.app.ui.theme.fontFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

import androidx.activity.compose.BackHandler

/** Genuine single-word RSVP. Focal glyph X is physically anchored. */
@Composable
fun RsvpScreen(
  tokens: List<RsvpToken>,
  settings: ReaderSettings,
  startIndex: Int,
  onWpm: (Int) -> Unit,
  onExit: (SemanticCursor) -> Unit,
) {
  val c = colorsFor(settings.background)
  val font = fontFor(settings.font)
  var index by remember { mutableStateOf(startIndex.coerceIn(0, (tokens.size - 1).coerceAtLeast(0))) }
  var playing by remember { mutableStateOf(true) }
  var wpm by remember { mutableStateOf(settings.rsvpWpm) }
  BackHandler { onExit(cursorOf(tokens, index)) }
  val token = tokens.getOrNull(index)

  // Monotonic scheduler: absolute deadlines, no drift accumulation.
  LaunchedEffect(playing, wpm, tokens) {
    if (!playing) return@LaunchedEffect
    val sched = RsvpModel.Scheduler(wpm)
    sched.reset(SystemClock.elapsedRealtime())
    var i = index
    while (isActive && i < tokens.size) {
      if (!playing) break
      val t = tokens[i]
      // Absolute-deadline scheduling: sleep only the remaining time so
      // frame/overhead costs never accumulate into drift.
      sched.delayFor(t, paragraphBreak = false)
      val wait = (sched.deadline() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
      if (wait > 0) delay(wait)
      if (!isActive || !playing) break
      i++
      index = i.coerceAtMost(tokens.size - 1)
    }
    if (i >= tokens.size) {
      playing = false
    }
  }

  Scaffold(containerColor = c.background) { pad ->
    Box(
      Modifier.padding(pad).fillMaxSize().clickable { playing = !playing },
      contentAlignment = Alignment.Center,
    ) {
      if (token != null && playing) {
        FocalWord(token = token.text, font = font, colors = c)
        Text(
          "${index + 1} / ${tokens.size}", fontFamily = ReaderFonts.Ui,
          fontSize = 12.sp, color = c.secondary,
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
      } else if (token != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          FocalWord(token = token.text, font = font, colors = c)
          Spacer(Modifier.height(32.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onExit(cursorOf(tokens, index)) }) { Text("Exit", color = c.text, fontFamily = ReaderFonts.Ui) }
            TextButton(onClick = { index = (index - 10).coerceAtLeast(0) }) { Text("-10", color = c.text, fontFamily = ReaderFonts.Ui) }
            Button(
              onClick = { playing = true },
              colors = ButtonDefaults.buttonColors(containerColor = c.text, contentColor = c.background),
            ) { Text("Play", fontFamily = ReaderFonts.Ui) }
            TextButton(onClick = { index = (index + 10).coerceAtMost(tokens.size - 1) }) { Text("+10", color = c.text, fontFamily = ReaderFonts.Ui) }
          }
          Text("$wpm WPM", fontFamily = ReaderFonts.Ui, color = c.secondary)
          Slider(
            value = wpm.toFloat(), onValueChange = { wpm = it.toInt(); onWpm(wpm) },
            valueRange = 100f..1200f, steps = 43,
            modifier = Modifier.padding(horizontal = 48.dp),
            colors = SliderDefaults.colors(thumbColor = c.text, activeTrackColor = c.text, inactiveTrackColor = c.divider),
          )
        }
      }
    }
  }
}

private fun cursorOf(tokens: List<RsvpToken>, index: Int): SemanticCursor {
  val t = tokens.getOrNull(index) ?: return SemanticCursor("", "b0", 0)
  return SemanticCursor("", t.blockId, t.start)
}

/** Custom layout: [left][FOCAL][right] with the focal glyph fixed at center X. */
@Composable
private fun FocalWord(token: String, font: FontFamily, colors: com.reader.app.ui.theme.ReaderColors) {
  val focalIdx = ReaderCore.Rsvp.focalIndex(token.length)
  val left = token.substring(0, focalIdx)
  val focal = token.getOrNull(focalIdx)?.toString() ?: ""
  val right = if (focalIdx + 1 <= token.length) token.substring(focalIdx + 1) else ""
  Layout(
    content = {
      Text(left, fontFamily = font, fontSize = 48.sp, color = colors.text)
      Text(focal, fontFamily = font, fontSize = 48.sp, color = colors.focal)
      Text(right, fontFamily = font, fontSize = 48.sp, color = colors.text)
    },
  ) { measurables, constraints ->
    val leftP = measurables[0].measure(constraints)
    val focalP = measurables[1].measure(constraints)
    val rightP = measurables[2].measure(constraints)
    val cx = constraints.maxWidth / 2
    val focalX = cx - focalP.width / 2 // anchored: never moves
    val y = 0
    layout(constraints.maxWidth, maxOf(leftP.height, focalP.height, rightP.height)) {
      leftP.placeRelative(focalX - leftP.width, y)
      focalP.placeRelative(focalX, y)
      rightP.placeRelative(focalX + focalP.width, y)
    }
  }
}
