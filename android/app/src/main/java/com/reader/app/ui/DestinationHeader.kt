package com.reader.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One geometry for every destination title: Shelf, Highlights, Settings and the
 * secondary Review screen share this row so the baseline never moves when the
 * user switches destination. The row reserves its height even when a
 * destination has no trailing actions (the old Shelf/Highlights mismatch came
 * from the actionless row collapsing to the text height). Height grows with the
 * system text scale instead of clipping.
 */
@Composable
fun DestinationHeader(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 52.dp)
      .padding(start = if (onBack == null) 20.dp else 4.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
    actions()
  }
}
