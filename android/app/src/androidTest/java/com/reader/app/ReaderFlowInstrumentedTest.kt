package com.reader.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ReaderCore
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
    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
      it.source = InputDevice.SOURCE_TOUCHSCREEN
      check(runner.uiAutomation.injectInputEvent(it, true)); it.recycle()
    }
  }
  private fun tap(text: String) {
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
      tap("Undo")
      waitFor("undo removed quote") { runBlocking { db.highlights().observeForDocument(id).first().isEmpty() } }
      tap("Speed")
      waitFor("speed reader") { node("Play") != null }
      assertNull(node("offset out of bounds"))
      screenshot("reader-speed-transition")
      tap("Exit")
      waitFor("returned to reader") { node("Appearance") != null }
    }
    db.documents().deleteById(id)
  }
}
