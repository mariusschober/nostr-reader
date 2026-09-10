package com.reader.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Haptic vocabulary: exactly six meanings. Call sites express intent, not
 * effect types, so the physical language stays consistent across Compose
 * rows, native views and future surfaces.
 *
 *  swipeArm        — a drag first crosses a commit threshold (light tick)
 *  commit          — a drag is released past the threshold (firm press)
 *  destructiveArm  — a drag crosses a destructive threshold (heavier tick)
 *  star            — a passage is starred/important (firm press)
 *  select          — long-press enters multi-select (firm press)
 *  finish          — a piece is finished / milestone (confirmation beat)
 *
 * The platform suppresses these automatically when haptics are disabled.
 */
object Haptics {
  // Compose surfaces (LocalHapticFeedback). Compose UI 1.6 exposes only
  // LongPress and TextHandleMove; heavier/destructive textures are only
  // available on the View layer, which the native reader uses.
  fun swipeArm(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.TextHandleMove)
  fun commit(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.LongPress)
  fun select(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.LongPress)
  fun star(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.LongPress)
  fun finish(h: HapticFeedback) = h.performHapticFeedback(HapticFeedbackType.LongPress)

  // View surfaces (the native article view and anything outside Compose).
  private val confirmConst: Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else null
  private val rejectConst: Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else null

  fun swipeArm(view: View) =
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

  fun destructiveArm(view: View) =
    view.performHapticFeedback(rejectConst ?: HapticFeedbackConstants.VIRTUAL_KEY)

  fun commit(view: View) =
    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

  fun finish(view: View) =
    view.performHapticFeedback(confirmConst ?: HapticFeedbackConstants.LONG_PRESS)
}
