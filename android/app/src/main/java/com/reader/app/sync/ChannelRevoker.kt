package com.reader.app.sync

import com.reader.app.data.ChannelEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Whether the best-effort disconnect notice reached Chrome; local state is revoked either way. */
data class ChannelRevokeResult(val notified: Boolean)

/**
 * Revokes a channel locally before attempting the best-effort disconnect notice.
 *
 * The key is copied into memory while it is still readable, the database revoke
 * and key deletion run in a non-cancellable block, and only then is Chrome
 * notified. A cancelled notice never cancels the local revoke, and the in-memory
 * key is zeroed on every path.
 */
class ChannelRevoker(
  private val loadChannel: suspend (channelId: String) -> ChannelEntity?,
  private val openChannelKey: (channelId: String) -> ByteArray?,
  private val revokeLocal: suspend (channelId: String, nowMillis: Long) -> Unit,
  private val deleteChannelKey: (channelId: String) -> Unit,
  private val publishNotice: suspend (channel: ChannelEntity, channelKey: ByteArray, nowMillis: Long) -> Boolean,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  suspend fun revoke(channelId: String): ChannelRevokeResult {
    val channel = withContext(ioDispatcher) { loadChannel(channelId) }
    val channelKey = openKeyBestEffort(channelId)
    try {
      val now = nowMillis()
      withContext(NonCancellable + ioDispatcher) {
        try {
          revokeLocal(channelId, now)
        } finally {
          // Never leave key material behind, even if the database update fails.
          deleteChannelKey(channelId)
        }
      }
      val notified = if (channel != null && channelKey != null) {
        try {
          withContext(ioDispatcher) { publishNotice(channel, channelKey, now) }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          false
        }
      } else {
        false
      }
      return ChannelRevokeResult(notified)
    } finally {
      channelKey?.fill(0)
    }
  }

  /** A missing or unreadable key must not prevent the local revoke. */
  private suspend fun openKeyBestEffort(channelId: String): ByteArray? =
    try {
      withContext(ioDispatcher) { openChannelKey(channelId) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
}
