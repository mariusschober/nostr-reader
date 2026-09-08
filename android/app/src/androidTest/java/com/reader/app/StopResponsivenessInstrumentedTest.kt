package com.reader.app

import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reader.app.data.ReaderDb
import com.reader.app.cursor.SemanticCursor
import com.reader.app.ui.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StopResponsivenessInstrumentedTest {
  @Test fun onStopReturnsWhileRoomTransactionRemainsHeld() = runBlocking {
    val runner = InstrumentationRegistry.getInstrumentation()
    val app = runner.targetContext.applicationContext as ReaderApp
    val db = ReaderDb.get(app)
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var activity: MainActivity
      scenario.onActivity { activity = it }
      val held = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val transaction = launch(Dispatchers.IO) { db.withTransaction { held.complete(Unit); release.await() } }
      held.await()
      app.progress.offer(SemanticCursor.start("synthetic-stop-check"), 0f)
      val stopped = CountDownLatch(1)
      val executor = Executors.newSingleThreadExecutor()
      try {
        val request = executor.submit { runner.runOnMainSync { runner.callActivityOnStop(activity); stopped.countDown() } }
        val responsive = stopped.await(500, TimeUnit.MILLISECONDS)
        release.complete(Unit)
        transaction.join()
        request.get(5, TimeUnit.SECONDS)
        assertTrue("onStop must not wait for the held Room transaction", responsive)
      } finally { release.complete(Unit); executor.shutdownNow() }
    }
  }
}
