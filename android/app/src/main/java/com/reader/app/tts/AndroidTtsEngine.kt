package com.reader.app.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat

/** Android callbacks are keyed to the exact utterance and delivered on the main thread. */
class AndroidTtsEngine(ctx: Context) : TtsEngine {
  private val context = ctx.applicationContext
  private val handler = Handler(Looper.getMainLooper())
  private val audio = context.getSystemService(AudioManager::class.java)
  private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
    .setOnAudioFocusChangeListener({ change -> if (change < 0) onFocusLost?.invoke() }, handler).build()
  private var tts: TextToSpeech? = null
  private var ready = false
  private var failed = false
  private data class Request(
    val id: String, val text: String, val speed: Float, val start: () -> Unit,
    val done: () -> Unit, val range: (Int, Int) -> Unit, val error: (String) -> Unit,
  )
  private var active: Request? = null
  var onFocusLost: (() -> Unit)? = null
  override var supportsRangeCallback = false
    private set
  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onFocusLost?.invoke() }
  }

  init {
    ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    tts = TextToSpeech(context) { status ->
      handler.post {
        ready = status == TextToSpeech.SUCCESS; failed = !ready
        active?.let { if (ready) submit(it) else { active = null; it.error("Speech engine unavailable") } }
      }
    }
    tts?.setAudioAttributes(attributes)
    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String?) = current(id) { it.start() }
      override fun onDone(id: String?) = current(id) { active = null; it.done() }
      override fun onError(id: String?) = current(id) { active = null; it.error("Speech could not continue") }
      override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) = current(id) {
        supportsRangeCallback = true; it.range(start, end)
      }
    })
  }

  private fun current(id: String?, action: (Request) -> Unit) {
    handler.post { active?.takeIf { it.id == id }?.let(action) }
  }

  override fun speak(utteranceId: String, text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onRange: (Int, Int) -> Unit, onError: (String) -> Unit) {
    val request = Request(utteranceId, text, speed, onStart, onDone, onRange, onError)
    active = request
    if (failed) { active = null; onError("Speech engine unavailable") }
    else if (ready) submit(request)
  }

  private fun submit(request: Request) {
    if (active?.id != request.id) return
    val engine = tts ?: return
    if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      active = null; request.error("Audio is in use. Try Listen again."); return
    }
    // Keep the system engine's configured voice/language; the UI locale is not a speech preference.
    engine.setSpeechRate(request.speed.coerceIn(.75f, 2.5f))
    if (engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, Bundle(), request.id) != TextToSpeech.SUCCESS) {
      active = null; audio.abandonAudioFocusRequest(focus); request.error("Speech engine unavailable")
    }
  }

  override fun stop() {
    active = null
    runCatching { tts?.stop() }
    audio.abandonAudioFocusRequest(focus)
  }
  override fun setSpeed(speed: Float) { runCatching { tts?.setSpeechRate(speed.coerceIn(.75f, 2.5f)) } }
  override fun shutdown() {
    stop(); runCatching { context.unregisterReceiver(noisy) }; runCatching { tts?.shutdown() }
    tts = null
  }
}
