package com.reader.app.sync

import com.reader.app.core.BoundedText
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.RelayDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One-shot public-article fetch for Android share. [UrlCapture.normalize]
 * rejects raw-IP URLs before a request is built, DNS answers are checked against
 * the same private/reserved guard as relays, and a network interceptor checks
 * the address actually connected to on every redirect hop. The last check also
 * covers OkHttp's IP-literal path, which bypasses [Dns]. No cookies,
 * credentials, or remote images are fetched.
 */
class WebArticleFetcher(private val client: OkHttpClient = defaultClient()) {
  companion object {
    const val MAX_HTML_BYTES = 5 * 1024 * 1024

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
          val addresses = RelayDns.lookup(hostname).toList()
          if (addresses.isEmpty() || addresses.any(PairingProtocol::isProhibitedAddress)) {
            throw UnknownHostException("Article host resolves to a prohibited network")
          }
          return addresses
        }
      })
      .addNetworkInterceptor(ProhibitedAddressInterceptor())
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .callTimeout(25, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .build()
  }

  suspend fun fetch(rawUrl: String): WebArticleExtractor.Article = withContext(Dispatchers.IO) {
    val url = UrlCapture.normalize(rawUrl) ?: throw IOException("Only public http(s) links are supported")
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", "ReaderAndroid/0.9 (article save)")
      .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.8")
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Article returned HTTP ${response.code}")
      val type = response.body?.contentType()
      val mime = type?.let { "${it.type}/${it.subtype}".lowercase() } ?: "text/html"
      if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain")) {
        throw IOException("This link is not an HTML article")
      }
      val html = response.body!!.byteStream().use { BoundedText.readUtf8(it, MAX_HTML_BYTES) }
      WebArticleExtractor.extract(html, response.request.url.toString())
        ?: throw IOException("No readable article was found at this link")
    }
  }
}

/**
 * Checks the socket address OkHttp actually connected to, including redirect
 * hops. It runs after connection setup, so it cannot be bypassed by IP-literal
 * hosts for which OkHttp skips [Dns.lookup].
 */
internal class ProhibitedAddressInterceptor : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val address = chain.connection()?.route()?.socketAddress?.address
    if (address == null || PairingProtocol.isProhibitedAddress(address)) {
      throw IOException("Article connection resolved to a prohibited network")
    }
    return chain.proceed(chain.request())
  }
}
