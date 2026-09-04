package com.reader.app.nostr

import com.reader.app.core.ReaderCore
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val SEAL_KIND = 13
const val WRAP_KIND = 1059
const val WRAP_KIND_EPHEMERAL = 21059
const val OUTER_TTL_SECS = 7 * 86400L

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

  /** Canonical event id: sha256 of [0,pubkey,created_at,kind,tags,content]. */
  fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
    val tagsJson = tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } }
    val canonical = "[0,\"$pubkey\",$createdAt,$kind,$tagsJson,\"${content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"]"
    return ReaderCore.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
  }

  fun signEvent(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String, seckey: ByteArray): NostrEvent {
    val id = eventId(pubkey, createdAt, kind, tags, content)
    val sig = Secp256k1.schnorrSign(Secp256k1.hexToBytes(id), seckey)
    return NostrEvent(id, pubkey, createdAt, kind, tags, content, Secp256k1.bytesToHex(sig))
  }

  fun verifyEvent(ev: NostrEvent): Boolean {
    val recomputed = eventId(ev.pubkey, ev.createdAt, ev.kind, ev.tags, ev.content)
    if (!recomputed.equals(ev.id, ignoreCase = true)) return false
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
    val o = Json.parseToJsonElement(json).jsonObject
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
    val rumorJson = buildJsonObject {
      put("kind", ReaderCore.RUMOR_KIND); put("created_at", now)
      put("tags", buildJsonArray {}); put("content", payloadJson); put("pubkey", senderPubkey)
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
      listOf(listOf("expiration", exp.toString())), wrapContent, wrapKey,
    )
    return seal to wrap
  }

  /**
   * Full verification checklist (PROTOCOL.md). Returns rumor content JSON.
   * Throws on ANY violation.
   */
  fun unwrapAndVerify(wrap: NostrEvent, recipientSeckey: ByteArray, expectedSenderPubkey: String): String {
    require(wrap.kind == WRAP_KIND || wrap.kind == WRAP_KIND_EPHEMERAL) { "bad wrap kind" }
    require(verifyEvent(wrap)) { "invalid outer signature" }
    val wrapCk = Nip44.getConversationKey(recipientSeckey, Secp256k1.hexToBytes(wrap.pubkey))
    val sealJson = try {
      Nip44.decrypt(wrap.content, wrapCk)
    } catch (e: Exception) {
      throw IllegalArgumentException("wrap decrypt failed")
    }
    val seal = parseEvent(sealJson)
    require(seal.kind == SEAL_KIND) { "bad seal kind" }
    require(verifyEvent(seal)) { "invalid seal signature" }
    val sealCk = Nip44.getConversationKey(recipientSeckey, Secp256k1.hexToBytes(seal.pubkey))
    val rumorJson = try {
      Nip44.decrypt(seal.content, sealCk)
    } catch (e: Exception) {
      throw IllegalArgumentException("seal decrypt failed")
    }
    val rumor = Json.parseToJsonElement(rumorJson).jsonObject
    val rumorPubkey = rumor["pubkey"]!!.jsonPrimitive.content
    require(rumorPubkey.equals(seal.pubkey, ignoreCase = true)) { "rumor/seal pubkey mismatch" }
    require(rumorPubkey.equals(expectedSenderPubkey, ignoreCase = true)) { "untrusted sender" }
    val content = rumor["content"]!!.jsonPrimitive.content
    val payload = Json.parseToJsonElement(content).jsonObject
    require(payload["protocol"]?.jsonPrimitive?.content == ReaderCore.READER_PROTOCOL) { "unsupported protocol version" }
    return content
  }
}

/** Minimal relay client over OkHttp WebSocket: publish w/ OK, subscribe window. */
class RelayClient(private val http: OkHttpClient = OkHttpClient()) {
  data class Health(val url: String, val ok: Boolean, val nip11: String?, val note: String)

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

  /** Publish one event, waiting for the relay OK. Returns relay message. */
  fun publish(url: String, event: NostrEvent, timeoutSecs: Long = 12): Boolean {
    val latch = CountDownLatch(1)
    var accepted = false
    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(event.frame())
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        try {
          val arr = Json.parseToJsonElement(text).jsonArray
          if (arr.size >= 3 && arr[0].jsonPrimitive.content == "OK" && arr[1].jsonPrimitive.content.equals(event.id, ignoreCase = true)) {
            accepted = arr[2].jsonPrimitive.boolean
            latch.countDown()
            webSocket.close(1000, null)
          }
        } catch (e: Exception) {
        }
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
    return accepted
  }

  /** Subscribe with a rolling-window filter; collects EVENTs for [collectSecs]. */
  fun subscribe(url: String, recipientPubkey: String, since: Long, kinds: List<Int>, collectSecs: Long = 25): List<NostrEvent> {
    val out = mutableListOf<NostrEvent>()
    val latch = CountDownLatch(1)
    val subId = "reader-" + SecureRandom().nextInt(1_000_000)
    val filter = buildJsonObject {
      put("kinds", buildJsonArray { kinds.forEach { add(it) } })
      put("#p", buildJsonArray { add(recipientPubkey) })
      put("since", since)
    }
    val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("[\"REQ\",\"$subId\",${filter}]")
      }
      override fun onMessage(webSocket: WebSocket, text: String) {
        try {
          val arr = Json.parseToJsonElement(text).jsonArray
          if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT" && arr[1].jsonPrimitive.content == subId) {
            out.add(NostrCodec.parseEvent(arr[2].toString()))
          } else if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
            // keep socket open for live events until timeout
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
    return out
  }
}
