package com.reader.app

import com.reader.app.core.ReaderCore
import com.reader.app.data.BackupArchive
import com.reader.app.data.HighlightEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
  private fun archive(entries: Map<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, body) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }

  private fun documentJson(title: String, list: String = "priority") = buildJsonObject {
    put("title", title)
    put("sourceType", "web")
    put("list", list)
    put("state", "reading")
    put("progressFraction", 0.42)
    put("sourceUrl", "https://example.com/a")
    put("author", "Ada")
  }.toString()

  @Test
  fun readsDocumentsHighlightsAndMetadata() {
    val canonical = ReaderCore.canonicalize("# Backup Article\n\nRestored body.\n")
    val id = ReaderCore.documentId(canonical)
    val highlight = HighlightEntity(
      id = "h1", documentId = id, quote = "Restored body.", sourceTitle = "Backup Article",
      sourceUrl = "https://example.com/a", createdAt = 10, updatedAt = 10,
      startBlockId = "b0", startOffset = 0, endBlockId = "b0", endOffset = 13,
      projectionVersion = 2, canonicalStart = 0, canonicalEnd = 13,
      prefixContext = "", suffixContext = "", color = "YELLOW", important = true,
    )
    val bundle = BackupArchive.read(ByteArrayInputStream(archive(mapOf(
      "reader-backup.json" to """{"format":"${BackupArchive.FORMAT}"}""",
      "$id.md" to canonical,
      "$id.json" to documentJson("Backup Article"),
      "highlights.jsonl" to Json.encodeToString(HighlightEntity.serializer(), highlight) + "\n",
    ))))
    assertEquals(1, bundle.documents.size)
    assertEquals("Backup Article", bundle.documents[0].title)
    assertEquals("priority", bundle.documents[0].list)
    assertEquals("reading", bundle.documents[0].state)
    assertEquals(0.42f, bundle.documents[0].progressFraction, 0.001f)
    assertEquals("Ada", bundle.documents[0].author)
    assertEquals(1, bundle.highlights.size)
    assertEquals(id, bundle.highlights[0].documentId)
    assertTrue(bundle.warnings.isEmpty())
  }

  @Test
  fun mismatchedDocumentHashIsSkippedWithWarning() {
    val canonical = ReaderCore.canonicalize("# Real\n\nBody.\n")
    val wrongId = "0".repeat(64)
    val bundle = BackupArchive.read(ByteArrayInputStream(archive(mapOf(
      "$wrongId.md" to canonical,
      "$wrongId.json" to documentJson("Wrong"),
    ))))
    assertEquals(0, bundle.documents.size)
    assertTrue(bundle.warnings.any { it.contains("mismatched hash") })
  }

  @Test
  fun unknownEntriesAreIgnoredAndUnsupportedFormatFails() {
    val canonical = ReaderCore.canonicalize("# Kept\n\nBody.\n")
    val id = ReaderCore.documentId(canonical)
    val bundle = BackupArchive.read(ByteArrayInputStream(archive(mapOf(
      "notes.txt" to "ignored",
      "$id.md" to canonical,
    ))))
    assertEquals(1, bundle.documents.size)
    assertTrue(bundle.warnings.any { it.contains("unknown entry") })

    try {
      BackupArchive.read(ByteArrayInputStream(archive(mapOf(
        "reader-backup.json" to """{"format":"other/9"}""",
      ))))
      throw AssertionError("unsupported format accepted")
    } catch (_: IllegalArgumentException) {
    }
  }
}
