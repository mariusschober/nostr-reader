package com.reader.app

import com.reader.app.nostr.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RelayReceiveTest {
  @Test fun deliversOnArrivalAndClosesOnInterruption() {
    val received = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val server = MockWebServer()
    val key = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(key))
    val event = NostrCodec.signEvent(pubkey, 1, 1059, emptyList(), "synthetic", key)
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(socket: WebSocket, text: String) {
        val frame = StrictJson.parse(text).jsonArray
        if (frame[0].jsonPrimitive.content == "REQ") {
          val id = frame[1].jsonPrimitive.content
          socket.send("[\"EVENT\",\"$id\",${event.toJson()}]")
          socket.send("[\"EOSE\",\"$id\"]")
        }
      }
      override fun onClosing(socket: WebSocket, code: Int, reason: String) { closed.countDown(); socket.close(code, reason) }
      override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
    }))
    server.start()
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit {
        RelayClient(allowLocalForTests = true).subscribe(server.url("/").toString().replaceFirst("http", "ws"), pubkey, 0, listOf(1059), 60) {
          assertEquals(event.id, it.id)
          received.countDown()
        }
      }
      assertTrue("arrival cannot wait for the 60 second collection window", received.await(2, TimeUnit.SECONDS))
      future.cancel(true)
      assertTrue("interruption must close its socket", closed.await(2, TimeUnit.SECONDS))
    } finally { executor.shutdownNow(); server.shutdown() }
  }

  @Test fun closedSubscriptionIsNotHealthyEmptySuccess() {
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(socket: WebSocket, text: String) {
        val frame = StrictJson.parse(text).jsonArray
        if (frame[0].jsonPrimitive.content == "REQ") socket.send("[\"CLOSED\",${frame[1]},\"restricted\"]")
      }
    }))
    server.start()
    try {
      try {
        RelayClient(allowLocalForTests = true).subscribe(server.url("/").toString().replaceFirst("http", "ws"), "00".repeat(32), 0, listOf(1059), 1)
        fail("closed subscription must fail")
      } catch (_: java.io.IOException) {}
    } finally { server.shutdown() }
  }

  @Test fun deeplyNestedJsonIsRejectedBeforeRecursiveParserExhaustion() {
    try { StrictJson.parse("[".repeat(1000) + "0" + "]".repeat(1000)); fail("nesting limit") }
    catch (_: IllegalArgumentException) {}
  }

  @Test fun cancelledPublicationClosesSocketBeforeReturning() {
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(socket: WebSocket, text: String) { opened.countDown() }
      override fun onClosing(socket: WebSocket, code: Int, reason: String) { closed.countDown(); socket.close(code, reason) }
      override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
    }))
    server.start()
    val executor = Executors.newSingleThreadExecutor()
    try {
      val key = Secp256k1.randomPrivateKey()
      val event = NostrCodec.signEvent(Secp256k1.bytesToHex(Secp256k1.getPublicKey(key)), 1, 1059, emptyList(), "synthetic", key)
      val future = executor.submit { RelayClient(true).publishDetailed(server.url("/").toString().replaceFirst("http", "ws"), event, 60) }
      assertTrue(opened.await(2, TimeUnit.SECONDS))
      future.cancel(true)
      assertTrue("cancelled publication leaked its socket", closed.await(2, TimeUnit.SECONDS))
    } finally { executor.shutdownNow(); server.shutdown() }
  }

  @Test fun earlySocketCloseAfterEoseIsDegradedNotHealthy() {
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(socket: WebSocket, text: String) {
        val frame = StrictJson.parse(text).jsonArray
        if (frame[0].jsonPrimitive.content == "REQ") {
          socket.send("[\"EOSE\",${frame[1]}]")
          socket.close(1000, "synthetic disconnection")
        }
      }
    }))
    server.start()
    try {
      try {
        RelayClient(true).subscribe(server.url("/").toString().replaceFirst("http", "ws"), "00".repeat(32), 0, listOf(1059), 1)
        fail("early close must report degraded visible receive coverage")
      } catch (_: java.io.IOException) {}
    } finally { server.shutdown() }
  }
}
