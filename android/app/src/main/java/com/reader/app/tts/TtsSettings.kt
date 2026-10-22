package com.reader.app.tts

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Opens the system engine/voice picker without hard-coding a vendor package. */
object TtsSettings {
  private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

  fun open(context: Context) {
    val direct = Intent(ACTION_TTS_SETTINGS)
    val started = runCatching { context.startActivity(direct) }.isSuccess
    if (!started) runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
  }
}
