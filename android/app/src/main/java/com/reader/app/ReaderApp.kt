package com.reader.app

import android.app.Application
import com.reader.app.sync.SyncWorker
import com.reader.app.nostr.RelayClient
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class ReaderApp : Application() {
  /** One transport owner. The instrumentation APK can supply its isolated TLS relay. */
  open val relayClient: RelayClient by lazy { RelayClient() }
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val articles by lazy { com.reader.app.data.ArticleRepository(com.reader.app.data.ReaderDb.get(this)) }
  val progress by lazy { com.reader.app.data.ProgressWriter(com.reader.app.data.ReaderDb.get(this)) }
  /** Moves an archived article to the local trash; [restoreArticle] can bring it back. */
  suspend fun deleteArticle(id: String) {
    progress.discard(id)
    articles.delete(id)
  }

  suspend fun restoreArticle(id: String) {
    articles.restore(id)
  }

  /** Activity lifecycle must not block the main thread on a disk flush. */
  fun flushProgressAsync() {
    appScope.launch { runCatching { progress.flush() } }
  }

  override fun onCreate() {
    super.onCreate()
    SyncWorker.schedule(this)
    appScope.launch { runCatching { articles.purgeDeleted() } }
  }
}

fun Context.readerRelayClient(): RelayClient = (applicationContext as ReaderApp).relayClient
