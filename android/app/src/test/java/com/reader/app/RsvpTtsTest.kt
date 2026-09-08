package com.reader.app

import com.reader.app.core.ArticleParser
import com.reader.app.rsvp.RsvpModel
import com.reader.app.tts.Narration
import com.reader.app.tts.TtsController
import com.reader.app.tts.TtsEngine
import com.reader.app.ui.screens.parsePairingQr
import org.junit.Assert.*
import org.junit.Test

class RsvpModelTest {
  @Test
  fun tokensSkipCodeAndUrls() {
    val blocks = ArticleParser.parse("Hello world test\n\n```\ncode here\n```\n\nSee https://example.com now\n")
    val tokens = RsvpModel.tokens(blocks)
    assertTrue(tokens.any { it.text == "Hello" })
    assertFalse(tokens.any { it.text == "code" })
    assertFalse(tokens.any { it.text.startsWith("http") })
    // Block mapping survives.
    assertTrue(tokens.all { it.blockId.isNotBlank() && it.end > it.start })
  }

  @Test
  fun schedulerHasNoDriftByConstruction() {
    val s = RsvpModel.Scheduler(300)
    s.reset(1_000L)
    val t = com.reader.app.rsvp.RsvpToken("word", "b0", 0, 4, false)
    assertEquals(200L, s.delayFor(t, false))
  }
}

class FakeEngine : TtsEngine {
  val spoken = mutableListOf<String>()
  var lastSpeed = 1f
  override var supportsRangeCallback = false
  override var onReady: (() -> Unit)? = null
  private var done: (() -> Unit)? = null
  override fun speak(utteranceId: String, text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onRange: (Int, Int) -> Unit, onError: (String) -> Unit) {
    spoken.add(text)
    onStart()
    done = onDone
  }
  fun finish() = done?.invoke()
  override fun stop() {}
  override fun setSpeed(speed: Float) {
    lastSpeed = speed
  }
  override fun shutdown() {}
}

class TtsControllerTest {
  @Test
  fun queueAdvancesAndCursorTracks() {
    val eng = FakeEngine()
    val ctl = TtsController(eng)
    val seen = mutableListOf<String>()
    ctl.onCursor = { seen.add(it) }
    val units = listOf(
      com.reader.app.tts.NarrationUnit("b0", "First sentence."),
      com.reader.app.tts.NarrationUnit("b1", "Second sentence."),
    )
    ctl.load(units, "b0", 1f)
    ctl.play()
    assertEquals(listOf("First sentence."), eng.spoken)
    eng.finish()
    assertEquals(listOf("First sentence.", "Second sentence."), eng.spoken)
    assertEquals(listOf("b0", "b1"), seen)
    val cursor = ctl.cursorFor("doc1")
    assertEquals("b1", cursor.blockId)
  }

  @Test
  fun narrationSkipsCode() {
    val blocks = ArticleParser.parse("Intro sentence here.\n\n```\ncode tokens\n```\n\nFinal words now.\n")
    val units = Narration.sentences(blocks)
    assertTrue(units.any { it.text.contains("Intro") })
    assertFalse(units.any { it.text.contains("code tokens") })
  }
}

class PairingQrTest {
  @Test
  fun rejectsExpiredAndWrongProtocol() {
    try {
      parsePairingQr("""{"protocol":"reader-pair/1","pairingPubkey":"a","chromeDevicePubkey":"b","nonce":"c","relays":["wss://x"],"expiresAt":1}""", 9999999999L)
      fail("expected expiry")
    } catch (e: IllegalArgumentException) {
    }
    try {
      parsePairingQr("""{"protocol":"other/1","expiresAt":9999999999}""", 1L)
      fail("expected protocol")
    } catch (e: IllegalArgumentException) {
    }
  }
}
