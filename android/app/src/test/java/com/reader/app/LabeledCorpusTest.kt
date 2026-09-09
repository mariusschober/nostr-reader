package com.reader.app

import com.reader.app.capture.ArticleExtractor
import com.reader.app.core.ArticleBlock
import com.reader.app.core.ArticleParser
import com.reader.app.core.ReaderCore
import org.junit.Assert.*
import org.junit.Test

/**
 * Labeled mini-corpus: 12 in-band pages (240 chars–5k words — the product's
 * actual job: posts, articles, essays). Each case pins what MUST survive
 * (core snippets, structure) and what MUST NOT (page chrome), plus a word
 * band. Offline, hermetic, CI-gated. Negatives (JS shells, paywalls, empty)
 * live in ArticleExtractorTest; live-web breadth lives in docs/eval/.
 */
class LabeledCorpusTest {
  private data class Case(
    val name: String,
    val html: String,
    val mustContain: List<String>,
    val mustNotContain: List<String>,
    val minWords: Int,
    val maxWords: Int,
    val expectTable: Boolean = false,
    val expectList: Boolean = false,
    val expectQuote: Boolean = false,
    val expectCode: Boolean = false,
  )

  private fun page(title: String, body: String, head: String = ""): String =
    "<!doctype html><html><head><title>$title</title>$head</head><body>$body</body></html>"

  private fun prose(sentence: String, reps: Int): String =
    "<p>" + sentence.repeat(reps) + "</p>"

