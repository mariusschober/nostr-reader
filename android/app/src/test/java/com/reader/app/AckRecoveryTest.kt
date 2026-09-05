package com.reader.app

import com.reader.app.data.AckIntentEntity
import com.reader.app.sync.*
import org.junit.Assert.*
import org.junit.Test

class AckRecoveryTest {
  private val now = 1_000_000L
  private fun completed() = AckIntentEntity("channel", "transfer", "manifest", "doc", "recipient", "stored", 1,
    10_000, "[\"relay-a\",\"relay-b\"]", 1, null, now - ACK_REFRESH_COOLDOWN_MILLIS, null, null)

  @Test fun lostCompletedQuorumReopensOnceAndKeepsLifetimeIdentity() {
    val original = completed()
    val reopened = refreshAckOnDemand(original, now)
    assertNull(reopened.completedAt)
    assertEquals("[]", reopened.acceptedRelaysJson)
    assertEquals(1, reopened.refreshCount)
    assertEquals(original.attemptCount, reopened.attemptCount)
    assertEquals(original.receivedAt, reopened.receivedAt)
    assertEquals(original.expiresAt, reopened.expiresAt)
    assertEquals(reopened, refreshAckOnDemand(reopened, now + 1))
  }
  @Test fun cooldownExpiryFailureAndLifetimeBudgetStayClosed() {
    val original = completed()
    for (closed in listOf(original.copy(completedAt = now), original.copy(expiresAt = now / 1000),
      original.copy(failedAt = now), original.copy(attemptCount = 168), original.copy(refreshCount = ACK_MAX_REFRESHES))) {
      assertEquals(closed, refreshAckOnDemand(closed, now))
    }
  }
  @Test fun repeatedCompletionCannotCreateUnlimitedRefreshes() {
    var intent = completed()
    repeat(ACK_MAX_REFRESHES) {
      intent = refreshAckOnDemand(intent, now)
      intent = intent.copy(completedAt = now - ACK_REFRESH_COOLDOWN_MILLIS)
    }
    assertEquals(ACK_MAX_REFRESHES, intent.refreshCount)
    assertEquals(intent, refreshAckOnDemand(intent, now))
  }
}
