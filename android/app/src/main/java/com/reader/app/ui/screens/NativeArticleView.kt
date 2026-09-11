package com.reader.app.ui.screens

import androidx.core.view.doOnPreDraw
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.Typeface
import android.text.Layout
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
  /** Body offsets the current session already owns; a drag only reshapes this. */
  private var sessionRange: IntRange? = null
  private var selectionSequence = 0L
  private var selectionCommitted = false
  /**
   * ActionMode may be recreated while Android is moving a selection handle. We
   * keep the committed bit out of the live callback while the old mode is
   * being torn down, then restore it only until a fresh article selection
   * begins. A new gesture clears this candidate before a new ordinary
   * selection can inherit an old saved session.
   */
  private var pendingActionModeContinuation = false
  private var pendingCommittedContinuation = false
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
  /** E-ink / reduced motion: swap the swipe-settle animation for an instant snap. */
  var reducedMotion = false
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
      if (reducedMotion || pen || hasSelection() || !isAttachedToWindow) { stopFling(); return }
      if (fling.computeScrollOffset()) {
        body.scrollTo(body.scrollX, fling.currY.coerceIn(0, maxScrollY()))
        postOnAnimation(this)
      }
    }
  }

  private fun hasSelection() = actionMode != null || body.selectionStart != body.selectionEnd
  private fun validSelection(start: Int, endExclusive: Int): Boolean =
    start >= 0 && endExclusive > start && endExclusive <= body.length()
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
    if (reducedMotion || body.scrollY <= 0 || maxScrollY() <= 0) {
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
        if (pendingActionModeContinuation && actionMode == null) {
          // A touch delivered to the article while no ActionMode is active is
          // the fresh-selection boundary. Handle popups continue through the
          // platform controller and do not dispatch this parent event, so
          // their ActionMode recreation retains the candidate below. A new
          // ordinary long-press, including one overlapping the old passage,
          // receives a fresh session and stays unsaved until Highlight.
          pendingActionModeContinuation = false
          pendingCommittedContinuation = false
          selectionSession = null; sessionRange = null; lastSelection = null
          selectionCommitted = false
        }
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
        body.animate().translationX(0f).setDuration(if (reducedMotion) 0L else 160L).start()
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
      if (!reducedMotion && flingEligible && !pen && !hasSelection() && kotlin.math.abs(dy) > touchConfig.scaledTouchSlop &&
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
    body.scrolled = { y, h ->
      if (!body.isInLayout) {
        // Edge rules are drawn by this parent over the scrolling TextView;
        // invalidate it whenever the child viewport moves so cached runs are
        // translated with the glyphs on hardware and software canvases.
        if (markRanges.any { it.edge != "none" }) invalidate()
        onViewport(y, h)
        if (!restoring && h > 0) onAtEnd(isAtEnd())
      }
    }
    body.setOnLongClickListener {
      // A second long-press is a new intentional selection even when Android
      // keeps the existing ActionMode alive. Flush the old pen/committed range
      // once, clear its owner, and return false so TextView retains its native
      // selection handles and default long-press behavior.
      if (actionMode != null || body.selectionStart != body.selectionEnd || selectionSession != null) {
        flushSelection()
        resetSelectionOwner()
      }
      false
    }
    body.customSelectionActionModeCallback = object : ActionMode.Callback {
      override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        stopFling()
        val start = body.selectionStart
        val end = body.selectionEnd
        val previousSession = selectionSession
        val previousRange = sessionRange
        val previousCommitted = pendingCommittedContinuation || selectionCommitted
        val continuingHandle = pendingActionModeContinuation &&
          previousSession != null && previousRange != null &&
          (!validSelection(start, end) || selectionRangesOverlap(previousRange, start, end))
        // Pen selection can notify onSelectionChanged before Android creates
        // its ActionMode. Preserve that pre-mode owner instead of minting a
        // second id when the mode finally appears.
        val preModePen = pen && !pendingActionModeContinuation &&
          previousSession != null && previousRange != null && validSelection(start, end)
        pendingActionModeContinuation = false
        pendingCommittedContinuation = false
        actionMode = mode
        if (validSelection(start, end)) {
          // A first ordinary selection starts ownership before the menu is
          // shown. A recreated mode keeps that ownership only when the touch
          // began as a continuation; a new overlapping long-press is a
          // separate gesture and must not autosave an old committed mark.
          if (!continuingHandle && !preModePen) {
            selectionSession = UUID.randomUUID().toString()
            lastSelection = null
          } else if (continuingHandle || preModePen) {
            selectionSession = previousSession
          }
          sessionRange = start until end
          selectionCommitted = continuingHandle && previousCommitted
        } else {
          if (selectionSession == null) selectionSession = UUID.randomUUID().toString()
          selectionCommitted = continuingHandle && previousCommitted
        }
        // A handle can deliver its final range while ActionMode is between
        // instances. Re-arm the same coalesced callback after restoration so
        // that pen and already-committed ordinary selections do not lose the
        // last adjustment merely because onSelectionChanged arrived early.
        if ((continuingHandle || preModePen) && validSelection(start, end) && (pen || selectionCommitted)) {
          selectionChanged(start, end)
        }
        if (pen) {
          // Continuous highlighting: keep the action mode (so handles,
          // magnifier and edge autoscroll keep working) but suppress the
          // floating text-action menu. The dock itself owns the actions.
          menu.clear()
        } else {
          // Ordinary selection: Highlight rides first; Copy/Define/Share stay
          // available. Clearing the menu surrendered the platform dictionary
          // for no gain.
          menu.add(0, HIGHLIGHT_ACTION, 0, "Highlight")
        }
        return true
      }
      override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (pen) { menu.clear(); return true }
        return false
      }
      override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != HIGHLIGHT_ACTION) return false
        val start = body.selectionStart
        val end = body.selectionEnd
        if (!validSelection(start, end)) return false
        // Establish ownership before the first repository emission. Without
        // this, the first ordinary Highlight click had a null range and the
        // next handle callback minted a second session id.
        if (selectionSession == null) selectionSession = UUID.randomUUID().toString()
        sessionRange = start until end
        selectionCommitted = true
        emitSelection()
        return true
      }
      override fun onDestroyActionMode(mode: ActionMode) {
        flushSelection()
        pendingSelection?.let { removeCallbacks(it) }
        pendingSelection = null
        // The platform may briefly collapse the selection while it tears down
        // the mode for a handle drag. Keep the owner/range candidate whenever
        // this mode had one; a fresh article ACTION_DOWN clears it before a
        // new ordinary or pen selection can inherit it.
        val keepForContinuation = selectionSession != null && sessionRange != null
        val committedForContinuation = selectionCommitted
        pendingActionModeContinuation = keepForContinuation
        pendingCommittedContinuation = committedForContinuation
        actionMode = null
        selectionCommitted = false
        // Keep the id/range as a candidate, but keep the committed bit cleared
        // until onCreate proves that the next touch is an existing-handle
        // recreation. This prevents Copy-only selections from saving later.
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
      if (right - left != oldRight - oldLeft) {
        monoRunLayout = null
        monoRunMarks = null
        updateTableStyles()
      }
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
    monoRunLayout = null
    monoRunMarks = null
    val saved = if (!changed) currentCursor() else initial
    documentId = id
    projection = value
    body.textSize = settings.fontSizeSp
    body.setLineSpacing(0f, settings.lineHeightMultiplier())
    val font = when (settings.font) {
      ArticleFont.NEWSREADER -> R.font.newsreader_var
      ArticleFont.CRIMSON_PRO -> R.font.crimsonpro_var
      ArticleFont.ASUL -> R.font.asul_regular
      ArticleFont.ATKINSON -> R.font.atkinson_regular
      ArticleFont.ABEEZEE -> R.font.abeezee_regular
      ArticleFont.INTER -> R.font.inter_regular
    }
    // Inter ships a real bold face; the other reading fonts use a synthetic
    // weight of the regular file.
    val boldFont = when (settings.font) {
      ArticleFont.INTER -> R.font.inter_bold
      else -> null
    }
    val base = ResourcesCompat.getFont(context, if (settings.bold && boldFont != null) boldFont else font)
    body.typeface = if (settings.bold && boldFont == null) android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD) else base
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
    if (!enabled) {
      // Leaving continuous highlighting flushes the final range under its
      // session id, then rotates the session so the next pen entry starts a
      // genuinely new highlight instead of reshaping the previous one.
      flushSelection()
      pendingSelection?.let { removeCallbacks(it) }
      pendingSelection = null
      pendingActionModeContinuation = false
      pendingCommittedContinuation = false
      selectionSession = null; sessionRange = null; lastSelection = null
      selectionCommitted = false
    } else {
      // Entering pen mode must not save a stale normal-mode selection as a
      // highlight. Clear visuals/session without emitting; the next long-press
      // allocates a fresh session.
      pendingSelection?.let { removeCallbacks(it) }
      pendingSelection = null
      pendingActionModeContinuation = false
      pendingCommittedContinuation = false
      selectionSession = null; sessionRange = null; lastSelection = null
      selectionCommitted = false
      actionMode?.finish()
      actionMode = null
      (body.text as? Spannable)?.let { android.text.Selection.removeSelection(it) }
    }
    pen = enabled
    actionMode?.invalidate()
  }

  fun setMarks(marks: List<NativeMark>) {
    if (marks == markRanges) return
    markRanges = marks
    monoRunLayout = null
    monoRunMarks = null
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
    val start = body.selectionStart
    val end = body.selectionEnd
    if (!validSelection(start, end)) return false
    if (selectionSession == null) selectionSession = UUID.randomUUID().toString()
    sessionRange = start until end
    selectionCommitted = true; emitSelection(); return true
  }

  private fun resetSelectionOwner() {
    pendingSelection?.let { removeCallbacks(it) }
    pendingSelection = null
    pendingActionModeContinuation = false
    pendingCommittedContinuation = false
    selectionSession = null
    sessionRange = null
    lastSelection = null
    selectionCommitted = false
  }

  fun clearSelection(): Boolean {
    val active = actionMode != null || body.selectionStart != body.selectionEnd
    pendingSelection?.let { removeCallbacks(it) }
    actionMode?.finish()
    (body.text as? Spannable)?.let { android.text.Selection.removeSelection(it) }
    resetSelectionOwner()
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
    // A collapsed selection (including the transient collapse while a handle is
    // grabbed) does not end the session. The next ActionMode creation decides
    // whether a valid range is a handle continuation or a new gesture.
    if (!validSelection(start, end)) return
    // Before ActionMode is created, ordinary Android selection is intentionally
    // unsaved. Do not let a new long-press mutate a committed session while the
    // old mode is being dismissed.
    if (!pen && actionMode == null) return
    if (selectionSession == null) selectionSession = UUID.randomUUID().toString()
    // While this mode owns the selection, the range is the latest actual
    // half-open selection. Replacing it (rather than unioning endpoints) lets a
    // handle shrink a quote as well as extend it.
    sessionRange = start until end
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
  private var monoRunLayout: Layout? = null
  private var monoRunMarks: List<NativeMark>? = null
  private var monoRuns: List<NativeMonoVisualRun> = emptyList()
  private var monoEffectsDensity = 0f
  private var monoDashEffect: android.graphics.DashPathEffect? = null
  private var monoDotEffect: android.graphics.DashPathEffect? = null
  private val monoEdgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    style = android.graphics.Paint.Style.STROKE
  }
  override fun dispatchDraw(canvas: android.graphics.Canvas) {
    super.dispatchDraw(canvas)
    drawMonoEdges(canvas)
  }
  /**
   * Monochrome saved-mark edge styles drawn from the native Layout's visual
   * selection path. Each line is clipped to its own selected runs, so a mark
   * that wraps or contains bidi text never bridges an unselected paragraph or
   * uses the next line's caret as its endpoint. This is drawing-only: text,
   * projection offsets and quote anchors remain untouched.
   */
  private fun drawMonoEdges(canvas: android.graphics.Canvas) {
    val layout = body.layout ?: return
    if (body.text.isEmpty()) return
    if (monoRunLayout !== layout || monoRunMarks != markRanges) {
      monoRunLayout = layout
      monoRunMarks = markRanges
      monoRuns = markRanges.asSequence()
        .filter { it.edge != "none" && it.start >= 0 && it.end > it.start && it.end <= body.length() }
        .flatMap { nativeMonoVisualRuns(body.text, it.start, it.end, layout, it.edge).asSequence() }
        .toList()
    }
    if (monoRuns.isEmpty()) return
    val density = resources.displayMetrics.density
    val stroke = 1.5f * density
    if (monoEffectsDensity != density) {
      monoEffectsDensity = density
      monoDashEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
      monoDotEffect = android.graphics.DashPathEffect(floatArrayOf(1.5f * density, 4f * density), 0f)
    }
    val contentLeft = body.left + body.paddingLeft
    canvas.save()
    canvas.clipRect(body.left.toFloat(), body.top.toFloat(), (body.left + body.width).toFloat(), (body.top + body.height).toFloat())
    for (run in monoRuns) {
      val runTop = body.top + body.paddingTop + layout.getLineTop(run.line) - body.scrollY
      val runBottom = body.top + body.paddingTop + layout.getLineBottom(run.line) - body.scrollY
      if (runBottom < body.top || runTop > body.bottom) continue
      val left = (run.left + contentLeft - body.scrollX).coerceAtLeast(body.left.toFloat())
      val right = (run.right + contentLeft - body.scrollX).coerceAtMost((body.left + body.width).toFloat())
      if (right - left < 2f) continue
      val ys = nativeMonoUnderlineOffsets(
        layout = layout,
        line = run.line,
        edge = run.edge,
        stroke = stroke,
        density = density,
        lastInset = body.paddingBottom.toFloat(),
      ) ?: continue
      val yOffset = body.top + body.paddingTop - body.scrollY
      monoEdgePaint.strokeWidth = stroke
      when (run.edge) {
        "solid" -> {
          monoEdgePaint.pathEffect = null
          monoEdgePaint.strokeCap = android.graphics.Paint.Cap.SQUARE
          canvas.drawLine(left, ys.first + yOffset, right, ys.first + yOffset, monoEdgePaint)
        }
        "double" -> {
          monoEdgePaint.pathEffect = null
          monoEdgePaint.strokeCap = android.graphics.Paint.Cap.SQUARE
          canvas.drawLine(left, ys.first + yOffset, right, ys.first + yOffset, monoEdgePaint)
          ys.second?.let { canvas.drawLine(left, it + yOffset, right, it + yOffset, monoEdgePaint) }
        }
        "dashed" -> {
          monoEdgePaint.pathEffect = monoDashEffect
          monoEdgePaint.strokeCap = android.graphics.Paint.Cap.BUTT
          canvas.drawLine(left, ys.first + yOffset, right, ys.first + yOffset, monoEdgePaint)
          monoEdgePaint.pathEffect = null
        }
        "dotted" -> {
          monoEdgePaint.pathEffect = monoDotEffect
          monoEdgePaint.strokeCap = android.graphics.Paint.Cap.ROUND
          canvas.drawLine(left, ys.first + yOffset, right, ys.first + yOffset, monoEdgePaint)
          monoEdgePaint.pathEffect = null
        }
      }
    }
    canvas.restore()
  }

  companion object {
    private const val HIGHLIGHT_ACTION = 0x52454144
  }
}

