package com.reader.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Routine updates coalesce; every committed Undo opportunity keeps its place. */
class NoticeCoordinator {
  data class Notice(val message: String, val undo: (suspend () -> Unit)? = null)
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val actions = java.util.ArrayDeque<Notice>()
  private var information: Notice? = null
  @Synchronized fun show(message: String, undo: (suspend () -> Unit)? = null) {
    val notice = Notice(message, undo)
    if (undo == null) information = notice else actions.addLast(notice)
    wake.trySend(Unit)
  }
  @Synchronized private fun next(): Notice? = actions.pollFirst() ?: information.also { information = null }
  private val _undoActive = MutableStateFlow(false)
  /** True while an Undo snackbar is on screen; overlays should yield. */
  val undoActive: StateFlow<Boolean> = _undoActive
  @Composable fun Host(modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(this) {
      for (signal in wake) while (true) {
        val notice = next() ?: break
        if (notice.undo != null) _undoActive.value = true
        try {
          val result = host.showSnackbar(notice.message, if (notice.undo != null) "Undo" else null,
            withDismissAction = true, duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
          if (result == SnackbarResult.ActionPerformed) notice.undo?.invoke()
        } finally {
          if (notice.undo != null) _undoActive.value = false
        }
      }
    }
    SnackbarHost(host, modifier) { data ->
      Snackbar(
        snackbarData = data,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
        dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
      )
    }
  }
}
