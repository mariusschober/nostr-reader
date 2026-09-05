package com.reader.app

import com.reader.app.nostr.PAIRING_CAPABILITIES
import com.reader.app.nostr.PAIRING_PROTOCOL
import com.reader.app.nostr.PairingProtocol
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PairingProtocolTest {
  private val now = 1_800_000_000L
  private val pairingKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
  private val deviceKey = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"
  private val channelKey = "dd308afec5777e13121fa72b9cc1b7cc0139715309b086c960e18fd969774eb8"

  private fun request() = run {
    val relays = listOf("wss://relay.example", "wss://second.example")
    val json = buildJsonObject {
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
    PairingProtocol.validateRequest(json.toString(), now + 1)
  }

  @Test
  fun responseAndAckBindTheVerifiedInnerSenders() {
    val request = request()
    val response = PairingProtocol.buildPairResponse(request, channelKey, "0.2.0-test", now + 2)
    assertEquals(channelKey, response["androidChannelPubkey"]!!.jsonPrimitive.content)
    assertEquals(pairingKey, response["recipientPairingPubkey"]!!.jsonPrimitive.content)

    val ack = buildJsonObject {
      put("protocol", PAIRING_PROTOCOL)
      put("type", "pair-ack")
      put("sessionId", request.sessionId)
      put("chromeDevicePubkey", request.chromeDevicePubkey)
      put("androidChannelPubkey", channelKey)
      put("relaySetDigest", request.relaySetDigest)
      put("acceptedRelays", JsonArray(listOf(JsonPrimitive(request.relays.first()))))
      put("status", "accepted")
      put("createdAt", now + 3)
      put("expiresAt", now + 303)
    }
    assertEquals(
      "accepted",
      PairingProtocol.validatePairAck(ack, request, channelKey, deviceKey, now + 4)["status"]!!.jsonPrimitive.content,
    )
    assertFails { PairingProtocol.validatePairAck(ack, request, channelKey, pairingKey, now + 4) }
    assertFails {
      PairingProtocol.validatePairAck(
        JsonObject(ack.toMutableMap().also { it["createdAt"] = JsonPrimitive(now - 61) }),
        request,
        channelKey,
        deviceKey,
        now + 4,
      )
    }
  }

  @Test
  fun completionBindsBothEndpointsAndRelayDigest() {
    val request = request()
    val complete = PairingProtocol.buildPairComplete(request, channelKey, now + 5)
    assertEquals(request.sessionId, complete["sessionId"]!!.jsonPrimitive.content)
    assertEquals(request.chromeDevicePubkey, complete["chromeDevicePubkey"]!!.jsonPrimitive.content)
    assertEquals(request.relaySetDigest, complete["relaySetDigest"]!!.jsonPrimitive.content)
  }

  @Test
  fun rejectsUnknownFieldsAndPrivateDnsAnswers() {
    val request = request()
    val withUnknown = buildJsonObject {
      request.json.forEach { (key, value) -> put(key, value) }
      put("redirect", "wss://attacker.example")
    }
    assertFails { PairingProtocol.validateRequest(withUnknown.toString(), now + 1) }
    assertFails {
      PairingProtocol.validateResolvedRelayAddresses(request.relays) {
        arrayOf(InetAddress.getByName("127.0.0.1"))
      }
    }
  }

  private fun assertFails(block: () -> Unit) {
    try {
      block()
      fail("expected validation failure")
    } catch (_: IllegalArgumentException) {
    }
  }
}