/**
 * Return safe baseline-relative Y positions for a monochrome mark edge.
 *
 * Layout.getLineBottom(line, false) is the metric edge without paragraph
 * line-spacing on API 34 and newer. It includes the actual line's metric
 * spans, so descenders and mixed heading/body fonts remain below the rule.
 * Older releases use the equivalent baseline plus Layout descent. The next
 * line's top (or the final text inset) bounds the reserved interline gap.
 * This helper is independent of the view so the geometry contract can be
 * exercised against a real Layout in a compact instrumented test.
 */
internal fun nativeMonoUnderlineOffsets(
  layout: Layout,
  line: Int,
  edge: String,
  stroke: Float,
  density: Float,
  lastInset: Float,
): Pair<Float, Float?>? {
  if (line !in 0 until layout.lineCount || stroke <= 0f || density <= 0f) return null
  val baseline = layout.getLineBaseline(line).toFloat()
  val glyphBottom = if (android.os.Build.VERSION.SDK_INT >= 34) {
    // Do not max with baseline + getLineDescent here: that value includes the
    // layout's added line spacing on affected releases and collapses the gap.
    layout.getLineBottom(line, false).toFloat()
  } else {
    baseline + layout.getLineDescent(line).toFloat()
  }
  val lower = if (line < layout.lineCount - 1) layout.getLineTop(line + 1).toFloat()
    else (layout.height + lastInset).toFloat()
  val gap = lower - glyphBottom
  if (gap < stroke * 0.9f) return null
  val firstOffset = minOf(gap * 0.38f, maxOf(stroke * 0.7f, density))
  val first = glyphBottom + firstOffset
  if (first + stroke * 0.5f > lower) return null
  if (edge != "double") return first to null
  val available = lower - first - stroke * 0.65f
  if (available < stroke * 0.8f) return first to null
  val second = minOf(first + 3f * density, lower - stroke * 0.5f)
  return if (second - first >= stroke * 0.7f) first to second else first to null
}

