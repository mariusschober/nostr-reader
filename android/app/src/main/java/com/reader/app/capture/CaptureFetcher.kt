package com.reader.app.capture

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Bounded article HTTP fetch. Single document request only:
 * - no subresource fetches (images, scripts, CSS are never requested),
 * - no cookie jar, no Authorization header, no credential import,
 * - default TLS hostname verification (never disabled),
 * - manual redirect handling so every hop is structurally + DNS validated.
 */
class CaptureFetcher(
  private val http: CaptureHttp = OkHttpCaptureHttp(),
  private val resolver: CaptureUrlPolicy.AddressResolver = CaptureUrlPolicy.productionResolver,
) {
  data class FetchedPage(
    val finalUrl: String,
    val mime: String,
    val htmlBytes: ByteArray,
    val redirectCount: Int,
  )

  companion object {
    const val CONNECT_TIMEOUT_SECS = 8L
    const val READ_TIMEOUT_SECS = 12L
    const val TOTAL_TIMEOUT_SECS = 30L
    /** Compressed (wire) cap. Matches the 5 MiB transport budget. */
    const val MAX_COMPRESSED_BYTES = 5 * 1024 * 1024
    /** Expanded (inflated) cap for article HTML. */
    const val MAX_EXPANDED_BYTES = 2 * 1024 * 1024
    /** Whole redirect-chain cap: every hop's body counts, redirects included. */
    const val MAX_CHAIN_BYTES = 8 * 1024 * 1024
    val ALLOWED_MIME = setOf("text/html", "application/xhtml+xml")
  }

  suspend fun fetch(originalUrl: String): FetchedPage {    val startUri = try {
      CaptureUrlPolicy.validateStructure(originalUrl)
    } catch (e: IllegalArgumentException) {
      throw CaptureException("invalid_url", e.message ?: "This link is not supported")
    }
    try {
      CaptureUrlPolicy.validateResolvedHost(requireNotNull(startUri.host), resolver)
    } catch (e: IllegalArgumentException) {
      throw CaptureException("prohibited_destination", e.message ?: "This link destination is not supported")
    } catch (e: CaptureException) { throw e }
    var current = startUri
    var redirects = 0
    var chainBytes = 0L
    while (true) {
      val response = http.get(current.toString(), TOTAL_TIMEOUT_SECS)
      chainBytes += response.body.size
      if (chainBytes > MAX_CHAIN_BYTES) {
        throw CaptureException("body_too_large", "This page's redirect chain is too large to capture")
      }
      if (response.isRedirect) {
        if (redirects >= CaptureUrlPolicy.MAX_REDIRECTS) {
          throw CaptureException("too_many_redirects", "This page redirects too many times")
        }
        val location = response.header("Location")
          ?: throw CaptureException("bad_redirect", "This page has a broken redirect")
        current = try {
          CaptureUrlPolicy.validateRedirect(current, location, resolver)
        } catch (e: IllegalArgumentException) {
          throw CaptureException("prohibited_destination", e.message ?: "This redirect is not supported")
        }
        redirects++
        continue
      }
      if (response.code in 301..308) {
        throw CaptureException("bad_redirect", "This page has a broken redirect")
      }
      when {
        response.code == 401 || response.code == 403 ->
          throw CaptureException("access_denied", "This page needs a login or subscription; Reader does not bypass access controls")
        response.code == 404 ->
          throw CaptureException("not_found", "This page was not found (404)")
        response.code == 429 ->
          throw CaptureException("rate_limited_retryable", "This site is rate-limiting Reader; try again later", retryable = true)
        response.code in 500..599 ->
          throw CaptureException("server_error_retryable", "This site had a server error; try again later", retryable = true)
        response.code !in 200..299 ->
          throw CaptureException("http_${response.code}", "This page could not be fetched (HTTP ${response.code})", retryable = response.code in 408..418)
      }
      val contentType = response.header("Content-Type") ?: ""
      val mime = contentType.substringBefore(';').trim().lowercase()
      if (mime !in ALLOWED_MIME) {
        throw CaptureException("unsupported_mime", "This link is a ${mime.ifBlank { "non-article" }} file, not an article page")
      }
      val expanded = response.bodyBytes(MAX_COMPRESSED_BYTES, MAX_EXPANDED_BYTES, response.header("Content-Encoding"))
      if (expanded.isEmpty()) throw CaptureException("empty_page", "This page has no readable content")
      // Final destination re-validation (defense in depth; DNS may have
      // changed between the pre-check and connect — the socket guard fails
      // closed, this gives an actionable error).
      try {
        CaptureUrlPolicy.validateResolvedHost(requireNotNull(current.host), resolver)
      } catch (e: IllegalArgumentException) {
        throw CaptureException("prohibited_destination", e.message ?: "This link destination is not supported")
      }
      return FetchedPage(current.toString(), mime, expanded, redirects)
    }
  }
}

class CaptureException(val code: String, message: String, val retryable: Boolean = false) : java.io.IOException("$code: $message")

