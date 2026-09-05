package com.reader.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic campaign through real UI. Never provisions keys/DB rows.
 * Transient runner bearer is never included in output; owner package forbidden. */
@RunWith(AndroidJUnit4::class)
class QaPairingUiInstrumentedTest {
  private val runner get() = InstrumentationRegistry.getInstrumentation()
  private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      if (predicate(node)) return node
      for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
      return null
    }
    return runner.uiAutomation.rootInActiveWindow?.let(::visit)
  }
  private fun waitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
    repeat(100) { find(predicate)?.let { return it }; SystemClock.sleep(100) }
    error("QA UI control unavailable: $label")
  }
  private fun click(label: String) {
    var node = waitNode(label) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    while (!node.isClickable && node.parent != null) node = node.parent
    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "QA UI click unavailable: $label" }
  }
  @Test fun pairThroughManualEntry() {
    val encoded = InstrumentationRegistry.getArguments().getString("qaPairing")
    assumeTrue("Opt-in isolated UI campaign", encoded != null)
    check(runner.targetContext.packageName == "com.reader.app.qa") { "QA package required" }
    val request = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
    runner.targetContext.startActivity(Intent().setClassName(runner.targetContext.packageName, "com.reader.app.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    click("Import, paste, or pair")
    click("Pair Chrome")
    // Decline if Android asks. Never grant camera permission or read clipboard.
    SystemClock.sleep(500)
    if (find { it.text?.toString() == "Don't allow" } != null) click("Don't allow")
    val field = waitNode("pairing text field") { it.isEditable }
    assertTrue("Native text entry accepted", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request)
    }))
    click("Review")
    click("Connect")
    repeat(600) {
      if (find { it.contentDescription?.toString() == "Import, paste, or pair" } != null) return
      SystemClock.sleep(100)
    }
    error("QA pairing did not reach library within 60 seconds")
  }
}
