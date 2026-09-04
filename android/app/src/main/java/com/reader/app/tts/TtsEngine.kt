package com.reader.app.tts

/** Narrow TTS seam: playback logic is unit-testable without Android TTS. */
interface TtsEngine {
  fun speak(utteranceId: String, text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onRange: (start: Int, end: Int) -> Unit)
  fun stop()
  fun setSpeed(speed: Float)
  fun shutdown()
  val supportsRangeCallback: Boolean
}
