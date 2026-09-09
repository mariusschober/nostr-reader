package com.reader.app

import com.reader.app.capture.CaptureUrlPolicy
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class CaptureUrlPolicyTest {
  private fun publicResolver(vararg ips: String): CaptureUrlPolicy.AddressResolver =
    CaptureUrlPolicy.AddressResolver { _ -> ips.map { InetAddress.getByName(it) }.toTypedArray() }

  @Test fun acceptsOrdinaryHttps() {
    CaptureUrlPolicy.validateStructure("https://example.com/article?utm=1#frag")
  }

  @Test fun rejectsUnsupportedSchemes() {
    for (url in listOf("ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)", "data:text/plain,hi", "about:blank")) {
      try {
        CaptureUrlPolicy.validateStructure(url)
        fail("must reject $url")
      } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun rejectsEmbeddedCredentials() {
    for (url in listOf("https://user:pass@example.com/", "https://user@example.com/")) {
      try {
        CaptureUrlPolicy.validateStructure(url)
        fail("must reject $url")
      } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun rejectsLocalAndSpecialHosts() {
    for (url in listOf("http://localhost/", "http://localhost:8080/x", "http://foo.local/", "http://foo.internal/")) {
      try {
        CaptureUrlPolicy.validateStructure(url)
        fail("must reject $url")
      } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun rejectsMalformedNumericHosts() {
    for (url in listOf("http://999.999.1.1/", "http://1.2.3.4.5/", "http://0x7f.0.0.1/", "http://2130706433/", "http://0177.0.0.1/")) {
      try {
        CaptureUrlPolicy.validateStructure(url)
        fail("must reject $url")
      } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun rejectsPrivateLoopbackLinkLocalTargets() {
    // Structure passes for literal IPs; the resolved-address guard rejects.
    for (host in listOf("127.0.0.1", "10.0.0.5", "192.168.1.2", "172.16.4.4", "100.64.0.1")) {
      try {
        CaptureUrlPolicy.validateResolvedHost(host, publicResolver(host))
        fail("must reject $host")
      } catch (_: IllegalArgumentException) { }
    }
    try {
      CaptureUrlPolicy.validateResolvedHost("example.com", publicResolver("::1"))
      fail("must reject ::1")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun acceptsPublicDnsAnswer() {
    CaptureUrlPolicy.validateResolvedHost("example.com", publicResolver("93.184.216.34"))
  }

  @Test fun rejectsDowngradeRedirect() {
    val from = java.net.URI("https://example.com/a")
    try {
      CaptureUrlPolicy.validateRedirect(from, "http://example.com/b", publicResolver("93.184.216.34"))
      fail("must reject https->http downgrade")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun acceptsUpgradeRedirect() {
    val from = java.net.URI("http://example.com/a")
    val next = CaptureUrlPolicy.validateRedirect(from, "https://example.com/b", publicResolver("93.184.216.34"))
    assertEquals("https", next.scheme)
  }

  @Test fun rejectsRedirectToPrivate() {
    val from = java.net.URI("https://example.com/a")
    try {
      CaptureUrlPolicy.validateRedirect(from, "https://192.168.1.9/x", publicResolver("192.168.1.9"))
      fail("must reject private redirect")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun dedupNormalizationIsStable() {
    assertEquals(
      "https://example.com/a?x=1",
      CaptureUrlPolicy.normalizeForDedup("HTTPS://Example.COM:443/a?x=1#frag"),
    )
    assertEquals(
      "http://example.com/a",
      CaptureUrlPolicy.normalizeForDedup("http://example.com:80/a/".trimEnd('/')),
    )
  }

  @Test fun productionResolverHasNoTestBypass() {
    // 127.0.0.1 must never validate in production code paths.
    try {
      CaptureUrlPolicy.validateResolvedHost("irrelevant.invalid", CaptureUrlPolicy.AddressResolver { _ ->
        arrayOf(InetAddress.getByName("127.0.0.1"))
      })
      fail("loopback must be rejected")
    } catch (_: IllegalArgumentException) { }
  }
}
