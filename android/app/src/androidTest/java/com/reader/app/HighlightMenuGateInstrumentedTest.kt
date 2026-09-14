package com.reader.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StyleSpan
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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.ui.MainActivity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Public native selection inside the real Compose host, before renderer choice. */
@RunWith(AndroidJUnit4::class)
class HighlightMenuGateInstrumentedTest {
  @Test fun nativeReaderMenuSuppressionPreservesHandlesAndAutoscroll() {
    val runner = InstrumentationRegistry.getInstrumentation()
    val text = buildString {
      repeat(40) { n ->
        append("Paragraph $n. One deliberate selection crosses paragraph boundaries while preserving every word. Unicode remains exact: café, e\u0301, 日本語, 🌱. A calm reader keeps the native text handles and lets the page scroll.\n\n")
      }
    }
    val reference = AtomicReference<TextView>()
    val native = AtomicReference<com.reader.app.ui.screens.NativeArticleView>()
    val started = SystemClock.elapsedRealtime()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> activity.setContent {
        AndroidView(modifier = Modifier.fillMaxSize().padding(bottom = 64.dp), factory = { context ->
          com.reader.app.ui.screens.NativeArticleView(context).apply {
            val projection = com.reader.app.core.RenderedText.project(com.reader.app.core.ArticleParser.parseWithSources(text))
            display("menu-gate-fixture", projection, com.reader.app.ui.screens.nativeArticleText(projection, Color.BLUE),
              com.reader.app.prefs.ReaderSettings(), Color.rgb(16, 15, 15), Color.rgb(255, 252, 240), 24,
              com.reader.app.cursor.SemanticCursor.start("menu-gate-fixture"))
            setPenMode(true)
            native.set(this)
            reference.set((0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>().first { it.isTextSelectable })
          }
        })
      } }
      fun waitFor(label: String, check: () -> Boolean) {
        repeat(150) { if (check()) return; SystemClock.sleep(100) }
        error("Selection spike timed out: $label")
      }
      waitFor("layout") { reference.get()?.layout != null && (reference.get()?.height ?: 0) > 0 }
      val view = reference.get()
      val firstLayoutMillis = SystemClock.elapsedRealtime() - started
      assertFalse(view.onCheckIsTextEditor())
      // Both-modes gate. The spec requires the native menu/handle gate to
      // assert ordinary selection *and* continuous highlighting. The floating
      // text-action popup is not guaranteed to appear in rootInActiveWindow,
      // so drive the exact ActionMode.Callback the view installs instead: the
      // menu decision is deterministic and does not depend on the popup tree.
      fun menuHas(menu: Menu, title: String) = (0 until menu.size()).any { menu.getItem(it)?.title?.toString() == title }
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
      runner.runOnMainSync {
        native.get().setPenMode(false)
        fakeMenu.clear(); fakeMenu.add("Copy")
        callback.onCreateActionMode(fakeMode, fakeMenu)
        assertTrue("Ordinary selection must keep Android's text-action menu", fakeMenu.size() >= 1)
        assertTrue("Ordinary selection must offer the app's Highlight action", menuHas(fakeMenu, "Highlight"))
        callback.onDestroyActionMode(fakeMode)
      }
      runner.runOnMainSync {
        native.get().setPenMode(true)
        fakeMenu.clear(); fakeMenu.add("Copy")
        callback.onCreateActionMode(fakeMode, fakeMenu)
        assertEquals("Continuous highlighting must clear Android's text-action menu", 0, fakeMenu.size())
        callback.onDestroyActionMode(fakeMode)
      }
      fun point(offset: Int, handle: Boolean = false): Pair<Float, Float> {
        var result = 0f to 0f
        runner.runOnMainSync {
          val location = IntArray(2); view.getLocationOnScreen(location)
          val layout = view.layout; val line = layout.getLineForOffset(offset)
          val y = if (handle) layout.getLineBottom(line).toFloat() + 8 * view.resources.displayMetrics.density
            else (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
          result = location[0] + view.totalPaddingLeft + layout.getPrimaryHorizontal(offset) - view.scrollX to
            location[1] + view.totalPaddingTop + y - view.scrollY
        }
        return result
      }
      fun event(action: Int, point: Pair<Float, Float>, down: Long) {
        if (action == MotionEvent.ACTION_DOWN) QaTouch.releaseStalePointer(runner.uiAutomation, point.first, point.second, down)
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(runner.uiAutomation.injectInputEvent(event, true)); event.recycle()
      }
      fun selection(): Pair<Int, Int> {
        var result = -1 to -1
        runner.runOnMainSync { result = view.selectionStart to view.selectionEnd }
        return result
      }
      fun screenshot(name: String) {
        runner.uiAutomation.waitForIdle(300, 3000)
        val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
        File(runner.targetContext.getExternalFilesDir("qa"), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
      }
      // Continuous highlighting owns the whole gesture: hold a word, drag
      // across the wrapped paragraph, keep dragging below the reserved dock so
      // the article autoscrolls, then release once. Package B settles the
      // range on release, so the extension must happen inside a single pointer
      // sequence rather than a release-and-regrab of a native handle.
      val first = point(text.indexOf("selection") + 3)
      val down = SystemClock.uptimeMillis()
      event(MotionEvent.ACTION_DOWN, first, down)
      SystemClock.sleep(700)
      waitFor("native word selection") { val (a,b) = selection(); a >= 0 && b > a }
      val word = selection(); screenshot("highlight-menu-gate-word")
      fun hasMenu(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        root ?: return false
        if (root.text?.toString() in setOf("Copy", "Share", "Select all")) return true
        return (0 until root.childCount).any { hasMenu(root.getChild(it)) }
      }
      assertFalse("Highlight mode must not display Android's text action menu", hasMenu(runner.uiAutomation.rootInActiveWindow))
      val target = point(text.indexOf("Paragraph 2.") + 10)
      for (step in 1..30) {
        val f = step / 30f
        event(MotionEvent.ACTION_MOVE, first.first + (target.first - first.first) * f to first.second + (target.second - first.second) * f, down)
        SystemClock.sleep(20)
      }
      val cross = selection(); screenshot("highlight-menu-gate-paragraphs")
      assertTrue("A held drag crosses the first paragraph boundary", cross.second > text.indexOf("\n\n") + 2)
      val location = IntArray(2); runner.runOnMainSync { view.getLocationOnScreen(location) }
      // Keep dragging through the reserved 64dp dock area. Android offsets the
      // touched range from the finger, so 24dp does not cross a tightly padded
      // text viewport.
      val bottom = view.width * 0.65f to (location[1] + view.height + 56 * view.resources.displayMetrics.density)
      for (step in 1..100) {
        val f = (step / 20f).coerceAtMost(1f)
        event(MotionEvent.ACTION_MOVE, target.first + (bottom.first - target.first) * f to target.second + (bottom.second - target.second) * f, down)
        SystemClock.sleep(25)
      }
      val extended = selection(); screenshot("highlight-menu-gate-scrolled")
      event(MotionEvent.ACTION_UP, bottom, down)
      val result = buildJsonObject {
        put("bytes", text.toByteArray().size); put("firstLayoutMillis", firstLayoutMillis)
        put("wordStart", word.first); put("wordEnd", word.second)
        put("paragraphStart", cross.first); put("paragraphEnd", cross.second)
        put("scrolledStart", extended.first); put("scrolledEnd", extended.second)
        put("scrollY", view.scrollY); put("viewHeight", view.height); put("layoutHeight", view.layout.height)
        put("editable", view.onCheckIsTextEditor())
      }
      File(runner.targetContext.getExternalFilesDir("qa"), "highlight-menu-gate.json").writeText(result.toString())
      assertTrue("Continuous highlighting autoscrolls while extending", view.scrollY > 0 && extended.second > cross.second)
    }
  }
}
