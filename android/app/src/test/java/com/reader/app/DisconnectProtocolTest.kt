package com.reader.app

import com.reader.app.sync.DisconnectProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectProtocolTest {
  private val now = 1_800_000_000L

  @Test
  fun payloadIsBoundedAndCarriesNoChannelSecretsOrContent() {
    val payload = Json.parseToJsonElement(DisconnectProtocol.payload(now)).jsonObject
    assertEquals("reader/2", payload["protocol"]!!.toString().trim('"'))
    assertEquals("disconnect", payload["type"]!!.toString().trim('"'))
    assertEquals(now, payload["createdAt"]!!.toString().toLong())
    assertEquals(now + DisconnectProtocol.TTL_SECS, payload["expiresAt"]!!.toString().toLong())
    assertEquals(
      setOf("protocol", "type", "createdAt", "expiresAt", "reasonCode"),
      payload.keys,
    )
  }

  @Test
  fun freshPayloadValidates() {
    val payload = Json.parseToJsonElement(DisconnectProtocol.payload(now)).jsonObject
    assertTrue(DisconnectProtocol.validate(payload, now + 1))
  }

  @Test
  fun expiredStaleAndOversizedLifetimesAreRejected() {
    fun payload(createdAt: Long, expiresAt: Long) = Json.parseToJsonElement(
      """{"protocol":"reader/2","type":"disconnect","createdAt":$createdAt,"expiresAt":$expiresAt,"reasonCode":"user-disconnect"}""",
    ).jsonObject
    assertFalse(DisconnectProtocol.validate(payload(now, now), now))
    assertFalse(DisconnectProtocol.validate(payload(now - 200_000, now + 600), now))
    assertFalse(DisconnectProtocol.validate(payload(now, now + DisconnectProtocol.TTL_SECS + 301), now))
    assertFalse(DisconnectProtocol.validate(payload(now, now + 600).toMutableMap().let { map ->
      kotlinx.serialization.json.JsonObject(map + ("type" to kotlinx.serialization.json.JsonPrimitive("ack")))
    }, now))
  }

  @Test
  fun invalidReasonIsRejectedAtCreation() {
    try {
      DisconnectProtocol.payload(now, "Bad Reason")
      throw AssertionError("invalid reason accepted")
    } catch (_: IllegalArgumentException) {
    }
  }
}
