package com.reader.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for the Activity window and system-bar appearance.
 *
 * The Activity owns the window; the reader does not capture and restore bar
 * colors itself. It only reports whether it is in fullscreen focus and which
 * surface it is painting. The Activity recomputes the decor/bar colors and
 * icon contrast from the current route, theme and focus state, so exiting focus
 * or changing theme can never restore a stale light color.
 *
 * A single app window exists, so this is a process-wide holder rather than a
 * CompositionLocal; the Activity reads both fields at composition time so a
 * focus change invalidates and re-runs its window side effect.
 */
object ReaderWindow {
  var readerFocused by mutableStateOf(false)
  /** Surface the focused reader is painting, or null outside focus. */
  var readerSurface by mutableStateOf<Color?>(null)
}
