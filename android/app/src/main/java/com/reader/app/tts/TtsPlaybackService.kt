package com.reader.app.tts

import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSessionService

/** Keeps TTS alive with screen off + lock-screen/headphone controls. */
@UnstableApi
class TtsPlaybackService : MediaSessionService() {
  private var session: MediaSession? = null

  override fun onCreate() {
    super.onCreate()
    val player = TtsMediaPlayer(mainLooper)
    session = MediaSession.Builder(this, player).build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

  override fun onDestroy() {
    session?.release()
    super.onDestroy()
  }
}
