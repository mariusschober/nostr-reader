package com.reader.app.ui

/** First-run onboarding is only for a genuinely empty, not-yet-dismissed library. */
object Onboarding {
  const val PREFS = "reader_hints"
  const val DISMISSED = "onboarding_dismissed"

  fun shouldShow(dismissed: Boolean, documentCount: Int): Boolean = !dismissed && documentCount == 0

  val steps = listOf(
    "Install the Reader Chrome extension.",
    "Pair Chrome once with the QR code.",
    "Capture an article; it arrives encrypted.",
  )
}
