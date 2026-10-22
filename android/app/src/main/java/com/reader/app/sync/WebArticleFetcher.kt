package com.reader.app.sync

import com.reader.app.core.BoundedText
import com.reader.app.nostr.PairingProtocol
import com.reader.app.nostr.RelayDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One-shot public-article fetch for Android share. Every connection, including
 * redirect hops, resolves through the same private/reserved DNS guard as relays.
 * No cookies, credentials, or remote images are fetched.
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
