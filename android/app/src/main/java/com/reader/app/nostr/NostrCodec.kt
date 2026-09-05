package com.reader.app.nostr

import android.util.Log
import com.reader.app.core.ReaderCore
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

const val SEAL_KIND = 13
const val WRAP_KIND = 1059
const val AUTH_KIND = 22242
const val OUTER_TTL_SECS = 7 * 86400L
const val MAX_RELAY_FRAME_BYTES = 512 * 1024

data class NostrEvent(
  val id: String,
  val pubkey: String,
  val createdAt: Long,
  val kind: Int,
  val tags: List<List<String>>,
  val content: String,
  val sig: String,
) {
  fun toJson(): String = buildJsonObject {
    put("id", id); put("pubkey", pubkey); put("created_at", createdAt)
    put("kind", kind); put("tags", buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(it) } }) } })
    put("content", content); put("sig", sig)
  }.toString()

  fun frame(): String = "[\"EVENT\",${toJson()}]"
}

object NostrCodec {
  private val random = SecureRandom()
  private val lowerHex64 = Regex("^[0-9a-f]{64}$")
  private val lowerHex128 = Regex("^[0-9a-f]{128}$")

  /** Canonical event id: sha256 of [0,pubkey,created_at,kind,tags,content]. */
  fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
    val canonical = buildJsonArray {
      add(0)
      add(pubkey)
      add(createdAt)
      add(kind)
      add(buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
      })
      add(content)
    }.toString()
    return ReaderCore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
  }

  fun signEvent(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String, seckey: ByteArray): NostrEvent {
    val id = eventId(pubkey, createdAt, kind, tags, content)
    val sig = Secp256k1.schnorrSign(Secp256k1.hexToBytes(id), seckey)
    return NostrEvent(id, pubkey, createdAt, kind, tags, content, Secp256k1.bytesToHex(sig))
  }

  fun verifyEvent(ev: NostrEvent): Boolean {
    if (!lowerHex64.matches(ev.id) || !lowerHex64.matches(ev.pubkey) || !lowerHex128.matches(ev.sig)) return false
    val recomputed = eventId(ev.pubkey, ev.createdAt, ev.kind, ev.tags, ev.content)
    if (recomputed != ev.id) return false
    return try {
      Secp256k1.schnorrVerify(
        Secp256k1.hexToBytes(ev.id),
        Secp256k1.hexToBytes(ev.pubkey),
        Secp256k1.hexToBytes(ev.sig),
      )
    } catch (e: Exception) {
      false
    }
  }

  fun parseEvent(json: String): NostrEvent {
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_RELAY_FRAME_BYTES) { "relay event too large" }
    val o = StrictJson.parseObject(json)
    require(o.keys == setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig")) { "invalid event fields" }
    return NostrEvent(
      id = o["id"]!!.jsonPrimitive.content,
      pubkey = o["pubkey"]!!.jsonPrimitive.content,
      createdAt = o["created_at"]!!.jsonPrimitive.long,
      kind = o["kind"]!!.jsonPrimitive.int,
      tags = o["tags"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } },
      content = o["content"]!!.jsonPrimitive.content,
      sig = o["sig"]!!.jsonPrimitive.content,
    )
  }

  private fun randomDelaySecs(): Long = (SecureRandom().nextInt(172800)).toLong()

  /** Build seal (stable sender key) + wrap (fresh key), NIP-59 layering. */
  fun sealAndWrap(
    senderSeckey: ByteArray,
    recipientPubkeyHex: String,
    payloadJson: String,
    wrapKind: Int = WRAP_KIND,
    expireSecs: Long = OUTER_TTL_SECS,
  ): Pair<NostrEvent, NostrEvent> {
    val now = System.currentTimeMillis() / 1000
    val senderPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(senderSeckey))
    val rumorBase = buildJsonObject {
      put("kind", ReaderCore.RUMOR_KIND); put("created_at", now)
      put("tags", buildJsonArray {}); put("content", payloadJson); put("pubkey", senderPubkey)
    }
    val rumorId = eventId(senderPubkey, now, ReaderCore.RUMOR_KIND, emptyList(), payloadJson)
    val rumorJson = buildJsonObject {
      put("id", rumorId)
      rumorBase.forEach { (key, value) -> put(key, value) }
    }.toString()
    val sealCk = Nip44.getConversationKey(senderSeckey, Secp256k1.hexToBytes(recipientPubkeyHex))
    val sealContent = Nip44.encrypt(rumorJson, sealCk)
    val seal = signEvent(senderPubkey, now - randomDelaySecs(), SEAL_KIND, emptyList(), sealContent, senderSeckey)
    val wrapKey = Secp256k1.randomPrivateKey()
    val wrapPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(wrapKey))
    val wrapCk = Nip44.getConversationKey(wrapKey, Secp256k1.hexToBytes(recipientPubkeyHex))
    val wrapContent = Nip44.encrypt(seal.toJson(), wrapCk)
    val exp = now + expireSecs
    val wrap = signEvent(
      wrapPubkey, now - randomDelaySecs(), wrapKind,
      listOf(listOf("p", recipientPubkeyHex), listOf("expiration", exp.toString())), wrapContent, wrapKey,
    )
    return seal to wrap
  }

  /**
   * Full verification checklist (PROTOCOL.md). Returns rumor content JSON.
   * Throws on ANY violation.
   */
  data class UnwrappedEnvelope(
    val payloadJson: String,
    val senderPubkey: String,
    val recipientPubkey: String,
    val rumorId: String,
  )

  fun unwrapAndVerifyEnvelope(
    wrap: NostrEvent,
    recipientSeckey: ByteArray,
    expectedSenderPubkey: String? = null,
    expectedProtocols: Set<String> = setOf(ReaderCore.READER_PROTOCOL),
    nowSecs: Long = System.currentTimeMillis() / 1000,
  ): UnwrappedEnvelope {
    // reader/2 deliberately uses durable 1059; accepting ephemeral 21059 here
    // would weaken restart recovery and the protocol's wrong-kind boundary.
    require(wrap.kind == WRAP_KIND) { "bad wrap kind" }
    require(verifyEvent(wrap)) { "invalid outer signature" }
    val recipientPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(recipientSeckey))
    val pTags = wrap.tags.filter { it.firstOrNull() == "p" }
    require(pTags.size == 1 && pTags.single().size == 2 && pTags.single()[1] == recipientPubkey) {
      "wrong or missing recipient tag"
    }
    val expirationTags = wrap.tags.filter { it.firstOrNull() == "expiration" }
    require(expirationTags.size == 1 && expirationTags.single().size == 2) { "missing or invalid expiration" }
    val expiration = expirationTags.single()[1].toLongOrNull()
      ?: throw IllegalArgumentException("missing or invalid expiration")
    require(expiration > nowSecs) { "outer event expired" }
    require(wrap.createdAt <= nowSecs + 600) { "outer timestamp in future" }
    val wrapCk = Nip44.getConversationKey(recipientSeckey, Secp256k1.hexToBytes(wrap.pubkey))
    val sealJson = try {
      Nip44.decrypt(wrap.content, wrapCk)
    } catch (e: Exception) {
      throw IllegalArgumentException("wrap decrypt failed")
    }
    val seal = parseEvent(sealJson)
    require(seal.kind == SEAL_KIND) { "bad seal kind" }
    require(seal.tags.isEmpty()) { "seal tags must be empty" }
    require(verifyEvent(seal)) { "invalid seal signature" }
    require(kotlin.math.abs(nowSecs - seal.createdAt) <= 30L * 86400L) { "seal timestamp out of range" }
    val sealCk = Nip44.getConversationKey(recipientSeckey, Secp256k1.hexToBytes(seal.pubkey))
    val rumorJson = try {
      Nip44.decrypt(seal.content, sealCk)
    } catch (e: Exception) {
      throw IllegalArgumentException("seal decrypt failed")
    }
    val rumor = StrictJson.parseObject(rumorJson)
    require(rumor["sig"] == null) { "rumor must not be signed" }
    val rumorPubkey = rumor["pubkey"]!!.jsonPrimitive.content
    require(lowerHex64.matches(rumorPubkey)) { "non-canonical rumor pubkey" }
    val rumorId = rumor["id"]?.jsonPrimitive?.content
      ?: throw IllegalArgumentException("missing rumor id")
    require(lowerHex64.matches(rumorId)) { "non-canonical rumor id" }
    val rumorTags = rumor["tags"]?.jsonArray?.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
      ?: throw IllegalArgumentException("missing rumor tags")
    val expectedRumorId = eventId(
      rumorPubkey,
      rumor["created_at"]!!.jsonPrimitive.long,
      rumor["kind"]!!.jsonPrimitive.int,
      rumorTags,
      rumor["content"]!!.jsonPrimitive.content,
    )
    require(rumorId == expectedRumorId) { "invalid rumor id" }
    require(rumorPubkey == seal.pubkey) { "rumor/seal pubkey mismatch" }
    if (expectedSenderPubkey != null) require(rumorPubkey == expectedSenderPubkey) { "untrusted sender" }
    val content = rumor["content"]!!.jsonPrimitive.content
    val payload = StrictJson.parseObject(content)
    require(payload["protocol"]?.jsonPrimitive?.content in expectedProtocols) { "unsupported protocol version" }
    return UnwrappedEnvelope(content, rumorPubkey, recipientPubkey, rumorId)
  }

  fun unwrapAndVerify(wrap: NostrEvent, recipientSeckey: ByteArray, expectedSenderPubkey: String): String {
    return unwrapAndVerifyEnvelope(wrap, recipientSeckey, expectedSenderPubkey).payloadJson
  }
}

