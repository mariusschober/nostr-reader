package com.reader.app

import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.core.ReaderCore
import com.reader.app.data.*
import com.reader.app.ui.MainActivity
import com.reader.app.ui.screens.NativeArticleView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTransitionInstrumentedTest {
  private fun native(root: View): NativeArticleView? {
    if (root is NativeArticleView) return root
    if (root is ViewGroup) for (i in 0 until root.childCount) native(root.getChildAt(i))?.let { return it }
    return null
  }
  @Test fun laterPartSurvivesRecreationAndSpeedWaitsForSelectionCommit() = runBlocking {
    val runner = InstrumentationRegistry.getInstrumentation()
    check(runner.targetContext.packageName == "com.reader.app.qa")
    val app = runner.targetContext.applicationContext as ReaderApp
    val db = ReaderDb.get(app)
    val text = ReaderCore.canonicalize((1..2500).joinToString("\n\n") { "Paragraph $it: repeated café 🌱 e\u0301 and nested **strong text** for a synthetic transition." })
    val id = ReaderCore.documentId(text)
    val doc = DocumentEntity(id, "QA transition specimen", "web", null, null, null, null, 1, null,
      text, ReaderCore.wordCount(text), 2, "unread", "inbox", null, 0, 0f, 0, 1, 1)
    db.documents().insert(doc)
    try {
      ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        val ui = QaPairingUiInstrumentedTest()
        ui.click(doc.title)
        ui.click("Next part")
        fun waitNative(): NativeArticleView {
          var found: NativeArticleView? = null
          repeat(500) {
            scenario.onActivity { found = native(it.window.decorView)?.takeIf { v -> v.height > 0 && v.currentCursor()?.blockId?.let { block -> block.startsWith("p") && !block.startsWith("p0/") } == true } }
            if (found != null) return found!!
            SystemClock.sleep(10)
          }
          error("Later article part did not appear")
        }
        var view = waitNative()
        lateinit var before: com.reader.app.cursor.SemanticCursor
        scenario.onActivity {
          val body = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
          body.scrollTo(0, 1400); view.reportCursor(); before = checkNotNull(view.currentCursor())
        }
        scenario.recreate()
        view = waitNative()
        SystemClock.sleep(300)
        scenario.onActivity { assertEquals(before, view.currentCursor()) }
        ui.click("Highlight")
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch(Dispatchers.IO) { db.withTransaction { held.complete(Unit); release.await() } }
        held.await()
        var quote = ""
        scenario.onActivity {
          val body = (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable }
          quote = body.text.substring(0, 30)
          Selection.setSelection(body.text as Spannable, 0, 30)
        }
        try {
          ui.click("Reading tools")
          ui.click("Speed")
          SystemClock.sleep(150)
          scenario.onActivity { assertNotNull("Reader remains until storage settles", native(it.window.decorView)) }
        } finally { release.complete(Unit); transaction.join() }
        withTimeout(10000) { while (db.highlights().exportPage(100, 0).none { it.documentId == id && it.quote == quote }) delay(20) }
        var left = false
        repeat(200) { if (!left) { scenario.onActivity { left = native(it.window.decorView) == null }; SystemClock.sleep(20) } }
        assertTrue("Speed opens after the quote commits", left)
      }
    } finally {
      withContext(Dispatchers.Main) { app.reading.flush() }
      db.openHelper.writableDatabase.execSQL("DELETE FROM highlights WHERE documentId = ?", arrayOf(id))
      db.openHelper.writableDatabase.execSQL("DELETE FROM documents WHERE documentId = ?", arrayOf(id))
    }
  }
}
