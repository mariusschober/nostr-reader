package com.reader.app.ui

/** Tiny back stack. Inbox is the root; system back pops until root, then exits. Pure logic, unit-tested. */
class RouteStack(restored: List<Route> = emptyList()) {
  private val stack: MutableList<Route> = restored.takeIf { it.firstOrNull() == Route.Inbox }?.toMutableList() ?: mutableListOf(Route.Inbox)

  fun snapshot(): List<Route> = stack.toList()

  fun current(): Route = stack.last()
  fun isRoot(): Boolean = stack.size == 1
  fun depth(): Int = stack.size

  fun push(route: Route) {
    // No duplicate consecutive entries (e.g. reopening the same article).
    if (stack.last() != route) stack.add(route)
  }

  /** Returns the new top, or null when already at root (caller should exit). */
  fun pop(): Route? {
    if (stack.size <= 1) return null
    stack.removeAt(stack.lastIndex)
    return stack.last()
  }

  fun reset() {
    stack.clear()
    stack.add(Route.Inbox)
  }
}
