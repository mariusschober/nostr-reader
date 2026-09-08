package com.reader.app

import android.app.Application
import com.reader.app.sync.SyncWorker
import com.reader.app.nostr.RelayClient
import android.content.Context

open class ReaderApp : Application() {
  /** One transport owner. The instrumentation APK can supply its isolated TLS relay. */
  open val relayClient: RelayClient by lazy { RelayClient() }
  val persistenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
  val reading by lazy { com.reader.app.data.ReadingSession(persistenceScope,
    java.io.File(filesDir, "pending-selections"),
    com.reader.app.data.HighlightRepository(com.reader.app.data.ReaderDb.get(this))::saveSelection) }
  val articles by lazy { com.reader.app.data.ArticleRepository(com.reader.app.data.ReaderDb.get(this)) }
  val progress by lazy { com.reader.app.data.ProgressWriter(com.reader.app.data.ReaderDb.get(this)) }
  suspend fun deleteArticle(id: String) {
    progress.discard(id)
    articles.delete(id)
  }
  override fun onCreate() {
    super.onCreate()
    reading // recover journaled selections before a reading session is opened
    SyncWorker.schedule(this)
  }
}

fun Context.readerRelayClient(): RelayClient = (applicationContext as ReaderApp).relayClient
