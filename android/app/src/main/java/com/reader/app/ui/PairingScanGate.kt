package com.reader.app.ui

/**
 * Camera analyzers see many frames per second. Claim only one validation at a
 * time, and do not re-submit the same rejected code every frame (the camera
 * stays live so a different, valid code is accepted immediately).
 */
class PairingScanGate(private val sameTextCooldownMs: Long = 2_000) {
  private var pending = false
  private var rejectedText: String? = null
  private var rejectedAt = 0L

  @Synchronized
  fun claim(text: String, nowMs: Long): Boolean {
    if (pending) return false
    if (text == rejectedText && nowMs - rejectedAt < sameTextCooldownMs) return false
    pending = true
    return true
  }

  @Synchronized
  fun accepted() {
    pending = false
    rejectedText = null
    rejectedAt = 0L
  }

  @Synchronized
  fun rejected(text: String, nowMs: Long) {
    pending = false
    rejectedText = text
    rejectedAt = nowMs
  }

  @Synchronized
  fun reset() {
    pending = false
    rejectedText = null
    rejectedAt = 0L
  }
}
