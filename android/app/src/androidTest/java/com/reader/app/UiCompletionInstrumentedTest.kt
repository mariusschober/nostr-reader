package com.reader.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import com.reader.app.core.RenderedText
import com.reader.app.data.*
import com.reader.app.prefs.*
import com.reader.app.ui.MainActivity
import com.reader.app.ui.Triage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual input and committed storage on the isolated package, never owner data. */
@RunWith(AndroidJUnit4::class)
class UiCompletionInstrumentedTest {
  private val runner get() = InstrumentationRegistry.getInstrumentation()
  private val automation get() = runner.getUiAutomation(
    android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
  private val context get() = runner.targetContext
  private val db get() = ReaderDb.get(context)
  private val docs = mutableListOf<String>()
  private val marks = mutableListOf<String>()

  private fun nodes(): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    fun walk(n: AccessibilityNodeInfo?) {
      n ?: return
      result += n
      for (i in 0 until n.childCount) walk(n.getChild(i))
    }
    walk(automation.rootInActiveWindow)
    return result
  }
  private fun node(label: String) = nodes().filter {
    it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
  }.sortedByDescending { it.isClickable }.firstOrNull()
  private fun waitFor(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeout
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate()) return
      SystemClock.sleep(100)
    }
    screenshot("timeout-${label.replace(Regex("[^a-zA-Z0-9]"), "-")}")
    error("Timed out: $label")
  }
  private fun screenshot(name: String) {
    SystemClock.sleep(350)
    val bitmap = checkNotNull(automation.takeScreenshot())
    File(context.getExternalFilesDir("qa"), "ui-completion-$name.png").outputStream().use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
  }
  private fun event(action: Int, x: Float, y: Float, down: Long) {
    check(automation.rootInActiveWindow?.packageName?.toString() == context.packageName)
    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
      it.source = InputDevice.SOURCE_TOUCHSCREEN
      check(automation.injectInputEvent(it, true)); it.recycle()
    }
  }
  private fun tap(label: String) {
    waitFor(label) { node(label) != null }
    val b = Rect().also { node(label)!!.getBoundsInScreen(it) }
    val down = SystemClock.uptimeMillis()
    event(MotionEvent.ACTION_DOWN, b.exactCenterX(), b.exactCenterY(), down)
    event(MotionEvent.ACTION_UP, b.exactCenterX(), b.exactCenterY(), down)
    SystemClock.sleep(250)
  }
  /** Tabs may overflow the viewport on narrow devices: nudge the tab row
   *  (the topmost scrollable surface) sideways until the label is visible. */
  private fun tapTab(label: String) {
    repeat(6) {
      if (node(label) != null) { tap(label); return }
      val row = nodes().filter { it.isVisibleToUser && it.isScrollable }
        .minByOrNull { Rect().also(it::getBoundsInScreen).top } ?: error("No tab row")
      val b = Rect().also(row::getBoundsInScreen)
      // Drag inside the scrollable's own bounds — the pinned attention
      // label to its right is not part of the scroll and would eat the
      // gesture.
      val y = b.exactCenterY()
      val startX = b.right - b.width() * .15f
      val endX = b.left + b.width() * .15f
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, startX, y, down)
      repeat(16) { step ->
        event(MotionEvent.ACTION_MOVE, startX - (startX - endX) * (step + 1) / 16f, y, down)
        SystemClock.sleep(20)
      }
      event(MotionEvent.ACTION_UP, endX, y, down)
      SystemClock.sleep(500)
      screenshot("tabscroll-${label.replace(Regex("[^a-zA-Z0-9]"), "-")}-$it")
    }
    error("Tab unreachable: $label")
  }
  private fun back() {
    check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    SystemClock.sleep(300)
  }
  private fun scroll(forward: Boolean = true) {
    val n = nodes().filter { it.isVisibleToUser && it.isScrollable }.maxByOrNull {
      Rect().also(it::getBoundsInScreen).height()
    } ?: error("No scrollable surface")
    val b = Rect().also(n::getBoundsInScreen)
    val x = b.left + b.width() * .6f
    val start = b.top + b.height() * if (forward) .82f else .25f
    val end = b.top + b.height() * if (forward) .25f else .82f
    val down = SystemClock.uptimeMillis()
    event(MotionEvent.ACTION_DOWN, x, start, down)
    repeat(24) { step ->
      event(MotionEvent.ACTION_MOVE, x, start + (end - start) * (step + 1) / 24f, down)
      SystemClock.sleep(20)
    }
    event(MotionEvent.ACTION_UP, x, end, down)
    SystemClock.sleep(700)
  }
  private fun anchor(prefix: String): Pair<String, Int> {
    val candidates = nodes().filter { it.isVisibleToUser && it.text?.startsWith(prefix) == true }
    val node = candidates.firstOrNull { Rect().also(it::getBoundsInScreen).height() > 30 } ?: error("No visible $prefix")
    return node.text.toString() to Rect().also(node::getBoundsInScreen).top
  }
  private fun assertAnchor(expected: Pair<String, Int>) {
    waitFor("return to ${expected.first}") { node(expected.first) != null }
    SystemClock.sleep(400)
    val y = Rect().also { node(expected.first)!!.getBoundsInScreen(it) }.top
    assertTrue("Row offset should be restored: ${expected.second} -> $y", kotlin.math.abs(y - expected.second) <= 4)
  }
  private fun reach(label: String) {
    repeat(12) {
      if (node(label) != null) return
      scroll()
    }
    error("Control is unreachable: $label")
  }
  private fun drag(label: String, right: Boolean, cancel: Boolean) {
    waitFor(label) { node(label) != null }
    val b = Rect().also { node(label)!!.getBoundsInScreen(it) }
    val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
    val start = screenWidth * if (right) .13f else .87f
    val end = screenWidth * if (right) .87f else .13f
    val down = SystemClock.uptimeMillis()
    event(MotionEvent.ACTION_DOWN, start, b.exactCenterY(), down)
    for (step in 1..20) {
      event(MotionEvent.ACTION_MOVE, start + (end - start) * step / 20f, b.exactCenterY(), down)
      SystemClock.sleep(20)
    }
    SystemClock.sleep(100)
    event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end, b.exactCenterY(), down)
    SystemClock.sleep(700)
  }
  private suspend fun fixture(title: String, list: String = Triage.INBOX, paragraphs: Int = 4): DocumentEntity {
    val markdown = ReaderCore.canonicalize("# $title\n\n" + (1..paragraphs).joinToString("\n\n") {
      "Paragraph $it. A deliberate reading session preserves this exact quotation: café, e\u0301, 日本語, 🌱. Native selection remains precise while the reader scrolls."
    } + "\n\nFixture ${System.nanoTime()}")
    val id = ReaderCore.documentId(markdown)
    val now = System.currentTimeMillis()
    val d = DocumentEntity(id, title, "web", null, "https://example.org/fixture", null, null,
      now, null, markdown, ReaderCore.wordCount(markdown), 1, "unread", list, "b0", 0, 0f, 0, now, now)
    db.documents().insert(d); docs += id
    return d
  }
  private suspend fun quote(d: DocumentEntity): HighlightEntity {
    val projection = RenderedText.project(ArticleParser.parseWithSources(d.canonicalMarkdown))
    val start = projection.text.indexOf("Paragraph")
    val mark = HighlightAnchors.create("ui-${System.nanoTime()}", d, projection, start,
      (start + 240).coerceAtMost(projection.text.length), System.currentTimeMillis())
    HighlightRepository(db).saveSelection(mark); marks += mark.id
    return mark
  }
  private fun isolated(block: suspend () -> Unit) = runBlocking {
    check(context.packageName == "com.reader.app.qa")
    val settings = Prefs(context).load()
    try {
      Prefs(context).save(ReaderSettings(themeMode = ThemeMode.LIGHT))
      block()
    } finally {
      try {
        (context.applicationContext as ReaderApp).reading.flush()
        val ownMarks = marks.toMutableSet()
        docs.forEach { id -> db.highlights().observeForDocument(id).first().forEach { ownMarks += it.id } }
        ownMarks.forEach { HighlightRepository(db).remove(it) }
        docs.forEach { db.documents().deleteById(it) }
      } finally { Prefs(context).save(settings) }
    }
  }

  // The old extensive campaign was superseded by the focused user-approved plan.
  @Test fun appearanceDefaultsBoldPersistenceAndNavigation() = isolated {
    val d = fixture("Focused appearance")
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox")
      for (label in listOf("Priority", "Later", "Archive", "Highlights", "Settings")) assertNotNull(node(label))
      tap(d.title); waitFor("reader ready") { node("Contents") != null }
      tap("Appearance"); assertNotNull(node("Text size · 19")); assertEquals(LineSpacing.COMFORT, Prefs(context).load().lineSpacing)
      tap("Font, margins & bold ▾"); reach("Bold text"); tap("Bold text")
      waitFor("bold saved") { runBlocking { Prefs(context).load().bold } }
      back(); tap("Appearance"); tap("Font, margins & bold ▾"); reach("Bold text")
      assertTrue(node("Bold text")!!.isChecked)
      tap("Bold text"); waitFor("bold off saved") { !runBlocking { Prefs(context).load().bold } }
      back(); tap("Back"); waitFor("continue entry") { node("Continue reading") != null }
      tap("Settings"); assertNotNull(node("Reading appearance")); tap("Reader")
      assertNotNull(node("Continue reading"))
    }
  }

  @Test fun quoteTapStartsReviewAndSingleRemovalHasUndo() = isolated {
    val d = fixture("Focused quotation")
    val q = quote(d)
    val reviewCountBefore = q.reviewCount
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Highlights"); tap("Newest"); tap(d.title)
      waitFor("review quote") { node("Next") != null && node("Open source") != null }
      assertEquals(reviewCountBefore, db.highlights().byId(q.id)!!.reviewCount)
      // Entering Review may persist its focused cursor, but it must not
      // credit the quote until an explicit Next/source action.
      tap("Back")
      tap("Highlight options"); tap("Important highlight")
      waitFor("important saved") { runBlocking { db.highlights().byId(q.id)!!.important } }
      tap("Done"); tap("Back to highlights")
      waitFor("highlight options") { node("Highlight options") != null }
      tap("Highlight options"); tap("Remove")
      waitFor("removed") { runBlocking { db.highlights().byId(q.id) == null } }
      // Importance may still own the active Undo; dismiss its notice first.
      if (node("Importance updated") != null) tap("Dismiss")
      waitFor("removal notice") { node("Highlight removed") != null }
      tap("Undo"); waitFor("restored") { runBlocking { db.highlights().byId(q.id) != null } }
      assertTrue(db.highlights().byId(q.id)!!.important)
      assertEquals(q.quote, db.highlights().byId(q.id)!!.quote)
      assertEquals(reviewBefore, db.review().parts())
    }
  }
}
