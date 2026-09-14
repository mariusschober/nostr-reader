package com.reader.app

import android.graphics.Color
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ArticleParser
import com.reader.app.core.RenderedText
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.NativeArticleView
import com.reader.app.ui.screens.nativeArticleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * Release-to-save settlement boundaries for continuous (pen) highlighting.
 *
 * Package B replaced the old debounced "finalize on pen-off / detach /
 * projection change" contract (previously pinned by SelectionFinalization
 * InstrumentedTest) with a single commit on release. These tests pin the two
 * boundaries that replacement introduced and that nothing else covers:
 * a cancelled gesture persists nothing, and a late acknowledgement from an
 * already-settled gesture cannot clear a newer selection. The release-then
 * persist path itself lives in HighlightSessionInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class PenSettlementInstrumentedTest {
  private fun fixtureText(): String = buildString {
    repeat(24) { n ->
      append("Paragraph $n keeps one deliberate continuous selection, and here the pointer holds the whole range before it is allowed to settle.\n\n")
    }
  }

  private fun waitFor(label: String, timeoutMillis: Long = 15000, check: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
      if (check()) return
      SystemClock.sleep(100)
    }
    error("Timed out: $label")
  }

  private fun launch(
    scenario: ActivityScenario<MainActivity>,
    text: String,
    onSettle: (String, IntRange, (Boolean) -> Unit) -> Unit,
  ): Pair<NativeArticleView, TextView> {
    val projection = RenderedText.project(ArticleParser.parseWithSources(text))
    val docId = "pen-settlement-" + System.nanoTime()
    val native = AtomicReference<NativeArticleView>()
    val body = AtomicReference<TextView>()
    scenario.onActivity { activity -> activity.setContent {
      AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        NativeArticleView(context).apply {
          display(docId, projection, nativeArticleText(projection, Color.BLUE), ReaderSettings(),
            Color.rgb(16, 15, 15), Color.rgb(255, 252, 240), 24, SemanticCursor.start(docId))
          this.onSettle = onSettle
          setPenMode(true)
          native.set(this)
          body.set((0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable })
        }
      })
    } }
    waitFor("native layout") { body.get()?.layout != null && (body.get()?.height ?: 0) > 0 }
    return native.get() to body.get()
  }

  private class Pointer(private val runner: android.app.Instrumentation) {
    fun point(view: TextView, offset: Int): Pair<Float, Float> {
      var out = 0f to 0f
      runner.runOnMainSync {
        val location = IntArray(2); view.getLocationOnScreen(location)
        val layout = view.layout ?: error("no layout")
        val line = layout.getLineForOffset(offset.coerceIn(0, view.length()))
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        out = location[0] + view.totalPaddingLeft + layout.getPrimaryHorizontal(offset.coerceIn(0, view.length())) - view.scrollX to
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

    fun selection(view: TextView): IntRange {
      var range = -1 until -1
      runner.runOnMainSync { range = view.selectionStart until view.selectionEnd }
      return range
    }
  }

  /** A gesture that the platform cancels must leave no settled range behind. */
  @Test fun cancelledPenGesturePersistsNothing() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val text = fixtureText()
    val settled = ConcurrentLinkedQueue<Pair<String, IntRange>>()
    val pointer = Pointer(runner)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      val (_, body) = launch(scenario, text, { session, range, ack -> settled.add(session to range); ack(true) })
      val wordOffset = text.indexOf("deliberate")
      val down = SystemClock.uptimeMillis()
      pointer.event(MotionEvent.ACTION_DOWN, pointer.point(body, wordOffset + 4), down)
      SystemClock.sleep(700)
      waitFor("word selection") { pointer.selection(body).last > pointer.selection(body).first }
      assertTrue("Holding must not settle before release", settled.isEmpty())
      pointer.event(MotionEvent.ACTION_CANCEL, pointer.point(body, wordOffset + 4), down)
      SystemClock.sleep(500)
      assertTrue("A cancelled gesture must persist nothing", settled.isEmpty())
    }
  }

  /**
   * The settle acknowledgement is asynchronous. Once a second gesture owns the
   * live selection, the first gesture's late ack must be ignored rather than
   * clearing the newer range.
   */
  @Test fun staleAckCannotClearANewerGesture() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val text = fixtureText()
    val settled = ConcurrentLinkedQueue<Pair<String, IntRange>>()
    val acks = ConcurrentLinkedQueue<(Boolean) -> Unit>()
    val pointer = Pointer(runner)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      val (native, body) = launch(scenario, text, { session, range, ack ->
        settled.add(session to range); acks.add(ack)
      })
      // Gesture A: hold a word, extend over wrapped lines, release. The ack is
      // deliberately withheld so it can later arrive "late".
      val wordA = text.indexOf("deliberate")
      val downA = SystemClock.uptimeMillis()
      pointer.event(MotionEvent.ACTION_DOWN, pointer.point(body, wordA + 4), downA)
      SystemClock.sleep(700)
      waitFor("first word selection") { pointer.selection(body).last > pointer.selection(body).first }
      val firstLive = pointer.selection(body)
      val endA = (firstLive.first + 300).coerceAtMost(body.length() - 1)
      runner.runOnMainSync { Selection.setSelection(body.text as Spannable, firstLive.first, endA + 1) }
      SystemClock.sleep(150)
      pointer.event(MotionEvent.ACTION_UP, pointer.point(body, endA), downA)
      waitFor("first settle") { settled.size == 1 }
      val sessionA = settled.toList().first().first
      // Gesture B starts while A's ack is still outstanding and becomes the
      // current owner of a fresh selection.
      runner.runOnMainSync { native.clearSelection() }
      SystemClock.sleep(250)
      val wordB = text.indexOf("pointer")
      val downB = SystemClock.uptimeMillis()
      pointer.event(MotionEvent.ACTION_DOWN, pointer.point(body, wordB + 3), downB)
      SystemClock.sleep(700)
      waitFor("second word selection") { pointer.selection(body).last > pointer.selection(body).first }
      val liveB = pointer.selection(body)
      acks.poll()!!.invoke(true)
      SystemClock.sleep(600)
      assertEquals("A stale acknowledgement must not clear the newer selection", liveB, pointer.selection(body))
      // Release B: it owns a distinct session and settles once.
      val endB = (liveB.first + 200).coerceAtMost(body.length() - 1)
      runner.runOnMainSync { Selection.setSelection(body.text as Spannable, liveB.first, endB + 1) }
      SystemClock.sleep(150)
      pointer.event(MotionEvent.ACTION_UP, pointer.point(body, endB), downB)
      waitFor("second settle") { settled.size == 2 }
      val sessions = settled.toList().map { it.first }
      assertEquals("Each gesture must own a distinct session", 2, sessions.distinct().size)
      assertTrue(sessions.first() == sessionA)
    }
  }
}
