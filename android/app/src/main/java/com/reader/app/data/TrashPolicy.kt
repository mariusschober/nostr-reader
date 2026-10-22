package com.reader.app.data

/**
 * Archive deletes are reversible for a bounded window. The tombstone survives process death
 * (so an accidental swipe is not immediately unrecoverable), then the next purge removes it.
 */
object TrashPolicy {
  /** Long enough to recover from a mistake or a crash; short enough not to retain a shadow library. */
  const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

  fun cutoff(now: Long): Long = now - RETENTION_MS

  fun isExpired(deletedAt: Long, now: Long): Boolean = deletedAt <= cutoff(now)
}
