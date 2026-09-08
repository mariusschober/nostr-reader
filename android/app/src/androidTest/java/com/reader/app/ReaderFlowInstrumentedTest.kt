package com.reader.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.accessibilityservice.AccessibilityService
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ReaderCore
import com.reader.app.core.ArticleParser
import com.reader.app.core.RenderedText
import com.reader.app.data.HighlightAnchors
import com.reader.app.data.DocumentEntity
import com.reader.app.data.ReaderDb
import com.reader.app.prefs.*
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.NativeArticleView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

/** Tests the production screen through physical input and committed Room records. */
@RunWith(AndroidJUnit4::class)
class ReaderFlowInstrumentedTest {
  private val runner get() = InstrumentationRegistry.getInstrumentation()
  private val context get() = runner.targetContext
  private fun waitFor(label: String, test: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15000
    while (SystemClock.elapsedRealtime() < deadline) {
      if (test()) return
      SystemClock.sleep(100)
    }
    screenshot("reader-flow-timeout")
    error("Reader flow timed out: $label")
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
  private fun event(action: Int, x: Float, y: Float, down: Long) {
    val foreground = runner.uiAutomation.rootInActiveWindow?.packageName?.toString()
    check(foreground in setOf(context.packageName, "${context.packageName}.test", "android",
      "com.android.intentresolver", "com.android.systemui")) {
      "Stopped touch test: an unrelated app is foreground ($foreground)"
    }
    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
      it.source = InputDevice.SOURCE_TOUCHSCREEN
      check(runner.uiAutomation.injectInputEvent(it, true)); it.recycle()
    }
  }
  private fun tap(text: String) {
    runner.uiAutomation.waitForIdle(300, 5000)
    waitFor("control $text") { node(text) != null }
    val bounds = Rect().also { node(text)!!.getBoundsInScreen(it) }
    val down = SystemClock.uptimeMillis()
    event(MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), down)
    event(MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), down)
    SystemClock.sleep(200)
  }
  private fun screenshot(name: String) {
    val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
    File(context.getExternalFilesDir("qa"), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
  }
  private fun back() {
    check(runner.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    SystemClock.sleep(300)
  }
  private fun receiverFile(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
    runner.uiAutomation.executeShellCommand("run-as com.reader.app.qa.test $command files/received-quote.json")
  ).bufferedReader().use { it.readText() }

  private fun openNewestQuote(title: String) {
    tap("Highlights")
    tap("Newest")
    // The keyed feed preserves its visible item when sorting changes.
    val feedScreen = context.resources.displayMetrics
    val feedX = feedScreen.widthPixels * .8f
    val feedY = feedScreen.heightPixels * .3f
    var attempts = 0
    while (node(title) == null && attempts++ < 12) {
      val feedDown = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, feedX, feedY, feedDown)
      for (step in 1..20) {
        event(MotionEvent.ACTION_MOVE, feedX, feedY + feedScreen.heightPixels * .5f * step / 20f, feedDown)
        SystemClock.sleep(20)
      }
      event(MotionEvent.ACTION_UP, feedX, feedY + feedScreen.heightPixels * .5f, feedDown)
      SystemClock.sleep(700)
    }
    tap(title)
  }

  private fun verifyQuoteSharing(quote: String) {
    tap("Share")
    waitFor("native sharesheet") {
      runner.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != context.packageName } == true
    }
    screenshot("reader-review-sharesheet")
    SystemClock.sleep(500)
    back()
    waitFor("review after sharesheet") { node("Next") != null }
    receiverFile("rm -f")
    tap("Share")
    SystemClock.sleep(500)
    val screen = context.resources.displayMetrics
    val gestureDown = SystemClock.uptimeMillis()
    val x = screen.widthPixels / 2f
    val y = screen.heightPixels * .65f
    event(MotionEvent.ACTION_DOWN, x, y, gestureDown)
    for (step in 1..20) {
      event(MotionEvent.ACTION_MOVE, x, y - screen.heightPixels * .5f * step / 20f, gestureDown)
      SystemClock.sleep(20)
    }
    event(MotionEvent.ACTION_UP, x, y - screen.heightPixels * .5f, gestureDown)
    tap("Reader QA Quote Receiver")
    var payload: JSONObject? = null
    waitFor("test receiver payload") { payload = runCatching { JSONObject(receiverFile("cat")) }.getOrNull(); payload != null }
    val received = payload!!
    assertEquals(android.content.Intent.ACTION_SEND, received.getString("action"))
    assertEquals("text/plain", received.getString("type"))
    assertEquals(quote, received.getString("text"))
    assertFalse(received.has("subject"))
    assertFalse(received.getBoolean("hasStream"))
    waitFor("review after test receiver") { node("Next") != null }
  }

  @Test fun nativeSelectionPersistsOneQuoteAndUndoRemovesIt() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaReaderUi") == "true")
    check(context.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(context)
    val unique = System.nanoTime()
    val title = "Reader selection check $unique"
    val markdown = ReaderCore.canonicalize("# Reader selection check\n\nParagraph zero. One deliberate selection keeps exact Unicode: café, e\u0301, 日本語, 🌱.\n\nParagraph one. A saved quote remains a single record while its handles move.\n\n" +
      (2..45).joinToString("\n\n") { "Paragraph $it. Native handles let us extend one selection while the article scrolls. The quote remains exact." } + "\n\nRun $unique\n")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    Prefs(context).save(ReaderSettings(themeMode = ThemeMode.LIGHT))
    db.documents().insert(DocumentEntity(id, title, "web", null, "https://example.org/reader-synthetic", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now))
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      tap("Inbox"); tap(title)
      var native: NativeArticleView? = null
      fun find(view: View): NativeArticleView? {
        if (view is NativeArticleView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
      }
      waitFor("native reader content") {
        scenario.onActivity { native = find(it.window.decorView) }
        var ready = false
        runner.runOnMainSync { ready = native?.let { (it.getChildAt(0) as TextView).text.contains("deliberate") } == true }
        ready
      }
      val textView = native!!.getChildAt(0) as TextView
      tap("Highlight")
      runner.waitForIdleSync()
      SystemClock.sleep(500)
      var point = 0f to 0f
      var layoutDebug = ""
      runner.runOnMainSync {
        val offset = textView.text.indexOf("deliberate") + 3
        val line = textView.layout.getLineForOffset(offset)
        val location = IntArray(2); textView.getLocationOnScreen(location)
        layoutDebug = "offset=$offset line=$line bounds=${location.toList()} width=${textView.width} layoutWidth=${textView.layout.width} lineStart=${textView.layout.getLineStart(line)} lineEnd=${textView.layout.getLineEnd(line)} text=${textView.text.take(160)}"
        point = location[0] + textView.totalPaddingLeft + textView.layout.getPrimaryHorizontal(offset) to
          location[1] + textView.totalPaddingTop + (textView.layout.getLineTop(line) + textView.layout.getLineBottom(line)) / 2f
      }
      screenshot("reader-before-selection")
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, point.first, point.second, down)
      SystemClock.sleep(750)
      event(MotionEvent.ACTION_UP, point.first, point.second, down)
      SystemClock.sleep(1000)
      screenshot("reader-after-selection-gesture")
      var debug = ""
      runner.runOnMainSync { debug = "point=$point selection=${textView.selectionStart}:${textView.selectionEnd} selectable=${textView.isTextSelectable} scroll=${native!!.scrollY} textLength=${textView.length()}" }
      File(context.getExternalFilesDir("qa"), "reader-selection-state.txt").writeText("$debug\n$layoutDebug")
      waitFor("automatic highlight save: $debug") { runBlocking { db.highlights().observeForDocument(id).first().size == 1 } }
      val saved = db.highlights().observeForDocument(id).first().single()
      var selected = ""
      runner.runOnMainSync {
        selected = textView.text.substring(minOf(textView.selectionStart, textView.selectionEnd), maxOf(textView.selectionStart, textView.selectionEnd))
      }
      assertEquals(selected, saved.quote)
      assertTrue(saved.quote.contains("deliberate"))
      screenshot("reader-native-selection-saved")
      if (InstrumentationRegistry.getArguments().getString("qaSelectionHandles") == "true") {
        fun selectionEnd(): Int {
          var end = -1
          runner.runOnMainSync { end = maxOf(textView.selectionStart, textView.selectionEnd) }
          return end
        }
        fun offsetPoint(offset: Int, handle: Boolean): Pair<Float, Float> {
          var result = 0f to 0f
          runner.runOnMainSync {
            val location = IntArray(2); textView.getLocationOnScreen(location)
            val layout = textView.layout
            val line = layout.getLineForOffset(offset)
            val y = if (handle) layout.getLineBottom(line) + 8 * textView.resources.displayMetrics.density
              else (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            // The end handle's round touch target sits to the right of its
            // text-boundary tip; touching the tip can miss the popup window.
            result = location[0] + textView.totalPaddingLeft + layout.getPrimaryHorizontal(offset) +
              (if (handle) 12 * textView.resources.displayMetrics.density else 0f) to
              location[1] + textView.totalPaddingTop + y - textView.scrollY
          }
          return result
        }
        fun dragEnd(target: Pair<Float, Float>, hold: Boolean = false) {
          val start = offsetPoint(selectionEnd(), true)
          val gesture = SystemClock.uptimeMillis()
          event(MotionEvent.ACTION_DOWN, start.first, start.second, gesture)
          for (step in 1..if (hold) 100 else 30) {
            val fraction = (step / 30f).coerceAtMost(1f)
            event(MotionEvent.ACTION_MOVE, start.first + (target.first - start.first) * fraction,
              start.second + (target.second - start.second) * fraction, gesture)
            SystemClock.sleep(25)
          }
          event(MotionEvent.ACTION_UP, target.first, target.second, gesture)
          SystemClock.sleep(800)
        }
        fun verifySameRecord() {
          var exact = ""
          runner.runOnMainSync {
            exact = textView.text.substring(minOf(textView.selectionStart, textView.selectionEnd),
              maxOf(textView.selectionStart, textView.selectionEnd))
          }
          waitFor("handle movement updates the original quote") {
            runBlocking { db.highlights().observeForDocument(id).first().let {
              it.size == 1 && it.single().id == saved.id && it.single().quote == exact
            } }
          }
        }
        val originalEnd = selectionEnd()
        val paragraph = textView.text.indexOf("Paragraph one.")
        val handleStart = offsetPoint(originalEnd, true)
        // Native drag tracking keeps the text endpoint above the finger.
        // Aim into the following paragraph, then assert the actual range.
        val handleTarget = offsetPoint(textView.text.indexOf("Paragraph 2.") + 10, false)
        dragEnd(handleTarget)
        val extendedEnd = selectionEnd()
        screenshot("reader-handle-extension-attempt")
        File(context.getExternalFilesDir("qa"), "reader-handle-extension.txt").writeText(
          "originalEnd=$originalEnd paragraph=$paragraph handle=$handleStart target=$handleTarget extendedEnd=$extendedEnd\n")
        assertTrue("Handle extends across paragraphs", extendedEnd > paragraph)
        verifySameRecord()
        screenshot("reader-handle-extended")
        dragEnd(offsetPoint(paragraph + 5, false))
        val reversedEnd = selectionEnd()
        assertTrue("Handle reverses to a smaller selection", reversedEnd in (originalEnd + 1) until extendedEnd)
        verifySameRecord()
        val bounds = Rect()
        runner.runOnMainSync { native!!.getGlobalVisibleRect(bounds) }
        dragEnd(bounds.exactCenterX() to (bounds.bottom + 16 * textView.resources.displayMetrics.density), true)
        var scroll = 0
        runner.runOnMainSync { scroll = textView.scrollY }
        screenshot("reader-handle-autoscroll")
        assertTrue("Handle scrolls the production article", scroll > 0 && selectionEnd() > reversedEnd)
        verifySameRecord()
      }
      tap("Undo")
      waitFor("undo removed quote") { runBlocking { db.highlights().observeForDocument(id).first().isEmpty() } }
      tap("Speed")
      waitFor("speed reader") { node("Play") != null }
      assertNull(node("offset out of bounds"))
      screenshot("reader-speed-transition")
      tap("Exit")
      waitFor("returned to reader") { node("Appearance") != null }
      // Reuse the exact physically selected quote after separately verifying Undo.
      db.highlights().insert(saved)
      tap("Back")
      openNewestQuote(title)
      waitFor("review controls") { node("Share") != null }
      tap("☆ Important")
      waitFor("important saved") { runBlocking { db.highlights().byId(saved.id)?.important == true } }
      waitFor("important indicated") { node("★ Important") != null }
      tap(title)
      waitFor("review source reader") { node("Appearance") != null }
      screenshot("reader-review-source")
      tap("Back")
      waitFor("source returned to same review") { node("Share") != null && node(saved.quote) != null }
      verifyQuoteSharing(saved.quote)
      if (InstrumentationRegistry.getArguments().getString("qaReviewGestures") == "true") {
        fun swipeReview(right: Boolean) {
          val metrics = context.resources.displayMetrics
          val start = metrics.widthPixels * if (right) .2f else .8f
          val end = metrics.widthPixels * if (right) .8f else .2f
          val y = metrics.heightPixels * .45f
          val gesture = SystemClock.uptimeMillis()
          event(MotionEvent.ACTION_DOWN, start, y, gesture)
          for (step in 1..25) {
            event(MotionEvent.ACTION_MOVE, start + (end - start) * step / 25f, y, gesture)
            SystemClock.sleep(20)
          }
          event(MotionEvent.ACTION_UP, end, y, gesture)
        }
        val beforeSwipe = db.highlights().byId(saved.id)!!
        swipeReview(true)
        waitFor("right swipe changes importance without advancing") {
          runBlocking { db.highlights().byId(saved.id)?.important == false } && node(saved.quote) != null
        }
        assertEquals(beforeSwipe.reviewCount, db.highlights().byId(saved.id)?.reviewCount)
        screenshot("reader-review-right-swipe")
        swipeReview(false)
      } else tap("Next")
      waitFor("next review card") { node(title) == null && (node("Share") != null || node("You’re caught up") != null) }
      waitFor("review counted") { runBlocking { (db.highlights().byId(saved.id)?.reviewCount ?: 0) > 0 } }
      val reviewed = db.highlights().byId(saved.id)!!
      db.highlights().deleteAtRevision(saved.id, reviewed.revision)
    }
    db.documents().deleteById(id)
  }

  @Test fun deletedSourceQuoteSharesExactUnicodeAndLineBreaks() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaReaderUi") == "true")
    check(context.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(context)
    val unique = System.nanoTime()
    val title = "Unicode share check $unique"
    val quote = "Café x\u0301 — 日本語 🌱\nLine two: \"exact\", <tag>, & spaces.\n\nRun $unique"
    val markdown = ReaderCore.canonicalize("```\n$quote\n```\n")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    val document = DocumentEntity(id, title, "web", null, "https://example.org/reader-synthetic", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now)
    val projection = RenderedText.project(ArticleParser.parseWithSources(markdown))
    val start = projection.text.indexOf(quote)
    assertTrue(start >= 0)
    val saved = HighlightAnchors.create("unicode-$unique", document, projection, start, start + quote.length, now)
    assertEquals(quote, saved.quote)
    db.documents().insert(document)
    db.highlights().insert(saved)
    db.documents().deleteById(id)
    try {
      assertNotNull(db.highlights().byId(saved.id))
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        openNewestQuote(title)
        waitFor("retained quote review") { node("Share") != null && node(quote) != null }
        tap(title)
        waitFor("deleted source leaves review usable") { node("Next") != null }
        assertFalse(db.documents().exists(id))
        verifyQuoteSharing(quote)
        assertEquals(quote, db.highlights().byId(saved.id)?.quote)
        scenario.recreate()
        waitFor("review restored after activity recreation") { node("Share") != null && node(quote) != null }
        screenshot("reader-unicode-review-return")
      }
    } finally {
      db.highlights().byId(saved.id)?.let { db.highlights().deleteAtRevision(it.id, it.revision) }
    }
  }

  @Test fun readerSystemBarContrast() = runBlocking {
    assumeTrue(context.packageName == "com.reader.app.qa")
    check(context.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(context)
    val original = Prefs(context).load()
    val title = "System bar contrast ${System.nanoTime()}"
    val markdown = ReaderCore.canonicalize("$title\n\nA short synthetic article for checking the system bars.")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    Prefs(context).save(ReaderSettings(themeMode = ThemeMode.LIGHT))
    db.documents().insert(DocumentEntity(id, title, "web", null, "https://example.org/reader-synthetic", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now))
    try {
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        tap("Inbox"); tap(title)
        Prefs(context).save(Prefs(context).load().copy(themeMode = ThemeMode.DARK))
        waitFor("dark reader system bars") {
          var correct = false
          scenario.onActivity {
            val bars = androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
            correct = !bars.isAppearanceLightStatusBars && !bars.isAppearanceLightNavigationBars
          }
          correct
        }
        runner.uiAutomation.waitForIdle(500, 5000)
        screenshot("reader-system-bars-settled")
      }
    } finally {
      db.documents().deleteById(id)
      Prefs(context).save(original)
    }
  }

  @Test fun speechFocusBackgroundAndThemeChanges() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaReaderLifecycle") == "true")
    check(context.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(context)
    val original = Prefs(context).load()
    val unique = System.nanoTime()
    val title = "Speech lifecycle check $unique"
    val markdown = ReaderCore.canonicalize((1..40).joinToString("\n\n") {
      "Paragraph $it. A saved idea deserves patient attention. Reading aloud should preserve our place when the speed changes or another app needs audio. Returning to the article should remain calm and predictable."
    } + "\n\nRun $unique\n")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    Prefs(context).save(ReaderSettings(themeMode = ThemeMode.LIGHT))
    db.documents().insert(DocumentEntity(id, title, "web", null, "https://example.org/reader-synthetic", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now))
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
      .setOnAudioFocusChangeListener { }.build()
    try {
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        tap("Inbox"); tap(title); tap("Listen")
        var controller: com.reader.app.tts.TtsController? = null
        val field = MainActivity::class.java.getDeclaredField("ttsController").apply { isAccessible = true }
        fun state(): com.reader.app.tts.TtsController.State? {
          var result: com.reader.app.tts.TtsController.State? = null
          scenario.onActivity { controller = field.get(it) as? com.reader.app.tts.TtsController; result = controller?.state }
          return result
        }
        waitFor("speech controller is available") { state() != null }
        val initialSpeech = state()!!
        waitFor("real speech callbacks advance") {
          state()?.let { value ->
            check(value.error == null) { "Speech unavailable: ${value.error}" }
            value.playing && (value.offset != initialSpeech.offset || value.index != initialSpeech.index)
          } == true
        }
        tap("1.0x")
        waitFor("active speech uses the changed speed") { state()?.let { it.playing && it.speed == 1.25f } == true }
        assertEquals(1.25f, Prefs(context).load().ttsSpeed)
        assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(focus))
        waitFor("audio focus loss pauses speech") { state()?.playing == false && node("Play") != null }
        audio.abandonAudioFocusRequest(focus)
        val paused = state()!!
        SystemClock.sleep(700)
        assertEquals(paused, state())
        tap("Next sentence")
        waitFor("next sentence while paused") { state()?.index == paused.index + 1 }
        tap("Previous sentence")
        waitFor("previous sentence while paused") { state()?.index == paused.index }
        tap("Play")
        waitFor("speech resumed") { state()?.playing == true }
        check(runner.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        // Read the existing controller without asking ActivityScenario to resume it.
        waitFor("background stops narration") {
          var pausedInBackground = false
          runner.runOnMainSync { pausedInBackground = controller?.state?.playing == false }
          pausedInBackground
        }
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor("return leaves speech paused") { node("Play") != null }
        screenshot("reader-speech-return-paused")
        tap("Close player")
        var native: NativeArticleView? = null
        fun find(view: View): NativeArticleView? {
          if (view is NativeArticleView) return view
          if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
          return null
        }
        scenario.onActivity { native = find(it.window.decorView) }
        val body = native!!.getChildAt(0) as TextView
        var before: com.reader.app.cursor.SemanticCursor? = null
        runner.runOnMainSync { before = native!!.currentCursor() }
        Prefs(context).save(Prefs(context).load().copy(themeMode = ThemeMode.DARK))
        waitFor("follow-app reader becomes dark") { body.currentTextColor == 0xFFFFFCF0.toInt() }
        var after: com.reader.app.cursor.SemanticCursor? = null
        runner.runOnMainSync { after = native!!.currentCursor() }
        assertEquals("Theme change preserves the semantic reading position", before, after)
        screenshot("reader-follow-app-dark")
        Prefs(context).save(Prefs(context).load().copy(background = ArticleBackground.PAPER))
        waitFor("explicit Paper overrides dark app") { body.currentTextColor == 0xFF100F0F.toInt() }
        screenshot("reader-explicit-paper-dark-app")
        scenario.recreate()
        waitFor("explicit appearance survives recreation") { node("Appearance") != null }
        assertEquals(ArticleBackground.PAPER, Prefs(context).load().background)
        tap("Back"); tap("Settings")
        waitFor("versioned settings and theme controls") { node("Dark") != null && node("Reader 0.9.0-beta.1 · Licenses bundled in-app") != null }
        screenshot("reader-settings-dark-beta")
      }
    } finally {
      audio.abandonAudioFocusRequest(focus)
      db.documents().deleteById(id)
      Prefs(context).save(original)
    }
  }

  @Test fun largeArticleViewportPerformance() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaReaderPerf") == "true")
    check(context.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(context)
    val unique = System.nanoTime()
    val title = "Large viewport check $unique"
    val markdown = ReaderCore.canonicalize("# Large article viewport\n\n" + (1..1000).joinToString("\n\n") {
      "Paragraph $it. A long article must remain readable while native handles, ordinary scrolling, and semantic progress share one viewport. Unicode: café 日本語 🌱."
    } + "\n\nRun $unique\n")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    val originalSettings = Prefs(context).load()
    Prefs(context).save(ReaderSettings(themeMode = ThemeMode.LIGHT))
    db.documents().insert(DocumentEntity(id, title, "web", null, "https://example.org/reader-synthetic", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", "inbox", "b0", 0, 0f, 0, now, now))
    val frameThread = android.os.HandlerThread("reader-qa-frames").apply { start() }
    val frames = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
      frames.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION))
    }
    fun pssKb(): Int = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss
    fun shellReport(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
      runner.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    try {
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        tap("Inbox")
        val beforePss = pssKb()
        shellReport("dumpsys gfxinfo ${context.packageName} reset")
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(frameThread.looper)) }
        var native: NativeArticleView? = null
        fun find(view: View): NativeArticleView? {
          if (view is NativeArticleView) return view
          if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
          return null
        }
        val openedAt = SystemClock.elapsedRealtime()
        tap(title)
        waitFor("large native layout") {
          scenario.onActivity { native = find(it.window.decorView) }
          var ready = false
          runner.runOnMainSync {
            ready = native?.let { (it.getChildAt(0) as TextView).let { body -> body.length() > 100000 && body.layout != null } } == true
          }
          ready
        }
        val openMillis = SystemClock.elapsedRealtime() - openedAt
        val body = native!!.getChildAt(0) as TextView
        val bounds = Rect()
        runner.runOnMainSync { native!!.getGlobalVisibleRect(bounds) }
        var beforeCursor: com.reader.app.cursor.SemanticCursor? = null
        runner.runOnMainSync { beforeCursor = native!!.currentCursor() }
        var maxScroll = 0
        repeat(6) {
          val x = bounds.exactCenterX()
          val start = bounds.top + bounds.height() * .8f
          val end = bounds.top + bounds.height() * .25f
          val down = SystemClock.uptimeMillis()
          event(MotionEvent.ACTION_DOWN, x, start, down)
          for (step in 1..20) {
            event(MotionEvent.ACTION_MOVE, x, start + (end - start) * step / 20f, down)
            SystemClock.sleep(15)
          }
          event(MotionEvent.ACTION_UP, x, end, down)
          SystemClock.sleep(250)
          runner.runOnMainSync { maxScroll = maxOf(maxScroll, body.scrollY) }
        }
        var afterCursor: com.reader.app.cursor.SemanticCursor? = null
        runner.runOnMainSync { afterCursor = native!!.currentCursor(); native!!.reportCursor() }
        val articlePss = pssKb()
        screenshot("reader-large-viewport")
        File(context.getExternalFilesDir("qa"), "reader-large-gfxinfo.txt")
          .writeText(shellReport("dumpsys gfxinfo ${context.packageName} framestats"))
        File(context.getExternalFilesDir("qa"), "reader-large-meminfo.txt")
          .writeText(shellReport("dumpsys meminfo ${context.packageName}"))
        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        val timings = frames.filter { it > 0 }.sorted()
        val result = JSONObject().apply {
          put("canonicalChars", markdown.length); put("canonicalBytes", markdown.toByteArray().size)
          put("renderedChars", body.length()); put("lineCount", body.lineCount)
          put("viewportHeight", body.height); put("layoutHeight", body.layout.height)
          put("openToReadyMillisIncludingTestTapWait", openMillis); put("scrollGestures", 6); put("maxScrollY", maxScroll)
          put("pssBeforeKb", beforePss); put("pssArticleKb", articlePss)
          put("frameCount", timings.size); put("framesOver100ms", timings.count { it > 100000000 })
          put("maxFrameMillis", (timings.lastOrNull() ?: 0) / 1000000.0)
          put("p95FrameMillis", if (timings.isEmpty()) 0.0 else timings[((timings.size - 1) * .95).toInt()] / 1000000.0)
          put("cursorMoved", beforeCursor != afterCursor)
          put("build", "debug isolated QA"); put("runs", 1)
        }
        File(context.getExternalFilesDir("qa"), "reader-large-viewport.json").writeText(result.toString(2))
        assertTrue("Ordinary finger scrolling moves the large native article", maxScroll > 0)
        assertNotEquals(beforeCursor, afterCursor)
        tap("Back")
        waitFor("library after large article") { node("Inbox") != null }
      }
    } finally {
      frameThread.quitSafely()
      db.documents().deleteById(id)
      Prefs(context).save(originalSettings)
    }
  }
}
