package com.reader.app.ui

/** Human labels for protocol source ids; never show raw values like "android-process-text". */
object SourceLabels {
  fun label(sourceType: String): String = when (sourceType) {
    "web" -> "Web article"
    "selection" -> "Chrome selection"
    "chatgpt" -> "ChatGPT"
    "claude" -> "Claude"
    "gemini" -> "Gemini"
    "perplexity" -> "Perplexity"
    "notebook" -> "Notebook"
    "grok" -> "Grok"
    "substack" -> "Substack"
    "x" -> "X post"
    "android-share" -> "Shared from Android"
    "android-process-text" -> "Android selection"
    "file" -> "Imported file"
    "paste" -> "Pasted text"
    "mac-share" -> "Shared from Mac"
    "mac-quick-action" -> "Mac Quick Action"
    else -> sourceType.split('-', '_').joinToString(" ") { word ->
      word.replaceFirstChar { it.uppercase() }
    }
  }
}
