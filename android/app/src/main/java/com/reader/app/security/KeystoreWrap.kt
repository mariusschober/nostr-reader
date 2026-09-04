package com.reader.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps secp256k1 channel private keys with a Keystore AES-256-GCM key.
 * Only ciphertext + nonce touch app-private prefs (excluded from backup).
 */
class KeystoreWrap(ctx: Context) {
  private val appCtx = ctx.applicationContext
  private val prefs: SharedPreferences =
    appCtx.getSharedPreferences("reader_keys", Context.MODE_PRIVATE)
  private val alias = "reader-channel-wrap"

  private fun wrapKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(alias, null) as? SecretKey)?.let { return it }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return gen.generateKey()
  }

  fun sealChannelKey(channelId: String, seckey: ByteArray) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
    val ct = cipher.doFinal(seckey)
    prefs.edit()
      .putString("ch_$channelId", Base64.encodeToString(ct, Base64.NO_WRAP))
      .putString("iv_$channelId", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
      .apply()
  }

  fun openChannelKey(channelId: String): ByteArray? {
    val ctB64 = prefs.getString("ch_$channelId", null) ?: return null
    val ivB64 = prefs.getString("iv_$channelId", null) ?: return null
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE, wrapKey(),
      GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
    )
    return cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
  }

  fun deleteChannelKey(channelId: String) {
    prefs.edit().remove("ch_$channelId").remove("iv_$channelId").apply()
  }
}
