package com.reader.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** One transaction gives content, provenance and retained quotations a coherent snapshot.
 *
 * Accepted trade-off: the transaction is held during ZIP I/O and can delay
 * writers for seconds on large libraries (see RELIABILITY_HARDENING). Callers
 * must single-flight exports (MainActivity.exportMutex) and log preparation
 * time. Fail-closed: a single corrupt/incomplete article aborts the whole
 * export with its documentId in the error — no partial/quarantined export is
 * produced, so a bad row cannot silently drop an article. */
class ArchiveExporter(private val db: ReaderDb) {
  suspend fun prepare(directory: File): File = withContext(Dispatchers.IO) {
    val file = File.createTempFile("reader-export-", ".zip", directory)
    try {
      val components = linkedMapOf<String, String>()
      db.withTransaction {
        ZipOutputStream(file.outputStream()).use { zip ->
          suspend fun entry(name: String, write: suspend ((ByteArray) -> Unit) -> Unit) {
            coroutineContext.ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            zip.putNextEntry(ZipEntry(name))
            write { bytes -> digest.update(bytes); zip.write(bytes) }
            zip.closeEntry()
            components[name] = digest.digest().joinToString("") { "%02x".format(it) }
          }
          for (summary in db.documents().observeSummaries().first()) {
            val d = checkNotNull(db.documents().metadataById(summary.documentId)) { "Article missing: ${summary.documentId}" }
            entry("articles/${d.documentId}.md") { write ->
              for (part in 0 until db.documents().contentPartCount(d.documentId)) {
                coroutineContext.ensureActive()
                write(checkNotNull(db.documents().contentPart(d.documentId, part)) { "Article content is incomplete: ${d.documentId} part $part" }.toByteArray(Charsets.UTF_8))
              }
            }
            check(components["articles/${d.documentId}.md"] == d.documentId) { "Article checksum mismatch: ${d.documentId}" }
            entry("articles/${d.documentId}.json") { write ->
              write(buildJsonObject {
                put("documentId", d.documentId); put("title", d.title)
                put("sourceType", d.sourceType); put("sourceName", d.sourceName); put("sourceUrl", d.sourceUrl)
                put("author", d.author); put("publishedAt", d.publishedAt); put("capturedAt", d.capturedAt)
                put("language", d.language); put("wordCount", d.wordCount); put("parserVersion", d.parserVersion)
                put("state", d.state); put("list", d.list); put("progressBlockId", d.progressBlockId)
                put("progressCharOffset", d.progressCharOffset); put("progressFraction", d.progressFraction)
                put("lastOpenedAt", d.lastOpenedAt); put("createdAt", d.createdAt); put("updatedAt", d.updatedAt)
              }.toString().toByteArray(Charsets.UTF_8))
            }
          }
          entry("highlights.jsonl") { write ->
            var offset = 0
            while (true) {
              coroutineContext.ensureActive()
              val page = db.highlights().exportPage(20, offset)
              if (page.isEmpty()) break
              page.forEach { write((Json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
              offset += page.size
            }
          }
          entry("review.json") { write ->
            val parts = db.review().parts()
            if (parts.isEmpty()) write("null".toByteArray())
            else parts.forEach { write(it.json.toByteArray(Charsets.UTF_8)) }
          }
          zip.putNextEntry(ZipEntry("manifest.json"))
          zip.write(buildJsonObject {
            put("format", "reader-portable-export"); put("version", 1)
            put("createdAt", System.currentTimeMillis()); put("restoreSupported", false)
            put("components", buildJsonObject { components.forEach { (name, hash) -> put(name, hash) } })
          }.toString().toByteArray(Charsets.UTF_8))
          zip.closeEntry()
        }
      }
      verify(file)
      file
    } catch (error: Throwable) { file.delete(); throw error }
  }

  companion object {
    fun verify(file: File) {
      ZipFile(file).use { zip ->
        val manifest = Json.parseToJsonElement(zip.getInputStream(checkNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { it.readText() }).jsonObject
        check(manifest["format"]?.jsonPrimitive?.content == "reader-portable-export")
        check(manifest["version"]?.jsonPrimitive?.int == 1)
        val components = manifest.getValue("components").jsonObject
        check(zip.entries().toList().map { it.name }.toSet() == components.keys + "manifest.json")
        for ((name, checksum) in components) {
          val digest = MessageDigest.getInstance("SHA-256")
          zip.getInputStream(checkNotNull(zip.getEntry(name))).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
          }
          check(digest.digest().joinToString("") { "%02x".format(it) } == checksum.jsonPrimitive.content) { "Export checksum mismatch" }
        }
      }
    }
  }
}
