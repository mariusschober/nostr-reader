package com.reader.app

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.tts.AndroidTtsEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SystemTtsInstrumentedTest {
  @Test fun followsInstalledSystemDefault() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val context = runner.targetContext
    val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    assertFalse("A system speech engine must be selected", selected.isNullOrBlank())
    val visible = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
    assertTrue("System default must be visible to Reader: $selected", visible.any { it.serviceInfo.packageName == selected })
    val done = CountDownLatch(1)
    val failure = AtomicReference<String?>(null)
    var engine: AndroidTtsEngine? = null
    val screen = androidx.test.core.app.ActivityScenario.launch(com.reader.app.ui.MainActivity::class.java)
    try {
      runner.runOnMainSync {
        engine = AndroidTtsEngine(context)
        engine!!.speak("system-default-check", "Reader follows your system speech engine.", 1f,
          onStart = {}, onDone = { done.countDown() }, onRange = { _, _ -> },
          onError = { failure.set(it); done.countDown() })
      }
      assertTrue("Default engine did not complete speech", done.await(45, TimeUnit.SECONDS))
      assertNull(failure.get())
    } finally {
      runner.runOnMainSync { engine?.shutdown() }
      screen.close()
    }
  }
}