/** Half-open overlap used by selection ownership and its boundary regression. */
internal fun selectionRangesOverlap(owned: IntRange, candidateStart: Int, candidateEndExclusive: Int): Boolean =
  candidateStart < owned.last + 1 && candidateEndExclusive > owned.first

internal data class NativeMonoVisualRun(val line: Int, val left: Float, val right: Float, val edge: String)

/**
 * Extract disjoint horizontal pieces of one saved mark on each visual line.
 * The selection path is generated by the same Layout used for glyphs and then
 * clipped to each line; no character-width approximation or bounding-box
 * bridge can invent a rule through whitespace or a bidi gap.
 */
internal fun nativeMonoVisualRuns(
  text: CharSequence,
  startOffset: Int,
  endOffsetExclusive: Int,
  layout: Layout,
  edge: String,
): List<NativeMonoVisualRun> {
  val start = startOffset.coerceIn(0, text.length)
  val end = endOffsetExclusive.coerceIn(0, text.length)
  if (end <= start || layout.lineCount <= 0) return emptyList()
  val firstLine = layout.getLineForOffset(start)
  val lastLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
  val result = mutableListOf<NativeMonoVisualRun>()
  for (line in firstLine..lastLine) {
    val lineStart = layout.getLineStart(line)
    // getLineVisibleEnd removes trailing whitespace and the explicit line
    // break. It prevents a selected newline from becoming a rule bridge.
    val visibleEnd = layout.getLineVisibleEnd(line).coerceAtMost(end)
    val segmentStart = maxOf(start, lineStart)
    val segmentEnd = visibleEnd
    if (segmentEnd <= segmentStart) continue

    val path = Path()
    layout.getSelectionPath(segmentStart, segmentEnd, path)
    val lineLeft = kotlin.math.floor(layout.getLineLeft(line).toDouble()).toInt() - 4
    val lineRight = kotlin.math.ceil(layout.getLineRight(line).toDouble()).toInt() + 4
    val lineTop = layout.getLineTop(line)
    val lineBottom = layout.getLineBottom(line).coerceAtLeast(lineTop + 1)
    if (lineRight <= lineLeft || lineBottom <= lineTop) continue
    val clipped = Region(lineLeft, lineTop, lineRight, lineBottom)
    val region = Region()
    if (!region.setPath(path, clipped) || region.isEmpty) continue
    val rects = mutableListOf<Rect>()
    val iterator = RegionIterator(region)
    val rect = Rect()
    while (iterator.next(rect)) {
      if (rect.right > rect.left && rect.bottom > lineTop && rect.top < lineBottom) rects += Rect(rect)
    }
    if (rects.isEmpty()) continue
    rects.sortBy { it.left }
    var left = rects.first().left
    var right = rects.first().right
    for (piece in rects.drop(1)) {
      // RegionIterator can split one rectangle into horizontal strips. Join
      // only touching strips; a real bidi gap remains an independent run.
      if (piece.left <= right + 1) right = maxOf(right, piece.right)
      else {
        result += NativeMonoVisualRun(line, left.toFloat(), right.toFloat(), edge)
        left = piece.left; right = piece.right
      }
    }
    result += NativeMonoVisualRun(line, left.toFloat(), right.toFloat(), edge)
  }
  return result
}

data class NativeMark(val id: String, val start: Int, val end: Int, val createdAt: Long, val background: Int, val foreground: Int, val edge: String = "none")
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
