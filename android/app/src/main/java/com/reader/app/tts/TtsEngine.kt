package com.reader.app.tts

/** Narrow TTS seam: playback logic is unit-testable without Android TTS. */
interface TtsEngine {
  fun speak(utteranceId: String, text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onRange: (start: Int, end: Int) -> Unit, onError: (String) -> Unit = { onDone() })
  fun stop()
  fun setSpeed(speed: Float)
  fun shutdown()
  val voiceRequiresNetwork: Boolean? get() = null
  /** Invoked on the main thread when engine init completes (voice may have been null). */
  var onReady: (() -> Unit)?
  val supportsRangeCallback: Boolean
}
