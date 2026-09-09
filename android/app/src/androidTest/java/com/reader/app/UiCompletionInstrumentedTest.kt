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
    if (InstrumentationRegistry.getArguments().getString("qaTalkBack") == "true") android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0)
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
  private fun node(label: String) = nodes().firstOrNull {
    it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
  }
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

  @Test fun cancelledArticleSwipeDoesNotMove() = isolated {
    val d = fixture("Cancellation article")
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox")
      drag(d.title, right = true, cancel = true)
      assertEquals("Cancellation must not move to Priority", Triage.INBOX, db.documents().metadataById(d.documentId)!!.list)
      assertNull(node("Moved to Priority"))
      drag(d.title, right = true, cancel = false)
      assertEquals("Deliberate release still moves", Triage.PRIORITY, db.documents().metadataById(d.documentId)!!.list)
    }
  }

  @Test fun cancelledHighlightSwipeDoesNotDelete() = isolated {
    val d = fixture("Cancellation quote")
    val q = quote(d)
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Highlights"); tap("Newest")
      drag(q.quote, right = false, cancel = true)
      assertNotNull("Cancelled swipe must retain quote", db.highlights().byId(q.id))
      drag(q.quote, right = true, cancel = true)
      assertFalse(db.highlights().byId(q.id)!!.important)
    }
  }

  @Test fun moveNotificationExpiresWithoutOpeningArticle() = isolated {
    fixture("Stay in Inbox")
    val d = fixture("Notification article")
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox")
      drag(d.title, right = true, cancel = false)
      waitFor("move notification") { node("Moved to Priority") != null }
      screenshot("move-notification")
      waitFor("move notification expires", 14000) { node("Moved to Priority") == null }
      assertNotNull("Still in library", node("Priority"))
      assertEquals(Triage.PRIORITY, db.documents().metadataById(d.documentId)!!.list)
      screenshot("move-notification-expired")
    }
  }

  @Test fun moveUndoRestoresArticle() = isolated {
    fixture("Stay in Inbox")
    val d = fixture("Undo article")
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox"); drag(d.title, right = true, cancel = false)
      tap("Undo")
      waitFor("article returns") { node(d.title) != null }
      assertEquals(Triage.INBOX, db.documents().metadataById(d.documentId)!!.list)
    }
  }

  @Test fun newerMoveReplacesNoticeAndDismissDoesNotUndo() = isolated {
    fixture("Inbox anchor")
    val first = fixture("First move")
    val second = fixture("Second move")
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox"); drag(first.title, right = true, cancel = false)
      waitFor("first notice") { node("Moved to Priority") != null }
      drag(second.title, right = false, cancel = false)
      waitFor("replacement notice") { node("Saved for later") != null && node("Moved to Priority") == null }
      tap("Dismiss")
      waitFor("notice dismissed") { node("Saved for later") == null }
      assertEquals(Triage.PRIORITY, db.documents().metadataById(first.documentId)!!.list)
      assertEquals(Triage.LATER, db.documents().metadataById(second.documentId)!!.list)
    }
  }

  @Test fun loadedEmptyInboxAndIncomingArticlesKeepChosenTab() = isolated {
    fixture("Priority anchor", Triage.PRIORITY)
    ActivityScenario.launch(MainActivity::class.java).use {
      waitFor("empty Inbox fallback") { node("Priority anchor") != null && node("Inbox") == null }
      fun selected(label: String): Boolean {
        var n = node(label)
        while (n != null) { if (n.isSelected) return true; n = n.parent }
        return false
      }
      for (tab in listOf("Priority", "Later", "Highlights")) {
        tap(tab)
        fixture("Incoming for $tab")
        waitFor("Inbox becomes available") { node("Inbox") != null }
        assertTrue("Incoming content must not steal $tab", selected(tab))
      }
      tap("Open archive")
      fixture("Incoming while archived")
      SystemClock.sleep(500)
      assertNotNull(node("No archived articles."))
      assertNull(node("Open archive"))
      back()
      waitFor("return to chosen Highlights tab") { selected("Highlights") }
    }
  }

  @Test fun allAppearanceChoicesPreserveLiveSelectionAndLogicalColors() = isolated {
    val d = fixture("Appearance selection specimen", paragraphs = 100)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      tap("Inbox"); tap(d.title)
      var native: com.reader.app.ui.screens.NativeArticleView? = null
      fun find(view: android.view.View): com.reader.app.ui.screens.NativeArticleView? {
        if (view is com.reader.app.ui.screens.NativeArticleView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
      }
      waitFor("native article") {
        scenario.onActivity { native = find(it.window.decorView) }
        native?.currentCursor() != null
      }
      val body = (0 until native!!.childCount).map { native!!.getChildAt(it) }.filterIsInstance<android.widget.TextView>().first { it.isTextSelectable }
      tap("Highlight")
      var range = 0 to 0
      var expectedText = ""
      runner.runOnMainSync {
        val start = body.text.indexOf("Paragraph 25.")
        // End at punctuation, not the whitespace before an emoji: saved
        // quotations intentionally trim leading/trailing selection whitespace.
        range = start to body.text.indexOf(" Native selection", start)
        val line = body.layout.getLineForOffset(start)
        body.scrollTo(0, body.layout.getLineTop(line) - body.height / 3)
        native!!.reportCursor()
        android.text.Selection.setSelection(body.text as android.text.Spannable, range.first, range.second)
        expectedText = body.text.substring(range.first, range.second)
      }
      var original: HighlightEntity? = null
      waitFor("saved live selection") {
        original = runBlocking { db.highlights().exportPage(100, 0).firstOrNull { it.documentId == d.documentId && it.quote == expectedText } }
        original != null
      }
      marks += original!!.id
      val base = Prefs(context).load()
      for (background in ArticleBackground.entries) for (font in ArticleFont.entries) {
        val settings = base.copy(background = background, font = font,
          themeMode = if (font.ordinal % 2 == 0) ThemeMode.LIGHT else ThemeMode.DARK,
          margin = ArticleMargin.entries[font.ordinal % ArticleMargin.entries.size])
        Prefs(context).save(settings)
        SystemClock.sleep(220)
        runner.runOnMainSync {
          assertEquals("Selection start survives $font/$background", range.first, body.selectionStart)
          assertEquals("Selection end survives $font/$background", range.second, body.selectionEnd)
          assertEquals(expectedText, body.text.substring(body.selectionStart, body.selectionEnd))
        }
      }
      assertEquals("YELLOW", db.highlights().byId(original!!.id)!!.color)
      for (color in listOf("Yellow", "Green", "Cyan", "Purple")) {
        reach("Highlight color $color"); tap("Highlight color $color")
        runner.runOnMainSync {
          native!!.clearSelection()
          val next = range.first + 100 + 50 * listOf("Yellow", "Green", "Cyan", "Purple").indexOf(color)
          android.text.Selection.setSelection(body.text as android.text.Spannable, next, next + 40)
          native!!.flushSelection()
        }
        (context.applicationContext as ReaderApp).reading.flush()
        val mark = db.highlights().exportPage(100, 0).first { it.documentId == d.documentId && it.color == color.uppercase() && it.id != original!!.id }
        marks += mark.id
      }
      screenshot("all-colors-live-selection")
    }
  }

  @Test fun archiveBatchBodySelectionAndOneUndo() = isolated {
    val a = fixture("Batch archived A", Triage.ARCHIVED)
    val b = fixture("Batch archived B", Triage.ARCHIVED)
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Open archive"); tap("Article options"); tap("Select")
      tap(a.title)
      waitFor("two archived selections") { node("2 selected") != null }
      screenshot("archive-batch-selection")
      tap("Unarchive")
      waitFor("batch notice") { node("Moved 2 articles to Inbox") != null }
      assertEquals(Triage.INBOX, db.documents().metadataById(a.documentId)!!.list)
      assertEquals(Triage.INBOX, db.documents().metadataById(b.documentId)!!.list)
      tap("Undo")
      waitFor("batch restored") { node(a.title) != null && node(b.title) != null }
      assertEquals(Triage.ARCHIVED, db.documents().metadataById(a.documentId)!!.list)
      assertEquals(Triage.ARCHIVED, db.documents().metadataById(b.documentId)!!.list)
    }
  }

  @Test fun articleListSettingsReaderAndRecreationKeepPosition() = isolated {
    for (list in listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER, Triage.ARCHIVED)) {
      repeat(18) { fixture("Nav $list ${it.toString().padStart(2, '0')}", list) }
    }
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      for (list in listOf(Triage.INBOX, Triage.PRIORITY, Triage.LATER, Triage.ARCHIVED)) {
        if (list == Triage.ARCHIVED) tap("Open archive") else tap(Triage.tabLabel(list))
        scroll(); scroll()
        val before = anchor("Nav $list")
        tap("Settings"); back(); assertAnchor(before)
        tap(before.first); waitFor("article") { node("Appearance") != null }
        back(); assertAnchor(before)
        scenario.recreate(); assertAnchor(before)
        screenshot("navigation-$list")
      }
    }
  }

  @Test fun highlightsReviewSourceAndSettingsKeepFeedPosition() = isolated {
    repeat(20) { quote(fixture("Nav quote ${it.toString().padStart(2, '0')}")) }
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      tap("Highlights"); tap("Newest"); scroll(); scroll()
      val before = anchor("Nav quote")
      tap("Settings"); back(); assertAnchor(before)
      tap(before.first); waitFor("review") { node("Next") != null }
      tap(before.first); waitFor("source reader") { node("Appearance") != null }
      back(); waitFor("review again") { node("Next") != null }
      back(); assertAnchor(before)
      scenario.recreate(); assertAnchor(before)
      screenshot("highlights-restored")
    }
  }

  @Test fun highlightMenuProvidesNonGestureActions() = isolated {
    val d = fixture("Accessible highlight")
    val q = quote(d)
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Highlights"); tap("Newest"); tap("Highlight options"); tap("Mark important")
      waitFor("important state") { node("Marked important") != null }
      assertTrue(db.highlights().byId(q.id)!!.important)
      tap("Highlight options"); tap("Remove highlight")
      assertNotNull(node("Remove highlight?"))
      tap("Cancel")
      assertNotNull(db.highlights().byId(q.id))
      tap("Highlight options"); tap("Remove highlight"); tap("Remove")
      waitFor("quote removed") { node(q.quote) == null }
      assertNull(db.highlights().byId(q.id))
      assertNotNull(db.documents().metadataById(d.documentId))
    }
  }

  @Test fun overlappingHighlightsCanCycleAndRecolorWithoutChangingText() = isolated {
    val d = fixture("Overlapping passages")
    val first = quote(d)
    val projection = RenderedText.project(ArticleParser.parseWithSources(d.canonicalMarkdown))
    val start = projection.text.indexOf("Paragraph")
    val second = HighlightAnchors.create("overlap-${System.nanoTime()}", d, projection, start + 12, start + 80,
      first.createdAt + 1).copy(color = "GREEN")
    HighlightRepository(db).saveSelection(second); marks += second.id
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      tap("Inbox"); tap(d.title)
      var body: android.widget.TextView? = null
      fun find(view: android.view.View): android.widget.TextView? {
        if (view is android.widget.TextView && view.isTextSelectable) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
      }
      waitFor("native overlap") { scenario.onActivity { body = find(it.window.decorView) }; body?.layout != null }
      var point = 0f to 0f
      runner.runOnMainSync {
        val b = body!!
        val at = start + 30
        val line = b.layout.getLineForOffset(at)
        val xy = IntArray(2); b.getLocationOnScreen(xy)
        point = xy[0] + b.paddingLeft + b.layout.getPrimaryHorizontal(at) - b.scrollX to
          xy[1] + b.paddingTop + (b.layout.getLineTop(line) + b.layout.getLineBottom(line)) / 2f - b.scrollY
      }
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, point.first, point.second, down)
      event(MotionEvent.ACTION_UP, point.first, point.second, down)
      tap("Next overlapping highlight")
      waitFor("older yellow overlap") { node("Yellow — selected") != null }
      screenshot("overlap-edit")
      tap("Purple")
      waitFor("edit closed") { node("Next overlapping highlight") == null }
      assertEquals("PURPLE", db.highlights().byId(first.id)!!.color)
      assertEquals(first.quote, db.highlights().byId(first.id)!!.quote)
      assertEquals("GREEN", db.highlights().byId(second.id)!!.color)
      assertEquals(second.quote, db.highlights().byId(second.id)!!.quote)
    }
  }

  @Test fun captureAppearanceReaderAndLongReviewAtCurrentDeviceConfiguration() = isolated {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaUiCapture") == "true")
    val tag = InstrumentationRegistry.getArguments().getString("qaCaptureName") ?: "configured"
    val d = fixture("A quiet reading session", paragraphs = 80)
    val projection = RenderedText.project(ArticleParser.parseWithSources(d.canonicalMarkdown))
    val start = projection.text.indexOf("Paragraph")
    val q = HighlightAnchors.create("long-${System.nanoTime()}", d, projection, start, start + 4200, System.currentTimeMillis())
    HighlightRepository(db).saveSelection(q); marks += q.id
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Inbox"); screenshot("$tag-library"); tap(d.title)
      waitFor("reader loaded") {
        nodes().any { it.className?.toString() == "android.widget.TextView" && it.text?.contains("Paragraph 1.") == true }
      }
      screenshot("$tag-reader")
      tap("Appearance")
      screenshot("$tag-appearance-fonts")
      reach("Atkinson Hyperlegible"); tap("Atkinson Hyperlegible")
      reach("Article text size")
      reach("Wide"); tap("Wide")
      reach("Black"); screenshot("$tag-appearance-backgrounds"); tap("Black")
      tap("Done"); screenshot("$tag-reader-black")
      reach("Highlight"); tap("Highlight")
      for (color in listOf("Yellow", "Green", "Cyan", "Purple")) {
        reach("Highlight color $color"); tap("Highlight color $color")
      }
      val swatches = listOf("Yellow", "Green", "Cyan", "Purple").map { color ->
        Rect().also { node("Highlight color $color")!!.getBoundsInScreen(it) }
      }
      assertTrue("All colors occupy one row", swatches.map { it.top }.distinct().size == 1)
      val density = context.resources.displayMetrics.density
      assertTrue("Color touch targets stay 48dp", swatches.all { it.width() >= 47 * density && it.height() >= 47 * density })
      assertNotNull(node("Turn highlighting off"))
      tap("Reading tools"); assertNotNull(node("Listen")); assertNotNull(node("Speed")); back()
      screenshot("$tag-pen-colors")
      // Back first clears native selection if any; this fixture has none.
      back()
      db.documents().setList(d.documentId, Triage.ARCHIVED, System.currentTimeMillis())
      (context.applicationContext as ReaderApp).deleteArticle(d.documentId)
      assertEquals(q.quote, db.highlights().byId(q.id)!!.quote)
      ReviewRepository(db).resume(q.id)
      tap("Highlights"); tap("Newest"); screenshot("$tag-highlights")
      tap("Review"); waitFor("long quote review") { node("Next") != null }
      screenshot("$tag-review-start")
      scroll(); screenshot("$tag-review-scrolled")
      reach("Share")
      reach("Next")
      reach("☆ Important"); tap("☆ Important")
      waitFor("important quote state") { node("★ Important") != null }
      screenshot("$tag-review-important")
      back(); tap("Settings"); screenshot("$tag-settings")
    }
  }

  @Test fun importsRenderTablesAndPreserveSelectableText() = isolated {
    val title = "Table layout specimen"
    val markdown = "# $title\n\n| Name | Value | Unit |\n|---|---|---|\n| Alpha | 12 | m |\n| Longer beta | 345 | seconds |\n| Gamma | 6 | kg |"
    val id = com.reader.app.sync.Ingest.importPasted(context, markdown); docs += id
    val htmlId = com.reader.app.sync.Ingest.importHtml(context,
      "<article><h2>HTML table specimen</h2><p>A <strong>bold</strong> paragraph.</p><table><tr><th>Item</th><th>Count</th></tr><tr><td>One</td><td>24</td></tr></table></article>", "qa")
    docs += htmlId
    val repo = (context.applicationContext as ReaderApp).articles
    assertEquals(1, repo.section(id, 0).projection.tables.size)
    assertEquals(1, repo.section(htmlId, 0).projection.tables.size)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      tap("Inbox"); tap(title)
      var body: android.widget.TextView? = null
      fun find(view: android.view.View): android.widget.TextView? {
        if (view is android.widget.TextView && view.isTextSelectable) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
      }
      waitFor("table native text") { scenario.onActivity { body = find(it.window.decorView) }; body?.text?.contains("Longer beta") == true }
      SystemClock.sleep(500)
      runner.runOnMainSync {
        val b = body!!
        val value = b.text.toString()
        val x1 = b.layout.getPrimaryHorizontal(value.indexOf("12"))
        val x2 = b.layout.getPrimaryHorizontal(value.indexOf("345"))
        assertTrue("Table values align in columns: $x1 vs $x2", kotlin.math.abs(x1 - x2) < 2f)
        assertEquals(repoText(markdown), value)
      }
      screenshot("markdown-table")
      tap("More actions"); tap("View tables")
      waitFor("expanded table") { node("Close table") != null }
      screenshot("expanded-table")
      val screen = context.resources.displayMetrics
      val down = SystemClock.uptimeMillis()
      val y = screen.heightPixels * .4f
      event(MotionEvent.ACTION_DOWN, screen.widthPixels * .8f, y, down)
      repeat(20) { step ->
        event(MotionEvent.ACTION_MOVE, screen.widthPixels * (.8f - .65f * (step + 1) / 20), y, down)
        SystemClock.sleep(20)
      }
      event(MotionEvent.ACTION_UP, screen.widthPixels * .15f, y, down)
      waitFor("last table column") { node("Unit") != null }
      screenshot("expanded-table-last-column")
      tap("Close table")
      back(); tap("HTML table specimen")
      waitFor("HTML table loaded") { nodes().any { it.text?.contains("One  │  24") == true } }
      screenshot("html-table")
    }
  }

  private fun repoText(markdown: String) = RenderedText.project(ArticleParser.parseWithSources(ReaderCore.canonicalize(markdown))).text

  @Test fun shuffleReordersAndReviewSwipeAnimatesWithoutDoubleAdvance() = isolated {
    val fixtures = (1..8).map { quote(fixture("Shuffle specimen $it")) }
    ActivityScenario.launch(MainActivity::class.java).use {
      tap("Highlights"); tap("Newest")
      fun visibleOrder() = nodes().mapNotNull { it.text?.toString() }.filter { it.startsWith("Shuffle specimen") }
      var previous = visibleOrder()
      repeat(4) {
        tap("Shuffle")
        waitFor("new shuffle order") { visibleOrder() != previous }
        previous = visibleOrder()
      }
      screenshot("asul-highlights-shuffled")
      ReviewRepository(db).resume(fixtures.first().id)
      tap("Review"); waitFor("review quote") { node("Next") != null }
      val current = ReviewRepository(db).resume().currentId!!
      val q = db.highlights().byId(current)!!
      val bounds = Rect().also { node(q.quote)!!.getBoundsInScreen(it) }
      val width = context.resources.displayMetrics.widthPixels.toFloat()
      val y = bounds.top + minOf(bounds.height() / 2f, 200f)
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, width * .8f, y, down)
      repeat(12) { step ->
        event(MotionEvent.ACTION_MOVE, width * (.8f - .5f * (step + 1) / 12), y, down)
        SystemClock.sleep(20)
      }
      screenshot("review-swipe-in-motion")
      event(MotionEvent.ACTION_CANCEL, width * .3f, y, down)
      SystemClock.sleep(500)
      assertEquals(current, ReviewRepository(db).resume().currentId)
      drag(q.quote, right = false, cancel = false)
      waitFor("review advanced") { runBlocking { ReviewRepository(db).resume().currentId != current } }
      assertEquals(1, db.highlights().byId(current)!!.reviewCount)
      screenshot("review-after-swipe")
    }
  }

  @Test fun talkBackLabelsStatesAndNonGestureActions() = isolated {
    assumeTrue(InstrumentationRegistry.getArguments().getString("qaTalkBack") == "true")
    val resolver = context.contentResolver
    val services = android.provider.Settings.Secure.getString(resolver, "enabled_accessibility_services")
    val enabled = android.provider.Settings.Secure.getString(resolver, "accessibility_enabled")
    val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    val record = org.json.JSONArray()
    fun focus(label: String) {
      waitFor(label) { node(label) != null }
      var n = node(label)
      var focused = false
      while (n != null && !focused) {
        focused = n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (!focused) n = n.parent
      }
      check(focused) { "Cannot accessibility-focus $label" }
      SystemClock.sleep(180)
      record.put(org.json.JSONObject().apply {
        put("label", label); put("text", n?.text); put("description", n?.contentDescription)
        put("selected", n?.isSelected); put("checkable", n?.isCheckable); put("checked", n?.isChecked)
        if (android.os.Build.VERSION.SDK_INT >= 30) put("state", n?.stateDescription)
        put("class", n?.className)
      })
    }
    fun click(label: String) {
      focus(label)
      var clicked = false
      repeat(3) {
        if (!clicked) {
          var n = node(label)
          while (n != null && !clicked) {
            clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
          }
          SystemClock.sleep(300)
        }
      }
      check(clicked) { "No accessible click for $label" }
    }
    fun accessibleReach(label: String) {
      repeat(12) {
        if (node(label) != null) return
        val scrollable = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser } ?: error("No accessible scroll for $label")
        check(scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        SystemClock.sleep(300)
      }
      error("No accessible route to $label")
    }
    val d = fixture("TalkBack reading example", paragraphs = 30)
    quote(d)
    automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
    try {
      val requested = (services.orEmpty().split(':').filter { it.isNotBlank() } + talkBack).distinct().joinToString(":")
      android.provider.Settings.Secure.putString(resolver, "enabled_accessibility_services", requested)
      android.provider.Settings.Secure.putString(resolver, "accessibility_enabled", "1")
      val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
      waitFor("TalkBack running") {
        manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
          .any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" }
      }
      SystemClock.sleep(1500)
      if (node("Finish") != null && nodes().any { it.text?.contains("Welcome to TalkBack") == true }) {
        click("Finish")
        SystemClock.sleep(500)
        if (node("Finish") != null) click("Finish")
      }
      ActivityScenario.launch(MainActivity::class.java).use {
        focus("Inbox"); focus("Priority"); focus("Later"); focus("Highlights")
        click("Article options"); focus("Move to Priority"); focus("Save for later"); focus("Archive"); click("Cancel")
        click(d.title); waitFor("reader") { node("Appearance") != null }
        focus("Back"); click("Appearance"); focus("Newsreader"); click("Atkinson Hyperlegible")
        accessibleReach("Article text size"); focus("Article text size")
        accessibleReach("Wide"); focus("Wide")
        accessibleReach("Follow system"); focus("Follow system"); focus("Paper"); focus("Soft")
        accessibleReach("Black"); focus("Ink"); focus("Black"); click("Done")
        click("Highlight")
        for (color in listOf("Yellow", "Green", "Cyan", "Purple")) {
          accessibleReach("Highlight color $color"); click("Highlight color $color"); focus("Highlight color $color")
        }
        screenshot("talkback-swatches")
        back(); click("Highlights"); click("Newest"); click("Highlight options")
        focus("Remove highlight"); click("Mark important")
        click(d.title); waitFor("review") { node("Next") != null }
        focus("★ Important"); focus("Share"); focus("Next")
        screenshot("talkback-review")
      }
    } finally {
      android.provider.Settings.Secure.putString(resolver, "enabled_accessibility_services", services)
      android.provider.Settings.Secure.putString(resolver, "accessibility_enabled", enabled)
      automation.dropShellPermissionIdentity()
      File(context.getExternalFilesDir("qa"), "ui-completion-talkback-traversal.json").writeText(record.toString(2))
    }
  }
}
