package com.reader.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.reader.app.ui.theme.appColors

@Composable fun ReaderBottomNavigation(selected: String, onSelect: (String) -> Unit) {
  val c = appColors()
  NavigationBar(containerColor = c.background, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
    listOf("shelf" to "Reader", "highlights" to "Highlights", "settings" to "Settings").forEach { (id, label) ->
      NavigationBarItem(selected = selected == id, onClick = { if (selected != id) onSelect(id) },
        icon = { Icon(when(id) { "shelf" -> Icons.Default.Bookmarks; "highlights" -> Icons.Default.FormatQuote; else -> Icons.Default.Settings }, null) },
        label = { Text(label) }, colors = NavigationBarItemDefaults.colors(
          selectedIconColor = c.text, selectedTextColor = c.text, indicatorColor = c.divider,
          unselectedIconColor = c.secondary, unselectedTextColor = c.secondary))
    }
  }
}
