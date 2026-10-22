package com.reader.app

import android.app.Application
import com.reader.app.sync.SyncWorker
import com.reader.app.nostr.RelayClient
import android.content.Context
import kotlinx.coroutines.launch

open class ReaderApp : Application() {
  /** One transport owner. The instrumentation APK can supply its isolated TLS relay. */
  open val relayClient: RelayClient by lazy { RelayClient() }
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

  override fun onCreate() {
    super.onCreate()
    SyncWorker.schedule(this)
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      runCatching { articles.purgeDeleted() }
    }
  }
}

fun Context.readerRelayClient(): RelayClient = (applicationContext as ReaderApp).relayClient
