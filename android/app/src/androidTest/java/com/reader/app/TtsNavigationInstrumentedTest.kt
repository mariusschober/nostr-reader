package com.reader.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ReaderCore
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import com.reader.app.tts.TtsPlaybackBus
import com.reader.app.tts.TtsPlaybackService
import com.reader.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in, isolated service/Media3 sequence. State/callback checks do not certify audibility. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class TtsNavigationInstrumentedTest {
  private val runner get() = InstrumentationRegistry.getInstrumentation()
  private val context get() = runner.targetContext
  private fun state() = TtsPlaybackBus.state.value

  private fun waitFor(label: String, test: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (test()) return
      SystemClock.sleep(100)
    }
    error("Speech sequence timed out: $label; state=${state()}")
  }

  private fun node(text: String): AccessibilityNodeInfo? {
    fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
      root ?: return null
      if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
      for (i in 0 until root.childCount) find(root.getChild(i))?.let { return it }
      return null
    }
    return find(runner.uiAutomation.rootInActiveWindow)
  }

  private fun click(text: String) {
    waitFor("control $text") { node(text) != null }
    check(runner.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName)
    var target = node(text)
    while (target != null && !target.isClickable) target = target.parent
    check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Control not clickable: $text" }
    SystemClock.sleep(200)
  }

  @Test fun backHomeMediaPauseResumeThenSpeed() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaSpeechLifecycle") == "true")
    check(context.packageName == "com.reader.app.qa")
    context.stopService(Intent(context, TtsPlaybackService::class.java))
    SystemClock.sleep(500)
    val db = ReaderDb.get(context)
    val unique = System.nanoTime()
    val title = "Speech navigation check $unique"
    val markdown = ReaderCore.canonicalize((1..20).joinToString("\n\n") {
      "Paragraph $it. Listening should continue after returning to the library. Pause must preserve the spoken place. Resume continues the same article, and entering Speed pauses listening."
    } + "\n\nRun $unique\n")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    db.documents().insert(DocumentEntity(id, title, "web", null, "https://example.org/reader-speech-check", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now))
    var controller: MediaController? = null
    try {
      ActivityScenario.launch(MainActivity::class.java).use {
        click("Inbox"); click(title); click("Listen")
        waitFor("service speech starts") { state().documentId == id && state().playing }
        val opening = state().cursor
        waitFor("engine callbacks advance") {
          check(state().error == null) { "Google speech error: ${state().error}" }
          state().playing && state().cursor != opening
        }
        check(runner.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitFor("library shows now-playing controls") { node("Stop listening") != null && node("Pause") != null }
        assertTrue("Back keeps listening intent", state().playWhenReady)
        assertEquals(id, state().documentId)
        check(runner.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        SystemClock.sleep(1200)
        assertTrue("Home keeps the service speaking", state().playing)

        // These are the same Media3 transport commands used by system media controls.
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        runner.runOnMainSync {
          future = MediaController.Builder(context, SessionToken(context,
            ComponentName(context, TtsPlaybackService::class.java))).buildAsync()
        }
        controller = future.get(10, TimeUnit.SECONDS)
        runner.runOnMainSync { controller!!.pause() }
        waitFor("media Pause") { !state().playWhenReady && !state().playing }
        val paused = state().cursor
        SystemClock.sleep(700)
        assertEquals("Paused speech cannot advance", paused, state().cursor)
        assertEquals(id, state().documentId)
        waitFor("spoken progress persisted") {
          runBlocking { db.documents().metadataById(id) }?.let {
            it.progressBlockId == paused?.blockId && it.progressCharOffset == paused?.charOffset
          } == true
        }
        runner.runOnMainSync { controller!!.play() }
        waitFor("media resume") { state().playing && state().playWhenReady }
        context.startActivity(Intent(context, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor("return to library") { node("Stop listening") != null }
        click(title)
        waitFor("reader restored") { node("Speed") != null }
        click("Speed")
        waitFor("Speed pauses narration") { node("Exit") != null && !state().playWhenReady && !state().playing }
        assertEquals("Speed retains a resumable session", id, state().documentId)
      }
    } finally {
      runner.runOnMainSync { controller?.stop(); controller?.release() }
      context.stopService(Intent(context, TtsPlaybackService::class.java))
      db.documents().deleteById(id)
    }
  }
  @Test fun mediaPauseAtADeferredSectionBoundary() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaSpeechLifecycle") == "true")
    check(context.packageName == "com.reader.app.qa")
    context.stopService(Intent(context, TtsPlaybackService::class.java))
    SystemClock.sleep(500)
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val last = java.util.concurrent.atomic.AtomicReference(com.reader.app.tts.TtsPlaybackState())
    val requested = java.util.concurrent.atomic.AtomicInteger()
    val doc = "qa-deferred-speech-boundary"
    fun section(index: Int, text: String): com.reader.app.data.PreparedSection {
      val projection = com.reader.app.core.RenderedText.project(com.reader.app.core.ArticleParser.parse(text))
      val parts = listOf(com.reader.app.core.ArticleSection(0, 1), com.reader.app.core.ArticleSection(0, 1))
      return com.reader.app.data.PreparedSection(com.reader.app.data.ArticleIndex(doc, projection.text.length, parts), index, projection)
    }
    lateinit var engine: com.reader.app.tts.AndroidTtsEngine
    lateinit var narrator: com.reader.app.tts.TtsNarrator
    lateinit var player: com.reader.app.tts.TtsMediaPlayer
    lateinit var session: androidx.media3.session.MediaSession
    lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
    var controller: MediaController? = null
    var initialized = false
    try {
      runner.runOnMainSync {
        engine = com.reader.app.tts.AndroidTtsEngine(context)
        narrator = com.reader.app.tts.TtsNarrator(engine, scope) { _, _, hint ->
          requested.incrementAndGet()
          if (hint == 0) section(0, "First part of the speech check.")
          else { gate.await(); section(1, "Second part should only play after resume.") }
        }
        player = com.reader.app.tts.TtsMediaPlayer(android.os.Looper.getMainLooper())
        narrator.onState = { last.set(it); player.update(it) }
        engine.onReady = { narrator.refreshVoice() }
        engine.onFocusLost = { narrator.pause() }
        player.onPlay = { narrator.play() }
        player.onPause = { narrator.pause() }
        player.onStop = { narrator.stop() }
        session = androidx.media3.session.MediaSession.Builder(context, player).build()
        future = MediaController.Builder(context, session.token).buildAsync()
        initialized = true
        narrator.start(doc, com.reader.app.cursor.SemanticCursor.start(doc), 1f, "Deferred boundary", null)
      }
      controller = future.get(10, TimeUnit.SECONDS)
      waitFor("next section suspends") {
        check(last.get().error == null) { "Google speech error: ${last.get().error}" }
        requested.get() == 2 && last.get().loading
      }
      assertTrue("Buffering keeps Pause available", last.get().playWhenReady)
      runner.runOnMainSync { controller!!.pause() }
      waitFor("boundary media Pause") { !last.get().playWhenReady }
      gate.complete(Unit)
      waitFor("paused next section adopted") { !last.get().loading }
      SystemClock.sleep(500)
      assertFalse("Load completion must not restart speech", last.get().playing)
      assertFalse(last.get().playWhenReady)
      assertEquals("No duplicate section load", 2, requested.get())
      runner.runOnMainSync { controller!!.play() }
      waitFor("boundary media resume") { last.get().playing }
    } finally {
      runner.runOnMainSync {
        controller?.release()
        if (initialized) { session.release(); narrator.stop(); engine.shutdown() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
      }
    }
  }

}
