package com.reader.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** kotlinx.serialization parsing with duplicate-object-key rejection at every depth. */
object StrictJson {
  fun parse(text: String): JsonElement {
    Guard(text).check()
    return Json.parseToJsonElement(text)
  }

  fun parseObject(text: String): JsonObject = parse(text).jsonObject

  private class Guard(private val text: String) {
    private var index = 0

    fun check() {
      whitespace()
      value()
      whitespace()
      require(index == text.length) { "trailing JSON data" }
    }

    private fun whitespace() {
      while (index < text.length && text[index] in charArrayOf('\t', '\n', '\r', ' ')) index++
    }

    private fun value() {
      whitespace()
      when (text.getOrNull(index)) {
        '{' -> objectValue()
        '[' -> arrayValue()
        '"' -> stringValue()
        't' -> literal("true")
        'f' -> literal("false")
        'n' -> literal("null")
        else -> numberValue()
      }
    }

    private fun objectValue() {
      index++
      whitespace()
      if (text.getOrNull(index) == '}') { index++; return }
      val keys = mutableSetOf<String>()
      while (true) {
        whitespace()
        require(text.getOrNull(index) == '"') { "object key must be a string" }
        val key = stringValue()
        require(keys.add(key)) { "duplicate JSON key: $key" }
        whitespace()
        require(text.getOrNull(index) == ':') { "missing JSON colon" }
        index++
        value()
        whitespace()
        when (text.getOrNull(index++)) {
          '}' -> return
          ',' -> Unit
          else -> throw IllegalArgumentException("invalid JSON object delimiter")
        }
      }
    }

    private fun arrayValue() {
      index++
      whitespace()
      if (text.getOrNull(index) == ']') { index++; return }
      while (true) {
        value()
        whitespace()
        when (text.getOrNull(index++)) {
          ']' -> return
          ',' -> Unit
          else -> throw IllegalArgumentException("invalid JSON array delimiter")
        }
      }
    }

    private fun stringValue(): String {
      require(text.getOrNull(index++) == '"') { "invalid JSON string" }
      val out = StringBuilder()
      while (index < text.length) {
        val char = text[index++]
        if (char == '"') return out.toString()
        require(char.code >= 0x20) { "unescaped JSON control character" }
        if (char != '\\') { out.append(char); continue }
        val escape = text.getOrNull(index++) ?: throw IllegalArgumentException("unterminated JSON escape")
        when (escape) {
          '"', '\\', '/' -> out.append(escape)
          'b' -> out.append('\b')
          'f' -> out.append('\u000c')
          'n' -> out.append('\n')
          'r' -> out.append('\r')
          't' -> out.append('\t')
          'u' -> {
            val end = index + 4
            require(end <= text.length) { "invalid JSON unicode escape" }
            val digits = text.substring(index, end)
            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "invalid JSON unicode escape" }
            out.append(digits.toInt(16).toChar())
            index = end
          }
          else -> throw IllegalArgumentException("invalid JSON escape")
        }
      }
      throw IllegalArgumentException("unterminated JSON string")
    }

    private fun literal(expected: String) {
      require(text.regionMatches(index, expected, 0, expected.length)) { "invalid JSON literal" }
      index += expected.length
    }

    private fun digit(char: Char?): Boolean = char != null && char in '0'..'9'

    private fun numberValue() {
      val start = index
      if (text.getOrNull(index) == '-') index++
      if (text.getOrNull(index) == '0') {
        index++
        require(!digit(text.getOrNull(index))) { "invalid leading zero" }
      } else {
        val first = text.getOrNull(index)
        require(first != null && first in '1'..'9') { "invalid JSON value" }
        while (digit(text.getOrNull(index))) index++
      }
      if (text.getOrNull(index) == '.') {
        index++
        require(digit(text.getOrNull(index))) { "invalid JSON fraction" }
        while (digit(text.getOrNull(index))) index++
      }
      if (text.getOrNull(index) == 'e' || text.getOrNull(index) == 'E') {
        index++
        if (text.getOrNull(index) == '+' || text.getOrNull(index) == '-') index++
        require(digit(text.getOrNull(index))) { "invalid JSON exponent" }
        while (digit(text.getOrNull(index))) index++
      }
      require(index > start) { "invalid JSON number" }
    }
  }
}
