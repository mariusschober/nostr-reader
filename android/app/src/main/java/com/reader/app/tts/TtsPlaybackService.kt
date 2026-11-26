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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
  private var pendingStart: PendingTtsStart? = null

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

    val starts = PendingTtsStart(
      scope = scope,
      loadMetadata = { id ->
        com.reader.app.data.ReaderDb.get(app).documents().metadataById(id)?.let { TtsSourceMetadata(it.title, it.sourceUrl) }
      },
      onPending = { request, wantsPlayback ->
        val state = TtsPlaybackState(
          documentId = request.documentId, sourceTitle = "Article", loading = true,
          playWhenReady = wantsPlayback, speed = request.speed, cursor = request.cursor,
        )
        TtsPlaybackBus.publish(state)
        mediaPlayer.update(state)
      },
      onReady = { request, metadata, wantsPlayback ->
        narration.start(request.documentId, request.cursor, request.speed, metadata.title, metadata.url, wantsPlayback)
      },
      onMissing = { narration.stop() },
    )
    pendingStart = starts
    fun pause() {
      if (!starts.pause()) narration.pause()
      flushProgress()
    }

    speechEngine.onReady = { narration.refreshVoice() }
    speechEngine.onFocusLost = { pause() }
    narration.onState = { state ->
      if (!starts.isPending) {
        TtsPlaybackBus.publish(state)
        mediaPlayer.update(state)
      }
    }
    narration.onProgress = { documentId, cursor, fraction -> app.progress.offer(cursor, fraction) }
    narration.onSectionBoundary = { flushProgress() }

    mediaPlayer.onPlay = { if (!starts.play()) narration.play() }
    mediaPlayer.onPause = { pause() }
    mediaPlayer.onStop = { starts.cancel(); narration.stop(); flushProgress() }
    mediaPlayer.onSpeed = { if (!starts.setSpeed(it)) narration.setSpeed(it) }

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
    if (current == null || !current.playWhenReady) {
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    session?.release()
    session = null
    pendingStart?.cancel()
    narrator?.stop()
    scope.cancel()
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
    // Replacement owns the command immediately, before Room metadata returns.
    narrator?.stop()
    flushProgress()
    pendingStart?.start(TtsStartRequest(documentId, SemanticCursor(documentId, blockId, charOffset), speed))
  }
}

internal data class TtsStartRequest(val documentId: String, val cursor: SemanticCursor, val speed: Float)
internal data class TtsSourceMetadata(val title: String, val url: String?)

/** The service's actual START boundary: pending metadata obeys later transport commands. */
internal class PendingTtsStart(
  private val scope: CoroutineScope,
  private val loadMetadata: suspend (String) -> TtsSourceMetadata?,
  private val onPending: (TtsStartRequest, Boolean) -> Unit,
  private val onReady: (TtsStartRequest, TtsSourceMetadata, Boolean) -> Unit,
  private val onMissing: () -> Unit,
) {
  private var generation = 0L
  private var pending: TtsStartRequest? = null
  private var playWhenReady = true
  private var job: Job? = null
  val isPending: Boolean get() = pending != null

  fun start(request: TtsStartRequest) {
    cancel()
    val own = generation
    pending = request
    playWhenReady = true
    onPending(request, playWhenReady)
    job = scope.launch {
      val metadata = try { loadMetadata(request.documentId) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }
      if (own != generation) return@launch
      val current = pending ?: return@launch
      pending = null
      if (metadata == null) onMissing() else onReady(current, metadata, playWhenReady)
    }
  }

  fun setSpeed(speed: Float): Boolean {
    val request = pending ?: return false
    val updated = request.copy(speed = speed.coerceIn(.75f, 2.5f))
    pending = updated
    onPending(updated, playWhenReady)
    return true
  }

  fun play(): Boolean = setIntent(true)
  fun pause(): Boolean = setIntent(false)

  private fun setIntent(value: Boolean): Boolean {
    val request = pending ?: return false
    playWhenReady = value
    onPending(request, value)
    return true
  }

  fun cancel() {
    generation++
    pending = null
    job?.cancel()
    job = null
  }
}
