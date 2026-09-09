package com.reader.app

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.*
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeScrollMeasurementInstrumentedTest {
  @Test fun recordsTouchVelocityScrollDistanceAndFramesIndependently() {
    val projection = RenderedText.project(ArticleParser.parseWithSources("A synthetic paragraph for controlled native scrolling. ".repeat(3000)))
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var native: NativeArticleView
      lateinit var body: TextView
      val frames = mutableListOf<Double>()
      val intervals = mutableListOf<Double>()
      var previousFrame = 0L
      var measuring = true
      val frameClock = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(time: Long) {
          if (previousFrame != 0L) intervals += (time - previousFrame) / 1_000_000.0
          previousFrame = time
          if (measuring) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
      }
      scenario.onActivity { activity ->
        native = NativeArticleView(activity)
        native.display("scroll-fixture", projection, nativeArticleText(projection, Color.BLUE), ReaderSettings(),
          Color.BLACK, Color.WHITE, 24, SemanticCursor.start("scroll-fixture"))
        activity.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { native }) }
        body = (0 until native.childCount).map { native.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
      }
      var laidOut = false
      repeat(500) {
        if (!laidOut) { scenario.onActivity { laidOut = native.height > 0 && body.layout != null }; SystemClock.sleep(10) }
      }
      assertTrue("Measurement requires a laid-out native viewport", laidOut)
      scenario.onActivity { activity ->
        android.view.Choreographer.getInstance().postFrameCallback(frameClock)
        activity.window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
        frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
      }, Handler(Looper.getMainLooper())) }
      val tracker = VelocityTracker.obtain()
      val down = SystemClock.uptimeMillis()
      var startY = 0f
      var endY = 0f
      var initialScroll = 0
      var releaseScroll = 0
      var velocity = 0f
      scenario.onActivity { startY = native.height * .78f; endY = native.height * .22f; initialScroll = body.scrollY }
      for (step in 0..12) {
        scenario.onActivity {
          val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
          val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, native.width * .5f,
            startY + (endY - startY) * step / 12, 0)
          event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
          tracker.addMovement(event)
          native.dispatchTouchEvent(event)
          event.recycle()
          if (step == 12) { releaseScroll = body.scrollY; tracker.computeCurrentVelocity(1000); velocity = tracker.yVelocity }
        }
        SystemClock.sleep(10)
      }
      SystemClock.sleep(800)
      scenario.onActivity { activity ->
        measuring = false
        android.view.Choreographer.getInstance().removeFrameCallback(frameClock)
        val values = frames.sorted()
        val cadence = intervals.sorted()
        val result = buildJsonObject {
          put("touchVelocityPixelsPerSecond", velocity)
          put("fingerDistancePixels", startY - endY)
          put("scrollDuringTouchPixels", releaseScroll - initialScroll)
          put("scrollAfterReleasePixels", body.scrollY - releaseScroll)
          put("frames", values.size)
          put("frameIntervalSamples", cadence.size)
          put("frameIntervalP95Millis", cadence.getOrNull((cadence.size * .95).toInt())?.let { JsonPrimitive(it) } ?: JsonNull)
          put("frameIntervalMaxMillis", cadence.lastOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
          put("frameP95Millis", values.getOrElse((values.size * .95).toInt().coerceAtMost(values.lastIndex.coerceAtLeast(0))) { 0.0 })
          put("frameMaxMillis", values.lastOrNull() ?: 0.0)
          put("input", "instrumented native touch dispatch")
          put("movementMethod", body.movementMethod.javaClass.name)
        }
        File(activity.getExternalFilesDir("qa"), "native-scroll-measurement.json").writeText(result.toString())
        assertTrue("Control swipe must scroll", releaseScroll > initialScroll)
        assertTrue("A fast released drag continues in the same native viewport", body.scrollY > releaseScroll)
      }
      tracker.recycle()
    }
  }

  @Test fun momentumStopsForNewTouchSelectionPenAndCancellation() {
    val runner = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val projection = RenderedText.project(ArticleParser.parseWithSources("Selectable synthetic paragraph. ".repeat(4000)))
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var native: NativeArticleView
      lateinit var body: TextView
      scenario.onActivity { activity ->
        native = NativeArticleView(activity)
        native.display("stop-fixture", projection, nativeArticleText(projection, Color.BLUE), ReaderSettings(),
          Color.BLACK, Color.WHITE, 24, SemanticCursor.start("stop-fixture"))
        activity.setContent { AndroidView(modifier = Modifier.fillMaxSize(), factory = { native }) }
        body = (0 until native.childCount).map { native.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
      }
      val deadline = SystemClock.elapsedRealtime() + 15000
      while (body.layout == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
      assertNotNull(body.layout)
      fun send(action: Int, y: Float, down: Long) = runner.runOnMainSync {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, native.width * .5f, y, 0).also {
          it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
          native.dispatchTouchEvent(it); it.recycle()
        }
      }
      fun fastDrag(cancel: Boolean = false): Int {
        val down = SystemClock.uptimeMillis()
        val start = native.height * .8f
        val end = native.height * .25f
        send(MotionEvent.ACTION_DOWN, start, down)
        for (step in 1..12) {
          send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 12, down)
          SystemClock.sleep(10)
        }
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end, down)
        return body.scrollY
      }
      fun assertStopped(label: String) {
        val at = body.scrollY
        SystemClock.sleep(250)
        assertEquals(label, at, body.scrollY)
      }
      val released = fastDrag()
      SystemClock.sleep(100)
      assertTrue(body.scrollY > released)
      val down = SystemClock.uptimeMillis()
      send(MotionEvent.ACTION_DOWN, native.height * .5f, down)
      assertStopped("New touch stops momentum immediately")
      send(MotionEvent.ACTION_CANCEL, native.height * .5f, down)
      fastDrag()
      runner.runOnMainSync { native.setPenMode(true) }
      assertStopped("Pen mode stops existing momentum")
      fastDrag()
      assertStopped("Pen-mode drag has no post-release momentum")
      runner.runOnMainSync { native.setPenMode(false) }
      fastDrag(cancel = true)
      assertStopped("Cancelled drag never starts momentum")
      fastDrag()
      var selectionStart = 0
      runner.runOnMainSync {
        selectionStart = body.layout.getLineStart(body.layout.getLineForVertical(body.scrollY + body.height / 2))
        android.text.Selection.setSelection(body.text as android.text.Spannable, selectionStart, selectionStart + 20)
      }
      // TextView may bring a newly selected range into view on the next frame;
      // that native selection behavior is independent from kinetic scrolling.
      SystemClock.sleep(100)
      assertStopped("Selection stops existing momentum")
      assertEquals(selectionStart, body.selectionStart)
      assertEquals(selectionStart + 20, body.selectionEnd)
      runner.runOnMainSync { native.clearSelection() }
      fastDrag()
      runner.runOnMainSync { native.flushSelection() }
      assertStopped("Navigation flush stops momentum before capturing progress")
    }
  }
}
