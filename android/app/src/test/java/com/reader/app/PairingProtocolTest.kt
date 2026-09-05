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

  private fun requestJson(
    relays: List<String> = listOf("wss://relay.example", "wss://second.example"),
    createdAt: Long = now,
    expiresAt: Long = now + 300,
    pairingPubkey: String = pairingKey,
    chromeDevicePubkey: String = deviceKey,
    capabilities: List<String> = PAIRING_CAPABILITIES,
  ) = buildJsonObject {
      put("protocol", PAIRING_PROTOCOL)
      put("sessionId", "01".repeat(16))
      put("pairingPubkey", pairingPubkey)
      put("chromeDevicePubkey", chromeDevicePubkey)
      put("nonce", "02".repeat(32))
      put("relays", JsonArray(relays.map(::JsonPrimitive)))
      put("relaySetDigest", PairingProtocol.relaySetDigest(relays))
      put("createdAt", createdAt)
      put("expiresAt", expiresAt)
      put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
    }

  private fun request() = PairingProtocol.validateRequest(requestJson().toString(), now + 1)

  private fun withField(json: JsonObject, name: String, value: JsonElement): JsonObject =
    JsonObject(json.toMutableMap().also { it[name] = value })

  @Test
  fun sharedChromeAndroidPairingTranscriptMatchesByteForField() {
    val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("pairing-v2.json")) {
      "shared pairing-v2.json test resource missing"
    }
    val vector = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val validationNow = vector["validationNow"]!!.jsonObject
    val verifiedSenders = vector["verifiedSenders"]!!.jsonObject
    val expectedRequest = vector["request"]!!.jsonObject
    val expectedResponse = vector["response"]!!.jsonObject
    val expectedAck = vector["ack"]!!.jsonObject
    val expectedComplete = vector["complete"]!!.jsonObject

    val request = PairingProtocol.validateRequest(
      expectedRequest.toString(),
      validationNow["request"]!!.jsonPrimitive.long,
    )
    assertEquals(expectedRequest, request.json)

    val response = PairingProtocol.buildPairResponse(
      request,
      verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
      expectedResponse["appVersion"]!!.jsonPrimitive.content,
      expectedResponse["createdAt"]!!.jsonPrimitive.long,
    )
    assertEquals(expectedResponse, response)

    assertEquals(
      expectedAck,
      PairingProtocol.validatePairAck(
        expectedAck,
        request,
        verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
        verifiedSenders["chromeDevicePubkey"]!!.jsonPrimitive.content,
        validationNow["ack"]!!.jsonPrimitive.long,
      ),
    )

    assertEquals(
      expectedComplete,
      PairingProtocol.buildPairComplete(
        request,
        verifiedSenders["androidChannelPubkey"]!!.jsonPrimitive.content,
        expectedComplete["createdAt"]!!.jsonPrimitive.long,
      ),
    )
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

  @Test
  fun rejectsOversizedDuplicateKeyAndUnsafeTimeWindows() {
    assertFails { PairingProtocol.validateRequest(" ".repeat(4097), now) }

    val valid = requestJson().toString()
    val duplicateProtocol = valid.replaceFirst(
      "\"protocol\":\"$PAIRING_PROTOCOL\"",
      "\"protocol\":\"$PAIRING_PROTOCOL\",\"protocol\":\"$PAIRING_PROTOCOL\"",
    )
    assertFails { PairingProtocol.validateRequest(duplicateProtocol, now + 1) }
    assertFails { PairingProtocol.validateRequest(requestJson(expiresAt = now).toString(), now) }
    assertFails {
      PairingProtocol.validateRequest(
        requestJson(createdAt = now + 61, expiresAt = now + 120).toString(),
        now,
      )
    }
    assertFails {
      PairingProtocol.validateRequest(
        requestJson(createdAt = now, expiresAt = now + 301).toString(),
        now,
      )
    }
  }

  @Test
  fun rejectsUnsafeAmbiguousAndExcessRelaySets() {
    val unsafe = listOf(
      "ws://relay.example",
      "wss://user:password@relay.example",
      "wss://relay.example?next=wss://attacker.example",
      "wss://relay.example/#fragment",
      "wss://relay.example/%2e%2e/private",
      "wss://relay.example/../private",
      "wss://localhost",
      "wss://relay.local",
      "wss://127.0.0.1",
      "wss://relay.example\u000a",
    )
    unsafe.forEach { relay ->
      assertFails { PairingProtocol.validateRequest(requestJson(relays = listOf(relay)).toString(), now + 1) }
    }

    assertFails {
      PairingProtocol.validateRequest(
        requestJson(relays = (1..9).map { "wss://relay-$it.example" }).toString(),
        now + 1,
      )
    }
    assertFails {
      PairingProtocol.validateRequest(
        requestJson(relays = listOf("wss://relay.example", "wss://RELAY.example:443/")).toString(),
        now + 1,
      )
    }
  }

  @Test
  fun rejectsInvalidKeysCapabilitiesAndRelayDigest() {
    val invalidKey = "ff".repeat(32)
    assertFails {
      PairingProtocol.validateRequest(requestJson(pairingPubkey = invalidKey).toString(), now + 1)
    }
    assertFails {
      PairingProtocol.validateRequest(requestJson(chromeDevicePubkey = invalidKey).toString(), now + 1)
    }
    assertFails {
      PairingProtocol.validateRequest(
        requestJson(capabilities = PAIRING_CAPABILITIES.dropLast(1)).toString(),
        now + 1,
      )
    }
    assertFails {
      PairingProtocol.validateRequest(
        requestJson(capabilities = PAIRING_CAPABILITIES + PAIRING_CAPABILITIES.last()).toString(),
        now + 1,
      )
    }
    assertFails {
      val badDigest = withField(requestJson(), "relaySetDigest", JsonPrimitive("00".repeat(32)))
      PairingProtocol.validateRequest(badDigest.toString(), now + 1)
    }
  }

  @Test
  fun rejectsPrivateCarrierGradeNatAndUniqueLocalDnsAnswers() {
    val relays = request().relays
    listOf("10.0.0.1", "100.64.0.1", "fc00::1").forEach { address ->
      assertFails {
        PairingProtocol.validateResolvedRelayAddresses(relays) {
          arrayOf(InetAddress.getByName(address))
        }
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
