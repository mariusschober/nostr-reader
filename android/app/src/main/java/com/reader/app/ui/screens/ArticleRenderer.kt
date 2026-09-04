package com.reader.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.reader.app.core.ArticleBlock
import com.reader.app.core.Inline
import com.reader.app.ui.theme.ReaderColors

/** Native Compose renderer for ArticleModel. Never shows raw markdown. */
@Composable
fun ArticleBlockView(
  block: ArticleBlock,
  font: FontFamily,
  bodySp: Float,
  colors: ReaderColors,
  highlighted: Boolean,
  onTextLayout: (TextLayoutResult) -> Unit,
  onPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
) {
  val bg = if (highlighted) colors.divider.copy(alpha = 0.5f) else Color.Transparent
  when (block) {
    is ArticleBlock.Paragraph -> ArticleText(
      annotated(block.inlines, colors), font, bodySp, colors, bg, onTextLayout, onPositioned,
    )
    is ArticleBlock.Heading -> {
      val size = when (block.level) {
        1 -> bodySp + 7
        2 -> bodySp + 4
        else -> bodySp + 2
      }
      Text(
        text = annotated(block.inlines, colors), fontFamily = font,
        fontWeight = FontWeight.Bold, fontSize = size.sp, color = colors.text,
        lineHeight = (size * 1.3).sp,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)
          .onGloballyPositioned(onPositioned),
      )
    }
    is ArticleBlock.BulletList -> Column(Modifier.fillMaxWidth().onGloballyPositioned(onPositioned)) {
      for (item in block.items) {
        Row(Modifier.fillMaxWidth()) {
          Text("•  ", fontFamily = font, fontSize = bodySp.sp, color = colors.text)
          Column(Modifier.weight(1f)) {
            for (sb in item.blocks) ArticleBlockView(sb, font, bodySp, colors, false, {}, {})
          }
        }
        Spacer(Modifier.height(4.dp))
      }
    }
    is ArticleBlock.OrderedList -> Column(Modifier.fillMaxWidth().onGloballyPositioned(onPositioned)) {
      var n = block.start
      for (item in block.items) {
        Row(Modifier.fillMaxWidth()) {
          Text("${n++}.  ", fontFamily = font, fontSize = bodySp.sp, color = colors.text)
          Column(Modifier.weight(1f)) {
            for (sb in item.blocks) ArticleBlockView(sb, font, bodySp, colors, false, {}, {})
          }
        }
        Spacer(Modifier.height(4.dp))
      }
    }
    is ArticleBlock.Quote -> Column(
      Modifier.fillMaxWidth().padding(start = 12.dp).onGloballyPositioned(onPositioned),
    ) {
      Divider(color = colors.divider, thickness = 2.dp, modifier = Modifier.width(28.dp))
      Spacer(Modifier.height(6.dp))
      for (sb in block.blocks) ArticleBlockView(sb, font, bodySp, colors, false, {}, {})
    }
    is ArticleBlock.CodeBlock -> {
      val scroll = rememberScrollState()
      Text(
        text = block.code.trimEnd(), fontFamily = FontFamily.Monospace,
        fontSize = (bodySp - 4).coerceAtLeast(12f).sp, color = colors.text,
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 8.dp),
      )
    }
    is ArticleBlock.Table -> {
      val scroll = rememberScrollState()
      Column(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 8.dp).onGloballyPositioned(onPositioned)) {
        TableRow(block.header, font, bodySp, colors, header = true)
        Divider(color = colors.divider)
        for (r in block.rows) {
          TableRow(r, font, bodySp, colors, header = false)
          Divider(color = colors.divider.copy(alpha = 0.5f))
        }
      }
    }
    is ArticleBlock.Image -> {
      val ctx = LocalContext.current
      Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
          model = block.url, contentDescription = block.alt.ifBlank { "article image" },
          modifier = Modifier.fillMaxWidth(),
          onError = { /* offline or failure: caption remains, text never depends on it */ },
        )
        if (block.alt.isNotBlank()) {
          Text(block.alt, fontFamily = font, fontSize = (bodySp - 5).coerceAtLeast(11f).sp, color = colors.secondary)
        }
      }
      Unit
    }
    is ArticleBlock.Divider -> Divider(color = colors.divider, modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth())
    is ArticleBlock.Footnotes -> Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
      for ((i, f) in block.items.withIndex()) {
        Text("[${i + 1}] $f", fontFamily = font, fontSize = (bodySp - 4).coerceAtLeast(12f).sp, color = colors.secondary)
      }
    }
  }
}

@Composable
private fun TableRow(cells: List<String>, font: FontFamily, bodySp: Float, colors: ReaderColors, header: Boolean) {
  Row(Modifier.fillMaxWidth()) {
    for (cell in cells) {
      Text(
        cell, fontFamily = font, fontSize = (bodySp - 3).coerceAtLeast(12f).sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        color = colors.text, modifier = Modifier.weight(1f).padding(6.dp),
      )
    }
  }
}

@Composable
private fun ArticleText(
  text: AnnotatedString, font: FontFamily, bodySp: Float, colors: ReaderColors,
  bg: Color, onTextLayout: (TextLayoutResult) -> Unit, onPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
) {
  val ctx = LocalContext.current
  ClickableText(
    text = text, style = androidx.compose.ui.text.TextStyle(
      fontFamily = font, fontSize = bodySp.sp, color = colors.text,
      lineHeight = (bodySp * 1.55).sp, background = bg,
    ),
    onTextLayout = onTextLayout,
    modifier = Modifier.fillMaxWidth().padding(bottom = (bodySp * 0.8).dp).onGloballyPositioned(onPositioned),
  ) { offset ->
    text.getStringAnnotations("url", offset, offset).firstOrNull()?.let { ann ->
      try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, ann.item.toUri()))
      } catch (e: Exception) {
      }
    }
  }
}

private fun annotated(inlines: List<Inline>, colors: ReaderColors): AnnotatedString = buildAnnotatedString {
  fun walk(items: List<Inline>) {
    for (i in items) when (i) {
      is Inline.Text -> append(i.text)
      is Inline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(i.inlines) }
      is Inline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(i.inlines) }
      is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(i.inlines) }
      is Inline.InlineCode -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(i.code) }
      is Inline.Link -> {
        pushStringAnnotation("url", i.url)
        withStyle(SpanStyle(color = colors.link)) { walk(i.inlines) }
        pop()
      }
      is Inline.FootnoteRef -> append("[${i.label}]")
    }
  }
  walk(inlines)
}
