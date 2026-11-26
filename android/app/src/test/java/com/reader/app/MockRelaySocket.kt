package com.reader.app

import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Complete the WebSocket close handshake so MockWebServer can release the connection. */
internal open class MockRelaySocket : WebSocketListener() {
  override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
    webSocket.close(code, null)
  }
}
