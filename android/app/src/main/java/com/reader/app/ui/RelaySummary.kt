package com.reader.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Readable Advanced-settings relay list instead of the raw persisted JSON array. */
object RelaySummary {
  fun format(
    rawJson: String?,
    defaults: List<String>,
    quorum: Int,
    customNote: String = "Add up to 2 custom relays in Chrome Settings, then re-pair so both devices authenticate the same relay set.",
  ): String {
    val relays: List<String> = runCatching {
      Json.parseToJsonElement(rawJson ?: "[]").jsonArray.mapNotNull { element ->
        (element as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
      }
    }.getOrDefault(emptyList())
    if (relays.isEmpty()) {
      return "${defaults.size} default public relays ($quorum required per payload).\n\n$customNote"
    }
    val defaultSet = defaults.toSet()
    val lines = relays.mapIndexed { index, relay ->
      val kind = if (relay in defaultSet) "default" else "custom"
      "${index + 1}. $relay ($kind)"
    }
    return "${relays.size} paired relays ($quorum required per payload):\n" +
      lines.joinToString("\n") + "\n\n" + customNote
  }
}
