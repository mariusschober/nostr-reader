package com.reader.app.nostr

import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownServiceException

/** A proxy may resolve the target elsewhere. Never send to it, or silently bypass it. */
internal object PublicNetworkRoute {
  fun requireDirect(uri: URI, selector: ProxySelector? = ProxySelector.getDefault()) {
    val route = selector?.select(uri)?.firstOrNull()
    if (route != null && route.type() != Proxy.Type.DIRECT) {
      throw UnknownServiceException("This network requires a proxy and its destination cannot be verified")
    }
  }
}
