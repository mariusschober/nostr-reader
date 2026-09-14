package com.reader.app.tts

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.reader.app.ReaderApp
import com.reader.app.cursor.SemanticCursor
import com.reader.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns real speech: the Android engine, the section-progressive narrator, the
 * Media3 player and session, and the playback scope. The Activity only observes
 * the compact [TtsPlaybackBus] state and sends commands through a
 * MediaController, so listening continues through navigation, Home, lock and
 * Recents dismissal.
 */
@UnstableApi
class TtsPlaybackService : MediaSessionService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var session: MediaSession? = null
  private var player: TtsMediaPlayer? = null
  private var engine: AndroidTtsEngine? = null
  private var narrator: TtsNarrator? = null

  override fun onCreate() {
    super.onCreate()
    val app = application as ReaderApp
    val mediaPlayer = TtsMediaPlayer(mainLooper)
    val speechEngine = AndroidTtsEngine(this)
    val narration = TtsNarrator(speechEngine, scope) { documentId, fromBlockId, sectionHint ->
      runCatching {
        val index = app.articles.index(documentId)
        val section = if (fromBlockId != null) {
          index.sectionFor(fromBlockId)
        } else {
          sectionHint.coerceIn(index.sections.indices)
        }
        app.articles.section(documentId, section)
      }.getOrNull()
    }

    speechEngine.onReady = { narration.refreshVoice() }
    speechEngine.onFocusLost = { narration.pause() }
    narration.onState = { state ->
      TtsPlaybackBus.publish(state)
      mediaPlayer.update(state)
    }
    narration.onProgress = { documentId, cursor, fraction -> app.progress.offer(cursor, fraction) }
    narration.onSectionBoundary = { flushProgress() }

    mediaPlayer.onPlay = { narration.play() }
    mediaPlayer.onPause = { narration.pause(); flushProgress() }
    mediaPlayer.onStop = { narration.stop(); flushProgress() }
    mediaPlayer.onSpeed = { narration.setSpeed(it) }

    val sessionActivity = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    session = MediaSession.Builder(this, mediaPlayer)
      .setSessionActivity(sessionActivity)
      .setCallback(Callback())
      .setCustomLayout(
        listOf(
          CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setSessionCommand(SessionCommand(TtsSessionCommands.PREV_SENTENCE, Bundle.EMPTY))
            .setDisplayName("Previous sentence")
            .build(),
          CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(SessionCommand(TtsSessionCommands.NEXT_SENTENCE, Bundle.EMPTY))
            .setDisplayName("Next sentence")
            .build(),
        ),
      )
      .build()

    player = mediaPlayer
    engine = speechEngine
    narrator = narration
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  /**
   * Keep playing when the task is removed from Recents; a paused session is
   * released so the service does not linger in the background doing nothing.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val current = player
    if (current == null || !current.isPlaying) {
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    session?.release()
    session = null
    narrator?.stop()
    engine?.shutdown()
    player = null
    engine = null
    narrator = null
    super.onDestroy()
  }

  private fun flushProgress() {
    val app = application as ReaderApp
    scope.launch {
      runCatching { app.progress.flush() }
    }
  }

  private inner class Callback : MediaSession.Callback {
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult =
      MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(
          MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(TtsSessionCommands.START, Bundle.EMPTY))
            .add(SessionCommand(TtsSessionCommands.PREV_SENTENCE, Bundle.EMPTY))
            .add(SessionCommand(TtsSessionCommands.NEXT_SENTENCE, Bundle.EMPTY))
            .build(),
        )
        .build()

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      when (customCommand.customAction) {
        TtsSessionCommands.START -> start(args)
        TtsSessionCommands.PREV_SENTENCE -> narrator?.prev()
        TtsSessionCommands.NEXT_SENTENCE -> narrator?.next()
        // Lint's @SessionResult.Code allow-list names SessionError.ERROR_NOT_SUPPORTED.
        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  private fun start(args: Bundle) {
    val documentId = args.getString(TtsSessionCommands.ARG_DOCUMENT_ID) ?: return
    val blockId = args.getString(TtsSessionCommands.ARG_BLOCK_ID) ?: "b0"
    val charOffset = args.getInt(TtsSessionCommands.ARG_CHAR_OFFSET)
    val speed = args.getFloat(TtsSessionCommands.ARG_SPEED, 1f)
    val app = application as ReaderApp
    scope.launch {
      val metadata = runCatching { com.reader.app.data.ReaderDb.get(app).documents().metadataById(documentId) }.getOrNull()
      // A stale START for a deleted article must not resurrect narration.
      if (metadata == null) { narrator?.stop(); return@launch }
      narrator?.start(
        documentId = documentId,
        cursor = SemanticCursor(documentId, blockId, charOffset),
        speed = speed,
        title = metadata.title,
        url = metadata.sourceUrl,
      )
    }
  }
}
