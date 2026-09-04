package com.reader.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Android TextToSpeech behind the narrow seam. Range callback when the engine offers it. */
class AndroidTtsEngine(ctx: Context) : TtsEngine {
  private var tts: TextToSpeech? = null
  private var ready = false
  private var rangeCb: ((Int, Int) -> Unit)? = null
  override var supportsRangeCallback: Boolean = false

  init {
    tts = TextToSpeech(ctx.applicationContext) { status ->
      ready = status == TextToSpeech.SUCCESS
    }
    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String?) {}
      override fun onDone(id: String?) {
        doneCb?.invoke()
      }
      override fun onError(id: String?) {
        doneCb?.invoke()
      }
      override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
        supportsRangeCallback = true
        rangeCb?.invoke(start, end)
      }
    })
  }

  private var doneCb: (() -> Unit)? = null

  override fun speak(utteranceId: String, text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onRange: (Int, Int) -> Unit) {
    doneCb = onDone
    rangeCb = onRange
    val t = tts ?: return onDone()
    t.language = Locale.getDefault()
    t.setSpeechRate(speed.coerceIn(0.75f, 2.5f))
    onStart()
    // Long paragraphs are pre-split by Narration; speak sequentially.
    val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    if (r != TextToSpeech.SUCCESS) onDone()
  }

  override fun stop() {
    try {
      tts?.stop()
    } catch (e: Exception) {
    }
  }

  override fun setSpeed(speed: Float) {
    try {
      tts?.setSpeechRate(speed.coerceIn(0.75f, 2.5f))
    } catch (e: Exception) {
    }
  }

  override fun shutdown() {
    try {
      tts?.shutdown()
    } catch (e: Exception) {
    }
  }
}