/** Minimal relay client over OkHttp WebSocket: publish w/ OK, subscribe window. */
class RelayClient private constructor(private val http: OkHttpClient) {
  constructor() : this(
    OkHttpClient.Builder().dns(object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(PairingProtocol::isProhibitedAddress)) {
          throw UnknownHostException("relay resolved to a prohibited network")
        }
        return addresses
      }
    }).build(),
  )

  /** Explicit test-only route for a loopback MockWebServer. Never used by the app. */
  internal constructor(allowLocalForTests: Boolean) : this(
    if (allowLocalForTests) OkHttpClient() else throw IllegalArgumentException("test client must opt into local relay mode"),
  )
  data class Health(val url: String, val ok: Boolean, val nip11: String?, val note: String)

  enum class PublishState {
    NORMALIZED,
    CONNECTING,
    OPEN,
    AUTH_CHALLENGE,
    AUTH_SENT,
    EVENT_QUEUED,
    EVENT_SENT,
    OK_TRUE,
    OK_FALSE,
    NO_OK_TIMEOUT,
    SOCKET_ERROR,
    TLS_ERROR,
    CLOSED,
    NOTICE,
    IGNORED_FRAME,
  }

  data class TracePoint(
    val state: PublishState,
    val wallClockUtc: String,
    val monotonicMillis: Long,
    val detail: String? = null,
  )

  data class PublishResult(
    val relayUrl: String,
    val eventId: String,
    val accepted: Boolean,
    val terminalState: PublishState,
    val reasonPrefix: String?,
    val trace: List<TracePoint>,
  )

  private fun normalizedRelayForEvidence(url: String): String {
    return try {
      val uri = URI(url)
      val scheme = uri.scheme?.lowercase() ?: "invalid"
      val host = uri.host?.lowercase() ?: "invalid-host"
      val port = if (uri.port >= 0) ":${uri.port}" else ""
      val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: ""
      "$scheme://$host$port$path"
    } catch (_: Exception) {
      "invalid-relay-url"
    }
  }

  private fun sanitizeReason(value: String?): String? {
    if (value == null) return null
    return value
      .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
      .replace(Regex("(?i)[0-9a-f]{48,}"), "[redacted-hex]")
      .replace(Regex("[A-Za-z0-9_+/=-]{48,}"), "[redacted-token]")
      .trim()
      .take(160)
      .ifEmpty { null }
  }

  private fun challengeFingerprint(challenge: String): String {
    return Secp256k1.bytesToHex(Secp256k1.sha256(challenge.toByteArray(Charsets.UTF_8))).take(16)
  }

  private fun normalizedRelayForAuth(url: String): String {
    val uri = URI(url)
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val port = when {
      uri.port < 0 || (scheme == "wss" && uri.port == 443) || (scheme == "ws" && uri.port == 80) -> ""
      else -> ":${uri.port}"
    }
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    return "$scheme://$host$port$path"
  }

  private fun authEvent(url: String, challenge: String, seckey: ByteArray): NostrEvent {
    require(challenge.length in 1..512) { "invalid relay authentication challenge" }
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(seckey))
    return NostrCodec.signEvent(
      pubkey = pubkey,
      createdAt = System.currentTimeMillis() / 1000,
      kind = AUTH_KIND,
      tags = listOf(listOf("relay", normalizedRelayForAuth(url)), listOf("challenge", challenge)),
      content = "",
      seckey = seckey,
    )
  }

  fun probe(url: String, timeoutSecs: Long = 9): Health {
    return try {
      val httpUrl = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
      val req = Request.Builder().url(httpUrl).header("Accept", "application/nostr+json").build()
      val nip11 = try {
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.take(500) else null }
      } catch (e: Exception) {
        null
      }
      val latch = CountDownLatch(1)
      var opened = false
      val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          opened = true
          latch.countDown()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          latch.countDown()
        }
      })
      latch.await(timeoutSecs, TimeUnit.SECONDS)
      try {
        ws.close(1000, null)
      } catch (e: Exception) {
      }
      if (opened) Health(url, true, nip11, "connect ok") else Health(url, false, nip11, "connect failed")
    } catch (e: Exception) {
      Health(url, false, null, e.message?.take(160) ?: "error")
    }
  }

  /** Publish one event, waiting for the exact matching relay OK. */
  fun publishDetailed(
    url: String,
    event: NostrEvent,
    timeoutSecs: Long = 12,
    authSeckey: ByteArray? = null,
  ): PublishResult {
    val relay = normalizedRelayForEvidence(url)
    val startedNanos = System.nanoTime()
    val trace = Collections.synchronizedList(mutableListOf<TracePoint>())
    val terminal = AtomicReference<PublishState?>(null)
    val reason = AtomicReference<String?>(null)
    fun record(state: PublishState, detail: String? = null) {
      val safeDetail = sanitizeReason(detail)
      val point = TracePoint(
        state = state,
        wallClockUtc = Instant.now().toString(),
        monotonicMillis = (System.nanoTime() - startedNanos) / 1_000_000,
        detail = safeDetail,
      )
      trace.add(point)
      Log.i(
        "NostrReaderRelay",
        "relay=$relay eventId=${event.id} state=${state.name} monotonicMs=${point.monotonicMillis}" +
          (safeDetail?.let { " detail=$it" } ?: ""),
      )
      if (state in setOf(
          PublishState.OK_TRUE,
          PublishState.OK_FALSE,
          PublishState.NO_OK_TIMEOUT,
          PublishState.SOCKET_ERROR,
          PublishState.TLS_ERROR,
          PublishState.CLOSED,
        )
      ) {
        if (terminal.compareAndSet(null, state) && safeDetail != null) reason.compareAndSet(null, safeDetail)
      }
    }

    record(PublishState.NORMALIZED)
    record(PublishState.CONNECTING)
    val latch = CountDownLatch(1)
    val accepted = AtomicReference(false)
    val authenticated = AtomicBoolean(false)
    val authEventId = AtomicReference<String?>(null)
    fun sendOriginal(webSocket: WebSocket) {
      record(PublishState.EVENT_QUEUED)
      if (webSocket.send(event.frame())) {
        record(PublishState.EVENT_SENT, if (authenticated.get()) "after relay authentication" else null)
      } else {
        record(PublishState.SOCKET_ERROR, "websocket send queue rejected frame")
        latch.countDown()
      }
    }
    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        record(PublishState.OPEN, "http=${response.code}")
        sendOriginal(webSocket)
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_RELAY_FRAME_BYTES) {
          record(PublishState.SOCKET_ERROR, "relay frame exceeded 512 KiB")
          webSocket.close(1009, "frame too large")
          latch.countDown()
          return
        }
        try {
          val arr = StrictJson.parse(text).jsonArray
          when (arr.firstOrNull()?.jsonPrimitive?.content) {
            "OK" -> {
              val responseId = arr.getOrNull(1)?.jsonPrimitive?.content
              if (arr.size >= 3 && responseId == authEventId.get()) {
                val ok = arr[2].jsonPrimitive.boolean
                val prefix = arr.getOrNull(3)?.jsonPrimitive?.content
                if (ok) {
                  authenticated.set(true)
                  record(PublishState.AUTH_SENT, "relay authentication accepted")
                  sendOriginal(webSocket)
                } else {
                  record(PublishState.OK_FALSE, "relay authentication rejected: ${prefix.orEmpty()}")
                  latch.countDown()
                }
              } else if (arr.size >= 3 && responseId == event.id) {
                val ok = arr[2].jsonPrimitive.boolean
                val prefix = arr.getOrNull(3)?.jsonPrimitive?.content
                if (!ok && authSeckey != null && !authenticated.get() && prefix.orEmpty().contains("auth-required", ignoreCase = true)) {
                  record(PublishState.AUTH_CHALLENGE, "relay requires authentication")
                } else {
                  accepted.set(ok)
                  record(if (ok) PublishState.OK_TRUE else PublishState.OK_FALSE, prefix)
                  latch.countDown()
                  webSocket.close(1000, null)
                }
              } else {
                record(PublishState.IGNORED_FRAME, "mismatched OK event id")
              }
            }
            "AUTH" -> {
              val challenge = arr.getOrNull(1)?.jsonPrimitive?.content.orEmpty()
              record(PublishState.AUTH_CHALLENGE, "challengeSha256=${challengeFingerprint(challenge)}")
              if (authSeckey != null) {
                val auth = runCatching { authEvent(url, challenge, authSeckey) }.getOrNull()
                if (auth != null) {
                  authEventId.set(auth.id)
                  if (webSocket.send("[\"AUTH\",${auth.toJson()}]")) {
                    record(PublishState.AUTH_SENT)
                  } else {
                    record(PublishState.SOCKET_ERROR, "authentication send queue rejected frame")
                    latch.countDown()
                  }
                }
              }
            }
            "NOTICE" -> record(PublishState.NOTICE, arr.getOrNull(1)?.jsonPrimitive?.content)
            "CLOSED" -> record(PublishState.CLOSED, arr.getOrNull(2)?.jsonPrimitive?.content)
            else -> record(PublishState.IGNORED_FRAME, "unexpected relay frame")
          }
        } catch (_: Exception) {
          record(PublishState.IGNORED_FRAME, "malformed relay frame")
        }
      }
      override fun onClosing(webSocket: WebSocket, code: Int, reasonText: String) {
        record(PublishState.CLOSED, "code=$code ${sanitizeReason(reasonText).orEmpty()}".trim())
        latch.countDown()
      }
      override fun onClosed(webSocket: WebSocket, code: Int, reasonText: String) {
        record(PublishState.CLOSED, "code=$code ${sanitizeReason(reasonText).orEmpty()}".trim())
        latch.countDown()
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val isTls = generateSequence(t as Throwable?) { it.cause }.any { it is SSLException }
        record(if (isTls) PublishState.TLS_ERROR else PublishState.SOCKET_ERROR, t.javaClass.simpleName)
        latch.countDown()
      }
    })
    val completed = latch.await(timeoutSecs, TimeUnit.SECONDS)
    if (!completed && terminal.get() == null) record(PublishState.NO_OK_TIMEOUT, "timeoutSeconds=$timeoutSecs")
    try {
      ws.close(1000, null)
    } catch (_: Exception) {
    }
    return PublishResult(
      relayUrl = relay,
      eventId = event.id,
      accepted = accepted.get(),
      terminalState = terminal.get() ?: PublishState.NO_OK_TIMEOUT,
      reasonPrefix = reason.get(),
      trace = synchronized(trace) { trace.toList() },
    )
  }

  /** Compatibility facade retained while the pairing state machine is repaired. */
  fun publish(url: String, event: NostrEvent, timeoutSecs: Long = 12, authSeckey: ByteArray? = null): Boolean {
    return publishDetailed(url, event, timeoutSecs, authSeckey).accepted
  }

  /** Subscribe with a rolling-window filter; collects EVENTs for [collectSecs]. */
  fun subscribe(
    url: String,
    recipientPubkey: String,
    since: Long,
    kinds: List<Int>,
    collectSecs: Long = 25,
    authSeckey: ByteArray? = null,
  ): List<NostrEvent> {
    val out = Collections.synchronizedList(mutableListOf<NostrEvent>())
    val latch = CountDownLatch(1)
    val subId = "reader-" + SecureRandom().nextInt(1_000_000)
    val authEventId = AtomicReference<String?>(null)
    val filter = buildJsonObject {
      put("kinds", buildJsonArray { kinds.forEach { add(it) } })
      put("#p", buildJsonArray { add(recipientPubkey) })
      put("since", since)
    }
    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      private fun request(webSocket: WebSocket) {
        webSocket.send("[\"REQ\",\"$subId\",${filter}]")
      }

      override fun onOpen(webSocket: WebSocket, response: Response) {
        request(webSocket)
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_RELAY_FRAME_BYTES) {
          webSocket.close(1009, "frame too large")
          latch.countDown()
          return
        }
        try {
          val arr = StrictJson.parse(text).jsonArray
          if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT" && arr[1].jsonPrimitive.content == subId) {
            out.add(NostrCodec.parseEvent(arr[2].toString()))
          } else if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
            // keep socket open for live events until timeout
          } else if (arr.size >= 2 && arr[0].jsonPrimitive.content == "AUTH" && authSeckey != null) {
            val challenge = arr[1].jsonPrimitive.content
            val auth = authEvent(url, challenge, authSeckey)
            authEventId.set(auth.id)
            webSocket.send("[\"AUTH\",${auth.toJson()}]")
          } else if (
            arr.size >= 3 && arr[0].jsonPrimitive.content == "OK" &&
            arr[1].jsonPrimitive.content == authEventId.get() && arr[2].jsonPrimitive.boolean
          ) {
            request(webSocket)
          }
        } catch (e: Exception) {
        }
      }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        latch.countDown()
      }
    })
    latch.await(collectSecs, TimeUnit.SECONDS)
    try {
      ws.send("[\"CLOSE\",\"$subId\"]")
      ws.close(1000, null)
    } catch (e: Exception) {
    }
    return synchronized(out) { out.toList() }
  }
}
