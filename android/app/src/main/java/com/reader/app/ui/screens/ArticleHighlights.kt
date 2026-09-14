package com.reader.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.ReaderApp
import com.reader.app.core.RenderedProjection
import com.reader.app.data.*
import com.reader.app.ui.NoticeCoordinator
import com.reader.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Order an article's saved quotes by source passage: article-index section,
 * then resolved offset inside that section, then creation time and id for ties.
 * Lexical block-id order is deliberately not used. When a section cannot be
 * loaded the highlight keeps its section and falls back to its canonical
 * position; wholly unresolvable quotes still sort deterministically.
 */
internal suspend fun orderedForPassage(
  articles: ArticleRepository,
  documentId: String,
  highlights: List<HighlightEntity>,
): List<HighlightEntity> {
  if (highlights.isEmpty()) return emptyList()
  val index = runCatching { articles.index(documentId) }.getOrNull()
  val projections = HashMap<Int, RenderedProjection?>()
  val keys = HashMap<String, Pair<Int, Int>>()
  for (highlight in highlights) {
    val section = index?.sectionFor(highlight.startBlockId) ?: 0
    val projection = if (index != null) {
      projections.getOrPut(section) { runCatching { articles.section(documentId, section).projection }.getOrNull() }
    } else null
    val offset = projection?.let { HighlightAnchors.resolve(highlight, it)?.first } ?: highlight.canonicalStart
    keys[highlight.id] = section to offset
  }
  return highlights.sortedWith(
    compareBy(
      { keys[it.id]?.first ?: 0 },
      { keys[it.id]?.second ?: it.canonicalStart },
      { it.createdAt },
      { it.id },
    ),
  )
}

/**
 * Article-scoped highlight list with a passage-ordered count, complete quote
 * cards and a prominent Review CTA. Unchanged global Review stays separate.
 */
@Composable
fun ArticleHighlightsScreen(
  documentId: String,
  onBack: () -> Unit,
  onReview: (startHighlightId: String?) -> Unit,
  onOpenSource: (documentId: String, quoteId: String) -> Unit,
  notices: NoticeCoordinator,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val app = context.applicationContext as ReaderApp
  val db = remember { ReaderDb.get(context) }
  val repo = remember { HighlightRepository(db) }
  val scope = rememberCoroutineScope()
  val doc by remember(documentId) { db.documents().observeMetadata(documentId) }.collectAsState(initial = null)
  val raw by remember(documentId) { db.highlights().observeForDocument(documentId) }.collectAsState(initial = emptyList())
  var ordered by remember(documentId) { mutableStateOf<List<HighlightEntity>>(emptyList()) }
  LaunchedEffect(raw, documentId) {
    ordered = runCatching { orderedForPassage(app.articles, documentId, raw) }.getOrDefault(raw)
  }
  val c = appColors()
  val dark = LocalReaderDark.current
  val mono = LocalDisplayPolicy.current.monochrome
  val title = doc?.title?.takeIf { it.isNotBlank() } ?: "Article highlights"
  Scaffold(
    containerColor = c.background,
    contentColor = c.text,
    topBar = {
      Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to article") }
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad).fillMaxSize()) {
      if (ordered.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("No highlights in this article yet", style = MaterialTheme.typography.titleMedium, color = c.text)
          TextButton(onClick = onBack) { Text("Back to article") }
        }
      } else {
        Text(
          "Article highlights · ${ordered.size}",
          style = MaterialTheme.typography.labelMedium,
          color = c.secondary,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(ordered, key = { it.id }) { quote ->
            val pres = highlightPresentation(quote.color, mono, dark)
            Surface(
              color = c.surface,
              contentColor = c.text,
              shape = RoundedCornerShape(10.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, c.divider),
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onReview(quote.id) },
            ) {
              Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    Modifier.size(12.dp).background(if (mono) pres.fill else HighlightColor.parse(quote.color).background(dark), RoundedCornerShape(3.dp))
                      .semantics { contentDescription = "Highlight color ${pres.label}" },
                  )
                  Spacer(Modifier.width(8.dp))
                  Text("${pres.shortId} · ${pres.label}", style = MaterialTheme.typography.labelSmall, color = c.secondary)
                  Spacer(Modifier.weight(1f))
                  if (quote.important) Icon(Icons.Default.Star, contentDescription = "Important highlight", tint = c.text, modifier = Modifier.size(16.dp))
                }
                Text(
                  quote.quote,
                  style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = fontFor(com.reader.app.prefs.ArticleFont.NEWSREADER),
                    fontSize = quoteSizeSp(19f, quote.quote.length).sp,
                  ),
                  color = c.text,
                  maxLines = 8,
                  overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                  TextButton(onClick = { onOpenSource(quote.documentId, quote.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open passage") }
                  TextButton(
                    onClick = { scope.launch {
                      try {
                        val removed = repo.remove(quote.id)
                        if (removed != null) notices.show("Highlight removed") { if (!repo.undo(removed)) notices.show("Later highlight changes were kept.") }
                      } catch (_: Exception) { notices.show("Couldn’t remove highlight. Try again.") }
                    } },
                    modifier = Modifier.heightIn(min = 48.dp),
                  ) { Text("Remove") }
                }
              }
            }
          }
        }
        Button(
          onClick = { onReview(null) },
          modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp),
        ) { Text("Review this article") }
      }
    }
  }
}

