package com.reader.app.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.*
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.reader.app.R
import com.reader.app.core.*
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ArticleFont
import com.reader.app.prefs.ReaderSettings
import java.util.UUID

/** A single selectable native surface per bounded article part. No editing or clipboard reads. */
class NativeArticleView(context: Context) : ScrollView(context) {
  private class SelectionText(context: Context) : TextView(context) {
    var changed: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(start: Int, end: Int) {
      super.onSelectionChanged(start, end)
      changed?.invoke(start, end)
    }
  }
  private val body = SelectionText(context)
  private var projection: RenderedProjection? = null
  private var documentId = ""
  private var actionMode: ActionMode? = null
  private var selectionSession: String? = null
  private var selectionSequence = 0L
  private var pendingSelection: Runnable? = null
  private var pen = false
  private var restoring = false
  private var markRanges: List<NativeMark> = emptyList()
  var onSelection: (String, Long, IntRange) -> Unit = { _, _, _ -> }
  var onCursor: (SemanticCursor, Float) -> Unit = { _, _ -> }
  var onMark: (List<String>) -> Unit = {}
  var onLink: (String) -> Unit = {}
  var onTap: () -> Unit = {}

  init {
    isFillViewport = true
    clipToPadding = false
    body.setTextIsSelectable(true)
    body.linksClickable = false
    body.setLineSpacing(0f, 1.35f)
    body.setPadding(0, dp(8), 0, dp(96))
    addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    body.changed = { start, end -> selectionChanged(start, end) }
    body.customSelectionActionModeCallback = object : ActionMode.Callback {
      override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        actionMode = mode
        selectionSession = UUID.randomUUID().toString()
        menu.add(0, HIGHLIGHT_ACTION, 0, "Highlight")
        return true
      }
      override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
      override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != HIGHLIGHT_ACTION) return false
        emitSelection()
        return true
      }
      override fun onDestroyActionMode(mode: ActionMode) {
        pendingSelection?.let { removeCallbacks(it) }
        pendingSelection = null
        actionMode = null
        selectionSession = null
      }
    }
    var downX = 0f; var downY = 0f
    body.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
        MotionEvent.ACTION_UP -> if (actionMode == null && body.selectionStart == body.selectionEnd &&
          kotlin.math.abs(event.x - downX) < dp(8) && kotlin.math.abs(event.y - downY) < dp(8)) {
          val at = body.getOffsetForPosition(event.x, event.y)
          val hits = markRanges.filter { at >= it.start && at < it.end }.sortedByDescending { it.createdAt }
          val link = projection?.styles?.lastOrNull { it.style == TextStyle.LINK && at >= it.start && at < it.end }?.value
          when {
            hits.isNotEmpty() -> onMark(hits.map { it.id })
            !pen && link != null -> onLink(link)
            !pen -> onTap()
          }
        }
      }
      false
    }
    setOnScrollChangeListener { _, _, _, _, _ -> if (!restoring) reportCursor() }
  }

  fun display(id: String, value: RenderedProjection, text: CharSequence, settings: ReaderSettings,
              foreground: Int, background: Int, margin: Int, initial: SemanticCursor) {
    val changed = projection !== value || documentId != id
    val saved = if (!changed) currentCursor() else initial
    documentId = id
    projection = value
    body.textSize = settings.fontSizeSp
    val font = when (settings.font) {
      ArticleFont.NEWSREADER -> R.font.newsreader_var
      ArticleFont.CRIMSON_PRO -> R.font.crimsonpro_var
      ArticleFont.ASUL -> R.font.asul_regular
      ArticleFont.ATKINSON -> R.font.atkinson_regular
      ArticleFont.ABEEZEE -> R.font.abeezee_regular
    }
    body.typeface = ResourcesCompat.getFont(context, font)
    body.setTextColor(foreground)
    setBackgroundColor(background)
    setPadding(dp(margin), 0, dp(margin), 0)
    fun restore() {
      restoring = true
      body.post {
        val layout = body.layout
        if (layout != null && saved != null) {
          val offset = value.offset(saved.blockId, saved.charOffset).coerceIn(0, body.length())
          val y = layout.getLineTop(layout.getLineForOffset(offset)) + body.paddingTop - height / 3
          scrollTo(0, y.coerceAtLeast(0))
        }
        restoring = false
      }
    }
    if (changed) {
      restoring = true
      clearSelection()
      markRanges = emptyList()
      val immutableText = SpannableString(text)
      // Use the same native layout for drawing and handle hit testing. On the TCL,
      // precomputed mixed heading/body spans reported incorrect horizontal positions.
      body.setText(immutableText, TextView.BufferType.SPANNABLE)
      restore()
    } else restore()
  }

  fun setPenMode(enabled: Boolean) {
    if (pen == enabled) return
    pen = enabled
    if (enabled) selectionChanged(body.selectionStart, body.selectionEnd)
    else pendingSelection?.let { removeCallbacks(it) }
  }

  fun setMarks(marks: List<NativeMark>) {
    if (marks == markRanges) return
    markRanges = marks
    val text = body.text as? Spannable ?: return
    text.getSpans(0, text.length, SavedBackground::class.java).forEach { text.removeSpan(it) }
    text.getSpans(0, text.length, SavedForeground::class.java).forEach { text.removeSpan(it) }
    // Split overlaps so each character has exactly one opaque foreground/background pair.
    val valid = marks.filter { it.start >= 0 && it.end <= text.length && it.end > it.start }
    val boundaries = valid.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    boundaries.zipWithNext().forEach { (start, end) ->
      val top = valid.filter { it.start <= start && it.end >= end }.maxWithOrNull(compareBy<NativeMark> { it.createdAt }.thenBy { it.id })
      if (top != null) {
        text.setSpan(SavedBackground(top.background), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(SavedForeground(top.foreground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
  }

  fun clearSelection(): Boolean {
    val active = actionMode != null || body.selectionStart != body.selectionEnd
    pendingSelection?.let { removeCallbacks(it) }
    actionMode?.finish()
    (body.text as? Spannable)?.let { android.text.Selection.removeSelection(it) }
    selectionSession = null
    return active
  }

  fun currentCursor(): SemanticCursor? {
    val value = projection ?: return null
    val layout = body.layout ?: return null
    val line = layout.getLineForVertical((scrollY + height / 3 - body.paddingTop).coerceAtLeast(0))
    return value.cursor(documentId, layout.getLineStart(line))
  }

  fun reportCursor() {
    if (restoring) return
    val value = projection ?: return
    val cursor = currentCursor() ?: return
    val at = value.offset(cursor.blockId, cursor.charOffset)
    onCursor(cursor, if (value.text.isEmpty()) 0f else at.toFloat() / value.text.length)
  }

  private fun selectionChanged(start: Int, end: Int) {
    pendingSelection?.let { removeCallbacks(it) }
    if (!pen || start < 0 || end < 0 || end == start) return
    val expectedStart = start; val expectedEnd = end
    pendingSelection = Runnable {
      if (pen && body.selectionStart == expectedStart && body.selectionEnd == expectedEnd) emitSelection()
    }.also { postDelayed(it, 450) }
  }

  private fun emitSelection() {
    val value = projection ?: return
    if (body.selectionStart < 0 || body.selectionEnd < 0) return
    val range = value.range(body.selectionStart, body.selectionEnd) ?: return
    val session = selectionSession ?: UUID.randomUUID().toString().also { selectionSession = it }
    onSelection(session, ++selectionSequence, range)
  }

  override fun onDetachedFromWindow() {
    reportCursor()
    pendingSelection?.let { removeCallbacks(it) }
    super.onDetachedFromWindow()
  }
  private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
  companion object {
    private const val HIGHLIGHT_ACTION = 0x52454144
  }
}

data class NativeMark(val id: String, val start: Int, val end: Int, val createdAt: Long, val background: Int, val foreground: Int)
private class SavedBackground(color: Int) : BackgroundColorSpan(color)
private class SavedForeground(color: Int) : ForegroundColorSpan(color)

/** Build on Dispatchers.Default before assigning to the native surface. */
fun nativeArticleText(value: RenderedProjection, linkColor: Int): SpannableString {
  val out = SpannableString(value.text)
  fun span(style: Any, start: Int, end: Int) {
    if (end > start) out.setSpan(style, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
  }
  value.styles.forEach {
    when (it.style) {
      TextStyle.BOLD -> span(StyleSpan(Typeface.BOLD), it.start, it.end)
      TextStyle.ITALIC -> span(StyleSpan(Typeface.ITALIC), it.start, it.end)
      TextStyle.STRIKE -> span(StrikethroughSpan(), it.start, it.end)
      TextStyle.CODE -> span(TypefaceSpan("monospace"), it.start, it.end)
      TextStyle.LINK -> { span(ForegroundColorSpan(linkColor), it.start, it.end); span(UnderlineSpan(), it.start, it.end) }
    }
  }
  value.blocks.forEach {
    if (it.kind == TextKind.HEADING) {
      span(RelativeSizeSpan(if (it.level == 1) 1.45f else 1.2f), it.start, it.bodyEnd)
      span(StyleSpan(Typeface.BOLD), it.start, it.bodyEnd)
    }
    if (it.kind == TextKind.CODE || it.kind == TextKind.TABLE) span(TypefaceSpan("monospace"), it.start, it.end)
    if (it.depth > 0) span(LeadingMarginSpan.Standard(it.depth * 16), it.start, it.end)
  }
  return out
}
