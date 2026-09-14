package com.reader.app.prefs

/**
 * Predefined label-badge palette. A stable key is stored per stable label id in
 * a dedicated appearance preference, so a whole-settings save (font, theme,
 * spacing) can never overwrite a label's colour, and a rename never loses it.
 */
enum class LabelColorKey { NEUTRAL, RED, ORANGE, GREEN, BLUE }

/** Unknown or absent values resolve safely to Neutral; no preference reset. */
fun parseLabelColorKey(raw: String?): LabelColorKey =
  runCatching { LabelColorKey.valueOf(raw ?: "NEUTRAL") }.getOrDefault(LabelColorKey.NEUTRAL)

/** Encode the label-id to key map as a preference set of "id=KEY" entries. */
internal fun encodeLabelColors(map: Map<String, LabelColorKey>): Set<String> =
  map.map { (id, key) -> "$id=${key.name}" }.toSet()

/** Decode "id=KEY" entries, ignoring anything malformed rather than throwing. */
internal fun decodeLabelColors(encoded: Set<String>): Map<String, LabelColorKey> =
  encoded.mapNotNull { entry ->
    val at = entry.lastIndexOf('=')
    if (at <= 0) null else entry.substring(0, at) to parseLabelColorKey(entry.substring(at + 1))
  }.toMap()

