package com.reader.app.data

import com.reader.app.core.ReaderCore
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class BackupDocument(
  val documentId: String,
  val markdown: String,
  val title: String,
  val sourceType: String,
  val sourceName: String?,
  val sourceUrl: String?,
  val author: String?,
  val publishedAt: Long?,
  val capturedAt: Long,
  val language: String?,
  val list: String,
  val state: String,
  val progressFraction: Float,
)

data class BackupBundle(
  val documents: List<BackupDocument>,
  val highlights: List<HighlightEntity>,
  val warnings: List<String>,
)

/**
 * Reads the one-way ZIP written by [ArchiveExport] and restores documents and
 * highlights. Keys, channels, and settings are deliberately never present.
 * Entry names, sizes, counts, and canonical hashes are validated before commit.
 */
object BackupArchive {
  const val FORMAT = "reader-backup/1"
  const val MAX_ENTRY_BYTES = ReaderCore.MAX_EXPANDED_BYTES
  const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
  const val MAX_DOCUMENTS = 500
  const val MAX_HIGHLIGHTS = 20_000

  private val documentEntry = Regex("^([0-9a-f]{64})\\.(md|json)$")
  private val allowedLists = setOf("inbox", "priority", "later", "archived")

  fun read(input: InputStream): BackupBundle {
    val markdown = HashMap<String, String>()
    val metadata = HashMap<String, JsonObject>()
    val highlights = ArrayList<HighlightEntity>()
    val warnings = ArrayList<String>()
    var total = 0L
    ZipInputStream(input.buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val name = entry.name
        if (entry.isDirectory) continue
        if (name != "reader-backup.json" && name != "highlights.jsonl" && !documentEntry.matches(name)) {
          warnings += "Skipped unknown entry: ${name.take(80)}"
          continue
        }
        if (entry.size > MAX_ENTRY_BYTES) throw IllegalArgumentException("Archive entry is too large")
        val bytes = readLimited(zip, MAX_ENTRY_BYTES)
        total += bytes.size
        if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Archive exceeds 256 MiB")
        when {
          name == "reader-backup.json" -> {
            val manifest = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
            require(manifest["format"]?.jsonPrimitive?.contentOrNull == FORMAT) { "Unsupported backup format" }
          }
          name == "highlights.jsonl" -> {
            for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
              if (line.isBlank()) continue
              if (highlights.size >= MAX_HIGHLIGHTS) throw IllegalArgumentException("Too many highlights")
              runCatching { Json.decodeFromString(HighlightEntity.serializer(), line) }
                .onSuccess { highlights += it }
                .onFailure { warnings += "Skipped malformed highlight" }
            }
          }
          name.endsWith(".md") -> {
            val id = documentEntry.matchEntire(name)!!.groupValues[1]
            if (markdown.containsKey(id)) warnings += "Duplicate markdown entry skipped"
            else markdown[id] = String(bytes, Charsets.UTF_8)
          }
          name.endsWith(".json") -> {
            val id = documentEntry.matchEntire(name)!!.groupValues[1]
            val objectValue = runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }.getOrNull()
            if (objectValue == null) warnings += "Skipped malformed metadata: $id"
            else metadata[id] = objectValue
          }
        }
      }
    }
    val documents = ArrayList<BackupDocument>()
    for ((id, markdownText) in markdown) {
      if (documents.size >= MAX_DOCUMENTS) throw IllegalArgumentException("Too many documents")
      val canonical = ReaderCore.canonicalize(markdownText)
      if (ReaderCore.documentId(canonical) != id) {
        warnings += "Skipped document with mismatched hash: ${id.take(12)}…"
        continue
      }
      val meta = metadata[id] ?: JsonObject(emptyMap())
      fun text(key: String): String? = meta[key]?.jsonPrimitive?.contentOrNull
      fun long(key: String): Long? = meta[key]?.jsonPrimitive?.longOrNull
      fun float(key: String): Float? = meta[key]?.jsonPrimitive?.floatOrNull
      val list = text("list")?.takeIf { it in allowedLists } ?: "inbox"
      documents += BackupDocument(
        documentId = id,
        markdown = canonical,
        title = text("title")?.trim()?.take(500)?.takeIf { it.isNotEmpty() } ?: "Untitled",
        sourceType = text("sourceType")?.take(32)?.takeIf { it.isNotEmpty() } ?: "file",
        sourceName = text("sourceName")?.take(200),
        sourceUrl = text("sourceUrl")?.take(2000),
        author = text("author")?.take(300),
        publishedAt = long("publishedAt"),
        capturedAt = long("capturedAt") ?: long("createdAt") ?: System.currentTimeMillis(),
        language = text("language")?.take(16),
        list = list,
        state = text("state")?.takeIf { it == "reading" || it == "unread" } ?: "unread",
        progressFraction = (float("progressFraction") ?: 0f).coerceIn(0f, 1f),
      )
    }
    return BackupBundle(documents, highlights, warnings)
  }

  private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      require(total <= limit) { "Archive entry exceeds its size limit" }
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
}
