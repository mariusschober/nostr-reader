package com.reader.app.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One publisher (the playback service) and independent observers (UI, media
 * session). Process-wide so the reader can render a now-playing row before a
 * MediaController connection completes and across Activity recreation.
 */
object TtsPlaybackBus {
  private val _state = MutableStateFlow(TtsPlaybackState())
  val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

  /** Only the service publishes; observers never write. */
  fun publish(value: TtsPlaybackState) { _state.value = value }
}

