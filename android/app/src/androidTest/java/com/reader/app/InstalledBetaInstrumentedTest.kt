package com.reader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.data.ReaderDb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only acceptance of the installed owner candidate; no fixture insertion or data reset. */
@RunWith(AndroidJUnit4::class)
class InstalledBetaInstrumentedTest {
  @Test fun installedVersionAndPreservedDatabaseOpen() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    org.junit.Assume.assumeTrue(context.packageName == "com.reader.app")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    assertEquals("0.9.0-beta.1", info.versionName)
    assertEquals(2, info.versionCode)
    val db = ReaderDb.get(context).openHelper.readableDatabase
    db.query("PRAGMA integrity_check").use {
      assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
    }
    for (table in listOf("documents", "highlights", "channels")) {
      db.query("SELECT count(*) FROM $table").use { assertTrue(it.moveToFirst()); assertTrue(it.getLong(0) >= 0) }
    }
  }
}
