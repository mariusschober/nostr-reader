package com.reader.app.ui.screens

import androidx.core.view.doOnPreDraw
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
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.reader.app.R
import com.reader.app.core.*
import com.reader.app.cursor.SemanticCursor
import com.reader.app.prefs.ArticleFont
import com.reader.app.prefs.ReaderSettings
import java.util.UUID
import com.reader.app.ui.ArticleAction
import com.reader.app.ui.Triage

/** A single selectable native surface per bounded article part. No editing or clipboard reads. */
class NativeArticleView(context: Context) : FrameLayout(context) {
  private class SelectionText(context: Context) : TextView(context) {
    var changed: ((Int, Int) -> Unit)? = null
    var scrolled: ((scrollY: Int, viewportHeight: Int) -> Unit)? = null
    override fun onSelectionChanged(start: Int, end: Int) {
      super.onSelectionChanged(start, end)
      changed?.invoke(start, end)
    }
    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
      super.onScrollChanged(x, y, oldX, oldY)
      if (y != oldY) scrolled?.invoke(y, height)
    }
  }
  private val body = SelectionText(context)
  private val swipeLabel = TextView(context)
  private var projection: RenderedProjection? = null
  private var documentId = ""
  private var actionMode: ActionMode? = null
  private var selectionSession: String? = null
  private var selectionSequence = 0L
  private var selectionCommitted = false
  private var pendingSelection: Runnable? = null
  private var pen = false
  private var restoring = false
  private var markRanges: List<NativeMark> = emptyList()
  var onSelection: (String, Long, IntRange) -> Unit = { _, _, _ -> }
  var onCursor: (SemanticCursor, Float) -> Unit = { _, _ -> }
  var onMark: (List<String>) -> Unit = {}
  var onLink: (String) -> Unit = {}
  var onTap: () -> Unit = {}
  var gestureActive = false
    private set
  var onTable: (Int) -> Unit = {}
  /** Viewport position for overlay affordances (e.g. back-to-top). */
  var onAtEnd: (Boolean) -> Unit = {}
  var onViewport: (scrollY: Int, viewportHeight: Int) -> Unit = { _, _ -> }

  var onArticleSwipe: (ArticleAction) -> Unit = {}
  var onSwipeProgress: (Float, Boolean) -> Unit = { _, _ -> }
  var articleList: String? = null
  var deleteTint: Int = 0xFFAF3029.toInt()
  private var swipeX = 0f
  private var swipeY = 0f
  private var swipeEligible = false
  private var swiping = false
  private var swipeArmed = false
  private val fling = OverScroller(context)
  private val touchConfig = ViewConfiguration.get(context)
  private var velocity: VelocityTracker? = null
  private var flingEligible = false
  private val flingFrame = object : Runnable {
    override fun run() {
      if (pen || hasSelection() || !isAttachedToWindow) { stopFling(); return }
      if (fling.computeScrollOffset()) {
        body.scrollTo(body.scrollX, fling.currY.coerceIn(0, maxScrollY()))
        postOnAnimation(this)
      }
    }
  }

  private fun hasSelection() = actionMode != null || body.selectionStart != body.selectionEnd
  private fun maxScrollY() = ((body.layout?.height ?: 0) + body.totalPaddingTop + body.totalPaddingBottom - body.height).coerceAtLeast(0)
  private fun isAtEnd(): Boolean {
    val layout = body.layout ?: return false
    if (body.height <= 0) return false
    // TextView can stop before trailing blank lines and extra line spacing.
    // Completion follows the last visible text line, not that empty padding.
    val last = body.text.indexOfLast { !it.isWhitespace() }.coerceAtLeast(0)
    val line = layout.getLineForOffset(last)
    val textBottom = layout.getLineBaseline(line) + layout.getLineDescent(line) + body.totalPaddingTop
    return body.scrollY + body.height - body.totalPaddingBottom >= textBottom - dp(2)
  }
  private fun stopFling() { fling.forceFinished(true); removeCallbacks(flingFrame) }

  /** Animated return to the article start (back-to-top bubble). */
  fun smoothScrollToTop() {
    stopFling()
    if (body.scrollY <= 0 || maxScrollY() <= 0) {
      body.scrollTo(0, 0)
      return
    }
    fling.fling(0, body.scrollY, 0, -12000, 0, 0, 0, maxScrollY())
    postOnAnimation(flingFrame)
  }
  private fun recycleVelocity() { velocity?.recycle(); velocity = null; flingEligible = false }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    val dx = event.x - swipeX
    val dy = event.y - swipeY
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        gestureActive = true
        stopFling()
        recycleVelocity()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        flingEligible = !pen && !hasSelection()
        swipeX = event.x; swipeY = event.y; swiping = false; swipeArmed = false
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
          ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures())
        val edge = maxOf(dp(24), insets?.left ?: 0, insets?.right ?: 0)
        val at = body.getOffsetForPosition(event.x - paddingLeft, event.y)
        val link = projection?.styles?.any { it.style in setOf(TextStyle.LINK, TextStyle.FOOTNOTE_REF) && at >= it.start && at < it.end } == true
        // A horizontal drag over code or a table is a content gesture, even
        // when this particular block fits the viewport without horizontal scroll.
        val horizontalContent = projection?.blocks?.any {
          it.kind in setOf(TextKind.CODE, TextKind.TABLE) && at >= it.start && at < it.end
        } == true
        swipeEligible = articleList != null && !pen && actionMode == null && body.selectionStart == body.selectionEnd &&
          event.x > edge && event.x < width - edge && !link && !horizontalContent
      }
      MotionEvent.ACTION_POINTER_DOWN -> { swipeEligible = false; flingEligible = false }
      MotionEvent.ACTION_MOVE -> {
        velocity?.addMovement(event)
        if (pen || hasSelection()) flingEligible = false
        if (pen || actionMode != null || body.selectionStart != body.selectionEnd) swipeEligible = false
        if (!swiping && kotlin.math.abs(dy) > dp(12)) swipeEligible = false
        if (!swiping && swipeEligible && kotlin.math.abs(dx) > dp(20) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.7f &&
          (ArticleAction.forArticle(articleList, dx > 0) != null)) {
          swiping = true
          flingEligible = false
          val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
          super.dispatchTouchEvent(cancel); cancel.recycle()
        }
        if (swiping) {
          val action = ArticleAction.forArticle(articleList, dx > 0)
          val allowed = action != null
          val offset = if (allowed && swipeEligible) dx.coerceIn(-width.toFloat(), width.toFloat()) else 0f
          body.translationX = offset
          val armed = ArticleAction.commits(action, offset, width.toFloat())
          if (armed && !swipeArmed) {
            if (action == ArticleAction.Delete) com.reader.app.ui.Haptics.destructiveArm(this)
            else com.reader.app.ui.Haptics.swipeArm(this)
          }
          swipeArmed = armed
          swipeLabel.layoutParams = LayoutParams(kotlin.math.abs(offset).toInt().coerceAtLeast(1), LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER_VERTICAL or (if (offset > 0) android.view.Gravity.LEFT else android.view.Gravity.RIGHT))
          swipeLabel.gravity = android.view.Gravity.CENTER
          val icon = context.getDrawable(when (action) { ArticleAction.Delete -> R.drawable.ic_swipe_delete; ArticleAction.Unarchive -> R.drawable.ic_swipe_unarchive; ArticleAction.Later -> R.drawable.ic_swipe_later; else -> R.drawable.ic_swipe_archive })?.mutate()
          val tint = if (action == ArticleAction.Delete) deleteTint else body.currentTextColor
          swipeLabel.setTextColor(tint)
          icon?.setTint(tint)
          swipeLabel.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
          swipeLabel.compoundDrawablePadding = dp(8)
          swipeLabel.text = if (offset == 0f) "" else if (armed) action?.releaseLabel else action?.label
          onSwipeProgress(offset / width.coerceAtLeast(1), armed)
          return true
        }
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
        val commit = event.actionMasked == MotionEvent.ACTION_UP && swipeEligible && swipeArmed
        val later = body.translationX > 0
        body.animate().translationX(0f).setDuration(160).start()
        swiping = false; swipeEligible = false; swipeArmed = false
        swipeLabel.text = ""
        swipeLabel.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        onSwipeProgress(0f, false)
        if (commit) {
          com.reader.app.ui.Haptics.commit(this)
          ArticleAction.forArticle(articleList, later)?.let { reportCursor(); onArticleSwipe(it) }
        }
        recycleVelocity()
        return true
      }
    }
    // Android's selectable TextView owns touch scrolling and native handles;
    // its movement method has no kinetic scroll. Add only post-release motion
    // to that same viewport, never a surrounding ScrollView or touch interceptor.
    val handled = super.dispatchTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP) {
      gestureActive = false
      onViewport(body.scrollY, body.height)
      onAtEnd(isAtEnd())
      velocity?.addMovement(event)
      if (flingEligible && !pen && !hasSelection() && kotlin.math.abs(dy) > touchConfig.scaledTouchSlop &&
        kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.7f) {
        velocity?.computeCurrentVelocity(1000, touchConfig.scaledMaximumFlingVelocity.toFloat())
        val speed = -(velocity?.yVelocity ?: 0f)
        if (kotlin.math.abs(speed) >= touchConfig.scaledMinimumFlingVelocity) {
          fling.fling(0, body.scrollY, 0, speed.toInt(), 0, 0, 0, maxScrollY())
          postOnAnimation(flingFrame)
        }
      }
      recycleVelocity()
    } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) { gestureActive = false; stopFling(); recycleVelocity(); onViewport(body.scrollY, body.height) }
    return handled
  }

  init {
    clipToPadding = false
    swipeLabel.textSize = 16f
    addView(swipeLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    body.setTextIsSelectable(true)
    body.isVerticalScrollBarEnabled = true
    body.linksClickable = false
    body.setLineSpacing(0f, 1.35f)
    // Bidirectional text: first-strong paragraph direction (Arabic/Hebrew
    // paragraphs align right automatically) with high-quality breaking for
    // CJK line wraps. Deliberately view-layer only: no projection/text
    // change, so highlight anchors and offsets are untouched.
    body.textDirection = android.view.View.TEXT_DIRECTION_FIRST_STRONG_LTR
    // High-quality breaking is API 29+; older runtimes keep the default
    // strategy (same rendering as before this change).
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      body.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
    }
    // Scaffold already places the reading dock outside this viewport. Keep
    // only a small text inset; dock-sized padding clips usable reading space.
    body.setPadding(0, dp(8), 0, dp(8))
    // Native selection handles scroll the TextView's own viewport. A tall
    // wrap-content TextView inside a ScrollView hides that viewport boundary
    // from Android's handle controller and prevents edge autoscrolling.
    addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    body.changed = { start, end -> selectionChanged(start, end) }
    body.scrolled = { y, h -> if (!body.isInLayout) { onViewport(y, h); if (!restoring && h > 0) onAtEnd(isAtEnd()) } }
    body.customSelectionActionModeCallback = object : ActionMode.Callback {
      override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        stopFling()
        actionMode = mode
        selectionCommitted = false
        selectionSession = UUID.randomUUID().toString()
        // Highlight rides first; Copy/Define/Share stay available. Clearing
        // the menu surrendered the platform dictionary for no gain.
        menu.add(0, HIGHLIGHT_ACTION, 0, "Highlight")
        return true
      }
      override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
      override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != HIGHLIGHT_ACTION) return false
        selectionCommitted = true
        emitSelection()
        return true
      }
      override fun onDestroyActionMode(mode: ActionMode) {
        flushSelection()
        pendingSelection?.let { removeCallbacks(it) }
        pendingSelection = null
        actionMode = null
        selectionCommitted = false
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
          val hits = markRanges.filter { it.id != "search-hit" && at >= it.start && at < it.end }.sortedByDescending { it.createdAt }
          val linkStyle = projection?.styles?.lastOrNull { it.style in setOf(TextStyle.LINK, TextStyle.FOOTNOTE_REF) && at >= it.start && at < it.end }
          val link = linkStyle?.let { if (it.style == TextStyle.FOOTNOTE_REF) "#^" + projection!!.text.substring(it.start, it.end).removeSurrounding("[", "]") else it.value }
          val table = projection?.tables?.indexOfFirst { rows ->
            rows.rows.firstOrNull()?.firstOrNull()?.start?.let { first ->
              at >= first && at < (rows.rows.lastOrNull()?.lastOrNull()?.end ?: first)
            } == true
          } ?: -1
          when {
            hits.isNotEmpty() -> onMark(hits.map { it.id })
            !pen && link != null -> onLink(link)
            !pen && table >= 0 -> onTable(table)
            !pen -> onTap()
          }
        }
      }
      false
    }
    body.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      if (right - left != oldRight - oldLeft) updateTableStyles()
      val oldHeight = oldBottom - oldTop
      val newHeight = bottom - top
      // Showing reader controls changes the viewport, never the passage.
      if (!restoring && oldHeight > 0 && newHeight != oldHeight && body.selectionStart == body.selectionEnd) {
        val anchor = pendingViewportAnchor
        val pinEnd = pendingViewportAtEnd
        pendingViewportAnchor = null; pendingViewportAtEnd = false
        val layout = body.layout
        val value = projection
        if (pinEnd) body.scrollTo(0, maxScrollY())
        else if (anchor != null && layout != null && value != null) {
          val offset = value.offset(anchor.blockId, anchor.charOffset).coerceIn(0, body.length())
          body.scrollTo(0, (layout.getLineTop(layout.getLineForOffset(offset)) + body.paddingTop - newHeight / 3).coerceIn(0, maxScrollY()))
        }
      }
    }
    body.setOnScrollChangeListener { _, _, _, _, _ -> if (!restoring && !body.isInLayout) reportCursor() }
  }

  private var pendingViewportAnchor: SemanticCursor? = null
  private var pendingViewportAtEnd = false
  private fun clearIdleTextCursor() {
    // TextView otherwise brings its initial collapsed cursor (offset zero)
    // into view after a resize, overriding our semantic reading position.
    if (actionMode == null && body.selectionStart == body.selectionEnd) {
      (body.text as? Spannable)?.let { android.text.Selection.removeSelection(it) }
      body.clearFocus()
    }
  }
  fun retainPassageOnLayout(pinEnd: Boolean = false) {
    pendingViewportAnchor = currentCursor(); pendingViewportAtEnd = pinEnd
    clearIdleTextCursor()
  }
  private var displayGeneration = 0L

  fun display(id: String, value: RenderedProjection, text: CharSequence, settings: ReaderSettings,
              foreground: Int, background: Int, margin: Int, initial: SemanticCursor) {
    stopFling()
    val changed = projection !== value || documentId != id
    if (changed) { flushSelection(); clearSelection() }
    val saved = if (!changed) currentCursor() else initial
    documentId = id
    projection = value
    body.textSize = settings.fontSizeSp
    body.setLineSpacing(0f, settings.lineHeightMultiplier())
    val font = when (settings.font) {      ArticleFont.NEWSREADER -> R.font.newsreader_var
      ArticleFont.CRIMSON_PRO -> R.font.crimsonpro_var
      ArticleFont.ASUL -> R.font.asul_regular
      ArticleFont.ATKINSON -> R.font.atkinson_regular
      ArticleFont.ABEEZEE -> R.font.abeezee_regular
    }
    val base = ResourcesCompat.getFont(context, when (settings.font) {
      ArticleFont.NEWSREADER -> R.font.newsreader_var
      ArticleFont.CRIMSON_PRO -> R.font.crimsonpro_var
      ArticleFont.ASUL -> R.font.asul_regular
      ArticleFont.ATKINSON -> R.font.atkinson_regular
      ArticleFont.ABEEZEE -> R.font.abeezee_regular
    })
    // Bold text is a real weight instance of the variable font, not a
    // synthetic stroke-widening pass.
    body.typeface = if (settings.bold) android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD) else base
    body.setTextColor(foreground)
    setBackgroundColor(background)
    body.setBackgroundColor(background)
    swipeLabel.setTextColor(foreground)
    setPadding(dp(margin), 0, dp(margin), 0)
    val generation = ++displayGeneration
    fun restore() {
      restoring = true
      body.doOnPreDraw {
        if (generation != displayGeneration) return@doOnPreDraw
        try {
          val layout = body.layout
          if (layout != null && saved != null) {
            val offset = value.offset(saved.blockId, saved.charOffset).coerceIn(0, body.length())
            val y = layout.getLineTop(layout.getLineForOffset(offset)) + body.paddingTop - body.height / 3
            val maxY = (layout.height + body.totalPaddingTop + body.totalPaddingBottom - body.height).coerceAtLeast(0)
            body.scrollTo(0, y.coerceIn(0, maxY))
          }
        } finally {
          restoring = false
          if (body.height > 0) { onAtEnd(isAtEnd()); onViewport(body.scrollY, body.height) }
        }
      }
    }
    if (changed) {
      restoring = true
      markRanges = emptyList()
      val immutableText = SpannableString(text)
      // Use the same native layout for drawing and handle hit testing. On the TCL,
      // precomputed mixed heading/body spans reported incorrect horizontal positions.
      body.setText(immutableText, TextView.BufferType.SPANNABLE)
      clearIdleTextCursor()
      restore()
    } else restore()
    body.post { updateTableStyles() }
  }

  fun jumpTo(cursor: SemanticCursor) {
    stopFling(); clearSelection()
    val value = projection ?: return
    val layout = body.layout ?: return
    val offset = value.offset(cursor.blockId, cursor.charOffset).coerceIn(0, body.length())
    body.scrollTo(0, (layout.getLineTop(layout.getLineForOffset(offset)) + body.paddingTop - body.height / 3).coerceIn(0, maxScrollY()))
    reportCursor()
    onAtEnd(isAtEnd())
  }

  private fun updateTableStyles() {
    val value = projection ?: return
    if (value.tables.isEmpty()) return
    val text = body.text as? Spannable ?: return
    styleNativeTables(text, value, body.paint, body.width - body.totalPaddingLeft - body.totalPaddingRight)
    body.requestLayout()
    body.invalidate()
  }

  fun setPenMode(enabled: Boolean) {
    if (pen == enabled) return
    stopFling()
    if (!enabled) flushSelection()
    pen = enabled
    actionMode?.invalidate()
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

  fun highlightSelection(): Boolean {
    if (!hasSelection() || body.selectionEnd <= body.selectionStart) return false
    selectionCommitted = true; emitSelection(); return true
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
    val line = layout.getLineForVertical((body.scrollY + body.height / 3 - body.paddingTop).coerceAtLeast(0))
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
    if (start >= 0 && end > start) stopFling()
    pendingSelection?.let { removeCallbacks(it) }
    if ((!pen && !selectionCommitted) || start < 0 || end < 0 || end == start) return
    val expectedStart = start; val expectedEnd = end
    pendingSelection = Runnable {
      if ((pen || selectionCommitted) && body.selectionStart == expectedStart && body.selectionEnd == expectedEnd) emitSelection()
    }.also { postDelayed(it, 100) }
  }

  fun flushSelection() {
    stopFling()
    pendingSelection?.let { removeCallbacks(it) }
    pendingSelection = null
    if (pen || selectionCommitted) emitSelection()
  }

  private var lastSelection: Pair<String, IntRange>? = null
  private fun emitSelection() {
    val value = projection ?: return
    if (body.selectionStart < 0 || body.selectionEnd < 0) return
    val range = value.range(body.selectionStart, body.selectionEnd) ?: return
    val session = selectionSession ?: UUID.randomUUID().toString().also { selectionSession = it }
    if (lastSelection == (session to range)) return
    lastSelection = session to range
    onSelection(session, ++selectionSequence, range)
  }

  override fun onDetachedFromWindow() {
    recycleVelocity()
    flushSelection()
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
      TextStyle.FOOTNOTE_REF -> span(RelativeSizeSpan(0.75f), it.start, it.end)
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
