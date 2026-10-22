package com.reader.app.nostr

import java.security.MessageDigest

/** Short authentication string shown on both devices before pairing is confirmed. */
object PairingSas {
  private val WORDS = listOf(
    "amber", "anchor", "apple", "arrow", "aspen", "beacon", "birch", "bloom",
    "breeze", "bridge", "cabin", "cactus", "canyon", "cedar", "cobalt", "comet",
    "coral", "cosmos", "creek", "crown", "dawn", "delta", "ember", "falcon",
    "fern", "fjord", "flame", "forest", "glacier", "granite", "harbor", "hazel",
    "heron", "indigo", "island", "ivory", "jasmine", "juniper", "lagoon", "lantern",
    "maple", "meadow", "meteor", "mint", "nectar", "nova", "oak", "ocean",
    "olive", "orchid", "pearl", "pine", "quartz", "raven", "river", "saffron",
    "silver", "spruce", "summit", "thistle", "timber", "valley", "willow", "zephyr",
  )

  fun words(
    sessionId: String,
    chromeDevicePubkey: String,
    pairingPubkey: String,
    nonce: String,
  ): String {
    val canonical = listOf(
      "reader-pair-sas/2",
      sessionId,
      chromeDevicePubkey,
      pairingPubkey,
      nonce,
    ).joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return (0 until 6).joinToString(" ") { WORDS[digest[it].toInt() and 63] }
  }

  fun forRequest(request: ValidatedPairingRequest): String = words(
    request.sessionId,
    request.chromeDevicePubkey,
    request.pairingPubkey,
    request.nonce,
  )
}
