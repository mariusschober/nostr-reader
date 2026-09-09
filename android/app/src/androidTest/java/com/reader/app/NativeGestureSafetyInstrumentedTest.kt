package com.reader.app

import android.graphics.Color
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.*
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.*
import com.reader.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGestureSafetyInstrumentedTest {
  @Test fun articleActionsIgnoreEdgesSelectionsCodeTablesAndCancellation() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val markdown = "A prose paragraph for article gestures.\n\n```\nCode must not archive an article.\n```\n\n| Table | Column |\n| --- | --- |\n| Horizontal | content |\n\nAnother prose paragraph."
    val projection = RenderedText.project(ArticleParser.parseWithSources(markdown))
    assertTrue(projection.blocks.any { it.kind == TextKind.TABLE })
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var native: NativeArticleView
      lateinit var body: TextView
      val actions = mutableListOf<ArticleAction>()
      scenario.onActivity { activity ->
        native = NativeArticleView(activity)
        native.display("gesture-fixture", projection, nativeArticleText(projection, Color.BLUE), ReaderSettings(),
          Color.BLACK, Color.WHITE, 24, SemanticCursor.start("gesture-fixture"))
        native.articleList = Triage.INBOX
        native.onArticleSwipe = { actions += it }
        activity.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { native }) }
        body = (0 until native.childCount).map { native.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
      }
      val deadline = SystemClock.elapsedRealtime() + 15000
      while (body.layout == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
      assertNotNull(body.layout)
      fun swipe(at: Int = 4, edge: Boolean = false, cancel: Boolean = false, retreat: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        var y = 0f
        runner.runOnMainSync {
          val line = body.layout.getLineForOffset(at)
          y = body.top + body.paddingTop + (body.layout.getLineTop(line) + body.layout.getLineBottom(line)) / 2f - body.scrollY
        }
        val start = if (edge) 1f else native.width * .3f
        val end = native.width * .85f
        fun event(action: Int, x: Float) = runner.runOnMainSync {
          MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            native.dispatchTouchEvent(it); it.recycle()
          }
        }
        event(MotionEvent.ACTION_DOWN, start)
        repeat(12) { event(MotionEvent.ACTION_MOVE, start + (end - start) * (it + 1) / 12); SystemClock.sleep(12) }
        if (retreat) event(MotionEvent.ACTION_MOVE, start + native.width * .1f)
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, if (retreat) start + native.width * .1f else end)
        SystemClock.sleep(180)
      }
      swipe(edge = true); assertTrue(actions.isEmpty())
      swipe(cancel = true); assertTrue(actions.isEmpty())
      swipe(retreat = true); assertTrue(actions.isEmpty())
      swipe(projection.blocks.first { it.kind == TextKind.CODE }.bodyStart + 2); assertTrue(actions.isEmpty())
      swipe(projection.blocks.first { it.kind == TextKind.TABLE }.bodyStart + 2); assertTrue(actions.isEmpty())
      runner.runOnMainSync { native.setPenMode(true) }
      swipe(); assertTrue(actions.isEmpty())
      runner.runOnMainSync { native.setPenMode(false); Selection.setSelection(body.text as Spannable, 2, 12) }
      swipe(); assertTrue(actions.isEmpty())
      runner.runOnMainSync { native.clearSelection() }
      swipe(); assertEquals(listOf(ArticleAction.Later), actions)
    }
  }
}
