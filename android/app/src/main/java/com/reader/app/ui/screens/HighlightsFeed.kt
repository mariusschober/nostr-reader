package com.reader.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reader.app.core.ReviewScheduler
import com.reader.app.data.HighlightSummary

@Composable
fun HighlightsFeed(quotes: List<HighlightSummary>, seed: Long, newest: Boolean, onNewest: (Boolean) -> Unit, onReview: (String?) -> Unit) {
  val sorted = remember(quotes, newest, seed) {
    if (newest) quotes.sortedWith(compareByDescending<HighlightSummary> { it.createdAt }.thenBy { it.id })
    else quotes.sortedWith(compareBy<HighlightSummary> { ReviewScheduler.feedKey(seed, it.id) }.thenBy { it.id })
  }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      FilterChip(selected = !newest, onClick = { onNewest(false) }, label = { Text("Shuffle") })
      FilterChip(selected = newest, onClick = { onNewest(true) }, label = { Text("Newest") })
      Button(enabled = quotes.isNotEmpty(), onClick = { onReview(null) }) { Text("Review") }
    }
    if (quotes.isEmpty()) Text("Save a passage while reading to find it here.", modifier = Modifier.padding(24.dp))
    else LazyColumn(Modifier.fillMaxSize()) {
      items(sorted, key = { it.id }) { quote ->
        Column(Modifier.fillMaxWidth().clickable { onReview(quote.id) }.padding(20.dp)) {
          val dark = com.reader.app.ui.theme.LocalReaderDark.current
          Text(quote.preview + if (quote.quoteLength > 800) "…" else "", style = MaterialTheme.typography.bodyLarge,
            color = com.reader.app.ui.theme.HighlightColor.text(dark),
            modifier = Modifier.background(com.reader.app.ui.theme.HighlightColor.parse(quote.color).background(dark)).padding(12.dp))
          Spacer(Modifier.height(8.dp))
          Text((if (quote.important) "★ " else "") + quote.sourceTitle, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
      }
    }
  }
}
