package com.reader.app

import android.graphics.Color
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ArticleParser
import com.reader.app.core.RenderedText
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.DocumentEntity
import com.reader.app.data.HighlightAnchors
import com.reader.app.data.HighlightRepository
import com.reader.app.data.ReaderDb
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.NativeArticleView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.reader.app.data.ReadingSession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for F01. One continuous highlighting session - a word long-press
 * saved, then a live-selection extension of the same selection - must drive
 * exactly one session id and persist exactly one highlight row with the final
 * range. The previous callback-only gate could not see the duplicate because it
 * never touched persistence.
 */
@RunWith(AndroidJUnit4::class)
class HighlightSessionInstrumentedTest {
  @Test fun oneHandleDragPersistsExactlyOneHighlight() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val app = ApplicationProvider.getApplicationContext<ReaderApp>()
    check(app.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(app)
    val repo = HighlightRepository(db)
    val run = System.currentTimeMillis()
    val docId = "session-gate-$run"
    val text = buildString {
      repeat(24) { n ->
        append("Paragraph $n keeps one deliberate continuous selection, and here the test drags the end handle across the boundary to extend it.\n\n")
      }
    }
    val projection = RenderedText.project(ArticleParser.parseWithSources(text))
    val doc = DocumentEntity(docId, "Session gate", "test", null, null, null, null, run, "en", text,
      text.split(' ').size, 1, "reading", "inbox", null, 0, 0f, run, run, run)
    val settled = ConcurrentLinkedQueue<Pair<String, IntRange>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val journal = File(app.cacheDir, docId)
    val reading = ReadingSession(scope, journal) { repo.saveSelection(it) }
    runBlocking { db.documents().insert(doc) }
    val native = AtomicReference<NativeArticleView>()
    val body = AtomicReference<TextView>()
    try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> activity.setContent {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
          NativeArticleView(context).apply {
            display(docId, projection, com.reader.app.ui.screens.nativeArticleText(projection),
              com.reader.app.prefs.ReaderSettings(), Color.rgb(16, 15, 15), Color.rgb(255, 252, 240), 24,
              SemanticCursor.start(docId))
            onSettle = { session, range, ack ->
              settled.add(session to range)
              val draft = HighlightAnchors.create(session, doc, projection, range.first, range.last + 1, System.currentTimeMillis())
              reading.submitSelection(draft) { ack(true) }
            }
            setPenMode(true)
            native.set(this)
            body.set((0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable })
          }
        })
      } }
      fun waitFor(label: String, check: () -> Boolean) {
        repeat(200) { if (check()) return; SystemClock.sleep(100) }
        var detail = ""
        runner.runOnMainSync {
          val textView = body.get()
          detail = "selection=" + textView?.selectionStart + ".." + textView?.selectionEnd +
            ", scroll=" + textView?.scrollY + ", focused=" + textView?.hasWindowFocus() +
            ", touching=" + native.get()?.gestureActive + ", settlements=" + settled.size
        }
        error("Timed out: $label ($detail)")
      }
      waitFor("focused article layout") {
        var ready = false
        runner.runOnMainSync { ready = body.get()?.let { it.hasWindowFocus() && it.isShown && it.layout != null && it.height > 0 } == true }
        ready
      }
      val view = body.get()
      val drawn = java.util.concurrent.CountDownLatch(1)
      val drawListener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
      runner.runOnMainSync { view.viewTreeObserver.addOnDrawListener(drawListener); view.invalidate() }
      assertTrue("The article must draw before injecting touch", drawn.await(20, java.util.concurrent.TimeUnit.SECONDS))
      runner.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(drawListener) }
      runner.waitForIdleSync()
      fun point(offset: Int, handle: Boolean = false): Pair<Float, Float> {
        var out = 0f to 0f
        runner.runOnMainSync {
          val location = IntArray(2); view.getLocationOnScreen(location)
          val layout = view.layout; val line = layout.getLineForOffset(offset)
          val y = if (handle) layout.getLineBottom(line).toFloat() + 8 * view.resources.displayMetrics.density
            else (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
          out = location[0] + view.totalPaddingLeft + layout.getPrimaryHorizontal(offset) - view.scrollX to
            location[1] + view.totalPaddingTop + y - view.scrollY
        }
        return out
      }
      fun event(action: Int, p: Pair<Float, Float>, down: Long) {
        if (action == MotionEvent.ACTION_DOWN) QaTouch.releaseStalePointer(runner.uiAutomation, p.first, p.second, down)
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.first, p.second, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        check(runner.uiAutomation.injectInputEvent(e, true)); e.recycle()
      }
      fun selection(): IntRange {
        var start = -1; var end = -1
        runner.runOnMainSync { start = view.selectionStart; end = view.selectionEnd }
        return start until end
      }
      // 1) Long-press a word and keep the finger down: the gesture is owned,
      // and holding alone must not commit anything yet.
      val word = text.indexOf("deliberate")
      val press = point(word + 4)
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, press, down)
      SystemClock.sleep(700)
      waitFor("word selection") { selection().last > selection().first }
      assertTrue("A held gesture must not settle before release", settled.isEmpty())
      // 2) Extend the live selection while the finger is still down, the way a
      // hold-drag travels over wrapped lines, then release once to commit the
      // final half-open range.
      val before = selection()
      val targetEnd = (before.first + 400).coerceAtMost(view.length() - 1)
      runner.runOnMainSync { Selection.setSelection(view.text as Spannable, before.first, targetEnd + 1) }
      SystemClock.sleep(150)
      event(MotionEvent.ACTION_UP, point(targetEnd), down)
      waitFor("settled emission") { settled.isNotEmpty() }
      SystemClock.sleep(400)
      // 3) The actual queue/Room bridge must commit before acknowledging and
      // clearing the native selection. Callback emission alone is insufficient.
      assertEquals("One gesture must produce exactly one settled emission", 1, settled.size)
      val (session, range) = settled.single()
      runBlocking { reading.flush() }
      waitFor("successful acknowledgement removes selection") { selection().first < 0 || selection().last < selection().first }
      val rows = runBlocking { db.highlights().observeForDocument(docId).first() }
      assertEquals("One gesture must persist exactly one highlight row", 1, rows.size)
      assertEquals("The persisted row carries the settled session id", session, rows.single().id)
      assertEquals(docId, rows.single().documentId)
      assertTrue("The hold-drag must extend past the initial word", range.last - range.first > 50)
      assertEquals("The saved quote equals the final selected range",
        projection.text.substring(range.first, range.last + 1), rows.single().quote)
      // A fresh long-press works immediately after acknowledgement, without an extra clear/tap.
      // Extending the first selection can scroll the first paragraph off screen.
      var secondWord = 0
      runner.runOnMainSync {
        val layout = view.layout
        val mid = layout.getLineForVertical(view.scrollY + (view.height - view.totalPaddingTop - view.totalPaddingBottom) / 2)
        val last = layout.getLineForVertical(view.scrollY + view.height - view.totalPaddingTop - view.totalPaddingBottom - 1)
        val visibleText = view.text.subSequence(layout.getLineStart(mid), layout.getLineEnd(last)).toString()
        val word = requireNotNull(Regex("[a-zA-Z]{3,}").find(visibleText)) { "Expected a word in the visible paragraph" }
        secondWord = layout.getLineStart(mid) + word.range.first + 1
      }
      val down2 = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, point(secondWord), down2)
      SystemClock.sleep(700)
      waitFor("second gesture selects") { selection().last > selection().first }
      val secondRange = selection()
      event(MotionEvent.ACTION_UP, point(secondWord), down2)
      waitFor("second settle") { settled.size == 2 }
      runBlocking { reading.flush() }
      waitFor("second acknowledgement removes selection") { selection().first < 0 || selection().last < selection().first }
      val finalRows = runBlocking { db.highlights().observeForDocument(docId).first() }
      assertEquals(2, finalRows.size)
      assertEquals(2, settled.map { it.first }.distinct().size)
      val second = settled.last()
      assertEquals(projection.text.substring(secondRange.first, secondRange.last + 1), finalRows.single { it.id == second.first }.quote)
    } } finally {
      scope.cancel()
      runBlocking {
        db.highlights().observeForDocument(docId).first().forEach { db.highlights().deleteAtRevision(it.id, it.revision) }
        db.documents().deleteById(docId)
      }
      if (journal.listFiles()?.none { it.extension == "json" } == true) journal.deleteRecursively()
    }
  }

  /**
   * The ordinary Android menu commits immediately, then a handle adjustment
   * must update that same row. The old path had no owned range at first commit
   * and treated a shared-boundary adjustment as a second session.
   */
  @Test fun ordinaryMenuHighlightThenBoundaryAdjustmentPersistsOneRow() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val app = ApplicationProvider.getApplicationContext<ReaderApp>()
    check(app.packageName == "com.reader.app.qa")
    val db = ReaderDb.get(app)
    val repo = HighlightRepository(db)
    val run = System.currentTimeMillis()
    val docId = "ordinary-session-gate-$run"
    val text = "Ordinary menu selection keeps one identity when its end handle moves across a shared boundary."
    val projection = RenderedText.project(ArticleParser.parseWithSources(text))
    val doc = DocumentEntity(docId, "Ordinary session", "test", null, null, null, null, run, "en", text,
      text.split(' ').size, 1, "reading", "inbox", null, 0, 0f, run, run, run)
    val emissions = ConcurrentLinkedQueue<Triple<String, Long, IntRange>>()
    val native = AtomicReference<NativeArticleView>()
    val body = AtomicReference<TextView>()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> activity.setContent {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
          NativeArticleView(context).apply {
            display(docId, projection, com.reader.app.ui.screens.nativeArticleText(projection),
              com.reader.app.prefs.ReaderSettings(), Color.rgb(16, 15, 15), Color.rgb(255, 252, 240), 24,
              SemanticCursor.start(docId))
            onSelection = { session, sequence, range -> emissions.add(Triple(session, sequence, range)) }
            native.set(this)
            body.set((0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable })
          }
        })
      } }
      fun waitFor(label: String, check: () -> Boolean) {
        repeat(200) { if (check()) return; SystemClock.sleep(50) }
        error("Timed out: $label")
      }
      waitFor("layout") { body.get()?.layout != null && (body.get()?.height ?: 0) > 0 }
      val view = body.get()
      val callback = view.customSelectionActionModeCallback
      val fakeMenu = PopupMenu(view.context, null).menu
      val fakeMode = object : ActionMode() {
        override fun getMenuInflater(): MenuInflater = MenuInflater(view.context)
        override fun getMenu(): Menu = fakeMenu
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setTitleOptionalHint(titleOptional: Boolean) {}
        override fun isTitleOptional(): Boolean = false
        override fun getCustomView(): View? = null
        override fun setCustomView(view: View?) {}
        override fun setType(type: Int) {}
        override fun getType(): Int = 0
        override fun invalidate() {}
        override fun finish() {}
      }
      val firstStart = 5
      val firstEndExclusive = 12
      val adjustedStart = firstEndExclusive - 1 // one shared selected character
      val adjustedEndExclusive = 28
      runner.runOnMainSync {
        Selection.setSelection(view.text as Spannable, firstStart, firstEndExclusive)
        fakeMenu.clear(); fakeMenu.add("Copy")
        check(callback.onCreateActionMode(fakeMode, fakeMenu))
        val highlight = (0 until fakeMenu.size()).map { fakeMenu.getItem(it) }.first { it.title == "Highlight" }
        check(callback.onActionItemClicked(fakeMode, highlight))
        Selection.setSelection(view.text as Spannable, adjustedStart, adjustedEndExclusive)
      }
      waitFor("ordinary adjustment emission") { emissions.size >= 2 }
      runner.runOnMainSync { callback.onDestroyActionMode(fakeMode) }
      val savedIds = mutableListOf<String>()
      for ((session, _, range) in emissions) {
        runBlocking { repo.saveSelection(HighlightAnchors.create(session, doc, projection, range.first, range.last + 1, run)) }
        savedIds += session
      }
      val rows = runBlocking { db.highlights().observeForDocument(docId).first() }
      val last = emissions.last().third
      assertEquals("Ordinary menu and handle adjustment share one id", 1, savedIds.distinct().size)
      assertEquals("One intentional selection persists one row", 1, rows.size)
      assertEquals("Final adjusted range is stored", projection.text.substring(last.first, last.last + 1), rows.single().quote)
      assertEquals("The adjustment starts at the requested shared boundary", adjustedStart, last.first)

      // A clear/navigation boundary must flush a committed adjustment before
      // its debounce fires, even when the old mode's teardown arrives later.
      val beforeClear = emissions.size
      runner.runOnMainSync {
        fakeMenu.clear(); fakeMenu.add("Copy"); fakeMenu.add("Share")
        check(callback.onCreateActionMode(fakeMode, fakeMenu))
        Selection.setSelection(view.text as Spannable, adjustedStart, adjustedEndExclusive + 4)
        native.get().clearSelection()
        callback.onDestroyActionMode(fakeMode)
      }
      assertEquals("Clear flushes the final committed range once", beforeClear + 1, emissions.size)
      val cleared = emissions.last()
      assertEquals(savedIds.singleOrNull() ?: savedIds.first(), cleared.first)
      runBlocking { repo.saveSelection(HighlightAnchors.create(cleared.first, doc, projection, cleared.third.first, cleared.third.last + 1, run)) }
      val clearedRows = runBlocking { db.highlights().observeForDocument(docId).first() }
      assertEquals(1, clearedRows.size)
      assertEquals(projection.text.substring(adjustedStart, adjustedEndExclusive + 4), clearedRows.single().quote)

      // Dismissing that committed mode and selecting an overlapping passage
      // for Copy must not inherit the committed bit or autosave. A real touch
      // away from the old handle clears the pending continuation; this fake
      // callback path models the same non-handle recreation boundary.
      val beforeCopy = emissions.size
      runner.runOnMainSync {
        // Explicitly end the old selection before the next ordinary gesture;
        // this is the ownership boundary that separates a Copy-only passage
        // from a transient ActionMode recreation.
        native.get().clearSelection()
        Selection.setSelection(view.text as Spannable, adjustedStart, adjustedStart + 6)
        fakeMenu.clear(); fakeMenu.add("Copy"); fakeMenu.add("Share")
        check(callback.onCreateActionMode(fakeMode, fakeMenu))
        assertTrue("Copy remains a platform action", !callback.onActionItemClicked(fakeMode, fakeMenu.findItem(0)))
        callback.onDestroyActionMode(fakeMode)
      }
      SystemClock.sleep(250)
      assertEquals("Copy-only overlapping selection must not autosave", beforeCopy, emissions.size)
    }
  }
}
