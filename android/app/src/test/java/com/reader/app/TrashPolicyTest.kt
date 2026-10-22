package com.reader.app

import com.reader.app.data.TrashPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPolicyTest {
  @Test
  fun cutoffIsRetentionBeforeNow() {
    val now = 10_000_000_000_000L
    assertEquals(now - TrashPolicy.RETENTION_MS, TrashPolicy.cutoff(now))
  }

  @Test
  fun tombstonesExpireOnlyAfterTheFullRetentionWindow() {
    val now = 1_000_000_000_000L
    assertFalse(TrashPolicy.isExpired(now, now))
    assertFalse(TrashPolicy.isExpired(now - TrashPolicy.RETENTION_MS + 1, now))
    assertTrue(TrashPolicy.isExpired(now - TrashPolicy.RETENTION_MS, now))
    assertTrue(TrashPolicy.isExpired(now - TrashPolicy.RETENTION_MS - 1, now))
  }

  @Test
  fun retentionIsBoundedToSevenDays() {
    assertEquals(7L * 24 * 60 * 60 * 1000, TrashPolicy.RETENTION_MS)
  }
}
