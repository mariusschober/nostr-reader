package com.reader.app.tts

/**
 * App-specific MediaSession commands. They carry only a document id, a
 * semantic cursor and a speed — never full article text or narration arrays.
 * Transport controls (play/pause/stop) use the standard Player commands so
 * lock-screen and headset buttons work without any custom protocol; sentence
 * stepping is exposed as custom commands and surfaced as custom notification
 * buttons, because a narration has no seekable media-item timeline to seek in.
 */
object TtsSessionCommands {
  const val START = "com.reader.app.tts.START"
  const val PREV_SENTENCE = "com.reader.app.tts.PREV_SENTENCE"
  const val NEXT_SENTENCE = "com.reader.app.tts.NEXT_SENTENCE"
  const val ARG_DOCUMENT_ID = "documentId"
  const val ARG_BLOCK_ID = "blockId"
  const val ARG_CHAR_OFFSET = "charOffset"
  const val ARG_SPEED = "speed"
}
