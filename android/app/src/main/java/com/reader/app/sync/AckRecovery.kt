package com.reader.app.sync

import com.reader.app.data.AckIntentEntity

internal const val ACK_REFRESH_COOLDOWN_MILLIS = 5 * 60 * 1000L
internal const val ACK_MAX_REFRESHES = 8

/** Demand is a new authenticated immutable-transfer wrapper, not an old replay. */
internal fun refreshAckOnDemand(intent: AckIntentEntity, nowMillis: Long): AckIntentEntity {
  val completed = intent.completedAt ?: return intent
  if (intent.failedAt != null || intent.attemptCount >= 168 || intent.refreshCount >= ACK_MAX_REFRESHES ||
    intent.expiresAt <= nowMillis / 1000 || nowMillis - completed < ACK_REFRESH_COOLDOWN_MILLIS) return intent
  return intent.copy(
    acceptedRelaysJson = "[]",
    completedAt = null,
    nextAttemptAt = nowMillis,
    refreshCount = intent.refreshCount + 1,
    lastErrorCode = null,
  )
}
