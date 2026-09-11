package com.reader.app

import android.graphics.Color
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.nativeMonoUnderlineOffsets
import com.reader.app.ui.screens.nativeMonoVisualRuns
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Compact real-Layout coverage for wrapped, boundary and bidi mark geometry. */
@RunWith(AndroidJUnit4::class)
class NativeMonoGeometryInstrumentedTest {
  @Test fun wrappedAndBidiRunsStayVisibleAndIndependent() {
    val articleText = "To save something, keep the first selected run on its own line and finish here.\n" +
      "English אבג mixed direction keeps visual runs separate when selected across a wrap.\n" +
      "A second overlapping mark remains individually reachable."
    val reference = AtomicReference<TextView>()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> activity.setContent {
        AndroidView(modifier = Modifier.width(280.dp).height(520.dp), factory = { context ->
          TextView(context).apply {
            textSize = 19f
            setTextColor(Color.BLACK)
            setPadding(0, 8, 0, 8)
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
            setText(articleText)
            reference.set(this)
          }
        })
      } }
      var laidOut = false
      repeat(200) {
        if (!laidOut) {
          laidOut = reference.get()?.layout != null && (reference.get()?.height ?: 0) > 0
          if (!laidOut) SystemClock.sleep(25)
        }
      }
      assertTrue("native TextView must lay out before geometry inspection", laidOut)
      val view = reference.get()
      val layout = checkNotNull(view.layout)
      val start = articleText.indexOf("something")
      val end = articleText.indexOf("finish") + "finish".length
      val wrapped = nativeMonoVisualRuns(view.text, start, end, layout, "double")
      assertTrue("partial/full/partial wrapped selection must draw runs", wrapped.size >= 3)
      assertTrue("every run has positive width", wrapped.all { it.right > it.left })
      assertTrue("runs stay within the native line width", wrapped.all { it.left >= layout.getLineLeft(it.line) - 4 && it.right <= layout.getLineRight(it.line) + 4 })
      assertTrue("wrapped selection reaches more than one line", wrapped.map { it.line }.distinct().size >= 2)
      val firstLine = layout.getLineForOffset(start)
      val lastLine = layout.getLineForOffset(end - 1)
      val startX = layout.getPrimaryHorizontal(start)
      val endX = layout.getPrimaryHorizontal(end)
      assertTrue(
        "first rule begins at the selected start caret",
        wrapped.filter { it.line == firstLine }.any { startX in (it.left - 1f)..(it.right + 1f) },
      )
      assertTrue(
        "last rule ends at the selected end caret",
        wrapped.filter { it.line == lastLine }.any { endX in (it.left - 1f)..(it.right + 1f) },
      )

      val bidiStart = articleText.indexOf("English")
      val bidiEnd = articleText.indexOf("selected", bidiStart)
      val bidi = nativeMonoVisualRuns(view.text, bidiStart, bidiEnd, layout, "dotted")
      assertFalse("mixed-direction selection must retain visible runs", bidi.isEmpty())
      assertTrue("bidi runs remain bounded to their visual lines", bidi.all { it.right > it.left && it.line in 0 until layout.lineCount })
      assertTrue(
        "partial bidi selection keeps disjoint visual pieces",
        bidi.groupBy { it.line }.values.any { pieces ->
          pieces.sortedBy { it.left }.zipWithNext().any { (left, right) -> right.left - left.right > 1f }
        },
      )

      val overlapA = nativeMonoVisualRuns(view.text, start, start + 30, layout, "solid")
      val overlapB = nativeMonoVisualRuns(view.text, start + 12, start + 52, layout, "double")
      assertTrue("first overlapping mark keeps its own geometry", overlapA.isNotEmpty())
      assertTrue("second overlapping mark keeps its own geometry", overlapB.isNotEmpty())

      // A real Layout with line spacing must leave a drawable metric gap. This
      // catches the old baseline + getLineDescent calculation, which included
      // the added spacing and returned no underline Y at all.
      val density = view.resources.displayMetrics.density
      val underline = wrapped.asSequence()
        .mapNotNull { run ->
          nativeMonoUnderlineOffsets(
            layout, run.line, run.edge, 1.5f * density, density, 8f, view.text, view.paint,
          )
        }
        .firstOrNull()
      assertTrue("wrapped mark must have a visible underline position", underline != null)
      val ys = checkNotNull(underline)
      assertTrue("underline must be below the glyph metric edge", ys.first > 0f)
      val second = ys.second
      assertTrue("double edge keeps two distinct rules when the gap allows it", second != null)
      if (second != null) assertTrue("double edge's rules are ordered", second > ys.first)
    }
  }
}
