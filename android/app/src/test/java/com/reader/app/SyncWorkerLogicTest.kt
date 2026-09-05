package com.reader.app

import com.reader.app.sync.acceptedAckRelays
import com.reader.app.sync.ackRetryDelayMillis
import com.reader.app.sync.syncNeedsRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerLogicTest {
  @Test
  fun persistedAckRelaysKeepConfiguredOrderAndDropUnknownValues() {
    val configured = listOf("wss://one.example", "wss://two.example", "wss://three.example")
    assertEquals(
      linkedSetOf("wss://one.example", "wss://three.example"),
      acceptedAckRelays(
        "[\"wss://three.example\",\"wss://unknown.example\",\"wss://one.example\"]",
        configured,
      ),
    )
    assertTrue(acceptedAckRelays("not-json", configured).isEmpty())
  }

  @Test
  fun ackBackoffIsDeterministicIncreasingAndBounded() {
    val transferId = "00".repeat(15) + "2a"
    val first = ackRetryDelayMillis(1, transferId)
    assertEquals(first, ackRetryDelayMillis(1, transferId))
    assertTrue(ackRetryDelayMillis(2, transferId) > first)
    assertTrue(ackRetryDelayMillis(500, transferId) <= 6L * 60L * 60L * 1000L)
  }

  @Test
  fun pendingPairingOrAckKeepsTheOneTimeWorkerRetryable() {
    assertFalse(syncNeedsRetry(pairingPending = false, ackPending = false))
    assertTrue(syncNeedsRetry(pairingPending = true, ackPending = false))
    assertTrue(syncNeedsRetry(pairingPending = false, ackPending = true))
    assertTrue(syncNeedsRetry(pairingPending = true, ackPending = true))
  }
}
