package com.reader.app

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Shared instrumented-touch helpers.
 *
 * `UiAutomation.injectInputEvent` tracks a per-source "pointers down" state. A
 * gesture that is aborted between ACTION_DOWN and ACTION_UP/ACTION_CANCEL (an
 * assertion failure, a timeout, a killed process) leaves that pointer down, and
 * every later ACTION_DOWN from the same instrumentation is then rejected with
 * "Invalid DOWN event - pointers already down". Releasing a stale pointer before
 * opening a fresh stream keeps an aborted run from poisoning the next one.
 */
object QaTouch {
  fun releaseStalePointer(
    automation: UiAutomation,
    x: Float,
    y: Float,
    downTime: Long = SystemClock.uptimeMillis(),
  ) {
    val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0)
    cancel.source = InputDevice.SOURCE_TOUCHSCREEN
    // Rejected harmlessly when no pointer is actually down.
    automation.injectInputEvent(cancel, true)
    cancel.recycle()
  }
}
