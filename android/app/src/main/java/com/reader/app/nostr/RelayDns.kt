package com.reader.app.nostr

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Platform DNS may ignore interruption; cap both threads and queued lookups. */
internal object RelayDns {
  private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), { runnable ->
    Thread(runnable, "reader-relay-dns").apply { isDaemon = true }
  })

  fun lookup(hostname: String): Array<InetAddress> {
    val task = try { executor.submit<Array<InetAddress>> { InetAddress.getAllByName(hostname) } }
    catch (_: java.util.concurrent.RejectedExecutionException) { throw UnknownHostException("relay DNS capacity exceeded") }
    return try { task.get(3, TimeUnit.SECONDS) }
    catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
    catch (_: Exception) { throw UnknownHostException("relay DNS lookup failed or timed out") }
    finally { task.cancel(true); executor.purge() }
  }
}