/**
 * Article-scoped review. Presents the passage-ordered members once, starting at
 * the tapped quote when present; Previous steps back without crediting, Next
 * credits once and advances, and the last card finishes the round.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ArticleReviewScreen(
  documentId: String,
  startHighlightId: String?,
  onBack: () -> Unit,
  onOpenSource: (documentId: String, quoteId: String) -> Unit,
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val app = context.applicationContext as ReaderApp
  val db = remember { ReaderDb.get(context) }
  val repo = remember { ReviewRepository(db) }
  val scope = rememberCoroutineScope()
  var round by remember(documentId) { mutableStateOf<ArticleRound?>(null) }
  var quote by remember { mutableStateOf<HighlightEntity?>(null) }
  var orderedIds by remember(documentId) { mutableStateOf<List<String>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  val c = appColors()
  val dark = LocalReaderDark.current
  val mono = LocalDisplayPolicy.current.monochrome

  suspend fun refresh(presented: ArticleRound?) {
    val id = presented?.presentedId
    quote = id?.let { db.highlights().byId(it) }
    round = presented
  }
  LaunchedEffect(documentId, startHighlightId) {
    loading = true
    try {
      val saved = repo.readArticleRound(documentId)
      val ordered = orderedForPassage(app.articles, documentId, db.highlights().observeForDocument(documentId).first())
      orderedIds = ordered.map { it.id }
      // Resume an unfinished round so advancing credits each quote once per
      // round; only start fresh (at the tapped quote or the first quote) when
      // there is nothing to resume.
      val resumed = saved?.takeIf { it.documentId == documentId && !it.completed && it.order.isNotEmpty() }
      val started = resumed ?: repo.startArticleRound(documentId, orderedIds, startHighlightId)
      refresh(started); error = null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: "Couldn’t prepare review" }
    finally { loading = false }
  }

  Scaffold(
    containerColor = c.background,
    contentColor = c.text,
    topBar = {
      Column(Modifier.statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to article highlights") }
          Text("Review", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
          val shown = round
          if (shown != null && !shown.completed && shown.total > 0) {
            Text("${shown.position} of ${shown.total}", style = MaterialTheme.typography.labelMedium, color = c.secondary)
          }
        }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad).fillMaxSize()) {
      when {
        error != null -> Column(Modifier.fillMaxSize().padding(24.dp)) { Text(error!!, color = c.error) }
        loading || round == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (mono) Text("Preparing review…", color = c.text) else CircularProgressIndicator()
        }
        round!!.completed -> Column(
          Modifier.fillMaxSize().padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          Text("Review complete", style = MaterialTheme.typography.titleLarge, color = c.text)
          Button(onClick = onBack) { Text("Back to article highlights") }
          TextButton(onClick = { scope.launch { refresh(repo.startArticleRound(documentId, orderedIds, null)) } }) { Text("Review again") }
        }
        quote == null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
          Text("This quote is no longer saved.", color = c.secondary)
          TextButton(onClick = onBack) { Text("Back to article highlights") }
        }
        else -> {
          val value = quote!!
          val pres = highlightPresentation(value.color, mono, dark)
          Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("Source", style = MaterialTheme.typography.labelSmall, color = c.secondary)
                Text(value.sourceTitle.ifBlank { "Saved passage" }, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${pres.shortId} · ${pres.label}", style = MaterialTheme.typography.labelSmall, color = c.secondary)
              }
              TextButton(onClick = { onOpenSource(value.documentId, value.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open passage") }
            }
            Box(Modifier.fillMaxWidth().padding(top = if (value.important) 10.dp else 0.dp)) {
              Box(
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .background(if (mono) pres.fill else c.surface)
                  .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
              ) {
                Text(
                  value.quote,
                  style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = fontFor(com.reader.app.prefs.ArticleFont.NEWSREADER),
                    fontSize = quoteSizeSp(19f, value.quote.length).sp,
                    lineHeight = (quoteSizeSp(19f, value.quote.length) * 1.5f).sp,
                  ),
                  color = c.text,
                )
              }
            }
          }
          FlowRow(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TextButton(
              enabled = round!!.cursor > 0,
              onClick = { scope.launch { refresh(repo.previousArticle(value.id)) } },
            ) { Text("Previous") }
            TextButton(onClick = {
              scope.launch {
                try { repo.toggleImportant(value.id); refresh(repo.readArticleRound(documentId)) }
                catch (_: Exception) { /* importance is best-effort here */ }
              }
            }) { Text(if (value.important) "★ Important" else "☆ Important") }
            TextButton(onClick = { share(context, value) }) { Text("Share") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { scope.launch { refresh(repo.advanceArticle(value.id)) } }) {
              Text(if (round!!.atLast) "Finish review" else "Next")
            }
          }
        }
      }
    }
  }
}

private fun share(context: android.content.Context, value: HighlightEntity) {
  val source = value.sourceUrl?.takeIf { it.isNotBlank() }?.let { " (${it.take(200)})" }.orEmpty()
  context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "“${value.quote}”\n— ${value.sourceTitle}$source")
  }, "Share quote"))
}
