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
    val feedDown = SystemClock.uptimeMillis()
    val feedX = feedScreen.widthPixels * .8f
    val feedY = feedScreen.heightPixels * .3f
    event(MotionEvent.ACTION_DOWN, feedX, feedY, feedDown)
    for (step in 1..20) {
      event(MotionEvent.ACTION_MOVE, feedX, feedY + feedScreen.heightPixels * .5f * step / 20f, feedDown)
      SystemClock.sleep(20)
    }
    event(MotionEvent.ACTION_UP, feedX, feedY + feedScreen.heightPixels * .5f, feedDown)
    SystemClock.sleep(700)
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
            result = location[0] + textView.totalPaddingLeft + layout.getPrimaryHorizontal(offset) to
              location[1] + textView.totalPaddingTop + y
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
        dragEnd(offsetPoint(paragraph + 25, false))
        val extendedEnd = selectionEnd()
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
        runner.runOnMainSync { scroll = native!!.scrollY }
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
      tap("Next")
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
}
