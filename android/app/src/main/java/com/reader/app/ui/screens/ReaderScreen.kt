package com.reader.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ReaderCore
import com.reader.app.cursor.SemanticCursor
import com.reader.app.data.DocumentEntity
import com.reader.app.prefs.*
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.readerColors
import com.reader.app.ui.theme.fontFor
import com.reader.app.ui.theme.marginDp
import kotlinx.coroutines.launch

/** Quiet editorial reader. Scroll, TTS, and RSVP share one semantic cursor. */
@Composable
fun ReaderScreen(
  doc: DocumentEntity,
  blocks: List<ArticleBlock>,
  settings: ReaderSettings,
  onSettingsChange: (ReaderSettings) -> Unit,
  onBack: () -> Unit,
  onCursor: (SemanticCursor, Float) -> Unit,
  initialBlockId: String?,
  initialOffset: Int,
  onEnterTts: (SemanticCursor) -> Unit,
  onEnterRsvp: (SemanticCursor) -> Unit,
  onReadLater: () -> Unit,
  onArchive: () -> Unit,
) {
  val c = readerColors(settings.background)
  val font = fontFor(settings.font)
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  var controlsVisible by remember { mutableStateOf(true) }
  var showAppearance by remember { mutableStateOf(false) }
  var cursor by remember { mutableStateOf(SemanticCursor(doc.documentId, initialBlockId ?: "b0", initialOffset)) }
  val layouts = remember { mutableMapOf<String, TextLayoutResult>() }
  val coords = remember { mutableMapOf<String, LayoutCoordinates>() }
  var atEnd by remember { mutableStateOf(false) }

  // Restore position once after composition.
  LaunchedEffect(doc.documentId) {
    val idx = blocks.indexOfFirst { it.id == cursor.blockId }.takeIf { it >= 0 } ?: 0
    if (idx > 0) listState.scrollToItem(idx)
  }

  // Throttled persist of the semantic cursor at the upper-third anchor.
  fun reportCursor() {
    val info = listState.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return
    val anchorY = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
    val item = info.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - anchorY) } ?: return
    val block = blocks.getOrNull(item.index) ?: return
    val layout = layouts[block.id]
    val coord = coords[block.id]
    var offset = 0
    if (layout != null && coord != null) {
      try {
        val localY = (anchorY - item.offset - coord.positionInParent().y).coerceAtLeast(0f)
        offset = layout.getOffsetForPosition(androidx.compose.ui.geometry.Offset(4f, localY))
      } catch (e: Exception) {
        offset = 0
      }
    }
    cursor = SemanticCursor(doc.documentId, block.id, offset)
    val readable = blocks.sumOf { readableLen(it) }
    val seen = blocks.take(item.index).sumOf { readableLen(it) } + offset
    val frac = if (readable > 0) (seen.toFloat() / readable).coerceIn(0f, 1f) else 0f
    onCursor(cursor, frac)
  }

  Scaffold(
    containerColor = c.background,
    topBar = {
      AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
        Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = c.text)
          }
          Spacer(Modifier.weight(1f))
          Text(
            "${(doc.progressFraction * 100).toInt()}%",
            fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.secondary,
          )
          Spacer(Modifier.weight(1f))
          IconButton(onClick = { showAppearance = true }) {
            Icon(Icons.Default.Settings, contentDescription = "Appearance", tint = c.text)
          }
          IconButton(onClick = { onEnterTts(cursor) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Listen", tint = c.text)
          }
          TextButton(onClick = { onEnterRsvp(cursor) }) {
            Text("Speed", fontFamily = ReaderFonts.Ui, color = c.text)
          }
        }
      }
    },
  ) { pad ->
    Box(Modifier.padding(pad).fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        val margin = marginDp(settings.margin, wide)
        val maxCol = if (wide) 680.dp else maxWidth
        LazyColumn(
          state = listState,
          // Tap toggles chrome with zero visual feedback: no ripple/gray wash on the page.
          modifier = Modifier.fillMaxSize().clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
          ) { controlsVisible = !controlsVisible },
          contentPadding = PaddingValues(start = margin.dp, end = margin.dp, top = 8.dp, bottom = 96.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          item {
            Column(Modifier.widthIn(max = maxCol).fillMaxWidth()) {
              Text(doc.title, fontFamily = font, fontSize = (settings.fontSizeSp + 7).sp, color = c.text)
              Spacer(Modifier.height(4.dp))
              val mins = ReaderCore.readingMinutes(doc.wordCount)
              Text("${doc.sourceType} · ${mins} min", fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.secondary)
              Spacer(Modifier.height(16.dp))
            }
          }
          itemsIndexed(blocks, key = { _, b -> b.id }) { _, block ->
            Box(Modifier.widthIn(max = maxCol).fillMaxWidth()) {
              ArticleBlockView(
                block = block, font = font, bodySp = settings.fontSizeSp, colors = c,
                highlighted = false,
                onTextLayout = { layouts[block.id] = it },
                onPositioned = { coords[block.id] = it },
              )
            }
          }
          item {
            Column(Modifier.widthIn(max = maxCol).fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              Divider(color = c.divider, modifier = Modifier.width(64.dp))
              Spacer(Modifier.height(12.dp))
              Row {
                TextButton(onClick = onReadLater) { Text("Read Later", fontFamily = ReaderFonts.Ui, color = c.text) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onArchive) { Text("Archive", fontFamily = ReaderFonts.Ui, color = c.text) }
              }
            }
          }
        }
      }
      // Persist cursor when scrolling settles.
      LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) reportCursor()
      }
    }
  }
  BackHandler {
    onBack()
  }
  if (showAppearance) {
    AppearanceSheet(settings, onSettingsChange) { showAppearance = false }
  }
  // Track end-of-list for finish affordance.
  LaunchedEffect(listState.layoutInfo.visibleItemsInfo) {
    val info = listState.layoutInfo
    atEnd = info.visibleItemsInfo.any { it.index >= blocks.size }
  }
}

