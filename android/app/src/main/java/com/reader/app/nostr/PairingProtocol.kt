package com.reader.app.nostr

import com.reader.app.core.ReaderCore
import kotlinx.serialization.json.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

const val PAIRING_PROTOCOL = "reader-pair/2"
const val PAIRING_MAX_BYTES = 4096
const val PAIRING_MAX_LIFETIME_SECS = 300L
const val PAIRING_MESSAGE_TTL_SECS = 600L

/** Must match shared/default-relays.json and Chrome's protocol/relays.ts. */
val READER_DEFAULT_RELAYS = listOf(
  "wss://nos.lol",
  "wss://relay.primal.net",
  "wss://relay.nostr.net",
  "wss://nostr.oxtr.dev",
  "wss://offchain.pub",
  "wss://nostr-pub.wellorder.net",
)
const val READER_RELAY_WRITE_QUORUM = 2

val PAIRING_CAPABILITIES = listOf(
  "durable-pair-response",
  "pair-ack-v2",
  "pair-complete-v2",
  "endpoint-ack-v2",
  "gzip",
)

data class ValidatedPairingRequest(
  val json: JsonObject,
  val sessionId: String,
  val pairingPubkey: String,
  val chromeDevicePubkey: String,
  val nonce: String,
  val relays: List<String>,
  val relaySetDigest: String,
  val createdAt: Long,
  val expiresAt: Long,
  val capabilities: Set<String>,
)

/** Strict, network-free QR validation. DNS destinations are checked before connect. */
object PairingProtocol {
  private val exactFields = setOf(
    "protocol",
    "sessionId",
    "pairingPubkey",
    "chromeDevicePubkey",
    "nonce",
    "relays",
    "relaySetDigest",
    "createdAt",
    "expiresAt",
    "capabilities",
  )
  private val requiredCapabilities = PAIRING_CAPABILITIES.toSet()
  private val lowerHex32 = Regex("^[0-9a-f]{32}$")
  private val lowerHex64 = Regex("^[0-9a-f]{64}$")
  private val capabilityName = Regex("^[a-z0-9][a-z0-9-]{0,47}$")

  fun relaySetDigest(relays: List<String>): String {
    val canonical = JsonArray(relays.map(::JsonPrimitive)).toString()
    return ReaderCore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
  }

