package com.reader.app

import com.reader.app.nostr.StrictJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {
  @Test
  fun validNestedJsonParses() {
    val parsed = StrictJson.parseObject("""{"a":[1,true,null,{"b":"x"}]}""")
    assertEquals("x", parsed["a"]!!.jsonArray[3].jsonObject["b"]!!.jsonPrimitive.content)
  }

  @Test
  fun duplicateKeysAndEscapedAliasesAreRejected() {
    for (json in listOf(
      """{"a":1,"a":2}""",
      """{"outer":{"x":1,"x":2}}""",
      """{"a":1,"\u0061":2}""",
    )) {
      try {
        StrictJson.parse(json)
        fail("expected duplicate key rejection")
      } catch (_: IllegalArgumentException) {
      }
    }
  }

  @Test
  fun malformedAndTrailingJsonAreRejected() {
    for (json in listOf("""{"a":01}""", """{"a":1} garbage""", "\"unterminated")) {
      try {
        StrictJson.parse(json)
        fail("expected malformed JSON rejection")
      } catch (_: IllegalArgumentException) {
      }
    }
  }
}
