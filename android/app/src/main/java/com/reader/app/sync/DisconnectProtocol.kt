package com.reader.app.sync

import com.reader.app.core.ReaderCore
import kotlinx.serialization.json.*

/**
 * Bounded, authenticated channel teardown. NIP-59 already authenticates the
 * sender and recipient; this payload only names the intent and its lifetime.
 */
object DisconnectProtocol {
  const val TYPE = "disconnect"
  const val TTL_SECS = 600L
  private val REASON = Regex("^[a-z0-9][a-z0-9-]{0,47}$")

  fun payload(nowSecs: Long, reasonCode: String = "user-disconnect"): String {
    require(nowSecs >= 0) { "invalid disconnect time" }
    require(REASON.matches(reasonCode)) { "invalid disconnect reason" }
    return buildJsonObject {
      put("protocol", ReaderCore.READER_PROTOCOL)
      put("type", TYPE)
      put("createdAt", nowSecs)
      put("expiresAt", nowSecs + TTL_SECS)
      put("reasonCode", reasonCode)
    }.toString()
  }

  fun validate(payload: JsonObject, nowSecs: Long): Boolean {
    if (payload["protocol"]?.jsonPrimitive?.contentOrNull != ReaderCore.READER_PROTOCOL) return false
    if (payload["type"]?.jsonPrimitive?.contentOrNull != TYPE) return false
    val createdAt = payload["createdAt"]?.jsonPrimitive?.longOrNull ?: return false
    val expiresAt = payload["expiresAt"]?.jsonPrimitive?.longOrNull ?: return false
    val reason = payload["reasonCode"]?.jsonPrimitive?.contentOrNull ?: return false
    if (!REASON.matches(reason)) return false
    if (createdAt > nowSecs + 600 || createdAt < nowSecs - 172800) return false
    if (expiresAt <= nowSecs || expiresAt - createdAt > TTL_SECS + 300) return false
    return true
  }
}
