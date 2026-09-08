package com.reader.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The host prepares/restores permission flags on com.reader.app.qa only. */
@RunWith(AndroidJUnit4::class)
class QaCameraPermissionInstrumentedTest {
  @Test fun cameraRequestAndPermanentDenialUseRealAndroidState() {
    val mode = InstrumentationRegistry.getArguments().getString("qaCamera")
    assumeTrue("Opt-in QA permission scenario", mode != null)
    val runner = InstrumentationRegistry.getInstrumentation()
    check(runner.targetContext.packageName == "com.reader.app.qa")
    assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(runner.targetContext, Manifest.permission.CAMERA))
    val ui = QaPairingUiInstrumentedTest()
    ui.openPairing()
    fun screenshot(name: String) {
      runner.uiAutomation.waitForIdle(400, 3000)
      val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
      File(runner.targetContext.getExternalFilesDir("qa"), "$name.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      bitmap.recycle()
    }
    when (mode) {
      "fresh" -> {
        // A prior opt-in test may have saved the asked-once hint; the explicit
        // button still has to request permission against current Android state.
        if (ui.find { it.text?.toString() == "Open app settings" } != null) {
          error("Fresh OS permission must remain requestable")
        }
        if (ui.find { it.text?.toString() == "Allow camera" } != null) ui.click("Allow camera")
        ui.waitNode("Android camera permission dialog") { it.text?.toString() == "While using the app" }
        screenshot("camera-request")
        ui.click("While using the app")
        ui.waitNode("live scanner fallback") { it.text?.toString() == "Or paste pairing code" }
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(runner.targetContext, Manifest.permission.CAMERA))
      }
      "denied" -> {
        ui.waitNode("permanent denial action") { it.text?.toString() == "Open app settings" }
        assertNull(ui.find { it.text?.toString() == "Allow camera" })
        screenshot("camera-denied")
        ui.click("Open app settings")
        ui.waitNode("Android app settings") { it.text?.toString() == "Permissions" }
        screenshot("camera-app-settings")
        runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitNode("pairing restored after Settings") { it.text?.toString() == "Open app settings" }
      }
      else -> error("Unknown QA camera scenario")
    }
  }
}
