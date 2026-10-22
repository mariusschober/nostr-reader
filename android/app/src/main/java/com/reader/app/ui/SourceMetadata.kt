package com.reader.app.ui

import java.text.DateFormat
import java.util.Date

/** Reader-facing source line and safe external-link handling. */
object SourceMetadata {
  fun safeHttpUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.length > 2048) return null
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null
    return value
  }

  fun line(
    author: String?,
    sourceName: String?,
    publishedAt: Long?,
    dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM),
  ): String? {
    val parts = buildList {
      author?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
      sourceName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
      publishedAt?.takeIf { it > 0 }?.let { raw ->
        val millis = if (raw < 10_000_000_000L) raw * 1000 else raw
        add(dateFormat.format(Date(millis)))
      }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }
}
