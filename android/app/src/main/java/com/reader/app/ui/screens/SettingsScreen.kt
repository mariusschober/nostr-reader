package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reader.app.data.ChannelEntity
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.DestinationHeader
import com.reader.app.ui.theme.appColors

data class LibraryStats(val total: Int = 0, val weekMinutes: Int = 0, val weekFinished: Int = 0, val runDays: Int = 0)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(settings: ReaderSettings, channels: List<ChannelEntity>, signerLabel: String,
  relaySummary: String, onBack: () -> Unit, onRevokeChannel: (String) -> Unit, onExport: () -> Unit,
  onSignerInfo: () -> Unit, onSettingsChange: (ReaderSettings) -> Unit,
  syncHealth: com.reader.app.data.SyncHealthEntity?, syncing: Boolean, onSync: () -> Unit,
  libraryStats: LibraryStats? = null, onLabels: () -> Unit = {}, onConnect: () -> Unit = {},
) {
  BackHandler(onBack = onBack)
  val c = appColors()
  val context = androidx.compose.ui.platform.LocalContext.current
  val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "debug" }
  var advanced by remember { mutableStateOf(false) }
  var appearance by remember { mutableStateOf(false) }
  var disconnect by remember { mutableStateOf<String?>(null) }
  Surface(color = c.background, contentColor = c.text, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      DestinationHeader(title = "Settings")
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      TextButton(onClick = { appearance = true }) { Text("Reading appearance", style = MaterialTheme.typography.titleMedium) }
      Text("App theme", style = MaterialTheme.typography.labelMedium, color = c.secondary)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.reader.app.prefs.ThemeMode.entries.forEach { mode -> FilterChip(settings.themeMode == mode,
          { onSettingsChange(settings.copy(themeMode = mode)) }, { Text(when (mode) {
            com.reader.app.prefs.ThemeMode.SYSTEM -> "Follow system"
            com.reader.app.prefs.ThemeMode.EINK -> "E-ink / NXTPAPER"
            else -> mode.name.lowercase().replaceFirstChar { it.uppercase() }
          }) }) }
      }
      HorizontalDivider(color = c.divider)
      TextButton(onClick = onLabels) { Text("Labels", style = MaterialTheme.typography.titleMedium) }
      HorizontalDivider(color = c.divider)
      Text("Connected devices", style = MaterialTheme.typography.titleMedium)
      if (channels.isEmpty()) Text("Connect Chrome to send articles from your computer.", color = c.secondary)
      channels.forEachIndexed { i, channel -> Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Chrome${if (channels.size > 1) " ${i+1}" else ""}", modifier = Modifier.weight(1f))
        TextButton(onClick = { disconnect = channel.channelId }) { Text("Disconnect") }
      } }
      Row {
        TextButton(onClick = onConnect) { Text("Connect Chrome") }
        TextButton(enabled = !syncing, onClick = onSync) { Text(if (syncing) "Checking…" else "Check for articles") }
      }
      HorizontalDivider(color = c.divider)
      Text("Export", style = MaterialTheme.typography.titleMedium)
      Text("Export saved articles and highlights as files in a ZIP. This is not a full backup of settings, keys or connected devices.", color = c.secondary)
      TextButton(onClick = onExport) { Text("Export articles & highlights") }
      HorizontalDivider(color = c.divider)
      Text("About Reader", style = MaterialTheme.typography.titleMedium)
      Text("A quiet place for things worth reading. Your saved text and highlights are available on this device.", color = c.secondary)
      Text("Version $version", style = MaterialTheme.typography.bodySmall, color = c.secondary)
      libraryStats?.let { stats -> Text("${stats.total} saved articles · ${stats.weekFinished} finished this week\nEstimated reading time: ${stats.weekMinutes} min this week", style = MaterialTheme.typography.bodySmall, color = c.secondary) }
      TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide Advanced ▴" else "Advanced ▾") }
      if (advanced) {
        Text("Connection diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(syncHealth?.checkedAt?.let { "Last checked: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Not checked yet", color = c.secondary)
        syncHealth?.let { Text("${it.pendingTransfers} incoming transfers · ${it.pendingReceipts} receipts pending", color = c.secondary); it.error?.let { e -> Text(e, color = c.error) } }
        Text(relaySummary, color = c.secondary)
        Text("Signing & keys", style = MaterialTheme.typography.titleMedium)
        Text(signerLabel, color = c.secondary)
        channels.forEach { Text("Device ${it.trustedSenderPubkey.take(12)}…", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onSignerInfo) { Text("About device keys") }
      }
      }
    }
  }
  if (appearance) AppearanceSheet(settings, onSettingsChange) { appearance = false }
  disconnect?.let { id -> AlertDialog(onDismissRequest = { disconnect = null }, title = { Text("Disconnect Chrome?") },
    text = { Text("This Chrome connection will no longer send articles to Reader. Saved articles and highlights stay on this device. Reconnect with a new code to resume sending.") },
    confirmButton = { TextButton(onClick = { disconnect = null; onRevokeChannel(id) }) { Text("Disconnect") } },
    dismissButton = { TextButton(onClick = { disconnect = null }) { Text("Keep connected") } }) }
}
