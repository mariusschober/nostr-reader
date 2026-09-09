package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.capture.CaptureException
import com.reader.app.capture.CaptureFetcher
import com.reader.app.core.ReaderCore
import java.io.File

/**
 * Manual eval harness (NOT a CI gate — it hits the live web).
 * Run: ./gradlew :app:runCaptureEval -Purls=docs/eval/article-urls-v1.txt -Pout=docs/eval/report-v1.tsv
 * Sequential + polite delay. Never throws: every URL gets one report row.
 */
object EvalMain {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 2) { "usage: EvalMain <urls-file> <out-tsv>" }
    val urls = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val out = File(args[1])
    out.printWriter().use { w ->
      w.println(listOf("url", "outcome", "words", "title", "fetchMs", "extractMs", "redirects", "error", "editMarkers", "citeCluster", "unavailableInArticle").joinToString("\t"))
      var i = 0
      for (url in urls) {
        i++
        val row = try {
          kotlinx.coroutines.runBlocking { evaluate(url) }
        } catch (e: Exception) {
          EvalRow(url, "harness_error", 0, "", 0, 0, 0, (e.message ?: "error").take(80), 0, 0, false)
        }
        w.println(row.toTsv())
        w.flush()
        println("[$i/${urls.size}] ${row.outcome} ${row.words}w ${url.take(70)} ${row.error}")
        Thread.sleep(400)
      }
    }
    println("report: ${out.absolutePath}")
  }

  private data class EvalRow(
    val url: String, val outcome: String, val words: Int, val title: String,
    val fetchMs: Long, val extractMs: Long, val redirects: Int, val error: String,
    val editMarkers: Int, val citeCluster: Int, val unavailableInArticle: Boolean,
  ) {
    fun toTsv(): String = listOf(
      url, outcome, words.toString(), title.replace("\t", " ").take(120),
      fetchMs.toString(), extractMs.toString(), redirects.toString(), error.replace("\t", " "),
      editMarkers.toString(), citeCluster.toString(), unavailableInArticle.toString(),
    ).joinToString("\t")
  }

  private suspend fun evaluate(url: String): EvalRow {
    val fetcher = CaptureFetcher()
    val t0 = System.nanoTime()
    val page = try {
      fetcher.fetch(url)
    } catch (e: CaptureException) {
      val ms = (System.nanoTime() - t0) / 1_000_000
      return EvalRow(url, "fetch_${e.code}", 0, "", ms, 0, 0, (e.message ?: "").take(80), 0, 0, false)
    }
    val fetchMs = (System.nanoTime() - t0) / 1_000_000
    val t1 = System.nanoTime()
    val extracted = try {
      ArticleExtractor.extract(page.htmlBytes, page.finalUrl)
    } catch (e: ArticleExtractor.ExtractionFailed) {
      val ms = (System.nanoTime() - t1) / 1_000_000
      return EvalRow(url, "link_only", 0, "", fetchMs, ms, page.redirectCount, e.errorCode, 0, 0, false)
    }
    val extractMs = (System.nanoTime() - t1) / 1_000_000
    val md = extracted.markdown
    val editMarkers = Regex("""\[edit\]""").findAll(md).count()
    // Longest run of adjacent citation markers like [12][13][14].
    val citeCluster = Regex("""(?:\[\d+\]){2,}""").findAll(md).map { it.value.length }.maxOrNull() ?: 0
    val unavailable = md.contains("Article text is unavailable")
    val words = ReaderCore.effectiveWords(md)
    val outcome = when {
      unavailable || words < 30 && ReaderCore.cjkCount(md) < 60 -> "suspect_thin"
      editMarkers > 0 || citeCluster > 0 -> "suspect_chrome"
      else -> "completed"
    }
    return EvalRow(url, outcome, words, extracted.title, fetchMs, extractMs, page.redirectCount, "", editMarkers, citeCluster, unavailable)
  }
}
