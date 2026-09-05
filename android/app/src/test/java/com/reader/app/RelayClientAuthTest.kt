package com.reader.app

import com.reader.app.nostr.AUTH_KIND
import com.reader.app.nostr.NostrCodec
import com.reader.app.nostr.NostrEvent
import com.reader.app.nostr.RelayClient
import com.reader.app.nostr.Secp256k1
import com.reader.app.nostr.StrictJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RelayClientAuthTest {
  @Test
  fun socketCloseBeforeMatchingOkIsClosedNotTimeoutOrRejection() {
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        webSocket.close(1000, "test close")
      }
    }))
    server.start()
    try {
      val url = server.url("/").toString().replaceFirst("http", "ws")
      val key = Secp256k1.randomPrivateKey()
      val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(key))
      val event = NostrCodec.signEvent(pubkey, System.currentTimeMillis() / 1000, 1, emptyList(), "test", key)
      val result = RelayClient(allowLocalForTests = true).publishDetailed(url, event, 2)
      assertFalse(result.accepted)
      assertEquals(RelayClient.PublishState.CLOSED, result.terminalState)
      assertTrue(result.trace.any { it.state == RelayClient.PublishState.EVENT_SENT })
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun challengedPublishAuthenticatesWithPseudonymousKeyAndRetries() {
    val server = MockWebServer()
    val challenge = "test-challenge"
    val authenticated = AtomicBoolean(false)
    val authEvent = AtomicReference<NostrEvent?>()
    val callbackError = AtomicReference<Throwable?>()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("[\"AUTH\",\"$challenge\"]")
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        try {
          val frame = StrictJson.parse(text).jsonArray
          when (frame[0].jsonPrimitive.content) {
            "AUTH" -> {
              val event = NostrCodec.parseEvent(frame[1].toString())
              authEvent.set(event)
              require(event.kind == AUTH_KIND && NostrCodec.verifyEvent(event))
              require(event.tags.any { it == listOf("challenge", challenge) })
              authenticated.set(true)
              webSocket.send("[\"OK\",\"${event.id}\",true,\"\"]")
            }
            "EVENT" -> {
              val event = NostrCodec.parseEvent(frame[1].toString())
              webSocket.send(
                if (authenticated.get()) "[\"OK\",\"${event.id}\",true,\"\"]"
                else "[\"OK\",\"${event.id}\",false,\"auth-required: test relay\"]",
              )
            }
          }
        } catch (error: Throwable) {
          callbackError.set(error)
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
      }
    }))
    server.start()
    try {
      val url = server.url("/").toString().replaceFirst("http", "ws")
      val authKey = Secp256k1.randomPrivateKey()
      val eventKey = Secp256k1.randomPrivateKey()
      val eventPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(eventKey))
      val event = NostrCodec.signEvent(eventPubkey, System.currentTimeMillis() / 1000, 1, emptyList(), "test", eventKey)
      val result = RelayClient(allowLocalForTests = true).publishDetailed(url, event, 4, authKey)
      assertNull(callbackError.get())
      assertTrue(result.accepted)
      assertTrue(result.trace.any { it.state == RelayClient.PublishState.AUTH_CHALLENGE })
      assertTrue(result.trace.any { it.state == RelayClient.PublishState.AUTH_SENT })
      assertEquals(Secp256k1.bytesToHex(Secp256k1.getPublicKey(authKey)), authEvent.get()!!.pubkey)
      assertTrue(authEvent.get()!!.tags.any { it.firstOrNull() == "relay" && it[1] == url })
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun challengedSubscriptionAuthenticatesAndRepeatsRequest() {
    val server = MockWebServer()
    val challenge = "read-challenge"
    val authenticated = AtomicBoolean(false)
    val authKey = Secp256k1.randomPrivateKey()
    val recipient = Secp256k1.bytesToHex(Secp256k1.getPublicKey(authKey))
    val eventKey = Secp256k1.randomPrivateKey()
    val eventPubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(eventKey))
    val event = NostrCodec.signEvent(
      eventPubkey,
      System.currentTimeMillis() / 1000,
      1059,
      listOf(listOf("p", recipient)),
      "opaque",
      eventKey,
    )
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("[\"AUTH\",\"$challenge\"]")
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = StrictJson.parse(text).jsonArray
        when (frame[0].jsonPrimitive.content) {
          "AUTH" -> {
            val auth = NostrCodec.parseEvent(frame[1].toString())
            authenticated.set(true)
            webSocket.send("[\"OK\",\"${auth.id}\",true,\"\"]")
          }
          "REQ" -> {
            val subId = frame[1].jsonPrimitive.content
            if (authenticated.get()) {
              webSocket.send("[\"EVENT\",\"$subId\",${event.toJson()}]")
              webSocket.send("[\"EOSE\",\"$subId\"]")
            } else {
              webSocket.send("[\"CLOSED\",\"$subId\",\"auth-required: read\"]")
            }
          }
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
      }
    }))
    server.start()
    try {
      val url = server.url("/").toString().replaceFirst("http", "ws")
      val events = RelayClient(allowLocalForTests = true).subscribe(url, recipient, 0, listOf(1059), 1, authKey)
      assertTrue(authenticated.get())
      assertEquals(listOf(event.id), events.map { it.id }.distinct())
    } finally {
      server.shutdown()
    }
  }
}
