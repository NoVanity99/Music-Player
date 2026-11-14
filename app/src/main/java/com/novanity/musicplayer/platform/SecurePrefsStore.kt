package com.novanity.musicplayer.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * String storage for secrets (the cloud server address and access key): values are encrypted with
 * an AES-GCM key that lives in the Android Keystore and never leaves the device. The file is also
 * left out of backups. Where the Keystore isn't available (unit tests), values are stored as they are
 * in the app's private preferences.
 */
class SecurePrefsStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val plain = SharedPrefsStore(context, name)

    private val key: SecretKey? by lazy {
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }.getOrNull()
    }

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX)) return stored
        val secret = this.key ?: return null
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull() // the key was lost (e.g. restored onto another device): ask again
    }

    override fun putString(key: String, value: String?) {
        val secret = this.key
        val stored = when {
            value == null -> null
            secret == null -> value
            else -> {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secret)
                PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }
        }
        prefs.edit { putString(key, stored) }
    }

    override fun getBoolean(key: String, default: Boolean) = plain.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = plain.putBoolean(key, value)
    override fun getInt(key: String, default: Int) = plain.getInt(key, default)
    override fun putInt(key: String, value: Int) = plain.putInt(key, value)
    override fun getLong(key: String, default: Long) = plain.getLong(key, default)
    override fun putLong(key: String, value: Long) = plain.putLong(key, value)
    override fun getFloat(key: String, default: Float) = plain.getFloat(key, default)
    override fun putFloat(key: String, value: Float) = plain.putFloat(key, value)
    override fun getStringSet(key: String): Set<String>? = plain.getStringSet(key)
    override fun putStringSet(key: String, value: Set<String>) = plain.putStringSet(key, value)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vanity_cloud_settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "enc1:"
        const val IV_SIZE = 12
    }
}
