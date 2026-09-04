package com.reader.app

import android.app.Application
import com.reader.app.sync.SyncWorker

class ReaderApp : Application() {
  override fun onCreate() {
    super.onCreate()
    SyncWorker.schedule(this)
  }
}
