package com.reader.app.capture

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Guarded DNS for article fetch. Mirrors the relay client's policy: any
 * private, loopback, link-local, multicast, carrier-grade-NAT, or IPv6
 * unique-local answer rejects the connection. This runs inside the HTTP
 * client on every socket, so a DNS change between validation and connect
 * still fails closed.
 *
 * No test bypass lives here. Tests inject a custom [Dns] (or a custom
 * [CaptureUrlPolicy.AddressResolver] + fake [CaptureHttp]) at the
 * [CaptureFetcher] boundary.
 */
class CaptureDns(
  private val delegate: (String) -> List<InetAddress> = { host ->
    com.reader.app.nostr.RelayDns.lookup(host).toList()
  },
) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = delegate(hostname)
    if (addresses.isEmpty() || addresses.any(com.reader.app.nostr.PairingProtocol::isProhibitedAddress)) {
      throw UnknownHostException("link resolved to a prohibited network")
    }
    return addresses
  }
}
