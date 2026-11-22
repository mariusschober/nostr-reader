package com.reader.app

import com.reader.app.ui.screens.PenSettlementPolicy
import org.junit.Assert.*
import org.junit.Test

class PenSettlementPolicyTest {
  @Test fun completedGestureClearsWithoutConsultingRecreatedActionModeSession() {
    val policy = PenSettlementPolicy()
    policy.beginGesture()
    val completion = policy.settle("persisted-id", 10, 30)!!
    assertNull("One release owns one completion", policy.settle("persisted-id", 10, 30))
    assertTrue(policy.acknowledge(completion, true, 10, 30, false))
    assertFalse("Duplicate acknowledgement cannot clear another selection", policy.acknowledge(completion, true, 10, 30, false))
  }

  @Test fun nextGestureRejectsOldAckEvenWhenItsRangeIsIdentical() {
    val policy = PenSettlementPolicy()
    policy.beginGesture()
    val old = policy.settle("old", 10, 30)!!
    policy.beginGesture()
    assertFalse(policy.acknowledge(old, true, 10, 30, true))
    val next = policy.settle("new", 10, 30)!!
    assertTrue(policy.acknowledge(next, true, 10, 30, false))
  }

  @Test fun aChangedRangeCannotBeClearedByAnOldCompletion() {
    val policy = PenSettlementPolicy()
    policy.beginGesture()
    val completion = policy.settle("id", 10, 30)!!
    assertFalse(policy.acknowledge(completion, true, 10, 40, false))
  }

  @Test fun failureKeepsSelectionAndAllowsRetryWithTheSameIdentity() {
    val policy = PenSettlementPolicy()
    policy.beginGesture()
    val first = policy.settle("stable-id", 10, 30)!!
    assertFalse(policy.acknowledge(first, false, 10, 30, false))
    val retry = policy.settle("stable-id", 10, 30)!!
    assertEquals(first.session, retry.session)
    assertFalse("Old failed callback cannot settle retry", policy.acknowledge(first, true, 10, 30, false))
    assertTrue(policy.acknowledge(retry, true, 10, 30, false))
  }

  @Test fun explicitCancellationInvalidatesPendingCompletion() {
    val policy = PenSettlementPolicy()
    policy.beginGesture()
    val completion = policy.settle("id", 10, 30)!!
    policy.invalidate()
    assertFalse(policy.acknowledge(completion, true, 10, 30, false))
  }
}
