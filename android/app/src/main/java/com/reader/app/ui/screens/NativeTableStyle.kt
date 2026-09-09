package com.reader.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import com.reader.app.core.RenderedProjection

private class TableScale(scale: Float) : RelativeSizeSpan(scale)
private class TableGap(private val width: Float, private val gutter: Float) : ReplacementSpan() {
  override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = kotlin.math.ceil(width).toInt()
  override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
    val rule = Paint(paint).apply { alpha = 80; strokeWidth = 1f }
    val column = x + width - gutter / 2f
    canvas.drawLine(column, top.toFloat(), column, bottom.toFloat(), rule)
  }
}
private class TableRules(private val width: Float) : LineBackgroundSpan {
  override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
                              text: CharSequence, start: Int, end: Int, lineNumber: Int) {
    val rule = Paint(p).apply { alpha = 65; strokeWidth = 1f }
    c.drawLine(left.toFloat(), bottom.toFloat(), left + width, bottom.toFloat(), rule)
  }
}

/** Layout spans align columns without inserting padding into stored/selectable quotations. */
internal fun styleNativeTables(text: Spannable, projection: RenderedProjection, paint: Paint, available: Int) {
  if (available <= 0) return
  text.getSpans(0, text.length, TableScale::class.java).forEach(text::removeSpan)
  text.getSpans(0, text.length, TableGap::class.java).forEach(text::removeSpan)
  text.getSpans(0, text.length, TableRules::class.java).forEach(text::removeSpan)
  val measure = Paint(paint).apply { typeface = Typeface.MONOSPACE }
  for (table in projection.tables) {
    val columns = table.rows.maxOfOrNull { it.size } ?: continue
    if (columns == 0) continue
    val widths = FloatArray(columns)
    for (row in table.rows) row.forEachIndexed { index, cell ->
      widths[index] = maxOf(widths[index], measure.measureText(projection.text, cell.start, cell.end))
    }
    val gutter = measure.textSize * 1.2f
    val natural = widths.sum() + gutter * (columns - 1)
    val scale = minOf(1f, (available - columns * 2f).coerceAtLeast(1f) / natural.coerceAtLeast(1f))
    val start = table.rows.first().first().start
    val end = table.rows.last().last().end
    if (end <= start) continue
    text.setSpan(TableScale(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(TableRules(natural * scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    for (row in table.rows) row.dropLast(1).forEachIndexed { index, cell ->
      val next = row[index + 1]
      val actual = measure.measureText(projection.text, cell.start, cell.end)
      text.setSpan(TableGap((widths[index] - actual + gutter) * scale, gutter * scale),
        cell.end, next.start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }
}
