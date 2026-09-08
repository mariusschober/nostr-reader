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
              put("list", document.list); put("progressBlockId", document.progressBlockId)
              put("progressCharOffset", document.progressCharOffset)
            }
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

  @Test fun controlledCampaign() {
    val args = InstrumentationRegistry.getArguments()
    val port = args.getString("qaControlPort")?.toIntOrNull()
    assumeTrue("Opt-in isolated campaign", port != null)
    check(runner.targetContext.packageName == "com.reader.app.qa" && port!! in 1024..65535)
    val token = checkNotNull(args.getString("qaControlToken"))
    launch()
    val deadline = SystemClock.elapsedRealtime() + 2 * 60 * 60 * 1000
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