/** Narrow HTTP boundary for test injection. Production uses OkHttp. */
interface CaptureHttp {
  data class Response(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
  ) {
    val isRedirect: Boolean get() = code in listOf(301, 302, 303, 307, 308)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun bodyBytes(maxCompressed: Int, maxExpanded: Int, contentEncoding: String?): ByteArray {
      if (body.size > maxCompressed) throw CaptureException("body_too_large", "This page is too large to capture", retryable = false)
      val encoding = contentEncoding?.lowercase() ?: ""
      // If the test fake already inflated, or the server sent identity, use raw.
      if ("gzip" in encoding || "x-gzip" in encoding) {
        return inflateGzipBounded(body, maxExpanded)
      }
      // Some servers gzip without a header; sniff the magic.
      if (body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) {
        return inflateGzipBounded(body, maxExpanded)
      }
      if (body.size > maxExpanded) throw CaptureException("body_too_large", "This page is too large to capture")
      return body
    }
    private fun inflateGzipBounded(compressed: ByteArray, maxExpanded: Int): ByteArray {
      try {
        GZIPInputStream(compressed.inputStream()).use { gzip ->
          val out = ByteArrayOutputStream(minOf(8192, maxExpanded))
          val buf = ByteArray(8192)
          var total = 0
          while (true) {
            val n = gzip.read(buf)
            if (n < 0) break
            total += n
            if (total > maxExpanded) throw CaptureException("body_too_large", "This page expands to more than the capture limit")
            out.write(buf, 0, n)
          }
          return out.toByteArray()
        }
      } catch (e: CaptureException) { throw e }
      catch (_: Exception) { throw CaptureException("bad_encoding", "This page could not be decoded") }
    }
  }
  suspend fun get(url: String, totalTimeoutSecs: Long): Response
}

class OkHttpCaptureHttp(
  dns: Dns = CaptureDns(),
) : CaptureHttp {
  private val client: OkHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .followRedirects(false)
    .followSslRedirects(false)
    .cookieJar(NoCookies)
    .connectTimeout(CaptureFetcher.CONNECT_TIMEOUT_SECS, TimeUnit.SECONDS)
    .readTimeout(CaptureFetcher.READ_TIMEOUT_SECS, TimeUnit.SECONDS)
    .callTimeout(CaptureFetcher.TOTAL_TIMEOUT_SECS, TimeUnit.SECONDS)
    .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
    // No cache, no authenticator, default hostname verifier + system trust.
    .build()

  override suspend fun get(url: String, totalTimeoutSecs: Long): CaptureHttp.Response =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Reader/0.9 (Android article capture; +https://example.invalid)")
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Encoding", "gzip")
        .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
        .get()
        .build()
      try {
        kotlinx.coroutines.withTimeout(totalTimeoutSecs.coerceIn(1, 120) * 1000) {
          try {
            client.newCall(request).execute().use { response ->
              streamBounded(response)
            }
          } catch (e: CaptureException) { throw e }
          catch (e: java.io.InterruptedIOException) {
            throw CaptureException("timeout_retryable", "Fetching this page timed out; try again later", retryable = true)
          } catch (e: java.net.UnknownHostException) {
            throw CaptureException("dns_retryable", "Could not resolve this link; check connection and retry", retryable = true)
          } catch (e: java.io.IOException) {
            throw CaptureException("network_retryable", "Network error while fetching; try again later", retryable = true)
          }
        }
      } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw CaptureException("timeout_retryable", "Fetching this page timed out; try again later", retryable = true)
      }
    }

  /**
   * Bounded single response read. The declared `Content-Length` fails fast
   * before any buffering, and the body streams through an 8 KiB window so a
   * lying server can never force the full payload into heap: at most
   * [CaptureFetcher.MAX_EXPANDED_BYTES] + 8 KiB is ever resident. (OkHttp
   * transparently inflates gzip, so observed bytes are expanded bytes.)
   */
  private fun streamBounded(response: okhttp3.Response): CaptureHttp.Response {
    val code = response.code
    val headers = buildMap {
      for (name in response.headers.names()) put(name, response.headers[name] ?: "")
    }
    val declared = response.header("Content-Length")?.trim()?.toLongOrNull()
    if (declared != null && declared > CaptureFetcher.MAX_EXPANDED_BYTES) {
      throw CaptureException("body_too_large", "This page is too large to capture")
    }
    val raw = try {
      val source = response.body?.source()
        ?: throw CaptureException("empty_page", "This page has no readable content")
      val out = java.io.ByteArrayOutputStream(8192)
      val buf = ByteArray(8192)
      var total = 0
      while (true) {
        val n = source.read(buf)
        if (n < 0) break
        total += n
        if (total > CaptureFetcher.MAX_EXPANDED_BYTES) {
          throw CaptureException("body_too_large", "This page is too large to capture")
        }
        out.write(buf, 0, n)
      }
      out.toByteArray()
    } catch (e: CaptureException) { throw e }
      catch (e: java.io.InterruptedIOException) {
      throw CaptureException("timeout_retryable", "Fetching this page timed out; try again later", retryable = true)
    } catch (e: java.net.UnknownHostException) {
      throw CaptureException("dns_retryable", "Could not resolve this link; check connection and retry", retryable = true)
    } catch (e: java.io.IOException) {
      throw CaptureException("network_retryable", "Network error while fetching; try again later", retryable = true)
    }
    return CaptureHttp.Response(code, headers, raw)
  }

  private object NoCookies : okhttp3.CookieJar {
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) { /* never store */ }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = emptyList()
  }
}