  fun normalizeRelayUrl(raw: String, allowDeveloperLocalRelay: Boolean = false): String {
    require(raw.length in 1..512 && raw.none { it.code < 0x20 || it.code == 0x7f }) { "invalid relay URL length" }
    val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("invalid relay URL") }
    require(uri.scheme?.lowercase() == if (allowDeveloperLocalRelay) uri.scheme?.lowercase() else "wss") {
      "relay URL must use wss"
    }
    if (allowDeveloperLocalRelay) require(uri.scheme.equals("wss", true) || uri.scheme.equals("ws", true)) {
      "relay URL must use WebSocket"
    }
    require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "relay URL has forbidden components" }
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("relay URL has invalid host")
    require(host.length <= 253 && !host.endsWith('.')) { "relay host is invalid" }
    require(uri.port in -1..65535 && uri.port != 0) { "relay port is invalid" }
    val rawPath = uri.rawPath.orEmpty()
    require(rawPath.length <= 128 && !rawPath.contains("%") && rawPath.split('/').none { it == "." || it == ".." }) {
      "relay path is ambiguous"
    }
    if (!allowDeveloperLocalRelay) {
      require(host != "localhost" && !host.endsWith(".local")) { "local relay host is prohibited" }
      require(!host.contains(':') && !host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) {
        "numeric relay hosts are prohibited"
      }
    }
    val port = when {
      uri.port < 0 -> ""
      uri.port == 443 && uri.scheme.equals("wss", true) -> ""
      else -> ":${uri.port}"
    }
    val path = if (rawPath.isEmpty() || rawPath == "/") "" else rawPath.trimEnd('/')
    return "${uri.scheme.lowercase()}://$host$port$path"
  }

  internal fun isProhibitedAddress(address: InetAddress): Boolean {
    if (
      address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress
    ) return true
    val bytes = address.address
    if (address is Inet4Address && bytes.size == 4) {
      val first = bytes[0].toInt() and 0xff
      val second = bytes[1].toInt() and 0xff
      // Carrier-grade NAT is not covered by InetAddress.isSiteLocalAddress.
      if (first == 100 && second in 64..127) return true
    }
    if (address is Inet6Address && bytes.size == 16) {
      // fc00::/7 unique-local addresses.
      if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
    }
    return false
  }

  /** Resolve before WebSocket creation and reject if any answer is non-public. */
  fun validateResolvedRelayAddresses(
    relays: List<String>,
    resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ) {
    for (relay in relays) {
      val host = URI(relay).host ?: throw IllegalArgumentException("relay URL has invalid host")
      val addresses = runCatching { resolver(host) }
        .getOrElse { throw IllegalArgumentException("relay DNS lookup failed") }
      require(addresses.isNotEmpty() && addresses.none(::isProhibitedAddress)) { "relay resolves to a prohibited network" }
    }
  }

  fun validateRequest(
    text: String,
    nowSecs: Long,
    allowDeveloperLocalRelay: Boolean = false,
  ): ValidatedPairingRequest {
    require(text.toByteArray(Charsets.UTF_8).size <= PAIRING_MAX_BYTES) { "pairing code is too large" }
    val json = runCatching { StrictJson.parseObject(text) }
      .getOrElse { throw IllegalArgumentException("pairing code is not a JSON object") }
    require(json.keys == exactFields) { "pairing code fields do not match protocol" }
    require(json["protocol"]?.jsonPrimitive?.content == PAIRING_PROTOCOL) { "unsupported pairing protocol" }

    val sessionId = json["sessionId"]!!.jsonPrimitive.content
    val pairingPubkey = json["pairingPubkey"]!!.jsonPrimitive.content
    val chromeDevicePubkey = json["chromeDevicePubkey"]!!.jsonPrimitive.content
    val nonce = json["nonce"]!!.jsonPrimitive.content
    require(lowerHex32.matches(sessionId)) { "invalid session id" }
    require(lowerHex64.matches(nonce)) { "invalid pairing nonce" }
    for ((name, key) in listOf("pairing" to pairingPubkey, "Chrome device" to chromeDevicePubkey)) {
      require(lowerHex64.matches(key)) { "invalid $name public key" }
      require(Secp256k1.isValidXOnlyPublicKey(Secp256k1.hexToBytes(key))) { "invalid $name public key" }
    }

    val createdAt = json["createdAt"]!!.jsonPrimitive.long
    val expiresAt = json["expiresAt"]!!.jsonPrimitive.long
    require(createdAt in (nowSecs - PAIRING_MAX_LIFETIME_SECS)..(nowSecs + 60)) { "pairing creation time is out of range" }
    require(expiresAt > nowSecs) { "pairing code expired; refresh it in Chrome" }
    require(expiresAt > createdAt && expiresAt - createdAt <= PAIRING_MAX_LIFETIME_SECS) { "pairing expiry is out of range" }

    val rawRelays = json["relays"]!!.jsonArray.map { it.jsonPrimitive.content }
    require(rawRelays.size in 1..8) { "pairing requires 1 to 8 relays" }
    val relays = rawRelays.map { normalizeRelayUrl(it, allowDeveloperLocalRelay) }
    require(relays.toSet().size == relays.size) { "duplicate relay URL" }
    val digest = json["relaySetDigest"]!!.jsonPrimitive.content
    require(lowerHex64.matches(digest) && digest == relaySetDigest(relays)) { "relay set digest mismatch" }

    val capabilities = json["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
    require(capabilities.size in 1..8 && capabilities.all { capabilityName.matches(it) }) { "invalid capabilities" }
    require(capabilities.toSet().size == capabilities.size) { "duplicate capability" }
    require(capabilities.containsAll(requiredCapabilities)) { "required pairing capabilities are missing" }

    val normalizedJson = buildJsonObject {
      json.forEach { (key, value) -> put(key, if (key == "relays") JsonArray(relays.map(::JsonPrimitive)) else value) }
    }
    return ValidatedPairingRequest(
      json = normalizedJson,
      sessionId = sessionId,
      pairingPubkey = pairingPubkey,
      chromeDevicePubkey = chromeDevicePubkey,
      nonce = nonce,
      relays = relays,
      relaySetDigest = digest,
      createdAt = createdAt,
      expiresAt = expiresAt,
      capabilities = capabilities.toSet(),
    )
  }

  fun buildPairResponse(
    request: ValidatedPairingRequest,
    androidChannelPubkey: String,
    appVersion: String,
    nowSecs: Long,
  ): JsonObject {
    require(lowerHex64.matches(androidChannelPubkey)) { "invalid Android channel public key" }
    require(Secp256k1.isValidXOnlyPublicKey(Secp256k1.hexToBytes(androidChannelPubkey))) { "invalid Android channel public key" }
    require(appVersion.matches(Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$"))) { "invalid app version" }
    require(nowSecs <= request.expiresAt) { "pairing request expired" }
    return buildJsonObject {
      put("protocol", PAIRING_PROTOCOL)
      put("type", "pair-response")
      put("sessionId", request.sessionId)
      put("nonce", request.nonce)
      put("recipientPairingPubkey", request.pairingPubkey)
      put("chromeDevicePubkey", request.chromeDevicePubkey)
      put("androidChannelPubkey", androidChannelPubkey)
      put("relaySetDigest", request.relaySetDigest)
      put("appVersion", appVersion)
      put("capabilities", JsonArray(PAIRING_CAPABILITIES.map(::JsonPrimitive)))
      put("createdAt", nowSecs)
      put("expiresAt", nowSecs + PAIRING_MESSAGE_TTL_SECS)
    }
  }

  fun validatePairAck(
    value: JsonObject,
    request: ValidatedPairingRequest,
    expectedAndroidChannelPubkey: String,
    verifiedInnerSenderPubkey: String,
    nowSecs: Long,
  ): JsonObject {
    val keys = setOf(
      "protocol", "type", "sessionId", "chromeDevicePubkey", "androidChannelPubkey",
      "relaySetDigest", "acceptedRelays", "status", "createdAt", "expiresAt",
    )
    require(value.keys == keys) { "pair ACK fields do not match protocol" }
    require(value["protocol"]?.jsonPrimitive?.content == PAIRING_PROTOCOL && value["type"]?.jsonPrimitive?.content == "pair-ack") {
      "wrong pairing ACK type"
    }
    require(value["status"]?.jsonPrimitive?.content == "accepted") { "pairing was not accepted" }
    require(value["sessionId"]?.jsonPrimitive?.content == request.sessionId) { "pairing session mismatch" }
    require(value["chromeDevicePubkey"]?.jsonPrimitive?.content == request.chromeDevicePubkey) { "Chrome device mismatch" }
    require(value["androidChannelPubkey"]?.jsonPrimitive?.content == expectedAndroidChannelPubkey) { "Android channel mismatch" }
    require(value["relaySetDigest"]?.jsonPrimitive?.content == request.relaySetDigest) { "relay set mismatch" }
    require(verifiedInnerSenderPubkey == request.chromeDevicePubkey) { "untrusted pairing ACK sender" }
    val acceptedRelays = value["acceptedRelays"]!!.jsonArray.map { normalizeRelayUrl(it.jsonPrimitive.content) }
    require(acceptedRelays.isNotEmpty() && acceptedRelays.toSet().size == acceptedRelays.size) { "invalid accepted relay set" }
    require(acceptedRelays.all { it in request.relays }) { "accepted relay not in pairing request" }
    val createdAt = value["createdAt"]!!.jsonPrimitive.long
    val expiresAt = value["expiresAt"]!!.jsonPrimitive.long
    require(
      createdAt >= request.createdAt - 60 && createdAt <= nowSecs + 60 &&
        expiresAt > nowSecs && expiresAt <= createdAt + PAIRING_MESSAGE_TTL_SECS,
    ) {
      "pairing ACK expired or timestamp invalid"
    }
    return value
  }

  fun buildPairComplete(
    request: ValidatedPairingRequest,
    androidChannelPubkey: String,
    nowSecs: Long,
  ): JsonObject = buildJsonObject {
    put("protocol", PAIRING_PROTOCOL)
    put("type", "pair-complete")
    put("sessionId", request.sessionId)
    put("chromeDevicePubkey", request.chromeDevicePubkey)
    put("androidChannelPubkey", androidChannelPubkey)
    put("relaySetDigest", request.relaySetDigest)
    put("status", "connected")
    put("createdAt", nowSecs)
    put("expiresAt", nowSecs + PAIRING_MESSAGE_TTL_SECS)
  }
}
