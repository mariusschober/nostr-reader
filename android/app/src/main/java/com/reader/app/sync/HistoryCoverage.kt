package com.reader.app.sync

import kotlinx.serialization.Serializable

@Serializable data class HistoryWindow(val since: Long, val until: Long, val limit: Int = 256)
@Serializable data class HistoryCoverage(
  val pending: List<HistoryWindow>, val incomplete: List<HistoryWindow> = emptyList(),
) {
  companion object { fun start(since: Long, until: Long) = HistoryCoverage(listOf(HistoryWindow(since, until))) }
}

/** A checkpoint follows durable consumption, never receipt into a memory queue. */
internal suspend fun <T> scanHistory(
  initial: HistoryCoverage,
  budget: Int = 2,
  query: suspend (HistoryWindow) -> List<T>,
  consume: suspend (T) -> Unit,
  checkpoint: suspend (HistoryCoverage) -> Unit,
): HistoryCoverage {
  var state = initial
  repeat(budget) {
    val window = state.pending.firstOrNull() ?: return state
    var overBudget = false
    val events = try { query(window) } catch (_: com.reader.app.nostr.ReceiveBudgetException) {
      overBudget = true
      emptyList()
    }
    events.forEach { consume(it) }
    val rest = state.pending.drop(1)
    state = if (overBudget || events.size >= 32) {
      if (window.until > window.since) {
        val middle = window.since + (window.until - window.since) / 2
        state.copy(pending = listOf(HistoryWindow(middle + 1, window.until), HistoryWindow(window.since, middle)) + rest)
      } else if (!overBudget && events.size >= window.limit && window.limit < 4096) {
        state.copy(pending = listOf(window.copy(limit = (window.limit * 4).coerceAtMost(4096))) + rest)
      } else {
        check(window in state.incomplete || state.incomplete.size < 4096) { "History coverage budget exhausted" }
        state.copy(pending = rest, incomplete = (state.incomplete + window).distinct())
      }
    } else state.copy(pending = rest)
    checkpoint(state)
  }
  return state
}