  private val cases = listOf(
    Case(
      name = "en-short-post",
      html = page(
        "Morning Note",
        "<main><article><h1>Morning Note</h1>" +
          prose("Walked the long way to the bakery and thought about nothing in particular. ", 8) +
          "</article></main><nav>Home</nav>",
      ),
      mustContain = listOf("Morning Note", "bakery"),
      mustNotContain = listOf("Home"),
      minWords = 30, maxWords = 200,
    ),
    Case(
      name = "en-essay",
      html = page(
        "On Attention",
        "<main><article><h1>On Attention</h1>" +
          prose("Attention is the rarest resource we own, and we spend it like tap water. ", 20) +
          "<h2>The Cost</h2>" + prose("Every notification is a small tax on the mind that compounds daily. ", 16) +
          "</article></main><aside>Related posts</aside><div class=\"ads\">Buy now</div>",
      ),
      mustContain = listOf("On Attention", "The Cost", "tap water"),
      mustNotContain = listOf("Related posts", "Buy now"),
      minWords = 200, maxWords = 1200,
    ),
    Case(
      name = "en-news",
      html = page(
        "City Council Approves Park",
        "<article><h1>City Council Approves Park</h1><div class=\"entry-meta\">June 1 • News • 3 min read</div>" +
          prose("The council voted 7 to 2 late Tuesday to fund the riverside park project. ", 14) +
          prose("Construction begins in spring with completion expected within two years. ", 10) +
          "</article><footer>Copyright</footer>",
      ),
      mustContain = listOf("riverside park", "7 to 2"),
      mustNotContain = listOf("Copyright", "min read"),
      minWords = 100, maxWords = 800,
    ),
    Case(
      name = "en-blog",
      html = page(
        "Tools I Actually Use",
        "<article><h1>Tools I Actually Use</h1>" +
          prose("After a decade of trying everything, three tools survived contact with real work. ", 10) +
          "<ul><li>A plain text editor</li><li>A paper notebook</li><li>A timer</li></ul>" +
          prose("The rest was theater, and I have the receipts to prove it twice over. ", 8) +
          "</article><div class=\"sharedaddy\">Like this:</div><div id=\"comments\">Nice!</div>",
      ),
      mustContain = listOf("paper notebook", "theater"),
      mustNotContain = listOf("Like this", "Nice!"),
      minWords = 80, maxWords = 600,
      expectList = true,
    ),
    Case(
      name = "en-list-heavy",
      html = page(
        "Seven Rules",
        "<article><h1>Seven Rules</h1>" + prose("Short intro that frames the list below with some care. ", 6) +
          "<ol><li>Sleep first</li><li>Write it down</li><li>Walk daily</li><li>Read slowly</li><li>Cook often</li><li>Call home</li><li>Begin again</li></ol>" +
          prose("Closing thought that ties the seven rules together neatly. ", 6) + "</article>",
      ),
      mustContain = listOf("Seven Rules", "Walk daily", "Begin again"),
      mustNotContain = emptyList(),
      minWords = 50, maxWords = 400,
      expectList = true,
    ),
    Case(
      name = "en-table",
      html = page(
        "Rail Fares Compared",
        "<article><h1>Rail Fares Compared</h1>" + prose("Three operators, three prices, one clear winner for weekend travel. ", 8) +
          "<table><tr><th>Operator</th><th>Off-peak</th></tr><tr><td>North</td><td>12</td></tr><tr><td>South</td><td>9</td></tr></table>" +
          prose("Book two weeks ahead and the gap widens further every single time. ", 8) + "</article>",
      ),
      mustContain = listOf("Operator", "North", "South"),
      mustNotContain = emptyList(),
      minWords = 60, maxWords = 500,
      expectTable = true,
    ),
    Case(
      name = "en-quote-code",
      html = page(
        "Notes on Simplicity",
        "<article><h1>Notes on Simplicity</h1>" + prose("A programmer I respect once wrote something worth framing. ", 8) +
          "<blockquote><p>Simplicity is prerequisite for reliability.</p></blockquote>" +
          "<pre><code>fn main() { println!(\"ok\"); }</code></pre>" +
          prose("I return to that sentence whenever a design starts growing teeth. ", 8) + "</article>",
      ),
      mustContain = listOf("Simplicity is prerequisite", "println"),
      mustNotContain = emptyList(),
      minWords = 60, maxWords = 500,
      expectQuote = true, expectCode = true,
    ),
    Case(
      name = "en-opinion",
      html = page(
        "In Praise of Boring Software",
        "<main><article><h1>In Praise of Boring Software</h1>" +
          prose("Choose the tool everyone already understands and get back to the actual problem. ", 18) +
          "<h2>Excitement Is Debt</h2>" + prose("Novelty compounds into maintenance load that someone must carry. ", 14) +
          "</article></main><div class=\"newsletter\">Subscribe!</div>",
      ),
      mustContain = listOf("Boring Software", "Excitement Is Debt"),
      mustNotContain = listOf("Subscribe!"),
      minWords = 150, maxWords = 900,
    ),
    Case(
      name = "de-essay",
      html = page(
        "Über das Lesen",
        "<main><article><h1>Über das Lesen</h1>" +
          prose("Lesen ist die leiseste Art zu reisen, und die günstigste dazu. ", 18) +
          "<h2>Geduld</h2>" + prose("Wer langsam liest, behält mehr als der eilige Überflieger. ", 14) +
          "</article></main><div class=\"werbung\">Kaufen!</div>",
      ),
      mustContain = listOf("Über das Lesen", "Geduld", "leiseste Art"),
      mustNotContain = listOf("Kaufen"),
      minWords = 150, maxWords = 900,
    ),
    Case(
      name = "de-news",
      html = page(
        "Stadtrat beschließt Park",
        "<article><h1>Stadtrat beschließt Park</h1>" +
          prose("Der Stadtrat hat am Dienstag mit sieben zu zwei Stimmen für den Uferpark gestimmt. ", 14) +
          prose("Der Baubeginn ist im Frühjahr, die Fertigstellung in zwei Jahren geplant. ", 10) +
          "</article>",
      ),
      mustContain = listOf("Uferpark", "sieben zu zwei"),
      mustNotContain = emptyList(),
      minWords = 100, maxWords = 800,
    ),
    Case(
      name = "zh-essay",
      html = page(
        "阅读的乐趣",
        "<main><article><h1>阅读的乐趣</h1>" +
          prose("阅读是最安静的旅行方式也是最便宜的一种让人足不出户便知天下事。", 6) +
          prose("每天坚持阅读半小时胜过一年偶尔的心血来潮式学习计划安排。", 6) +
          "</article></main><nav>导航</nav>",
      ),
      mustContain = listOf("阅读的乐趣", "最安静的旅行"),
      mustNotContain = listOf("导航"),
      minWords = 60, maxWords = 900,
    ),
    Case(
      name = "ar-essay",
      html = page(
        "متعة القراءة",
        "<main><article><h1>متعة القراءة</h1>" +
          prose("القراءة أنبل العادات اليومية التي تصاحب الإنسان في رحلته المعرفية الطويلة. ", 8) +
          prose("من يقرأ كل يوم يكتشف عوالم جديدة دون أن يغادر مكانه المعتاد. ", 8) +
          "</article></main><nav>تصفح</nav>",
      ),
      mustContain = listOf("متعة القراءة", "عوالم جديدة"),
      mustNotContain = listOf("تصفح"),
      minWords = 60, maxWords = 900,
    ),
  )

  @Test fun allTwelveCasesHoldTheirContract() {
    for (c in cases) {
      val out = ArticleExtractor.extract(c.html.toByteArray(Charsets.UTF_8), "https://example.com/${c.name}")
      val md = out.markdown
      for (s in c.mustContain) assertTrue("[${c.name}] missing: $s", md.contains(s))
      for (s in c.mustNotContain) assertFalse("[${c.name}] leaked: $s", md.contains(s))
      assertTrue("[${c.name}] words=${out.wordCount}", out.wordCount in c.minWords..c.maxWords)
      assertTrue("[${c.name}] title blank", out.title.isNotBlank())
      val blocks = ArticleParser.parse(md)
      if (c.expectTable) assertTrue("[${c.name}] no table", blocks.any { it is ArticleBlock.Table })
      if (c.expectList) assertTrue("[${c.name}] no list", blocks.any { it is ArticleBlock.BulletList || it is ArticleBlock.OrderedList })
      if (c.expectQuote) assertTrue("[${c.name}] no quote", blocks.any { it is ArticleBlock.Quote })
      if (c.expectCode) assertTrue("[${c.name}] no code", blocks.any { it is ArticleBlock.CodeBlock })
      assertEquals("[${c.name}] count drift", out.wordCount, ReaderCore.effectiveWords(md))
    }
  }
}
