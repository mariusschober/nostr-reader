package com.reader.app.tts

import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.PreparedSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Section-progressive narration. Owns the controller and turns "the bounded
 * section finished" into "load the next section and keep going" until the
 * final section, where it stops and reports Ended. Deliberately free of
 * Android services so the lifecycle — load, advance, retry, stale-callback
 * guards — is unit-testable against a fake engine.
 */
class TtsNarrator(
  engine: TtsEngine,
  private val scope: CoroutineScope,
  private val loadSection: suspend (documentId: String, fromBlockId: String?, sectionHint: Int) -> PreparedSection?,
) {
  private val controller = TtsController(engine)

  var onState: ((TtsPlaybackState) -> Unit)? = null
  var onProgress: ((String, SemanticCursor, Float) -> Unit)? = null
  /** Called when narration crosses into another section, so the caller can flush. */
  var onSectionBoundary: (() -> Unit)? = null

  private var documentId: String? = null
  private var sourceTitle = ""
  private var sourceUrl: String? = null
  private var sectionIndex = 0
  private var sectionCount = 1
  private var loaded: PreparedSection? = null
  private var speed = 1f
  private var loading = false
  private var ended = false
  private var error: String? = null
  private var failedSection: Int? = null
  private var session = 0L

  init {
    controller.onState = { publish() }
    controller.onPosition = { blockId, offset ->
      val doc = documentId
      val section = loaded
      if (doc != null && section != null) {
        val fraction = section.fraction(section.projection.offset(blockId, offset))
        onProgress?.invoke(doc, SemanticCursor(doc, blockId, offset), fraction)
        publish()
      }
    }
    controller.onComplete = { advance() }
  }

  /** Re-read voice capability (e.g. after TTS init completes post-load). */
  fun refreshVoice() = controller.refreshVoice()

  /** Begin narration at [cursor]. Any earlier narration is replaced. */
  fun start(documentId: String, cursor: SemanticCursor, speed: Float, title: String, url: String?) {
    val own = ++session
    this.documentId = documentId
    this.sourceTitle = title
    this.sourceUrl = url
    this.speed = speed.coerceIn(.75f, 2.5f)
    loaded = null
    ended = false
    error = null
    failedSection = null
    loading = true
    publish()
    scope.launch {
      val first = runCatching { loadSection(documentId, cursor.blockId, 0) }.getOrNull()
      if (own != session) return@launch
      if (first == null) { fail("Couldn’t load this article for listening."); return@launch }
      adopt(first, cursor.blockId, cursor.charOffset)
      controller.play()
    }
  }

  fun play() {
    // After the final section there is nothing left to resume; a fresh Listen
    // is required to hear the article again.
    if (ended) return
    val retry = failedSection
    if (retry != null) { retrySection(retry); return }
    if (loading || loaded == null) return
    error = null
    controller.play()
    publish()
  }

  fun pause() {
    controller.pause()
    publish()
  }

  /** Full stop: forgets the document so no now-playing row remains. */
  fun stop() {
    session++
    controller.pause()
    documentId = null
    loaded = null
    ended = false
    error = null
    failedSection = null
    loading = false
    publish()
  }

  fun next() { controller.next(); publish() }

  fun prev() { controller.prev(); publish() }

  fun setSpeed(value: Float) {
    speed = value.coerceIn(.75f, 2.5f)
    controller.setSpeed(speed)
    publish()
  }

  private fun adopt(section: PreparedSection, fromBlockId: String?, fromOffset: Int) {
    // Crossing a section boundary is a write checkpoint for the spoken cursor.
    if (loaded != null) onSectionBoundary?.invoke()
    loaded = section
    sectionIndex = section.section
    sectionCount = section.index.sections.size.coerceAtLeast(1)
    loading = false
    failedSection = null
    controller.load(Narration.sentences(section.projection), fromBlockId, speed, fromOffset)
    publish()
  }

  private fun advance() {
    val next = sectionIndex + 1
    if (next >= sectionCount) { finish(); return }
    retrySection(next)
  }

  /**
   * Load a following section and continue. A failure keeps the previous
   * section intact and only offers a retry of that section — narration never
   * silently restarts from the beginning of the article.
   */
  private fun retrySection(target: Int) {
    val doc = documentId ?: return
    val own = session
    loading = true
    error = null
    failedSection = target
    publish()
    scope.launch {
      val section = runCatching { loadSection(doc, null, target) }.getOrNull()
      if (own != session) return@launch
      if (section == null) { fail("Couldn’t load the next part. Try Play to retry."); return@launch }
      adopt(section, null, 0)
      controller.play()
    }
  }

  private fun finish() {
    ended = true
    loading = false
    error = null
    failedSection = null
    controller.pause()
    publish()
  }

  private fun fail(message: String) {
    loading = false
    error = message
    publish()
  }

  private fun publish() {
    val state = controller.state
    onState?.invoke(
      TtsPlaybackState(
        documentId = documentId,
        sourceTitle = sourceTitle,
        sourceUrl = sourceUrl,
        playing = state.playing,
        loading = loading,
        ended = ended,
        error = error ?: state.error,
        requiresNetwork = state.voiceRequiresNetwork,
        speed = state.speed,
        cursor = documentId?.let { controller.cursorFor(it) },
        index = state.index,
        total = state.units.size,
        retryable = failedSection != null,
      ),
    )
  }
}
