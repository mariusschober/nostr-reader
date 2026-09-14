package com.reader.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reader.app.data.*
import com.reader.app.ui.NoticeCoordinator
import com.reader.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable fun HighlightDetailScreen(id: String, db: ReaderDb, onBack: () -> Unit, onSource: (String, String) -> Unit, notices: NoticeCoordinator,
  onArticleHighlights: (String) -> Unit = {}) {
  BackHandler(onBack = onBack)
  val c = appColors()
  val quote by remember(id) { db.highlights().observeById(id) }.collectAsState(initial = null)
  val source by remember(quote?.documentId) { db.documents().observeMetadata(quote?.documentId ?: "") }.collectAsState(initial = null)
  val scope = rememberCoroutineScope()
  val repo = remember { HighlightRepository(db) }
  val context = LocalContext.current
  var actions by remember { mutableStateOf(false) }
  fun mutate(message: String, action: suspend () -> HighlightMutation?) { scope.launch {
    try { val change = action() ?: return@launch; notices.show(message) { if (!repo.undo(change)) notices.show("Later highlight changes were kept.") } }
    catch (_: Exception) { notices.show("Couldn’t update the highlight. Try again.") }
  } }
  Scaffold(containerColor = c.background, contentColor = c.text, topBar = {
    Row(Modifier.statusBarsPadding().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to highlights") }
      Text("Highlight", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      IconButton(enabled = quote != null, onClick = { actions = true }) { Icon(Icons.Default.MoreVert, "Highlight options") }
    }
  }) { pad ->
    Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      val value = quote
      if (value == null) Text("Highlight unavailable. If you just removed it, use Undo to bring it back.", color = c.secondary)
      else {
        Text(value.sourceTitle, style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(value.quote, style = MaterialTheme.typography.bodyLarge, fontFamily = ReaderFonts.Newsreader) }
        if (source != null) TextButton(onClick = { onSource(value.documentId, value.id) }) { Text("Open source at this passage") }
        else Text("The source article is no longer saved. Your highlight and attribution remain here.", color = c.secondary)
      }
    }
  }
  val value = quote
  if (actions && value != null) HighlightActionsSheet(value,
    onColor = { mutate("Highlight color updated") { repo.recolor(id, it) } },
    onImportant = { mutate("Importance updated") { repo.toggleImportant(id) } },
    onShare = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "“${value.quote}”\n— ${value.sourceTitle}" + (value.sourceUrl?.let { " ($it)" } ?: ""))
    }, "Share highlight")) },
    onArticleHighlights = { actions = false; onArticleHighlights(value.documentId) },
    onRemove = { actions = false; mutate("Highlight removed") { repo.remove(id) } }, onDismiss = { actions = false })
}
