package com.reader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.nostr.PAIRING_CAPABILITIES
import com.reader.app.nostr.PAIRING_PROTOCOL
import com.reader.app.nostr.PairingProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class PairingProtocolInstrumentedTest {
  private val now = 1_800_000_000L
  private val pairingKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
  private val deviceKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"

  private fun request(relays: List<String> = listOf("wss://relay.example")) = buildJsonObject {
    put("protocol", PAIRING_PROTOCOL)
    put("sessionId", "01".repeat(16))
    put("pairingPubkey", pairingKey)
    put("chromeDevicePubkey", deviceKey)
    put("nonce", "02".repeat(32))
    put("relays", JsonArray(relays.map(::JsonPrimitive)))
    put("relaySetDigest", PairingProtocol.relaySetDigest(relays))
    put("createdAt", now)
    put("expiresAt", now + 300)
    put("capabilities", JsonArray(PAIRING_CAPABILITIES.map(::JsonPrimitive)))
  }

  private fun changed(base: JsonObject, field: String, value: JsonPrimitive): JsonObject =
    JsonObject(base.toMutableMap().also { it[field] = value })

  @Test
  fun physicalRuntimeMatchesSharedChromeAndroidPairingTranscript() {
    val context = InstrumentationRegistry.getInstrumentation().context
    val vector = kotlinx.serialization.json.Json.parseToJsonElement(
      context.assets.open("pairing-v2.json").bufferedReader().use { it.readText() },
    ).jsonObject
    val validationNow = vector["validationNow"]!!.jsonObject
    val verifiedSenders = vector["verifiedSenders"]!!.jsonObject
    val expectedRequest = vector["request"]!!.jsonObject
    val expectedResponse = vector["response"]!!.jsonObject
    val expectedAck = vector["ack"]!!.jsonObject
    val expectedComplete = vector["complete"]!!.jsonObject

    val parsed = PairingProtocol.validateRequest(
      expectedRequest.toString(),
      validationNow["request"]!!.jsonPrimitive.long,
    )
    assertEquals(expectedRequest, parsed.json)
    assertEquals(
      expectedResponse,
      PairingProtocol.buildPairResponse(
        parsed,
        verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
        expectedResponse["appVersion"]!!.jsonPrimitive.content,
        expectedResponse["createdAt"]!!.jsonPrimitive.long,
      ),
    )
    assertEquals(
      expectedAck,
      PairingProtocol.validatePairAck(
        expectedAck,
        parsed,
        verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
        verifiedSenders["chromeDevicePubkey"]!!.jsonPrimitive.content,
        validationNow["ack"]!!.jsonPrimitive.long,
      ),
    )
    assertEquals(
      expectedComplete,
      PairingProtocol.buildPairComplete(
        parsed,
        verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
        expectedComplete["createdAt"]!!.jsonPrimitive.long,
      ),
    )
  }

  @Test
  fun physicalRuntimeRejectsHostilePairingCodesBeforeNetwork() {
    val valid = request()
    val parsed = PairingProtocol.validateRequest(valid.toString(), now + 1)
    assertEquals(listOf("wss://relay.example"), parsed.relays)

    val hostile = listOf(
      changed(valid, "relaySetDigest", JsonPrimitive("00".repeat(32))),
      changed(valid, "pairingPubkey", JsonPrimitive("ff".repeat(32))),
      changed(valid, "expiresAt", JsonPrimitive(now)),
      request(listOf("wss://127.0.0.1")),
    )
    hostile.forEach { payload ->
      assertTrue(runCatching { PairingProtocol.validateRequest(payload.toString(), now + 1) }.isFailure)
    }
    assertTrue(
      runCatching {
        PairingProtocol.validateResolvedRelayAddresses(parsed.relays) {
          arrayOf(InetAddress.getByName("100.64.0.1"))
        }
      }.isFailure,
    )
  }
}
