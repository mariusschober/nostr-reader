package com.reader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.core.ReaderCore
import com.reader.app.data.ReaderDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/** Opt-in campaign controller; read-only DB evidence, real pairing/reader UI. */
@RunWith(AndroidJUnit4::class)
class QaCampaignInstrumentedTest {
  private val runner get() = InstrumentationRegistry.getInstrumentation()
  private fun request(port: Int, token: String, path: String, payload: String = ""): String {
    // Only the test APK's loopback control channel uses a raw socket. Production
    // relay traffic still uses real WSS, signatures, encryption and validation.
    Socket("127.0.0.1", port).use { socket ->
      socket.soTimeout = 5000
      val bytes = payload.toByteArray(Charsets.UTF_8)
      socket.getOutputStream().apply {
        write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Reader-QA: $token\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        write(bytes); flush()
      }
      val response = socket.getInputStream().readBytes()
      check(response.size <= 2 * 1024 * 1024) { "QA control response too large" }
      val text = String(response, Charsets.UTF_8)
      check(text.startsWith("HTTP/1.1 200")) { "QA control unavailable" }
      return text.substringAfter("\r\n\r\n")
    }
  }

  private fun launch() {
    runner.targetContext.startActivity(Intent().setClassName(runner.targetContext.packageName,
      "com.reader.app.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private fun snapshot(command: JsonObject): JsonObject = runBlocking {
    val db = ReaderDb.get(runner.targetContext)
    val channels = db.channels().active()
    buildJsonObject {
      put("phoneTimeMillis", System.currentTimeMillis())
      put("activeChannels", channels.size)
      put("channels", buildJsonArray { channels.forEach { channel -> add(buildJsonObject {
        put("receiverPubkey", channel.receiverPubkey); put("senderPubkey", channel.trustedSenderPubkey)
        put("sessionId", channel.sessionId); put("relayDigest", channel.relaySetDigest)
      }) } })
      db.openHelper.readableDatabase.query("SELECT count(*) FROM documents").use { cursor ->
        cursor.moveToFirst(); put("documentCount", cursor.getInt(0))
      }
      put("documents", buildJsonArray {
        for (id in command["documentIds"]?.jsonArray.orEmpty()) {
          val document = db.documents().byId(id.jsonPrimitive.content)
          add(buildJsonObject {
            put("documentId", id); put("stored", document != null)
            if (document != null) {
              put("sha256", ReaderCore.sha256Hex(document.canonicalMarkdown.toByteArray(Charsets.UTF_8)))
              put("bytes", document.canonicalMarkdown.toByteArray(Charsets.UTF_8).size)
              put("storedAtMillis", document.createdAt)
              put("list", document.list); put("progressBlockId", document.progressBlockId)
              put("progressCharOffset", document.progressCharOffset)
            }
          })
        }
      })
      put("library", buildJsonArray {
        db.openHelper.readableDatabase.query("SELECT documentId, title, sourceUrl, sourceType, wordCount, list, finishedAt, progressBlockId, progressCharOffset FROM documents ORDER BY createdAt").use { cursor ->
          while (cursor.moveToNext()) add(buildJsonObject {
            put("id", cursor.getString(0)); put("title", cursor.getString(1)); put("url", cursor.getString(2)); put("sourceType", cursor.getString(3))
            put("words", cursor.getInt(4)); put("list", cursor.getString(5)); put("finishedAt", if (cursor.isNull(6)) JsonNull else JsonPrimitive(cursor.getLong(6)))
            put("block", cursor.getString(7)); put("offset", cursor.getInt(8))
          })
        }
      })
      put("captures", buildJsonArray {
        db.openHelper.readableDatabase.query("SELECT requestId, originalUrl, state, documentId, errorCode FROM capture_requests").use { cursor ->
          while (cursor.moveToNext()) add(buildJsonObject {
            put("id", cursor.getString(0)); put("url", cursor.getString(1)); put("status", cursor.getString(2)); put("documentId", cursor.getString(3)); put("error", cursor.getString(4))
          })
        }
      })
      put("acks", buildJsonArray {
        for (id in command["transferIds"]?.jsonArray.orEmpty()) for (channel in channels) {
          val ack = db.ackIntents().byTransfer(channel.channelId, id.jsonPrimitive.content) ?: continue
          add(buildJsonObject {
            put("transferId", ack.transferId); put("documentId", ack.documentId)
            put("attempts", ack.attemptCount); put("refreshes", ack.refreshCount)
            put("quorumComplete", ack.completedAt != null); put("status", ack.status)
          })
        }
      })
    }
  }

  private fun ui(command: JsonObject): JsonObject {
    val automation = runner.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> = buildList {
      fun visit(n: android.view.accessibility.AccessibilityNodeInfo?) { if(n == null) return; add(n); for(i in 0 until n.childCount) visit(n.getChild(i)) }
      visit(automation.rootInActiveWindow)
    }
    check(automation.rootInActiveWindow?.packageName?.toString() == runner.targetContext.packageName) { "QA app must be foreground" }
    val action = command["action"]!!.jsonPrimitive.content
    val label = command["label"]?.jsonPrimitive?.content
    val node = nodes().filter { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }.sortedByDescending { it.isClickable }.firstOrNull()
    when (action) {
      "tap" -> {
        var target = checkNotNull(node) { "QA control missing: $label" }
        while (!target.isClickable && target.parent != null) target = target.parent
        check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
      }
      "text" -> {
        val target = node?.takeIf { it.isEditable } ?: nodes().first { it.isEditable }
        check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
          putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command["text"]!!.jsonPrimitive.content)
        }))
      }
      "back" -> {
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), true)
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), true)
      }
      "scroll" -> {
        val target = node?.takeIf { it.isScrollable } ?: nodes().filter { it.isVisibleToUser && it.isScrollable }.maxBy { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.height() }
        target.performAction(if (command["backward"]?.jsonPrimitive?.booleanOrNull == true) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
      }
      "select" -> {
        val target = nodes().first { it.className?.toString() == "android.widget.TextView" && (it.text?.length ?: 0) > 200 }
        check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
          putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, command["start"]!!.jsonPrimitive.int)
          putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, command["end"]!!.jsonPrimitive.int)
        }))
      }
      "snapshot" -> Unit
      else -> error("Unknown UI command")
    }
    SystemClock.sleep(250)
    val name = command["name"]?.jsonPrimitive?.content?.replace(Regex("[^a-zA-Z0-9_-]"), "")
    if (name != null) {
      val bitmap = checkNotNull(automation.takeScreenshot())
      val folder = java.io.File(runner.targetContext.getExternalFilesDir(null), "qa").also { it.mkdirs() }
      java.io.File(folder, "focused-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    var viewport: JsonObject? = null
    runner.runOnMainSync {
      fun find(view: android.view.View): android.widget.TextView? {
        if (view is com.reader.app.ui.screens.NativeArticleView) return (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<android.widget.TextView>().maxByOrNull { it.text.length }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
      }
      androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()?.let { activity ->
        find(activity.window.decorView)?.let { body -> viewport = buildJsonObject {
          put("scrollY", body.scrollY); put("height", body.height); put("layoutHeight", body.layout?.height ?: 0)
          val start = body.layout?.let { it.getLineStart(it.getLineForVertical(body.scrollY.coerceAtLeast(0))) } ?: 0
          put("visibleStart", body.text.toString().drop(start).take(160))
          put("selectionStart", body.selectionStart); put("selectionEnd", body.selectionEnd)
        } }
      }
    }
    return buildJsonObject {
      viewport?.let { put("readerViewport", it) }
      put("nodes", buildJsonArray { nodes().filter { it.isVisibleToUser }.forEach { n ->
        if (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()) add(buildJsonObject {
          put("text", n.text?.toString()?.take(220)); put("description", n.contentDescription?.toString()); put("editable", n.isEditable); put("scrollable", n.isScrollable); put("checked", n.isChecked); put("selected", n.isSelected)
        })
      } })
    }
  }

  @Test fun controlledCampaign() {
    val args = InstrumentationRegistry.getArguments()
    val port = args.getString("qaControlPort")?.toIntOrNull()
    assumeTrue("Opt-in isolated campaign", port != null)
    check(runner.targetContext.packageName == "com.reader.app.qa" && port!! in 1024..65535)
    val token = checkNotNull(args.getString("qaControlToken"))
    launch()
    val deadline = SystemClock.elapsedRealtime() + 40 * 60 * 1000
    while (SystemClock.elapsedRealtime() < deadline) {
      val command = Json.parseToJsonElement(request(port, token, "/android/next", "{}")).jsonObject
      if (command.isEmpty()) { SystemClock.sleep(200); continue }
      val id = command["id"]!!.jsonPrimitive.content
      val type = command["type"]!!.jsonPrimitive.content
      val result = try {
        when (type) {
          "pair" -> {
            // The transient bearer is never included in a result or assertion.
            QaPairingUiInstrumentedTest().pairNative(command["request"]!!.jsonPrimitive.content)
            snapshot(command)
          }
          "snapshot" -> snapshot(command)
          "ui" -> ui(command)
          "saveLink" -> {
            val url = command["url"]!!.jsonPrimitive.content
            check(url.startsWith("https://"))
            runner.targetContext.startActivity(Intent(Intent.ACTION_SEND).setClassName(runner.targetContext.packageName,
              "com.reader.app.ui.MainActivity").setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            buildJsonObject { put("submitted", true); put("url", url) }
          }
          "launch" -> { launch(); buildJsonObject { put("launched", true) } }
          "stop" -> buildJsonObject { put("stopped", true) }
          else -> error("Unknown QA command")
        }
      } catch (error: Exception) { buildJsonObject {
        put("errorType", error.javaClass.simpleName)
        put("error", error.message?.take(180)?.takeIf { type != "pair" || it.startsWith("QA ") } ?: "QA action failed")
      } }
      request(port, token, "/android/result", buildJsonObject { put("id", id); put("result", result) }.toString())
      if (type == "stop") return
    }
    error("QA campaign deadline reached")
  }
}
