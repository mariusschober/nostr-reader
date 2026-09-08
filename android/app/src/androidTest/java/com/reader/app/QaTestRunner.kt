package com.reader.app

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.reader.app.nostr.RelayClient
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** All local-network and certificate configuration stays in the test APK. */
class QaTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader, name: String, context: Context): Application {
    return if (context.packageName == "com.reader.app.qa") {
      super.newApplication(javaClass.classLoader, QaReaderApplication::class.java.name, context)
    } else super.newApplication(cl, name, context)
  }
}

class QaReaderApplication : ReaderApp() {
  override val relayClient: RelayClient by lazy {
    val args = InstrumentationRegistry.getArguments()
    val port = args.getString("qaRelayProxy")?.toIntOrNull()
    if (port == null) super.relayClient else {
      check(packageName == "com.reader.app.qa" && port in 1024..65535)
      val hosts = checkNotNull(args.getString("qaRelayHosts")).split(',').toSet()
      check(hosts.isNotEmpty() && hosts.size <= 8)
      val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
        Base64.decode(checkNotNull(args.getString("qaRelayCert")), Base64.NO_WRAP).inputStream())
      val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null); setCertificateEntry("isolated-reader-qa", certificate)
      }
      val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
      val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
      val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
      val client = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .sslSocketFactory(ssl.socketFactory, manager)
        .dns(object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            if (hostname !in hosts) throw UnknownHostException("Not an isolated QA relay")
            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
          }
        })
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
      RelayClient(client)
    }
  }
}
