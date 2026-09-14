package com.reader.app.tts

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.reader.app.cursor.SemanticCursor

/**
 * Activity-side bridge to the playback service. Commands travel as a document
 * id, a semantic cursor and a speed — never article text or narration arrays —
 * and state is read from the process-wide [TtsPlaybackBus], so the UI can
 * render before the controller finishes binding.
 *
 * The controller binds lazily on the first command, so launching the app does
 * not spin up the speech engine. Commands issued while binding are queued in
 * order, which keeps controls usable right after an Activity recreation.
 */
@UnstableApi
class TtsPlaybackClient(context: Context) {
  private val appContext = context.applicationContext
  private var controller: MediaController? = null
  private var connecting = false
  private var released = false
  private val queued = ArrayDeque<(MediaController) -> Unit>()

  fun start(documentId: String, cursor: SemanticCursor, speed: Float) {
    val args = Bundle().apply {
      putString(TtsSessionCommands.ARG_DOCUMENT_ID, documentId)
      putString(TtsSessionCommands.ARG_BLOCK_ID, cursor.blockId)
      putInt(TtsSessionCommands.ARG_CHAR_OFFSET, cursor.charOffset)
      putFloat(TtsSessionCommands.ARG_SPEED, speed)
    }
    whenConnected { it.sendCustomCommand(SessionCommand(TtsSessionCommands.START, Bundle.EMPTY), args) }
  }

  fun play() = commandIfActive { it.play() }

  fun pause() = commandIfActive { it.pause() }

  fun stop() = commandIfActive { it.stop() }

  fun next() = commandIfActive { it.sentence(TtsSessionCommands.NEXT_SENTENCE) }

  fun prev() = commandIfActive { it.sentence(TtsSessionCommands.PREV_SENTENCE) }

  fun setSpeed(speed: Float) = commandIfActive { it.setPlaybackSpeed(speed) }

  fun release() {
    released = true
    queued.clear()
    controller?.release()
    controller = null
  }

  private fun MediaController.sentence(action: String) {
    sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
  }

  /**
   * Reconnect after an Activity recreation so visible controls still reach a
   * running session; never bind the service just to issue a no-op.
   */
  private fun commandIfActive(action: (MediaController) -> Unit) {
    if (controller == null && !TtsPlaybackBus.state.value.active) return
    whenConnected(action)
  }

  private fun whenConnected(action: (MediaController) -> Unit) {
    val connected = controller
    if (connected != null) { action(connected); return }
    if (released) return
    if (queued.size < MAX_QUEUED) queued.addLast(action)
    connect()
  }

  private fun connect() {
    if (controller != null || connecting || released) return
    connecting = true
    val token = SessionToken(appContext, ComponentName(appContext, TtsPlaybackService::class.java))
    val future: ListenableFuture<MediaController> = MediaController.Builder(appContext, token).buildAsync()
    future.addListener(
      {
        connecting = false
        if (released) { MediaController.releaseFuture(future); return@addListener }
        val connected = runCatching { future.get() }.getOrNull()
        controller = connected
        if (connected == null) { queued.clear(); return@addListener }
        while (queued.isNotEmpty()) queued.removeFirst().invoke(connected)
      },
      ContextCompat.getMainExecutor(appContext),
    )
  }

  private companion object {
    /** Bounded so a broken bind can never accumulate unbounded work. */
    const val MAX_QUEUED = 8
  }
}
