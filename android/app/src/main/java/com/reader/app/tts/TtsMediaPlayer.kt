package com.reader.app.tts

import android.os.Looper
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/** Thin Media3 Player around the TTS controller: lock-screen + headphone keys. */
@UnstableApi
class TtsMediaPlayer(private val looper: Looper) : BasePlayer() {
  private var controller: TtsController? = null
  private val listeners = mutableSetOf<Player.Listener>()
  private var playWhenReadyFlag = false

  fun attach(c: TtsController) {
    controller = c
    c.onState = { s ->
      playWhenReadyFlag = s.playing
      listeners.toList().forEach {
        it.onPlayWhenReadyChanged(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        it.onPlaybackStateChanged(STATE_READY)
      }
    }
  }

  override fun getApplicationLooper(): Looper = looper
  override fun addListener(listener: Player.Listener) {
    listeners.add(listener)
  }
  override fun removeListener(listener: Player.Listener) {
    listeners.remove(listener)
  }
  override fun getPlaybackState(): Int = STATE_READY
  override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE
  override fun getPlayerError(): androidx.media3.common.PlaybackException? = null
  override fun getPlayWhenReady(): Boolean = playWhenReadyFlag
  override fun setPlayWhenReady(playWhenReady: Boolean) {
    playWhenReadyFlag = playWhenReady
    if (playWhenReady) controller?.play() else controller?.pause()
  }
  override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
  override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
  override fun getSeekBackIncrement(): Long = 0L
  override fun getSeekForwardIncrement(): Long = 0L
  override fun getMaxSeekToPreviousPosition(): Long = 0L
  override fun seekTo(windowIndex: Int, positionMs: Long, seekCommand: Int, isRepeatingCurrentItem: Boolean) {
    when (seekCommand) {
      Player.COMMAND_SEEK_TO_NEXT -> controller?.next()
      Player.COMMAND_SEEK_TO_PREVIOUS -> controller?.prev()
      else -> {}
    }
  }
  override fun getAvailableCommands(): Player.Commands =
    Player.Commands.Builder()
      .addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_STOP,
      ).build()
  override fun getCurrentTimeline(): Timeline = Timeline.EMPTY
  override fun getCurrentPeriodIndex(): Int = 0
  override fun getCurrentMediaItemIndex(): Int = 0
  override fun getDuration(): Long = C.TIME_UNSET
  override fun getCurrentPosition(): Long = 0L
  override fun getBufferedPosition(): Long = 0L
  override fun getTotalBufferedDuration(): Long = 0L
  override fun getContentPosition(): Long = 0L
  override fun getContentBufferedPosition(): Long = 0L
  override fun setMediaItems(mediaItems: MutableList<androidx.media3.common.MediaItem>, resetPosition: Boolean) {}
  override fun setMediaItems(mediaItems: MutableList<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long) {}
  override fun addMediaItems(index: Int, mediaItems: MutableList<androidx.media3.common.MediaItem>) {}
  override fun moveMediaItems(fromIndex: Int, toIndex: Int, newFromIndex: Int) {}
  override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<androidx.media3.common.MediaItem>) {}
  override fun removeMediaItems(fromIndex: Int, toIndex: Int) {}
  override fun prepare() {}

  // ---- No-op surface: TTS has no playlist, video, audio routing, or ads. ----
  override fun setRepeatMode(repeatMode: Int) {}
  override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF
  override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {}
  override fun getShuffleModeEnabled(): Boolean = false
  override fun isLoading(): Boolean = false
  override fun isPlayingAd(): Boolean = false
  override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET
  override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET
  override fun getCurrentTracks(): androidx.media3.common.Tracks = androidx.media3.common.Tracks.EMPTY
  override fun getTrackSelectionParameters(): androidx.media3.common.TrackSelectionParameters =
    androidx.media3.common.TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
  override fun setTrackSelectionParameters(parameters: androidx.media3.common.TrackSelectionParameters) {}
  override fun getMediaMetadata(): androidx.media3.common.MediaMetadata = androidx.media3.common.MediaMetadata.EMPTY
  override fun getPlaylistMetadata(): androidx.media3.common.MediaMetadata = androidx.media3.common.MediaMetadata.EMPTY
  override fun setPlaylistMetadata(mediaMetadata: androidx.media3.common.MediaMetadata) {}
  override fun getVolume(): Float = 1f
  override fun setVolume(volume: Float) {}
  override fun getAudioAttributes(): androidx.media3.common.AudioAttributes = androidx.media3.common.AudioAttributes.DEFAULT
  override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes, handleAudioFocus: Boolean) {}
  override fun getVideoSize(): androidx.media3.common.VideoSize = androidx.media3.common.VideoSize.UNKNOWN
  override fun getSurfaceSize(): androidx.media3.common.util.Size = androidx.media3.common.util.Size.UNKNOWN
  override fun clearVideoSurface() {}
  override fun clearVideoSurface(surface: android.view.Surface?) {}
  override fun setVideoSurface(surface: android.view.Surface?) {}
  override fun setVideoSurfaceHolder(surfaceHolder: android.view.SurfaceHolder?) {}
  override fun clearVideoSurfaceHolder(surfaceHolder: android.view.SurfaceHolder?) {}
  override fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
  override fun clearVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
  override fun setVideoTextureView(textureView: android.view.TextureView?) {}
  override fun clearVideoTextureView(textureView: android.view.TextureView?) {}
  override fun getCurrentCues(): androidx.media3.common.text.CueGroup = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO
  override fun getDeviceInfo(): androidx.media3.common.DeviceInfo = androidx.media3.common.DeviceInfo.UNKNOWN
  override fun getDeviceVolume(): Int = 0
  override fun isDeviceMuted(): Boolean = false
  override fun setDeviceVolume(volume: Int) {}
  override fun setDeviceVolume(volume: Int, flags: Int) {}
  override fun increaseDeviceVolume() {}
  override fun increaseDeviceVolume(flags: Int) {}
  override fun decreaseDeviceVolume() {}
  override fun decreaseDeviceVolume(flags: Int) {}
  override fun setDeviceMuted(muted: Boolean) {}
  override fun setDeviceMuted(muted: Boolean, flags: Int) {}

  override fun stop() {
    controller?.pause()
  }
  override fun release() {
    controller = null
    listeners.clear()
  }
}
