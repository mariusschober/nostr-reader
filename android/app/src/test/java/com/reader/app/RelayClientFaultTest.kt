package com.reader.app

import com.reader.app.nostr.MAX_RELAY_FRAME_BYTES
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClientFaultTest {
  private fun event(): NostrEvent {
    val key = Secp256k1.randomPrivateKey()
    val pubkey = Secp256k1.bytesToHex(Secp256k1.getPublicKey(key))
    return NostrCodec.signEvent(pubkey, System.currentTimeMillis() / 1000, 1059, emptyList(), "synthetic", key)
  }

  private fun runCase(
    timeoutSecs: Long = 2,
    response: (WebSocket, NostrEvent) -> Unit,
  ): RelayClient.PublishResult {
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = runCatching { StrictJson.parse(text).jsonArray }.getOrNull() ?: return
        if (frame.firstOrNull()?.jsonPrimitive?.content == "EVENT") {
          response(webSocket, NostrCodec.parseEvent(frame[1].toString()))
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
      }
    }))
    server.start()
    return try {
      val url = server.url("/").toString().replaceFirst("http", "ws")
      RelayClient(allowLocalForTests = true).publishDetailed(url, event(), timeoutSecs)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun exactOkTrueAndFalseRemainDistinct() {
    val accepted = runCase { socket, event -> socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]") }
    assertTrue(accepted.accepted)
    assertEquals(RelayClient.PublishState.OK_TRUE, accepted.terminalState)

    val rejected = runCase { socket, event -> socket.send("[\"OK\",\"${event.id}\",false,\"blocked: policy\"]") }
    assertFalse(rejected.accepted)
    assertEquals(RelayClient.PublishState.OK_FALSE, rejected.terminalState)
    assertEquals("blocked: policy", rejected.reasonPrefix)
  }

  @Test
  fun firstMatchingOkIsTerminalAndContradictoryDuplicatesCannotFlipAcceptance() {
    val accepted = runCase { socket, event ->
      socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]")
      socket.send("[\"OK\",\"${event.id}\",false,\"contradiction\"]")
    }
    assertTrue(accepted.accepted)
    assertEquals(RelayClient.PublishState.OK_TRUE, accepted.terminalState)
    assertFalse(accepted.trace.any { it.state == RelayClient.PublishState.OK_FALSE })

    val rejected = runCase { socket, event ->
      socket.send("[\"OK\",\"${event.id}\",false,\"blocked: policy\"]")
      socket.send("[\"OK\",\"${event.id}\",true,\"contradiction\"]")
    }
    assertFalse(rejected.accepted)
    assertEquals(RelayClient.PublishState.OK_FALSE, rejected.terminalState)
    assertFalse(rejected.trace.any { it.state == RelayClient.PublishState.OK_TRUE })
  }

  @Test
  fun absentAndMismatchedOkBecomeTimeoutNotRejection() {
    val absent = runCase(timeoutSecs = 1) { _, _ -> }
    assertEquals(RelayClient.PublishState.NO_OK_TIMEOUT, absent.terminalState)

    val mismatched = runCase(timeoutSecs = 1) { socket, _ ->
      socket.send("[\"OK\",\"${"00".repeat(32)}\",true,\"wrong id\"]")
    }
    assertEquals(RelayClient.PublishState.NO_OK_TIMEOUT, mismatched.terminalState)
    assertTrue(mismatched.trace.any { it.state == RelayClient.PublishState.IGNORED_FRAME })
  }

  @Test
  fun delayedDuplicateNoticeMalformedAndBinaryFramesDoNotForgeFailure() {
    val delayed = runCase { socket, event ->
      Thread {
        Thread.sleep(25)
        socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]")
      }.start()
    }
    assertEquals(RelayClient.PublishState.OK_TRUE, delayed.terminalState)

    val duplicate = runCase { socket, event ->
      socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]")
      socket.send("[\"OK\",\"${event.id}\",true,\"duplicate\"]")
    }
    assertEquals(RelayClient.PublishState.OK_TRUE, duplicate.terminalState)

    val notice = runCase { socket, event ->
      socket.send("[\"NOTICE\",\"synthetic notice\"]")
      socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]")
    }
    assertEquals(RelayClient.PublishState.OK_TRUE, notice.terminalState)
    assertTrue(notice.trace.any { it.state == RelayClient.PublishState.NOTICE })

    val malformed = runCase { socket, event ->
      socket.send("{")
      socket.send(okio.ByteString.of(0xff.toByte(), 0x00))
      socket.send("[\"OK\",\"${event.id}\",true,\"saved\"]")
    }
    assertEquals(RelayClient.PublishState.OK_TRUE, malformed.terminalState)
    assertTrue(malformed.trace.any { it.state == RelayClient.PublishState.IGNORED_FRAME })
  }

  @Test
  fun oversizedRelayFrameIsSocketFailureAndNeverAcceptance() {
    val oversized = runCase { socket, _ ->
      socket.send("[\"NOTICE\",\"${"x".repeat(MAX_RELAY_FRAME_BYTES)}\"]")
    }
    assertFalse(oversized.accepted)
    assertEquals(RelayClient.PublishState.SOCKET_ERROR, oversized.terminalState)
    assertTrue(oversized.reasonPrefix.orEmpty().contains("512 KiB"))
  }

  @Test
  fun productionClientRejectsLoopbackAtActualDnsResolution() {
    val result = RelayClient().publishDetailed("ws://127.0.0.1:9", event(), 1)
    assertFalse(result.accepted)
    assertEquals(RelayClient.PublishState.SOCKET_ERROR, result.terminalState)
  }
}
