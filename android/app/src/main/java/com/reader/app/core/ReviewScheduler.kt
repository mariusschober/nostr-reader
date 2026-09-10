package com.reader.app.core

import kotlinx.serialization.Serializable

@Serializable
data class ReviewState(
  val format: Int = 1, val seed: Long, val randomState: Long,
  val members: List<String>, val baseRemaining: List<String>,
  val bonusRemaining: List<String> = emptyList(), val bonusConsumed: Set<String> = emptySet(),
  val currentId: String? = null, val phase: String = "base", val currentReviewed: Boolean = false,
  val lastPresentedId: String? = null,
  /** A quote presented from the feed without consuming the unfinished round. */
  val focusedId: String? = null,
  val focusedReviewed: Boolean = false,
)

/** Seeded base coverage, then at most one importance bonus per ID and cycle. */
object ReviewScheduler {
  /** The quote currently on screen: a focused presentation wins over the round. */
  fun presentedId(state: ReviewState): String? = state.focusedId ?: state.currentId

  /**
   * Present `chosen` without discarding an unfinished round. With no unfinished
   * round this starts a normal cycle at `chosen`; when `chosen` is already the
   * current quote it simply resumes. Entering does not record a review.
   */
  fun focus(state: ReviewState?, ids: Collection<String>, seed: Long, important: Set<String>, chosen: String): ReviewState {
    if (state == null || state.currentId == null) return start(ids, seed, state?.lastPresentedId, chosen)
    if (chosen == state.currentId) return state.copy(focusedId = null, focusedReviewed = false)
    if (chosen !in ids.toSet()) return state
    val refreshed = refresh(state, ids, important)
    if (chosen !in ids.toSet()) return refreshed
    return refreshed.copy(focusedId = chosen, focusedReviewed = false)
  }

  private class Rng(var state: Long) {
    fun next(bound: Int): Int {
      require(bound > 0)
      state += -7046029254386353131L
      var x = state
      x = (x xor (x ushr 30)) * -4658895280553007687L
      x = (x xor (x ushr 27)) * -7723592293110705685L
      x = x xor (x ushr 31)
      return ((x ushr 1) % bound).toInt()
    }
    fun shuffle(values: MutableList<String>) {
      for (i in values.lastIndex downTo 1) {
        val j = next(i + 1); val old = values[i]; values[i] = values[j]; values[j] = old
      }
    }
  }

  fun start(ids: Collection<String>, seed: Long, lastPresentedId: String? = null, chosen: String? = null): ReviewState {
    val rng = Rng(seed)
    val order = ids.distinct().sorted().toMutableList()
    rng.shuffle(order)
    if (chosen != null && order.remove(chosen)) order.add(0, chosen)
    else if (order.size > 1 && order.first() == lastPresentedId) {
      val replacement = order.indexOfFirst { it != lastPresentedId }
      val first = order[0]; order[0] = order[replacement]; order[replacement] = first
    }
    return ReviewState(seed = seed, randomState = rng.state, members = order.toList(),
      currentId = order.firstOrNull(), baseRemaining = order.drop(1),
      phase = if (order.isEmpty()) "done" else "base", lastPresentedId = lastPresentedId)
  }

  fun refresh(state: ReviewState, ids: Collection<String>, important: Set<String>): ReviewState {
    require(state.format == 1) { "Unsupported review state" }
    val eligible = ids.toSet()
    val rng = Rng(state.randomState)
    val known = state.members.toSet()
    val fresh = (eligible - known).sorted()
    val base = state.baseRemaining.filter { it in eligible }.distinct().toMutableList()
    for (id in fresh) base.add(rng.next(base.size + 1), id)
    val current = state.currentId?.takeIf { it in eligible }
    var updated = state.copy(
      randomState = rng.state, members = (state.members.filter { it in eligible } + fresh).distinct(),
      baseRemaining = base, currentId = current,
      bonusRemaining = state.bonusRemaining.filter { it in eligible && it in important && it !in state.bonusConsumed && it != current }.distinct(),
      bonusConsumed = state.bonusConsumed.intersect(eligible),
      focusedId = state.focusedId?.takeIf { it in eligible },
    )
    if (current == null) updated = choose(updated, important)
    return updated
  }

  fun advance(state: ReviewState, ids: Collection<String>, important: Set<String>): ReviewState {
    // A focused presentation is reviewed first, then the preserved round resumes
    // from its untouched current quote.
    if (state.focusedId != null) {
      val focused = state.focusedId
      val base = state.baseRemaining.filter { it != focused }
      var resumed = state.copy(focusedId = null, focusedReviewed = false, baseRemaining = base)
      if (resumed.currentId == null) resumed = choose(resumed.copy(lastPresentedId = focused), important)
      return refresh(resumed, ids, important)
    }
    if (state.currentId !in ids) return refresh(state, ids, important)
    val ready = refresh(state, ids, important)
    if (ready.currentId == null) return ready
    return choose(ready.copy(currentId = null, lastPresentedId = ready.currentId, currentReviewed = false), important)
  }

  private fun choose(state: ReviewState, important: Set<String>): ReviewState {
    if (state.baseRemaining.isNotEmpty()) {
      val base = state.baseRemaining.toMutableList()
      if (base.first() == state.lastPresentedId && base.size > 1) {
        val different = base.indexOfFirst { it != state.lastPresentedId }
        if (different > 0) { val first = base[0]; base[0] = base[different]; base[different] = first }
      }
      return state.copy(currentId = base.first(), baseRemaining = base.drop(1), phase = "base", currentReviewed = false)
    }
    val rng = Rng(state.randomState)
    val pending = state.bonusRemaining.filter { it in important && it !in state.bonusConsumed }.toMutableList()
    val extra = state.members.filter { it in important && it !in state.bonusConsumed && it !in pending }.sorted().toMutableList()
    rng.shuffle(extra); pending.addAll(extra)
    val next = pending.indexOfFirst { it != state.lastPresentedId }
    if (next < 0) return state.copy(currentId = null, phase = "done", bonusRemaining = pending, randomState = rng.state)
    val id = pending.removeAt(next)
    return state.copy(currentId = id, phase = "bonus", bonusRemaining = pending,
      bonusConsumed = state.bonusConsumed + id, randomState = rng.state, currentReviewed = false)
  }

  /** Stable feed order is independent of the review queue. */
  fun feedKey(seed: Long, id: String): Long {
    var hash = seed xor -3750763034362895579L
    for (c in id) hash = (hash xor c.code.toLong()) * 1099511628211L
    return hash
  }
}
