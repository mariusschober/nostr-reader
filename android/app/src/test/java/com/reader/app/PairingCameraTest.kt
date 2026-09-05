package com.reader.app

import com.reader.app.ui.screens.copyLuminancePlane
import com.reader.app.ui.screens.parsePairingQr
import com.reader.app.nostr.PairingProtocol
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingCameraTest {
  @Test
  fun copiesCompactLuminancePlane() {
    val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))

    val actual = copyLuminancePlane(
      buffer = source,
      width = 3,
      height = 2,
      rowStride = 3,
      pixelStride = 1,
    )

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), actual)
  }

  @Test
  fun removesRowPaddingFromLuminancePlane() {
    val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 99, 4, 5, 6, 99))

    val actual = copyLuminancePlane(
      buffer = source,
      width = 3,
      height = 2,
      rowStride = 4,
      pixelStride = 1,
    )

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), actual)
  }

  @Test
  fun respectsLuminancePixelStride() {
    val source = ByteBuffer.wrap(byteArrayOf(1, 99, 2, 99, 3, 99, 4, 99))

    val actual = copyLuminancePlane(
      buffer = source,
      width = 2,
      height = 2,
      rowStride = 4,
      pixelStride = 2,
    )

    assertArrayEquals(byteArrayOf(1, 2, 3, 4), actual)
  }

  @Test
  fun rejectsQrThatCanReachAPrivateNetwork() {
    val relays = listOf("ws://192.168.1.2:8080")
    val qr = """{
      "protocol":"reader-pair/2",
      "sessionId":"00000000000000000000000000000000",
      "pairingPubkey":"f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
      "chromeDevicePubkey":"dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
      "nonce":"0000000000000000000000000000000000000000000000000000000000000000",
      "relays":["ws://192.168.1.2:8080"],
      "relaySetDigest":"${PairingProtocol.relaySetDigest(relays)}",
      "createdAt":1700000000,
      "expiresAt":1700000300,
      "capabilities":["durable-pair-response","pair-ack-v2","pair-complete-v2","endpoint-ack-v2","gzip"]
    }""".trimIndent()
    try {
      parsePairingQr(qr, 1_700_000_000)
      fail("expected unsafe relay rejection")
    } catch (_: IllegalArgumentException) {
      // Expected: QR policy is applied before any network activity.
    }
  }
}
