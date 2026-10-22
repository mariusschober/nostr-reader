package com.reader.app

import com.reader.app.ui.PairingScanGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingScanGateTest {
  @Test
  fun oneValidationIsInFlightAtATime() {
    val gate = PairingScanGate()
    assertTrue(gate.claim("code-a", 1000))
    assertFalse(gate.claim("code-a", 1001))
    assertFalse(gate.claim("code-b", 1002))
    gate.accepted()
    assertTrue(gate.claim("code-b", 1003))
  }

  @Test
  fun sameRejectedCodeIsNotRetriedEveryFrameButADifferentCodeIsAccepted() {
    val gate = PairingScanGate(sameTextCooldownMs = 2_000)
    assertTrue(gate.claim("bad", 1000))
    gate.rejected("bad", 1000)
    assertFalse(gate.claim("bad", 1500))
    assertFalse(gate.claim("bad", 2999))
    assertTrue(gate.claim("good", 1500))
  }

  @Test
  fun rejectedCodeCanBeRetriedAfterTheCooldown() {
    val gate = PairingScanGate(sameTextCooldownMs = 2_000)
    assertTrue(gate.claim("bad", 1000))
    gate.rejected("bad", 1000)
    assertTrue(gate.claim("bad", 3000))
  }

  @Test
  fun resetClearsPendingAndRejectionHistory() {
    val gate = PairingScanGate()
    assertTrue(gate.claim("bad", 1000))
    gate.rejected("bad", 1000)
    gate.reset()
    assertTrue(gate.claim("bad", 1001))
  }
}
