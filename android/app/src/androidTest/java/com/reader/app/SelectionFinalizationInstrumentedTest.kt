package com.reader.app

import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.*
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.*
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SelectionFinalizationInstrumentedTest {
  @Test fun nativeExitsFreezeTheLastRangeAtZeroFiftyHundredAndTwoHundredMillis() = runBlocking {
    val runner = InstrumentationRegistry.getInstrumentation()
    val projection = RenderedText.project(ArticleParser.parseWithSources("Repeated café 🌱 e\u0301 text.\n\nRepeated café 🌱 e\u0301 text.\n"))
    val doc = DocumentEntity("selection-fixture", "Synthetic", "selection", null, null, null, null, 1, null,
      "", 8, 2, "unread", "inbox", null, 0, 0f, 0, 1, 1)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    for (wait in listOf(0L, 50L, 100L, 200L)) for (exit in listOf("pen-off", "detach", "projection-change")) {
      val directory = File(runner.targetContext.cacheDir, "selection-exit-$wait-$exit").apply { mkdirs() }
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      val release = CompletableDeferred<Unit>()
      val drafts = mutableListOf<HighlightEntity>()
      val owner = withContext(Dispatchers.Main) { ReadingSession(scope, directory) {
        release.await(); drafts += it; HighlightMutation(null, it)
      } }
      try {
        run {
          val reference = AtomicReference<NativeArticleView>()
          scenario.onActivity { activity ->
            val native = NativeArticleView(activity)
            native.display(doc.documentId, projection, nativeArticleText(projection, Color.BLUE), ReaderSettings(),
              Color.BLACK, Color.WHITE, 16, SemanticCursor.start(doc.documentId))
            native.onSelection = { session, _, range ->
              val draft = HighlightAnchors.create(session, doc, projection, range.first, range.last + 1, 1).copy(color = "CYAN")
              owner.submitSelection(draft) { }
            }
            native.setPenMode(true)
            activity.setContent { key(native) { AndroidView(modifier = Modifier.fillMaxSize(), factory = { native }) } }
            reference.set(native)
          }
          val native = reference.get()
          var laidOut = false
          repeat(500) {
            if (!laidOut) { scenario.onActivity { laidOut = native.height > 0 && native.isAttachedToWindow }; SystemClock.sleep(10) }
          }
          assertTrue("Selection fixture must be attached and laid out", laidOut)
          val start = projection.text.lastIndexOf("Repeated")
          val end = projection.text.length
          scenario.onActivity {
            val body = (0 until native.childCount).map { native.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
            Selection.setSelection(body.text as Spannable, start, start + 8)
            Selection.setSelection(body.text as Spannable, start, end)
          }
          SystemClock.sleep(wait)
          scenario.onActivity { activity ->
            when (exit) {
              "pen-off" -> native.setPenMode(false)
              "detach" -> activity.setContent { androidx.compose.material3.Text("Fixture detached") }
              else -> {
                val next = RenderedText.project(ArticleParser.parseWithSources("Different next part."))
                native.display(doc.documentId, next, nativeArticleText(next, Color.BLUE), ReaderSettings(),
                  Color.BLACK, Color.WHITE, 16, SemanticCursor.start(doc.documentId))
              }
            }
          }
          if (exit == "detach") {
            var detached = false
            repeat(500) { if (!detached) { scenario.onActivity { detached = !native.isAttachedToWindow }; SystemClock.sleep(10) } }
            assertTrue(detached)
          }
          assertTrue("DB write is deliberately delayed", drafts.isEmpty())
          release.complete(Unit)
          withContext(Dispatchers.Main) { owner.flush() }
          assertTrue("$exit at $wait ms must finalize selection", drafts.isNotEmpty())
          assertEquals(projection.text.substring(start, end), drafts.last().quote)
          assertEquals("CYAN", drafts.last().color)
          assertEquals(RENDERED_PROJECTION_VERSION, drafts.last().projectionVersion)
          assertTrue(drafts.last().startBlockId != projection.blocks.first().id)
        }
      } finally { scope.cancel(); directory.deleteRecursively() }
    }
    }
  }
}
