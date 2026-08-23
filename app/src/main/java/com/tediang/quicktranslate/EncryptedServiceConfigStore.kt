package com.tediang.quicktranslate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedServiceConfigStore(
    context: Context,
    private val preferencesName: String = "quick_translate_service_config",
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyAlias = "quick_translate_config_${preferencesName.hashCode()}"

    fun save(config: ServiceConfig) {
        val encryptedKey = encrypt(config.apiKey)
        preferences.edit()
            .putString(KEY_NAME, config.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_API_KEY_CIPHERTEXT, encryptedKey.ciphertext)
            .putString(KEY_API_KEY_IV, encryptedKey.iv)
            .apply()
    }

    fun load(): ServiceConfig? {
        val name = preferences.getString(KEY_NAME, null) ?: return null
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val model = preferences.getString(KEY_MODEL, null) ?: return null
        val ciphertext = preferences.getString(KEY_API_KEY_CIPHERTEXT, null).orEmpty()
        val iv = preferences.getString(KEY_API_KEY_IV, null).orEmpty()
        val apiKey = if (ciphertext.isBlank() || iv.isBlank()) "" else decrypt(ciphertext, iv)
        return ServiceConfig(name, baseUrl, apiKey, model)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun encrypt(plainText: String): EncryptedValue {
        if (plainText.isEmpty()) return EncryptedValue("", "")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedValue(
            ciphertext = Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private data class EncryptedValue(val ciphertext: String, val iv: String)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_NAME = "name"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_API_KEY_IV = "api_key_iv"
    }
}
