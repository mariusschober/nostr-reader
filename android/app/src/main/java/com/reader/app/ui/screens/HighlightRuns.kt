package com.reader.app.ui.screens

import java.util.PriorityQueue

/** Paint the newest mark at each offset. End offsets are exclusive. */
internal fun forEachVisibleMark(
  marks: List<NativeMark>, length: Int, emit: (Int, Int, NativeMark) -> Unit,
) {
  val sorted = marks.filter { it.start >= 0 && it.start < it.end && it.end <= length }.sortedBy { it.start }
  val active = PriorityQueue(compareByDescending<NativeMark> { it.createdAt }.thenByDescending { it.id })
  var index = 0
  var pos = 0
  while (index < sorted.size || active.isNotEmpty()) {
    if (active.isEmpty()) pos = sorted[index].start
    while (index < sorted.size && sorted[index].start <= pos) active.add(sorted[index++])
    while (active.isNotEmpty() && active.peek().end <= pos) active.remove()
    val top = active.peek() ?: continue
    val end = minOf(top.end, sorted.getOrNull(index)?.start ?: length)
    emit(pos, end, top)
    pos = end
    while (active.isNotEmpty() && active.peek().end <= pos) active.remove()
  }
}
