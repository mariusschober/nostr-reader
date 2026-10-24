package com.reader.app

import com.reader.app.sync.ProhibitedAddressInterceptor
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class WebArticleFetcherTest {
  private val interceptor = ProhibitedAddressInterceptor()

  @Test
  fun rejectsLoopbackRedirectHopAndNeverProceeds() {
    val chain = FakeChain(connectionFor(InetAddress.getByName("127.0.0.1")))
    val error = assertThrows(IOException::class.java) { interceptor.intercept(chain) }
    assertTrue(error.message!!.contains("prohibited"))
    assertFalse(chain.proceeded)
  }

  @Test
  fun rejectsMappedLoopbackRedirectHopAndNeverProceeds() {
    val mapped = InetAddress.getByAddress(
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 127, 0, 0, 1),
    )
    val chain = FakeChain(connectionFor(mapped))
    assertThrows(IOException::class.java) { interceptor.intercept(chain) }
    assertFalse(chain.proceeded)
  }

  @Test
  fun rejectsMissingConnectionAddressAndNeverProceeds() {
    val chain = FakeChain(connection = null)
    assertThrows(IOException::class.java) { interceptor.intercept(chain) }
    assertFalse(chain.proceeded)
  }

  @Test
  fun allowsPublicConnectedAddress() {
    val chain = FakeChain(connectionFor(InetAddress.getByName("1.1.1.1")))
    val response = interceptor.intercept(chain)
    assertTrue(chain.proceeded)
    assertTrue(response.isSuccessful)
    assertSame(chain.request(), response.request)
    response.close()
  }

  private fun connectionFor(address: InetAddress): Connection = object : Connection {
    override fun route(): Route {
      val url = "https://example.com/".toHttpUrl()
      val target = Address(
        url.host,
        url.port,
        Dns.SYSTEM,
        SocketFactory.getDefault(),
        null,
        null,
        CertificatePinner.DEFAULT,
        Authenticator.NONE,
        Proxy.NO_PROXY,
        listOf(Protocol.HTTP_1_1),
        listOf(ConnectionSpec.MODERN_TLS),
        ProxySelector.getDefault(),
      )
      return Route(target, Proxy.NO_PROXY, InetSocketAddress(address, url.port))
    }

    override fun socket(): Socket = throw UnsupportedOperationException()

    override fun handshake(): Handshake? = null

    override fun protocol(): Protocol = Protocol.HTTP_1_1
  }

  private class FakeChain(private val connection: Connection?) : Interceptor.Chain {
    var proceeded = false
      private set

    private val request = Request.Builder().url("https://example.com/article").build()

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
      proceeded = true
      return Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("ok".toResponseBody(null))
        .build()
    }

    override fun connection(): Connection? = connection

    override fun call(): Call = throw UnsupportedOperationException()

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
  }
}