private fun readableLen(b: ArticleBlock): Int = when (b) {
  is ArticleBlock.Paragraph -> b.inlines.sumOf { inlineLen(it) }
  is ArticleBlock.Heading -> b.inlines.sumOf { inlineLen(it) }
  else -> 40
}

private fun inlineLen(i: com.reader.app.core.Inline): Int = when (i) {
  is com.reader.app.core.Inline.Text -> i.text.length
  is com.reader.app.core.Inline.Strong -> i.inlines.sumOf { inlineLen(it) }
  is com.reader.app.core.Inline.Emphasis -> i.inlines.sumOf { inlineLen(it) }
  is com.reader.app.core.Inline.Strike -> i.inlines.sumOf { inlineLen(it) }
  is com.reader.app.core.Inline.InlineCode -> 0
  is com.reader.app.core.Inline.Link -> i.inlines.sumOf { inlineLen(it) }
  is com.reader.app.core.Inline.FootnoteRef -> 0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSheet(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onClose: () -> Unit) {
  val c = readerColors(settings.background)
  AlertDialog(
    onDismissRequest = onClose,
    containerColor = c.background,
    title = { Text("Appearance", fontFamily = ReaderFonts.Ui, color = c.text) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Font", fontFamily = ReaderFonts.Ui, color = c.secondary)
        ArticleFont.entries.forEach { f ->
          val label = when (f) {
            ArticleFont.NEWSREADER -> "Newsreader"
            ArticleFont.CRIMSON_PRO -> "Crimson Pro"
            ArticleFont.ASUL -> "Asul"
            ArticleFont.ATKINSON -> "Atkinson Hyperlegible"
            ArticleFont.ABEEZEE -> "ABeeZee"
          }
          Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = settings.font == f, role = Role.RadioButton, onClick = { onChange(settings.copy(font = f)) }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
              selected = settings.font == f, onClick = null,
              colors = RadioButtonDefaults.colors(selectedColor = c.text, unselectedColor = c.secondary),
            )
            Text(label, fontFamily = fontFor(f), color = c.text, modifier = Modifier.weight(1f))
          }
        }
        Spacer(Modifier.height(8.dp))
        Text("Size: ${settings.fontSizeSp.toInt()}", fontFamily = ReaderFonts.Ui, color = c.secondary)
        Slider(
          modifier = Modifier.semantics { contentDescription = "Article text size"; stateDescription = "${settings.fontSizeSp.toInt()}" },
          value = settings.fontSizeSp, onValueChange = { onChange(settings.copy(fontSizeSp = it)) },
          valueRange = 14f..32f,
          colors = SliderDefaults.colors(thumbColor = c.text, activeTrackColor = c.text, inactiveTrackColor = c.divider),
        )
        Text("Margins", fontFamily = ReaderFonts.Ui, color = c.secondary)
        // Wrapping row: every choice stays reachable on narrow screens and
        // large text. No horizontal clipping; no new settings.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ArticleMargin.entries.forEach { m ->
            FilterChip(
              selected = settings.margin == m, onClick = { onChange(settings.copy(margin = m)) },
              label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, fontFamily = ReaderFonts.Ui) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = c.text, selectedLabelColor = c.background,
                containerColor = c.background, labelColor = c.text,
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = c.divider, selectedBorderColor = c.text,
                enabled = true, selected = settings.margin == m,
              ),
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Text("Background", fontFamily = ReaderFonts.Ui, color = c.secondary)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ArticleBackground.entries.forEach { b ->
            FilterChip(
              selected = settings.background == b, onClick = { onChange(settings.copy(background = b)) },
              label = { Text(when (b) { ArticleBackground.FOLLOW_APP -> "Follow system"; ArticleBackground.PAPER -> "Paper"; ArticleBackground.SOFT -> "Soft"; ArticleBackground.INK -> "Ink"; ArticleBackground.BLACK -> "Black" }, fontFamily = ReaderFonts.Ui, maxLines = 2) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = c.text, selectedLabelColor = c.background,
                containerColor = c.background, labelColor = c.text,
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = c.divider, selectedBorderColor = c.text,
                enabled = true, selected = settings.background == b,
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = c.text)) {
        Text("Done", fontFamily = ReaderFonts.Ui)
      }
    },
  )
}

/**
 * Display-only duplicate-title check. Compares the metadata title against the
 * first rendered heading's body text after trimming, collapsing internal
 * whitespace and ignoring case. Extraction, canonical Markdown, hashes and
 * highlight anchors are untouched; callers simply hide the redundant metadata
 * title line when this returns true.
 */
internal fun isDuplicateTitle(title: String, projection: com.reader.app.core.RenderedProjection): Boolean {
  if (title.isBlank()) return false
  val first = projection.blocks.firstOrNull() ?: return false
  if (first.kind != com.reader.app.core.TextKind.HEADING) return false
  fun normalize(value: String): String = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ").lowercase(java.util.Locale.ROOT)
  val heading = projection.text.substring(first.bodyStart.coerceIn(0, projection.text.length), first.bodyEnd.coerceIn(0, projection.text.length))
  if (heading.isBlank()) return false
  return normalize(title) == normalize(heading)
}
