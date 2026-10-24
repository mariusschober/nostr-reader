package com.reader.app

import com.reader.app.data.ChannelEntity
import com.reader.app.sync.ChannelRevoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRevokerTest {
  private val now = 1_800_000_000_000L

  private fun channel() = ChannelEntity(
    channelId = "channel",
    receiverPubkey = "receiver",
    trustedSenderPubkey = "sender",
    createdAt = now - 10_000,
    revokedAt = null,
    relaysJson = """["wss://relay-a.example","wss://relay-b.example"]""",
  )

  @Test
  fun localRevokeAndKeyDeleteHappenBeforePublish() = runTest {
    val events = mutableListOf<String>()
    val key = byteArrayOf(1, 2, 3)
    val revoker = ChannelRevoker(
      loadChannel = { events += "load"; channel() },
      openChannelKey = { events += "open"; key },
      revokeLocal = { id, at -> events += "revoke:$id:$at" },
      deleteChannelKey = { id -> events += "delete:$id" },
      publishNotice = { _, _, at -> events += "publish:$at"; true },
      nowMillis = { now },
      ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    val result = revoker.revoke("channel")

    assertTrue(result.notified)
    assertEquals(
      listOf("load", "open", "revoke:channel:$now", "delete:channel", "publish:$now"),
      events,
    )
    assertArrayEquals(ByteArray(key.size), key)
  }

  @Test
  fun cancellationDuringPublishKeepsLocalRevokeAndPropagates() = runTest {
    val events = mutableListOf<String>()
    val key = byteArrayOf(4, 5, 6)
    val publishStarted = CompletableDeferred<Unit>()
    var returnedNormally = false
    val revoker = ChannelRevoker(
      loadChannel = { events += "load"; channel() },
      openChannelKey = { events += "open"; key },
      revokeLocal = { id, _ -> events += "revoke:$id" },
      deleteChannelKey = { id -> events += "delete:$id" },
      publishNotice = { _, _, _ ->
        events += "publish"
        publishStarted.complete(Unit)
        awaitCancellation()
      },
      nowMillis = { now },
      ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    val job = launch {
      revoker.revoke("channel")
      returnedNormally = true
    }
    advanceUntilIdle()

    assertTrue(publishStarted.isCompleted)
    assertEquals(
      listOf("load", "open", "revoke:channel", "delete:channel", "publish"),
      events,
    )

    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertFalse(returnedNormally)
    assertEquals(1, events.count { it == "revoke:channel" })
    assertEquals(1, events.count { it == "delete:channel" })
    assertArrayEquals(ByteArray(key.size), key)
  }

  @Test
  fun publishFailureStillRevokesAndReportsNotNotified() = runTest {
    val events = mutableListOf<String>()
    val key = byteArrayOf(7, 8, 9)
    val revoker = ChannelRevoker(
      loadChannel = { events += "load"; channel() },
      openChannelKey = { events += "open"; key },
      revokeLocal = { id, _ -> events += "revoke:$id" },
      deleteChannelKey = { id -> events += "delete:$id" },
      publishNotice = { _, _, _ -> events += "publish"; throw IOException("offline") },
      nowMillis = { now },
      ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    val result = revoker.revoke("channel")

    assertFalse(result.notified)
    assertEquals(
      listOf("load", "open", "revoke:channel", "delete:channel", "publish"),
      events,
    )
    assertArrayEquals(ByteArray(key.size), key)
  }

  @Test
  fun keyBytesAreZeroedAfterSuccessAndFailure() = runTest {
    for (publishFails in listOf(false, true)) {
      val key = byteArrayOf(10, 11, 12)
      val revoker = ChannelRevoker(
        loadChannel = { channel() },
        openChannelKey = { key },
        revokeLocal = { _, _ -> },
        deleteChannelKey = {},
        publishNotice = { _, _, _ ->
          if (publishFails) throw IOException("offline") else true
        },
        nowMillis = { now },
        ioDispatcher = StandardTestDispatcher(testScheduler),
      )

      val result = revoker.revoke("channel")

      assertEquals(!publishFails, result.notified)
      assertArrayEquals(ByteArray(key.size), key)
    }
  }

  @Test
  fun missingChannelKeyStillRevokesLocally() = runTest {
    val events = mutableListOf<String>()
    val revoker = ChannelRevoker(
      loadChannel = { events += "load"; channel() },
      openChannelKey = { events += "open"; null },
      revokeLocal = { id, _ -> events += "revoke:$id" },
      deleteChannelKey = { id -> events += "delete:$id" },
      publishNotice = { _, _, _ -> events += "publish"; true },
      nowMillis = { now },
      ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    val result = revoker.revoke("channel")

    assertFalse(result.notified)
    assertEquals(listOf("load", "open", "revoke:channel", "delete:channel"), events)
  }
}
