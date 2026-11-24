package com.example.voiceagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AES/GCM wrapper over the Android Keystore. Provider keys never touch
 * SharedPreferences in plain text and are excluded from backups by the manifest.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("voiceagent_secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun put(id: String, value: String) {
        if (value.isBlank()) {
            remove(id)
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(id, blob).apply()
        } catch (_: Exception) {
            // A failed keystore write must not crash the app; the user can
            // re-enter the key on the next launch.
        }
    }

    fun get(id: String): String {
        val blob = prefs.getString(id, null) ?: return ""
        return try {
            val parts = blob.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val payload = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    private fun key(): SecretKey {
        val existing = keyStore.getKey(ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "voiceagent_provider_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
