package com.reader.app.signer

import android.content.Context
import android.content.Intent

/**
 * NIP-55 / Amber boundary. v1 role only: connect/test signer + device
 * authorization proof. Transport encrypt/decrypt NEVER goes through Amber.
 * The [Backend] seam lets tests simulate installed/missing/rejecting signers.
 */
class AmberSigner(private val ctx: Context, private val backend: Backend = IntentBackend()) {
  interface Backend {
    fun installed(ctx: Context): Boolean
    fun getPublicKey(ctx: Context): String?
    fun signEvent(ctx: Context, eventJson: String, kind: Int): String?
  }

  class IntentBackend : Backend {
    override fun installed(ctx: Context): Boolean = try {
      ctx.packageManager.getPackageInfo("com.greenart7c3.nostrsigner", 0)
      true
    } catch (e: Exception) {
      false
    }

    override fun getPublicKey(ctx: Context): String? {
      // NIP-55 get_public_key resolves via the Activity result path at
      // runtime when Amber exists; null here means unavailable.
      @Suppress("UNUSED_VARIABLE")
      val intent = Intent().apply {
        `package` = "com.greenart7c3.nostrsigner"
        action = "get_public_key"
      }
      return null
    }

    override fun signEvent(ctx: Context, eventJson: String, kind: Int): String? = null
  }

  fun status(): String = when {
    !backend.installed(ctx) -> "Android signer: not installed (Amber)"
    backend.getPublicKey(ctx) == null -> "Android signer: installed, not connected"
    else -> "Android signer: connected"
  }
}
