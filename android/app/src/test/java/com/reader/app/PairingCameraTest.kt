package com.reader.app

import com.reader.app.ui.screens.copyLuminancePlane
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
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
}
