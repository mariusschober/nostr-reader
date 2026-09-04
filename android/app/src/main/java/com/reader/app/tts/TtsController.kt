package com.reader.app.tts

import com.reader.app.cursor.SemanticCursor

/** Sentence queue with prev/next, skip-code already handled by Narration model. */
class TtsController(private val engine: TtsEngine) {
  data class State(
    val units: List<NarrationUnit> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val speed: Float = 1.0f,
  )

  var state = State()
    private set
  var onCursor: ((blockId: String) -> Unit)? = null
  var onState: ((State) -> Unit)? = null

  fun load(units: List<NarrationUnit>, fromBlockId: String?, speed: Float) {
    val idx = fromBlockId?.let { id -> units.indexOfFirst { it.blockId == id }.takeIf { it >= 0 } } ?: 0
    state = State(units, idx, false, speed)
    onState?.invoke(state)
  }

  fun play() {
    val u = state.units.getOrNull(state.index) ?: return
    state = state.copy(playing = true)
    onState?.invoke(state)
    engine.setSpeed(state.speed)
    engine.speak("u${state.index}", u.text, state.speed,
      onStart = { onCursor?.invoke(u.blockId) },
      onDone = {
        val next = state.index + 1
        if (next >= state.units.size) {
          state = state.copy(playing = false)
          onState?.invoke(state)
        } else {
          state = state.copy(index = next)
          onState?.invoke(state)
          play()
        }
      },
      onRange = { _, _ -> onCursor?.invoke(u.blockId) },
    )
  }

  fun pause() {
    engine.stop()
    state = state.copy(playing = false)
    onState?.invoke(state)
  }

  fun next() {
    engine.stop()
    if (state.index + 1 < state.units.size) {
      state = state.copy(index = state.index + 1)
      onState?.invoke(state)
      if (state.playing) play()
    }
  }

  fun prev() {
    engine.stop()
    if (state.index > 0) {
      state = state.copy(index = state.index - 1)
      onState?.invoke(state)
      if (state.playing) play()
    }
  }

  fun cursorFor(documentId: String): SemanticCursor =
    SemanticCursor(documentId, state.units.getOrNull(state.index)?.blockId ?: "b0", 0)
}
