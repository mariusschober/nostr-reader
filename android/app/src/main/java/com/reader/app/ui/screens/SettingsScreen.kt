package com.reader.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reader.app.data.ChannelEntity
import com.reader.app.prefs.ReaderSettings
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.appColors

/** Minimal normal settings. Nostr details stay under Advanced. */
@Composable
fun SettingsScreen(
  settings: ReaderSettings,
  channels: List<ChannelEntity>,
  signerLabel: String,
  relaySummary: String,
  onBack: () -> Unit,
  onRevokeChannel: (String) -> Unit,
  onExport: () -> Unit,
  onSignerInfo: () -> Unit,
  onSettingsChange: (ReaderSettings) -> Unit,
  syncHealth: com.reader.app.data.SyncHealthEntity?,
  syncing: Boolean,
  onSync: () -> Unit,
) {
  BackHandler { onBack() }
  val c = appColors()
  val context = androidx.compose.ui.platform.LocalContext.current
  val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "beta" }
  var showAdvanced by remember { mutableStateOf(false) }
  Scaffold(
    containerColor = c.background,
    topBar = {
      Row(Modifier.fillMaxWidth().padding(8.dp, 4.dp)) {
        IconButton(onClick = onBack) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = c.text)
        }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
      Text("Appearance", color = c.secondary)
      Row {
        com.reader.app.prefs.ThemeMode.entries.forEach { mode ->
          FilterChip(selected = settings.themeMode == mode, onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.padding(end = 8.dp))
        }
      }
      Spacer(Modifier.height(16.dp))
      Text("Sync", color = c.secondary)
      Text(syncHealth?.checkedAt?.let { "Last checked: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "Not checked yet", color = c.text)
      syncHealth?.let { health ->
        Text("${health.pendingTransfers} incoming transfers · ${health.pendingReceipts} receipts pending", color = c.secondary)
        health.error?.let { Text(it, color = c.error) }
      }
      TextButton(enabled = !syncing, onClick = onSync) { Text(if (syncing) "Checking…" else "Sync now") }
      Spacer(Modifier.height(16.dp))
      Text("Chrome", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary)
      if (channels.isEmpty()) {
        Text("No devices connected.", fontFamily = ReaderFonts.Ui, color = c.text)
      } else {
        for (ch in channels) {
          Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
              Text("Chrome device", fontFamily = ReaderFonts.Ui, color = c.text)
              Text(ch.trustedSenderPubkey.take(12) + "…", fontFamily = ReaderFonts.Ui, fontSize = 12.sp, color = c.secondary)
            }
            TextButton(onClick = { onRevokeChannel(ch.channelId) }) {
              Text("Revoke", fontFamily = ReaderFonts.Ui, color = c.error)
            }
          }
          Divider(color = c.divider)
        }
      }
      Spacer(Modifier.height(16.dp))
      Text("Signing", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary)
      Text(signerLabel, fontFamily = ReaderFonts.Ui, color = c.text)
      TextButton(onClick = onSignerInfo) { Text("About signing", fontFamily = ReaderFonts.Ui, color = c.text) }
      Spacer(Modifier.height(16.dp))
      Text("About", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary)
      Text("Reader $version · Licenses bundled in-app", fontFamily = ReaderFonts.Ui, color = c.text)
      TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/mariusschober/nostr-reader"))) }) { Text("GitHub · Source code") }
      Text("Vision and guidance: Marius Schober\nDevelopment and implementation: AI\nOne human. Many tokens.", fontFamily = ReaderFonts.Ui, color = c.secondary, fontSize = 14.sp)
      Spacer(Modifier.height(16.dp))
      TextButton(onClick = { showAdvanced = !showAdvanced }) {
        Text(if (showAdvanced) "Hide Advanced" else "Advanced", fontFamily = ReaderFonts.Ui, color = c.text)
      }
      if (showAdvanced) {
        Text("Relays", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary)
        Text(relaySummary, fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("Security / keys", fontFamily = ReaderFonts.Ui, fontSize = 15.sp, color = c.secondary)
        Text(
          "Channel keys are wrapped by the Android Keystore and excluded from backups. Export never includes keys.",
          fontFamily = ReaderFonts.Ui, fontSize = 13.sp, color = c.text,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExport) { Text("Export archive (ZIP)", fontFamily = ReaderFonts.Ui, color = c.text) }
      }
    }
  }
}
