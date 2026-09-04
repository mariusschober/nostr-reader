package com.reader.app

import com.reader.app.cursor.SemanticCursor
import com.reader.app.ui.Route
import com.reader.app.ui.RouteStack
import org.junit.Assert.*
import org.junit.Test

class RouteStackTest {
  private fun cursor() = SemanticCursor("doc", "b0", 0)

  @Test
  fun startsAtInboxRoot() {
    val s = RouteStack()
    assertTrue(s.isRoot())
    assertEquals(Route.Inbox, s.current())
    assertNull(s.pop())
  }

  @Test
  fun pushPopWalksBack() {
    val s = RouteStack()
    s.push(Route.Reader("a"))
    s.push(Route.Rsvp("a", cursor()))
    assertEquals(3, s.depth())
    assertTrue(s.pop() is Route.Reader)
    assertTrue(s.pop() is Route.Inbox)
    assertTrue(s.isRoot())
    assertNull(s.pop())
  }

  @Test
  fun noDuplicateConsecutiveEntries() {
    val s = RouteStack()
    s.push(Route.Reader("a"))
    s.push(Route.Reader("a"))
    assertEquals(2, s.depth())
  }

  @Test
  fun resetReturnsToRoot() {
    val s = RouteStack()
    s.push(Route.Settings)
    s.push(Route.Pairing)
    s.reset()
    assertTrue(s.isRoot())
    assertEquals(Route.Inbox, s.current())
  }

  @Test
  fun rsvpExitPreservesCursorTarget() {
    // Popping RSVP must land back on its own Reader so the cursor has a home.
    val s = RouteStack()
    s.push(Route.Reader("a"))
    s.push(Route.Rsvp("a", cursor()))
    val top = s.pop()
    assertTrue(top is Route.Reader && (top as Route.Reader).id == "a")
  }
}
