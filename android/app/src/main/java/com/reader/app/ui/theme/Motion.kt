package com.reader.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion vocabulary. Every duration and easing in the app comes from here;
 * screens never write inline tween durations. The vocabulary is small on
 * purpose: gesture physics stay native, state changes use exactly these
 * curves.
 */
object Motion {
  /** Swipe settle, row reflow. */
  val Settle = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)

  /** Card exits, list item leave/enter. */
  val Reveal = tween<Float>(durationMillis = 220, easing = LinearOutSlowInEasing)

  /** Chrome fade in/out (reader top bar, immersive toggle). */
  val Fade = tween<Float>(durationMillis = 150)

  /** Finish celebration entrance. */
  val Celebrate = tween<Float>(durationMillis = 250)

  /** Route push/pop: fade + small slide. */
  val Nav = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
}

/**
 * True when the user has requested reduced motion system-wide (the
 * accessibility "remove animations" switch zeroes the animator duration
 * scale). Animated transitions should become instant swaps; drag physics
 * never change.
 */
@Composable
fun rememberReduceMotion(): Boolean {
  val context = LocalContext.current
  return remember {
    runCatching {
      Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
      ) == 0f
    }.getOrDefault(false)
  }
}

/**
 * True while animated transitions must become instant swaps: the e-ink theme
 * or the system accessibility "remove animations" switch. Drag physics and
 * direct touch scrolling must never consult this.
 */
@Composable
fun reduceMotionActive(): Boolean = LocalDisplayPolicy.current.reducedMotion

@Composable fun settleSpec(): AnimationSpec<Float> = if (reduceMotionActive()) snap() else Motion.Settle
@Composable fun revealSpec(): AnimationSpec<Float> = if (reduceMotionActive()) snap() else Motion.Reveal
@Composable fun celebrateSpec(): AnimationSpec<Float> = if (reduceMotionActive()) snap() else Motion.Celebrate
@Composable fun navSpec(): AnimationSpec<Float> = if (reduceMotionActive()) snap() else Motion.Nav
@Composable fun chromeFadeSpec(): FiniteAnimationSpec<Float> = if (reduceMotionActive()) snap() else Motion.Fade
