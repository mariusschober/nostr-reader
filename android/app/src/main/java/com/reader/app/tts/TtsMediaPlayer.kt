package com.reader.app.tts

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Truthful Media3 [Player] over the narrator. It advertises only commands it
 * really implements, reports a real single-item timeline with the article's
 * title/source, and reflects IDLE / BUFFERING / READY state instead of always
 * claiming READY. Transport commands are routed back to the owning service.
 */
@UnstableApi
class TtsMediaPlayer(looper: Looper) : SimpleBasePlayer(looper) {
  var onPlay: () -> Unit = {}
  var onPause: () -> Unit = {}
  var onStop: () -> Unit = {}
  var onSpeed: (Float) -> Unit = {}

  private val commands: Player.Commands = Player.Commands.Builder()
    .addAll(
      Player.COMMAND_PLAY_PAUSE,
      Player.COMMAND_STOP,
      Player.COMMAND_SET_SPEED_AND_PITCH,
      Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
      Player.COMMAND_GET_TIMELINE,
      Player.COMMAND_GET_METADATA,
      Player.COMMAND_GET_MEDIA_ITEMS_METADATA,
    ).build()

  private var documentId: String? = null
  private var title: String = ""
  private var url: String? = null
  private var source: String = ""
  private var playbackState: Int = Player.STATE_IDLE
  private var playWhenReady: Boolean = false
  private var speed: Float = 1f

  /** Push the narrator's compact state into the player surface. */
  fun update(state: TtsPlaybackState) {
    documentId = state.documentId
    title = state.sourceTitle
    url = state.sourceUrl
    source = displaySource(state.sourceUrl)
    speed = state.speed
    playWhenReady = state.playing
    playbackState = when {
      state.documentId == null -> Player.STATE_IDLE
      // Ended releases the notification/foreground resource: the session has
      // nothing left to resume, so it must not keep reporting a live player.
      state.ended -> Player.STATE_IDLE
      state.loading -> Player.STATE_BUFFERING
      else -> Player.STATE_READY
    }
    invalidateState()
  }

  override fun getState(): State {
    val doc = documentId
    val playlist = if (doc == null) {
      emptyList()
    } else {
      listOf(
        MediaItemData.Builder(doc)
          .setMediaItem(
            MediaItem.Builder()
              .setMediaId(doc)
              .setUri(url)
              .build(),
          )
          .setMediaMetadata(
            MediaMetadata.Builder()
              .setTitle(title.ifBlank { "Article" })
              .setArtist(source.ifBlank { "Reader" })
              .setIsBrowsable(false)
              .setIsPlayable(true)
              .build(),
          )
          .setDurationUs(C.TIME_UNSET)
          .setIsSeekable(false)
          .setIsDynamic(false)
          .build(),
      )
    }
    return State.Builder()
      .setAvailableCommands(commands)
      .setPlaylist(playlist)
      .setCurrentMediaItemIndex(0)
      .setPlaybackState(playbackState)
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
      .setPlaybackParameters(PlaybackParameters(speed))
      .setContentPositionMs(C.TIME_UNSET)
      .build()
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    if (playWhenReady) onPlay() else onPause()
    return Futures.immediateVoidFuture()
  }

  override fun handleStop(): ListenableFuture<*> {
    onStop()
    return Futures.immediateVoidFuture()
  }

  override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
    onSpeed(playbackParameters.speed)
    return Futures.immediateVoidFuture()
  }

  override fun handleRelease(): ListenableFuture<*> {
    documentId = null
    playbackState = Player.STATE_IDLE
    playWhenReady = false
    return Futures.immediateVoidFuture()
  }

  private companion object {
    fun displaySource(url: String?): String {
      val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
      return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Reader"
    }
  }
}
