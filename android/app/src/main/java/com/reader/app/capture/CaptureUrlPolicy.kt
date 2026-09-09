package com.reader.app.capture

import com.reader.app.nostr.PairingProtocol
import java.net.IDN
import java.net.InetAddress
import java.net.URI

/**
 * Structural URL validation + SSRF boundary for article capture.
 *
 * Production path uses the same prohibited-address definition as pairing
 * ([PairingProtocol.isProhibitedAddress]) and resolves every host before
 * trust. There is intentionally no test-local-network bypass in production
 * code: tests inject an [AddressResolver] that permits loopback for
 * MockWebServer fixtures only.
 */
object CaptureUrlPolicy {
  const val MAX_URL_LEN = 2000
  const val MAX_REDIRECTS = 5

  fun interface AddressResolver {
    @Throws(Exception::class)
    fun lookup(host: String): Array<InetAddress>
  }

  /** Production resolver: bounded platform DNS (same budget as relays). */
  val productionResolver: AddressResolver = AddressResolver { host ->
    com.reader.app.nostr.RelayDns.lookup(host)
  }

  fun isSupportedHttpUrl(raw: String): Boolean = runCatching { validateStructure(raw) }.isSuccess

  /**
   * Structural validation only (no DNS). Throws [IllegalArgumentException]
   * with an actionable message on any violation.
   */
  fun validateStructure(raw: String): URI {
    require(raw.length in 1..MAX_URL_LEN) { "Link is too long or empty" }
    require(raw.none { it.code < 0x20 || it.code == 0x7f }) { "Link contains control characters" }
    val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("Link is not a valid URL") }
    val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Link must use http or https")
    require(scheme == "http" || scheme == "https") { "Link must use http or https" }
    require(uri.userInfo == null && uri.rawUserInfo == null) { "Links with credentials are not supported" }
    val host = uri.host ?: throw IllegalArgumentException("Link has no host")
    validateHost(host)
    val port = uri.port
    require(port in -1..65535 && port != 0) { "Link has an invalid port" }
    // Reject hosts that look numeric but are malformed (e.g. 999.1.1.1,
    // 1.2.3.4.5, 0x7f.0.0.1, bare decimal). Valid literal IPs pass through to
    // DNS/prohibited checks which reject non-public answers.
    rejectMalformedNumericHost(host)
    return uri
  }

  private fun validateHost(host: String) {
    require(host.isNotEmpty() && host.length <= 253) { "Link host is invalid" }
    require(!host.endsWith('.')) { "Link host is invalid" }
    require(host.none { it.isWhitespace() }) { "Link host is invalid" }
    // IDN: toASCII throws on malformed international names.
    runCatching { IDN.toASCII(host) }.getOrElse { throw IllegalArgumentException("Link host is invalid") }
    val lower = host.lowercase()
    require(lower != "localhost") { "This link target is not supported" }
    require(!lower.endsWith(".local") && !lower.endsWith(".localhost")) { "This link target is not supported" }
    require(!lower.endsWith(".internal") && !lower.endsWith(".intranet")) { "This link target is not supported" }
  }

  internal fun rejectMalformedNumericHost(host: String) {
    val h = host.trim().trimEnd('.')
    // Dotted-decimal lookalike: only digits and dots.
    if (h.matches(Regex("^[0-9.]+$"))) {
      val parts = h.split('.')
      val validV4 = parts.size == 4 && parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
          // No leading zeros ambiguity except single zero.
          (p == "0" || !p.startsWith("0")) &&
          (p.toIntOrNull() in 0..255)
      }
      if (!validV4) throw IllegalArgumentException("Link host is an invalid numeric address")
      // Valid dotted quads continue to DNS + prohibited-network checks.
      return
    }
    // Hex/octal/decimal literal lookalikes (0x..., 0..., bare digits).
    if (h.matches(Regex("^0[xX][0-9a-fA-F.]+$")) ||
      h.matches(Regex("^[0-9]+$")) ||
      h.matches(Regex("^0[0-7.]+$"))
    ) {
      throw IllegalArgumentException("Numeric link hosts are not supported")
    }
  }

  /**
   * Resolve and reject prohibited destinations. Must be called for the
   * original URL, every redirect target, and the final destination — and the
   * HTTP client itself re-validates on every socket via [CaptureDns].
   */
  fun validateResolvedHost(host: String, resolver: AddressResolver = productionResolver) {
    val addresses = try {
      resolver.lookup(host)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalArgumentException("Could not resolve this link (interrupted)")
    } catch (_: Exception) { throw IllegalArgumentException("Could not resolve this link (offline or unknown host)") }
    require(addresses.isNotEmpty()) { "Could not resolve this link (offline or unknown host)" }
    require(addresses.none(PairingProtocol::isProhibitedAddress)) {
      "This link points to a private or local address"
    }
  }

  /** Normalize for dedup: lowercase scheme/host, drop default port, trim. */
  fun normalizeForDedup(raw: String): String {
    val uri = validateStructure(raw)
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase().trimEnd('.')
    val port = when {
      uri.port < 0 -> ""
      scheme == "http" && uri.port == 80 -> ""
      scheme == "https" && uri.port == 443 -> ""
      else -> ":${uri.port}"
    }
    val path = uri.rawPath?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    // Fragment never changes the fetched resource; drop for dedup but keep
    // the original URL separately for provenance.
    return "$scheme://$host$port$path$query"
  }

  /**
   * Validate a redirect step. Throws on unsafe transitions:
   * - scheme downgrade (https -> http)
   * - unsupported target scheme / credentials
   * - prohibited resolved destination
   */
  fun validateRedirect(from: URI, location: String, resolver: AddressResolver = productionResolver): URI {
    val next = runCatching { from.resolve(location) }
      .getOrElse { throw IllegalArgumentException("This page redirects to an invalid link") }
    validateStructure(next.toString())
    val fromScheme = from.scheme.lowercase()
    val toScheme = (next.scheme ?: "").lowercase()
    require(!(fromScheme == "https" && toScheme == "http")) {
      "This page redirects to an insecure address"
    }
    val host = next.host ?: throw IllegalArgumentException("This page redirects to an invalid link")
    validateResolvedHost(host, resolver)
    return next
  }

  fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")
}
