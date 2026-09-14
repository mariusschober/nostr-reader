package com.reader.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reader.app.prefs.LabelColorKey
import com.reader.app.ui.theme.LocalDisplayPolicy
import com.reader.app.ui.theme.LocalReaderDark
import com.reader.app.ui.theme.labelAccent

/** One descriptive, non-interactive hashtag badge carrying its label's identity. */
@Composable
fun LabelBadge(name: String, key: LabelColorKey, modifier: Modifier = Modifier) {
  val mono = LocalDisplayPolicy.current.monochrome
  val dark = LocalReaderDark.current
  val accent = labelAccent(key, dark)
  // Monochrome keeps identity as an outlined hashtag with no hue dependence.
  val edge = if (mono) MaterialTheme.colorScheme.onSurface else accent
  Box(
    modifier
      .widthIn(max = 140.dp)
      .border(1.dp, edge, RoundedCornerShape(6.dp))
      .padding(horizontal = 6.dp, vertical = 1.dp),
  ) {
    Text(
      "#$name",
      style = MaterialTheme.typography.labelSmall,
      color = edge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Stable identity for one label badge: stable id, display name, palette key. */
data class LabelBadgeSpec(val labelId: String, val name: String, val key: LabelColorKey)

/**
 * Compact badge strip beneath article metadata: at most two names plus "+N",
 * case-insensitive name order with stable-id ties. Descriptive only — never a
 * tap target, so the row's own tap / long-press / swipe stay intact.
 * Accessibility exposes the full names.
 */
@Composable
fun LabelBadgesRow(badges: List<LabelBadgeSpec>, modifier: Modifier = Modifier) {
  if (badges.isEmpty()) return
  val ordered = remember(badges) {
    badges.sortedWith(compareBy({ it.name.lowercase(java.util.Locale.ROOT) }, { it.labelId }))
  }
  val shown = ordered.take(2)
  val extra = ordered.size - shown.size
  val full = ordered.joinToString(", ") { it.name }
  Row(
    modifier.semantics(mergeDescendants = true) {
      contentDescription = if (extra > 0) "Labels: $full, and $extra more" else "Labels: $full"
    },
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    shown.forEach { spec -> LabelBadge(spec.name, spec.key) }
    if (extra > 0) {
      Text(
        "+$extra",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
