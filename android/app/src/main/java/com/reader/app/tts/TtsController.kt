package com.reader.app.tts

import com.reader.app.cursor.SemanticCursor

/** One generation owns callbacks. A speed change restarts at the current spoken range. */
class TtsController(private val engine: TtsEngine) {
  data class State(
    val units: List<NarrationUnit> = emptyList(), val index: Int = 0,
    val voiceRequiresNetwork: Boolean? = null,
    val playing: Boolean = false, val speed: Float = 1f, val offset: Int = 0, val error: String? = null,
  )
  var state = State()
    private set
  var onCursor: ((String) -> Unit)? = null
  var onPosition: ((String, Int) -> Unit)? = null
  var onState: ((State) -> Unit)? = null
  private var generation = 0L

  private fun stopGeneration() { generation++; engine.stop() }
  private fun publish() { state = state.copy(voiceRequiresNetwork = engine.voiceRequiresNetwork); onState?.invoke(state) }

  fun load(units: List<NarrationUnit>, fromBlockId: String?, speed: Float, fromOffset: Int = 0) {
    stopGeneration()
    val candidates = units.indices.filter { units[it].blockId == fromBlockId }
    val idx = candidates.lastOrNull { units[it].blockOffset(0) <= fromOffset } ?: candidates.firstOrNull() ?: 0
    val unit = units.getOrNull(idx)
    val offset = unit?.renderedOffsets?.indexOfFirst { it >= fromOffset }?.coerceAtLeast(0) ?: 0
    state = State(units, idx, speed = speed.coerceIn(.75f, 2.5f), offset = offset)
    publish()
  }

  fun play() {
    if (state.playing) return
    val unit = state.units.getOrNull(state.index) ?: return
    val from = state.offset.coerceIn(0, unit.text.lastIndex.coerceAtLeast(0))
    val own = ++generation
    state = state.copy(playing = true, error = null)
    publish(); engine.setSpeed(state.speed)
    fun current() = generation == own && state.playing
    fun position(offset: Int) {
      onCursor?.invoke(unit.blockId); onPosition?.invoke(unit.blockId, unit.blockOffset(offset))
    }
    engine.speak("r$own-u${state.index}-$from", unit.text.substring(from), state.speed,
      onStart = { if (current()) { position(from); publish() } },
      onDone = {
        if (current()) {
          val next = state.index + 1
          state = state.copy(index = next.coerceAtMost(state.units.lastIndex), playing = false, offset = 0)
          publish()
          if (next < state.units.size) play()
        }
      },
      onRange = { start, _ ->
        if (current()) { state = state.copy(offset = (from + start).coerceAtMost(unit.text.lastIndex)); position(state.offset) }
      },
      onError = { message -> if (current()) { stopGeneration(); state = state.copy(playing = false, error = message); publish() } },
    )
  }

  fun pause() { stopGeneration(); state = state.copy(playing = false); publish() }
  fun setSpeed(speed: Float) {
    val value = speed.coerceIn(.75f, 2.5f)
    if (state.speed == value) return
    val resume = state.playing
    stopGeneration()
    state = state.copy(speed = value, playing = false)
    engine.setSpeed(value); publish()
    if (resume) play()
  }
  fun next() = move(1)
  fun prev() = move(-1)
  private fun move(delta: Int) {
    val resume = state.playing
    stopGeneration()
    val target = state.index + delta
    state = state.copy(index = target.coerceIn(0, state.units.lastIndex.coerceAtLeast(0)), offset = 0, playing = false, error = null)
    publish()
    if (resume && target <= state.units.lastIndex) play()
  }
  fun cursorFor(documentId: String): SemanticCursor {
    val unit = state.units.getOrNull(state.index)
    return SemanticCursor(documentId, unit?.blockId ?: "b0", unit?.blockOffset(state.offset) ?: 0)
  }
}
