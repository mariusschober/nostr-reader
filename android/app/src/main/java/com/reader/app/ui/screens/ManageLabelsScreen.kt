package com.reader.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reader.app.data.*
import com.reader.app.ui.theme.appColors
import kotlinx.coroutines.launch
import androidx.room.withTransaction

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ManageLabelsScreen(db: ReaderDb, onBack: () -> Unit, onMerge: (String, String) -> Unit, onDeleted: (String) -> Unit) {
  BackHandler(onBack = onBack)
  val c = appColors()
  val scope = rememberCoroutineScope()
  val labels by remember { db.labels().observeLabels() }.collectAsState(initial = emptyList())
  var editing by remember { mutableStateOf<LabelCount?>(null) }
  var showing by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var merge by remember { mutableStateOf<LabelCount?>(null) }
  var deleting by remember { mutableStateOf<LabelCount?>(null) }
  var busy by remember { mutableStateOf(false) }
  fun save() {
    val norm = LabelNorm.normalize(text)
    if (norm == null) { error = "Use 1–50 characters, without commas, quotes or backslashes."; return }
    val collision = labels.find { it.normalized == norm && it.labelId != editing?.labelId }
    if (collision != null) {
      if (editing != null) merge = collision else error = "That label already exists."
      return
    }
    busy = true
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val old = editing
        if (old != null) db.labels().rename(old.labelId, LabelNorm.display(text)!!, norm, now)
        else db.labels().insertLabel(LabelEntity(java.util.UUID.randomUUID().toString(), LabelNorm.display(text)!!, norm, now, now))
        showing = false
      } catch (_: Exception) { error = "Couldn’t save the label. Try again." }
      finally { busy = false }
    }
  }
  Scaffold(containerColor = c.background, contentColor = c.text, topBar = {
    Row(Modifier.statusBarsPadding().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
      Text("Labels", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
      TextButton(onClick = { editing = null; text = ""; error = null; showing = true }) { Text("New label") }
    }
  }) { pad ->
    LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(20.dp)) {
      item { Text("Labels group related reading across shelves. An article can have several; choosing several filters matches all of them.", color = c.secondary, modifier = Modifier.padding(bottom = 20.dp)) }
      if (labels.isEmpty()) item { Text("No labels yet. Create one here or add labels from an article.") }
      items(labels, key = { it.labelId }) { label ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) { Text(label.name); Text("${label.count} article${if(label.count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = c.secondary) }
          TextButton(onClick = { editing = label; text = label.name; error = null; showing = true }) { Text("Edit") }
        }
      }
    }
  }
  if (showing) ModalBottomSheet(onDismissRequest = { if (!busy) showing = false }, containerColor = c.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(if (editing == null) "New label" else "Edit label", style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(text, { text = it; error = null }, label = { Text("Label name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null)
      error?.let { Text(it, color = c.error) }
      Button(enabled = !busy, onClick = ::save) { Text(if (editing == null) "Create label" else "Save name") }
      editing?.let { label -> TextButton(enabled = !busy, onClick = { deleting = label }) { Text("Delete label…", color = c.error) } }
    }
  }
  merge?.let { target -> AlertDialog(onDismissRequest = { merge = null }, title = { Text("Merge labels?") },
    text = { Text("“${target.name}” already exists. Move every “${editing?.name}” assignment into it and remove the old label? Articles keep their other labels.") },
    confirmButton = { TextButton(enabled = !busy, onClick = {
      val from = editing ?: return@TextButton
      busy = true
      scope.launch {
        try { db.labels().mergeInto(from.labelId, target.labelId); onMerge(from.labelId, target.labelId); merge = null; showing = false }
        catch (_: Exception) { error = "Couldn’t merge labels. Try again."; merge = null }
        finally { busy = false }
      }
    }) { Text("Merge labels") } }, dismissButton = { TextButton(onClick = { merge = null }) { Text("Cancel") } }) }
  deleting?.let { label -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${label.name}”?") },
    text = { Text("The label is removed from all articles. No articles or highlights are deleted.") },
    confirmButton = { TextButton(onClick = { scope.launch {
      try { db.labels().deleteLabel(label.labelId); onDeleted(label.labelId); deleting = null; showing = false }
      catch (_: Exception) { error = "Couldn’t delete the label. Try again."; deleting = null }
    } }) { Text("Delete label", color = c.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
