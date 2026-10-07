package com.bydassistantng.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Stores the Gemini API key encrypted with an AndroidKeyStore-backed AES key, the value in plain
 * DataStore. `androidx.security.crypto`'s `EncryptedSharedPreferences` (what the old app used) is
 * now deprecated by Google itself — this is the same "DataStore + Keystore-backed crypto" shape
 * its own migration guidance recommends, without pulling in a new crypto library (Tink) for one
 * secret string: just the standard `java.security.KeyStore`/`javax.crypto.Cipher` JCA APIs.
 */
@Singleton
class SecureCredentials @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.dataStore

    private object Keys {
        val API_KEY_CIPHERTEXT = stringPreferencesKey("gemini_api_key_ciphertext")
        val API_KEY_IV = stringPreferencesKey("gemini_api_key_iv")
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** @return null if no key is stored, or if decryption fails (e.g. the Keystore key was wiped
     * by a factory reset/OEM quirk) — callers treat this the same as "not configured yet". */
    suspend fun getApiKey(): String? {
        val prefs = dataStore.data.first()
        val ciphertextB64 = prefs[Keys.API_KEY_CIPHERTEXT] ?: return null
        val ivB64 = prefs[Keys.API_KEY_IV] ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt API key", e)
            null
        }
    }

    /** @return false on any encryption/storage failure — the caller must tell the user rather
     * than assuming the key was actually saved. */
    suspend fun setApiKey(key: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        dataStore.edit {
            it[Keys.API_KEY_CIPHERTEXT] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            it[Keys.API_KEY_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to encrypt/save API key", e)
        false
    }

    suspend fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    suspend fun clearApiKey() {
        dataStore.edit {
            it.remove(Keys.API_KEY_CIPHERTEXT)
            it.remove(Keys.API_KEY_IV)
        }
    }

    companion object {
        private const val TAG = "SecureCredentials"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gemini_api_key_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
