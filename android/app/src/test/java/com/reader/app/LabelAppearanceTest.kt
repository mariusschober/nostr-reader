package com.reader.app

import com.reader.app.prefs.LabelColorKey
import com.reader.app.prefs.decodeLabelColors
import com.reader.app.prefs.encodeLabelColors
import com.reader.app.prefs.parseLabelColorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused checks for the dedicated label-colour appearance map: it must survive
 * a round trip keyed by stable label id, resolve unknown/absent values to
 * Neutral, and never throw on malformed entries.
 */
class LabelAppearanceTest {
  @Test fun roundTripsStableIdToKey() {
    val map = mapOf("label-a" to LabelColorKey.RED, "label-b" to LabelColorKey.BLUE)
    assertEquals(map, decodeLabelColors(encodeLabelColors(map)))
  }

  @Test fun idsWithDashesAndColonsSurvive() {
    val id = "a1b2-c3d4:e5f6"
    assertEquals(
      mapOf(id to LabelColorKey.ORANGE),
      decodeLabelColors(encodeLabelColors(mapOf(id to LabelColorKey.ORANGE))),
    )
  }

  @Test fun neutralAndUnknownResolveSafely() {
    assertEquals(LabelColorKey.NEUTRAL, parseLabelColorKey(null))
    assertEquals(LabelColorKey.NEUTRAL, parseLabelColorKey("NOT_A_COLOR"))
    assertEquals(LabelColorKey.GREEN, parseLabelColorKey("GREEN"))
  }

  @Test fun malformedEntriesAreIgnoredNotThrown() {
    val decoded = decodeLabelColors(setOf("no-separator", "=EMPTYID", "id=UNKNOWN", "id2="))
    // Malformed entries are dropped; a bad key degrades to Neutral, never throws.
    assertTrue("no-separator" !in decoded)
    assertTrue("" !in decoded)
    assertEquals(mapOf("id" to LabelColorKey.NEUTRAL, "id2" to LabelColorKey.NEUTRAL), decoded)
  }
}
